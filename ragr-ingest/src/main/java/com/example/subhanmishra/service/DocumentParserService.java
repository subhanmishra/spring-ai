package com.example.subhanmishra.service;

import com.example.subhanmishra.chunk.ChunkMetadata;
import com.example.subhanmishra.config.IngestionProperties;
import com.example.subhanmishra.exception.DocumentProcessingException;
import com.example.subhanmishra.service.parse.ContentBlock;
import com.example.subhanmishra.service.parse.MarkdownBlockReader;
import com.example.subhanmishra.service.parse.TableChunker;
import com.example.subhanmishra.service.parse.TokenCounter;
import com.example.subhanmishra.service.parse.XhtmlBlockParser;
import com.example.subhanmishra.service.parse.pdf.PageFooterStripper;
import com.example.subhanmishra.service.parse.pdf.PdfBlockReader;
import com.example.subhanmishra.service.parse.pdf.PdfTableDetector;
import com.example.subhanmishra.service.parse.pdf.TocEntryStripper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DocumentParserService {

    private final static Logger log = LoggerFactory.getLogger(DocumentParserService.class);

    // A regex for splitting by one or more blank lines (which separate paragraphs).
    private final static Pattern PARAGRAPH_PATTERN = Pattern.compile("\\n\\s*\\n");

    /** What paragraphs are re-joined with inside a group; must stay matched to PARAGRAPH_PATTERN. */
    private final static String PARAGRAPH_SEPARATOR = "\n\n";

    /** A code fence line, opening or closing, at any indent or inside a quote. */
    private final static Pattern FENCE_LINE = Pattern.compile("(?m)^[ \\t>]*(```|~~~)");

    /**
     * Room kept below {@code max-embed-tokens} when a code block is cut to fit it: the citation and
     * section lines are added to every chunk later, and the task prefix to every embedding request.
     */
    private final static int HEADER_ALLOWANCE_TOKENS = 128;

    /** Front-matter keys copied onto every chunk of a Markdown file, and the metadata key each becomes. */
    private final static Map<String, String> FRONT_MATTER_KEYS = Map.of(
            "project", ChunkMetadata.PROJECT,
            "version", ChunkMetadata.PROJECT_VERSION,
            "online_url", ChunkMetadata.SOURCE_URL,
            "kind", ChunkMetadata.DOC_KIND);

    private final TextSplitter textSplitter;
    private final ForkJoinPool documentProcessingPool;
    private final IngestionProperties ingestionProperties;

    public DocumentParserService(TextSplitter textSplitter, ForkJoinPool documentProcessingPool, IngestionProperties ingestionProperties) {
        this.textSplitter = textSplitter;
        this.documentProcessingPool = documentProcessingPool;
        this.ingestionProperties = ingestionProperties;
    }

    /**
     * What a chunk may never span: one PDF page, or one whole Tika document. Every chunk inherits its
     * metadata, page number included, so a chunk crossing pages would cite the wrong one.
     *
     * @param tablesJoinProse whether a small table may share a chunk with the prose around it; see
     *                        {@link #coalesceBlocks(List, Map, boolean)}
     */
    private record SourceUnit(List<ContentBlock> blocks, Map<String, Object> metadata, boolean tablesJoinProse) {

        private SourceUnit(List<ContentBlock> blocks, Map<String, Object> metadata) {
            this(blocks, metadata, false);
        }
    }

    public Map<String, Object> parse(MultipartFile file) {

        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
        String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";

        log.info("Parsing document: {}, size: {} bytes, contentType: {}", fileName, file.getSize(), contentType);

        try {

            Resource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public @Nullable String getFilename() {
                    return fileName;
                }
            };


            String lowerName = fileName.toLowerCase(Locale.ROOT);
            if (lowerName.endsWith(".pdf") || contentType.contains("pdf")) {
                return parsePdf(resource);
            } else if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown") || contentType.contains("markdown")) {
                return parseMarkdown(file.getBytes(), fileName);
            } else {
                return parseGenericFile(resource);
            }


        } catch (IOException e) {
            log.error("Failed to read file bytes {}", fileName, e);
            throw new DocumentProcessingException("Could not read uploaded file : " + fileName, e);
        } catch (Exception e) {
            log.error("Error during document parsing: {}", fileName, e);
            throw new DocumentProcessingException("Failed to parse document content: " + fileName, e);
        }


    }

    /**
     * With {@code app.ingestion.table-detection} off, PDFs go through {@code PagePdfDocumentReader}: plain
     * page text, one prose block per page. (Tika would not do better - it finds tables only in tagged
     * PDFs.)
     */
    private Map<String, Object> parsePdf(Resource resource) throws IOException {
        if (ingestionProperties.tableDetection() != IngestionProperties.TableDetection.OFF) {
            return parsePdfWithTableDetection(resource);
        }

        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPageBottomMargin(0)
                .withPageTopMargin(0)
                .build();

        PagePdfDocumentReader documentReader = new PagePdfDocumentReader(resource, config);
        List<Document> pageDocs = documentReader.get();

        // The page-number footers and table-of-contents entries must be removed on this path too, as in
        // PdfBlockReader. Left in, they hand the model a second, wrong page number to cite.
        PageFooterStripper stripper = PageFooterStripper.detect(
                pageDocs.stream()
                        .filter(page -> pageNumberOf(page) != null)
                        .collect(Collectors.toMap(DocumentParserService::pageNumberOf,
                                                  Document::getText,
                                                  (first, second) -> first)));
        TocEntryStripper tocStripper = TocEntryStripper.detect(pageDocs.stream().map(Document::getText).toList());

        List<SourceUnit> units = pageDocs.stream()
                                         .map(page -> {
                                             Integer pageNumber = pageNumberOf(page);
                                             String text = pageNumber != null
                                                     ? stripper.strip(pageNumber, page.getText())
                                                     : page.getText();
                                             return new SourceUnit(
                                                     List.of(new ContentBlock.Prose(tocStripper.strip(text))),
                                                     page.getMetadata());
                                         })
                                         // A page that held only a footer or contents entries is now empty,
                                         // and would still make a chunk.
                                         .filter(unit -> unit.blocks().stream()
                                                             .anyMatch(b -> !(b instanceof ContentBlock.Prose p)
                                                                     || !p.text().isBlank()))
                                         .toList();

        return Map.of("documentStream", chunk(units), "totalPages", pageDocs.size());
    }

    /**
     * The 1-based page number {@code PagePdfDocumentReader} put on a page, or null. Read defensively,
     * not cast, because readers are inconsistent about the value's type.
     */
    private static Integer pageNumberOf(Document page) {
        Object value = page.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER);
        return switch (value) {
            case Number number -> number.intValue();
            case String string -> {
                try {
                    yield Integer.valueOf(string.trim());
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            case null, default -> null;
        };
    }

    /**
     * Reads the PDF from the position of its text, so a table's columns survive. The plain reader pads
     * the gaps between cells with spaces, after which the columns cannot be recovered.
     */
    private Map<String, Object> parsePdfWithTableDetection(Resource resource) throws IOException {
        PdfTableDetector.Mode forced = switch (ingestionProperties.tableDetection()) {
            case LATTICE -> PdfTableDetector.Mode.LATTICE;
            case STREAM -> PdfTableDetector.Mode.STREAM;
            default -> null;
        };

        PdfBlockReader.Pdf pdf;
        try (var stream = resource.getInputStream()) {
            pdf = PdfBlockReader.read(stream, forced);
        }
        List<PdfBlockReader.Page> pages = pdf.pages();

        List<SourceUnit> units = pages.stream()
                                      .map(page -> new SourceUnit(page.blocks(),
                                                                  Map.of("page_number", page.pageNumber())))
                                      .toList();

        long tables = pages.stream()
                           .flatMap(page -> page.blocks().stream())
                           .filter(ContentBlock.Table.class::isInstance)
                           .count();
        log.info("Read {} of {} page(s) of {} with table detection {}, recovering {} table(s)",
                 pages.size(), pdf.pageCount(), resource.getFilename(), ingestionProperties.tableDetection(), tables);

        return Map.of("documentStream", chunk(units), "totalPages", pdf.pageCount());
    }

    /**
     * Everything that is not a PDF goes through Tika, with a handler that keeps the XHTML markup. Tika
     * already finds the tables in DOCX, XLSX, PPTX and HTML; its default handler throws the markup away.
     */
    private Map<String, Object> parseGenericFile(Resource resource) throws TransformerConfigurationException {
        // The JDK's identity transformer writes the SAX events back out as XHTML. Using it rather than
        // Tika's own handler keeps tika-core out of our dependencies, so Spring AI decides its version.
        StringWriter xhtml = new StringWriter();
        TransformerHandler serializer =
                ((SAXTransformerFactory) SAXTransformerFactory.newInstance()).newTransformerHandler();
        serializer.setResult(new StreamResult(xhtml));

        new TikaDocumentReader(resource, serializer, ExtractedTextFormatter.defaults()).get();

        List<ContentBlock> blocks = XhtmlBlockParser.parse(xhtml.toString());
        long tableCount = blocks.stream().filter(ContentBlock.Table.class::isInstance).count();
        log.info("Tika produced {} block(s) for {}, {} of them tables", blocks.size(), resource.getFilename(), tableCount);

        Map<String, Object> sourceMetadata = Map.of(TikaDocumentReader.METADATA_SOURCE,
                                                    resource.getFilename() != null ? resource.getFilename() : "document");

        // Tika reads the file as a whole, so there is exactly one "page".
        return Map.of("documentStream", chunk(List.of(new SourceUnit(blocks, sourceMetadata))), "totalPages", 1);
    }

    /**
     * Markdown is read by {@link MarkdownBlockReader}, not Tika, which flattens it to one line. Like a
     * Tika document the file is one source unit with no page. Its front matter names the product and
     * version, which the citation header and evaluation use.
     */
    private Map<String, Object> parseMarkdown(byte[] bytes, String fileName) {
        MarkdownBlockReader.Page page = MarkdownBlockReader.read(new String(bytes, StandardCharsets.UTF_8),
                                                                 ingestionProperties.markdown().dropTabLabels(),
                                                                 fileName);

        Map<String, Object> sourceMetadata = new HashMap<>();
        FRONT_MATTER_KEYS.forEach((key, metadataKey) -> {
            String value = page.frontMatter().get(key);
            if (value != null && !value.isBlank()) {
                sourceMetadata.put(metadataKey, value);
            }
        });

        long tables = page.blocks().stream().filter(ContentBlock.Table.class::isInstance).count();
        long code = page.blocks().stream().filter(ContentBlock.Code.class::isInstance).count();
        log.info("Read {} block(s) from Markdown {}, {} of them code and {} tables", page.blocks().size(), fileName, code, tables);

        return Map.of("documentStream", chunk(List.of(new SourceUnit(page.blocks(), Map.copyOf(sourceMetadata), true))),
                      "totalPages", 1);
    }

    private Stream<Document> chunk(List<SourceUnit> units) {
        try {
            // Submitted to documentProcessingPool as a whole, so the parallel stream runs on that pool.
            // A bare parallelStream() would run on the common pool and bypass the limit on parse threads.
            // Collected inside the pool so all the work is done there.
            List<Document> chunks = documentProcessingPool.submit(() ->
                    units.parallelStream()
                         .flatMap(unit -> coalesceBlocks(unit.blocks(), unit.metadata(), unit.tablesJoinProse()).stream())
                         .flatMap(this::splitIfOverBudget)
                         .collect(Collectors.toList())
            ).get();

            return chunks.stream();

        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt(); // Preserve the interrupted status
            throw new DocumentProcessingException("Failed to process document stream in parallel", e);
        }
    }

    /**
     * Only prose goes through the splitter, and only when it is over budget. A table chunk is already
     * final: the splitter would cut a row in half and drop the header from the rest. So is a chunk with a
     * code block that the embedding model reads whole: the splitter cuts by token count, through the
     * middle of the code and its fence.
     */
    // Package-private so the chunking tests can drive the splitter path directly.
    Stream<Document> splitIfOverBudget(Document chunk) {
        if (ChunkMetadata.TABLE.equals(chunk.getMetadata().get(ChunkMetadata.BLOCK_TYPE))) {
            return Stream.of(chunk);
        }
        int tokens = TokenCounter.count(chunk.getText());
        // Not merely an optimisation: the splitter cuts even short text at its last full stop or line
        // break and stores the rest as a second piece, so text within the budget must not reach it.
        if (tokens <= ingestionProperties.chunkSize()) {
            return Stream.of(chunk);
        }
        if (FENCE_LINE.matcher(chunk.getText()).find() && tokens <= ingestionProperties.maxEmbedTokens()) {
            return Stream.of(chunk);
        }
        return absorbShortPieces(textSplitter.apply(List.of(chunk))).stream();
    }

    /**
     * Applies {@code app.ingestion.min-chunk-length-to-embed} by <em>merging</em> a short piece into the
     * one before it, never by deleting it.
     * <p>
     * The splitter's own floor deletes short pieces - and the short pieces are usually the leftovers of its
     * own cuts, which is real content. So the splitter is built with no floor (see {@code ChunkingConfig})
     * and the floor is applied here. A lone short piece is kept: it is the whole of its block.
     */
    private List<Document> absorbShortPieces(List<Document> pieces) {
        List<Document> kept = new ArrayList<>(pieces.size());

        for (Document piece : pieces) {
            if (kept.isEmpty() || piece.getText().length() >= ingestionProperties.minChunkLengthToEmbed()) {
                kept.add(piece);
                continue;
            }
            Document previous = kept.removeLast();
            kept.add(new Document(previous.getText() + PARAGRAPH_SEPARATOR + piece.getText(),
                                  previous.getMetadata()));
        }
        return kept;
    }

    /**
     * Groups one source unit's blocks into chunks that fill {@code app.ingestion.chunk-size} tokens.
     * <p>
     * Paragraphs are joined until the next one would go over the budget, or until a heading starts a new
     * section (see {@link ProseGroups#addHeading}). Without joining, every short paragraph was a chunk of
     * its own.
     * <p>
     * A code block joins the prose around it, but whole: if it does not fit the open group, the group is
     * closed first, and a block bigger than the budget gets chunks of its own (see {@link #splitCode}).
     * <p>
     * A table closes the open prose group and becomes its own chunk, or several, split between rows with
     * the header repeated. Prose and tables never share a chunk - except a heading with nothing after it
     * but the table, which opens the table's first chunk instead of being a chunk of one line.
     * <p>
     * Every chunk records the heading path where it starts as {@link ChunkMetadata#SECTION}. Only
     * Markdown has headings, so PDF and Tika chunks have no section.
     *
     * @param blocks          the blocks of one page, one Tika document or one Markdown file, in reading order
     * @param sourceMetadata  metadata carried onto every chunk produced from this unit
     * @param tablesJoinProse Markdown only: a table that is one piece and fits the open group joins it,
     *                        like a code block. Reference pages describe each option in a sentence over
     *                        a three-row table, and kept apart each made two chunks of about 80 tokens,
     *                        half of them header. PDF tables stay apart: their detection can be wrong.
     */
    List<Document> coalesceBlocks(List<ContentBlock> blocks, Map<String, Object> sourceMetadata, boolean tablesJoinProse) {
        List<Document> chunks = new ArrayList<>();
        HeadingPath path = new HeadingPath();
        ProseGroups prose = new ProseGroups(chunks, sourceMetadata, ingestionProperties.chunkSize(), path);
        int tableIndex = 0;

        for (ContentBlock block : blocks) {
            switch (block) {
                case ContentBlock.Prose paragraphs -> {
                    for (String paragraph : PARAGRAPH_PATTERN.split(paragraphs.text())) {
                        prose.add(paragraph);
                    }
                }
                case ContentBlock.Heading heading -> {
                    path.enter(heading.level(), heading.text());
                    prose.addHeading("#".repeat(heading.level()) + " " + heading.text());
                }
                case ContentBlock.Code code when TokenCounter.count(code.text()) <= ingestionProperties.chunkSize() ->
                        prose.add(code.text());
                case ContentBlock.Code code -> {
                    String heading = prose.takeHeadings();
                    prose.flush();
                    List<String> pieces = splitCode(code.text(), ingestionProperties.maxEmbedTokens() - HEADER_ALLOWANCE_TOKENS);
                    for (int i = 0; i < pieces.size(); i++) {
                        prose.emitAlone(i == 0 && heading != null ? heading + PARAGRAPH_SEPARATOR + pieces.get(i) : pieces.get(i));
                    }
                }
                case ContentBlock.Table table -> {
                    List<Document> pieces = renderTable(table, tableIndex++, sourceMetadata, path.current());
                    if (tablesJoinProse && pieces.size() == 1 && prose.fits(pieces.getFirst().getText())) {
                        prose.add(pieces.getFirst().getText());
                        continue;
                    }
                    String heading = prose.takeHeadings();
                    prose.flush();
                    if (heading != null && !pieces.isEmpty()) {
                        Document first = pieces.getFirst();
                        pieces.set(0, new Document(heading + PARAGRAPH_SEPARATOR + first.getText(), first.getMetadata()));
                    }
                    chunks.addAll(pieces);
                }
            }
        }
        prose.flush();

        log.debug("Coalesced {} block(s) into {} chunk(s) on thread: {}",
                  blocks.size(), chunks.size(), Thread.currentThread().getName());
        return chunks;
    }

    /** PDF and Tika units: tables never share a chunk with prose. */
    List<Document> coalesceBlocks(List<ContentBlock> blocks, Map<String, Object> sourceMetadata) {
        return coalesceBlocks(blocks, sourceMetadata, false);
    }

    /** Retained for the prose-only case: one flat document in, budgeted prose groups out. */
    List<Document> coalesceParagraphs(Document source) {
        return coalesceBlocks(List.of(new ContentBlock.Prose(source.getText())), source.getMetadata());
    }

    /**
     * Cuts a code block bigger than the embedding model reads into pieces it reads whole: at blank lines
     * first, then at line ends, never inside a line. Each piece is fenced again in the same language, so
     * every piece is still code. A block that fits is returned whole - a long block is better as one
     * chunk than cut at the chunk size, since it is one example.
     *
     * @param code        the block, fences included
     * @param limitTokens the most a piece may hold, fences included
     */
    static List<String> splitCode(String code, int limitTokens) {
        if (TokenCounter.count(code) <= limitTokens) {
            return List.of(code);
        }
        List<String> lines = List.of(code.split("\n", -1));
        String open = lines.getFirst();
        int last = lines.size() - 1;
        while (last > 0 && lines.get(last).isBlank()) {
            last--;
        }
        if (last < 1 || !FENCE_LINE.matcher(open).find() || !FENCE_LINE.matcher(lines.get(last)).find()) {
            // A list or quote holding code: no single fence to repeat, so it is kept whole.
            log.warn("A {} token block with code has no fence to split on; stored whole, its embedding truncated",
                     TokenCounter.count(code));
            return List.of(code);
        }
        String close = lines.get(last).strip();
        int fenceTokens = TokenCounter.count(open + "\n" + close);
        List<String> body = lines.subList(1, last);

        List<String> pieces = new ArrayList<>();
        List<String> piece = new ArrayList<>();
        int pieceTokens = fenceTokens;
        for (List<String> paragraph : codeParagraphs(body)) {
            int paragraphTokens = TokenCounter.count(String.join("\n", paragraph));
            if (fenceTokens + paragraphTokens > limitTokens) {
                // One paragraph alone is too big: fall back to whole lines.
                for (String line : paragraph) {
                    int lineTokens = TokenCounter.count(line) + 1;
                    if (!piece.isEmpty() && pieceTokens + lineTokens > limitTokens) {
                        pieces.add(fence(open, piece, close));
                        piece = new ArrayList<>();
                        pieceTokens = fenceTokens;
                    }
                    piece.add(line);
                    pieceTokens += lineTokens;
                }
                continue;
            }
            if (!piece.isEmpty() && pieceTokens + paragraphTokens + 1 > limitTokens) {
                pieces.add(fence(open, piece, close));
                piece = new ArrayList<>();
                pieceTokens = fenceTokens;
            }
            piece.addAll(paragraph);
            pieceTokens += paragraphTokens + 1;
        }
        if (!piece.isEmpty()) {
            pieces.add(fence(open, piece, close));
        }
        return pieces;
    }

    /** Lines grouped at blank lines; each blank line stays at the end of the group before it. */
    private static List<List<String>> codeParagraphs(List<String> lines) {
        List<List<String>> paragraphs = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : lines) {
            current.add(line);
            if (line.isBlank()) {
                paragraphs.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            paragraphs.add(current);
        }
        return paragraphs;
    }

    private static String fence(String open, List<String> lines, String close) {
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).isBlank()) {
            end--;
        }
        return open + "\n" + String.join("\n", lines.subList(0, end)) + "\n" + close;
    }

    private List<Document> renderTable(ContentBlock.Table table, int tableIndex, Map<String, Object> sourceMetadata,
                                       @Nullable String section) {
        List<TableChunker.TableChunk> pieces =
                TableChunker.chunk(table, ingestionProperties.chunkSize(), ingestionProperties.maxEmbedTokens());

        List<Document> documents = new ArrayList<>(pieces.size());
        for (TableChunker.TableChunk piece : pieces) {
            Map<String, Object> metadata = new HashMap<>(sourceMetadata);
            metadata.put(ChunkMetadata.BLOCK_TYPE, ChunkMetadata.TABLE);
            metadata.put(ChunkMetadata.TABLE_INDEX, tableIndex);
            if (piece.lastRow() > 0) {
                metadata.put(ChunkMetadata.TABLE_ROWS, piece.firstRow() + "-" + piece.lastRow());
            }
            if (section != null) {
                metadata.put(ChunkMetadata.SECTION, section);
            }
            documents.add(new Document(piece.markdown(), metadata));
        }

        if (documents.size() > 1) {
            log.debug("Table {} of {} rows split into {} chunks, header repeated on each",
                      tableIndex, table.rows().size(), documents.size());
        }
        return documents;
    }

    /** The headings above the current point, one per level: entering a level forgets every deeper one. */
    private static final class HeadingPath {

        private final String[] titles = new String[6];

        private void enter(int level, String title) {
            int index = Math.clamp(level, 1, titles.length) - 1;
            titles[index] = title;
            Arrays.fill(titles, index + 1, titles.length, null);
        }

        /** {@code "Task Execution and Scheduling > Customizing the Executor"}, or null before any heading. */
        private @Nullable String current() {
            String joined = Arrays.stream(titles).filter(Objects::nonNull).collect(Collectors.joining(" > "));
            return joined.isEmpty() ? null : joined;
        }
    }

    /**
     * Collects paragraphs into prose chunks within the budget. A separate object because a table can
     * close the open group at any point.
     */
    private static final class ProseGroups {

        /**
         * A heading closes the open group, so each section starts its own chunk. An embedding is the
         * average of its chunk, so a section buried half-way into a chunk about something else is hard to
         * find.
         * <p>
         * Only once the open group has this many tokens: a shorter tail would embed as little more than
         * noise, so the heading joins it instead.
         */
        private static final int SECTION_BREAK_MIN_TOKENS = 80;

        private final List<Document> out;
        private final Map<String, Object> sourceMetadata;
        private final int budgetTokens;
        private final HeadingPath path;

        private final StringBuilder current = new StringBuilder();
        private int currentTokens;
        /** The heading path where the open group started. */
        private @Nullable String section;
        /** True while the open group holds nothing but headings - or nothing. */
        private boolean headingsOnly = true;

        private ProseGroups(List<Document> out, Map<String, Object> sourceMetadata, int budgetTokens, HeadingPath path) {
            this.out = out;
            this.sourceMetadata = sourceMetadata;
            this.budgetTokens = budgetTokens;
            this.path = path;
        }

        /**
         * A group that so far holds only headings takes the section of the deepest one, so a chunk opening
         * {@code # Task Execution} then {@code ## Customizing the Executor} is filed under the second.
         */
        private void addHeading(String line) {
            if (currentTokens >= SECTION_BREAK_MIN_TOKENS) {
                flush();
            }
            boolean onlyHeadingsSoFar = headingsOnly;
            add(line);
            // add() may have closed a full group first, in which case the heading opened a new one.
            if (onlyHeadingsSoFar || current.toString().equals(line)) {
                headingsOnly = true;
                section = path.current();
            }
        }

        /**
         * Removes and returns the open group when it is nothing but headings, or null. Called as a table
         * or a large code block arrives, so the headings can open its chunk.
         */
        private @Nullable String takeHeadings() {
            if (!headingsOnly || current.isEmpty()) {
                return null;
            }
            String heading = current.toString();
            current.setLength(0);
            currentTokens = 0;
            return heading;
        }

        /**
         * The budget is measured on the <em>joined</em> text, never on the sum of the parts: the line
         * breaks between paragraphs add tokens too. Summing put groups just over the budget, and the
         * splitter then cut a small, useless piece off each.
         */
        private void add(String paragraph) {
            if (paragraph.isBlank()) {
                return;
            }
            headingsOnly = false;
            int paragraphTokens = TokenCounter.count(paragraph);

            if (current.isEmpty()) {
                start(paragraph, paragraphTokens);
                return;
            }

            int joinedTokens = TokenCounter.count(current + PARAGRAPH_SEPARATOR + paragraph);

            // Close the group rather than go over. A paragraph bigger than the whole budget gets a group
            // of its own, which the splitter then cuts.
            if (joinedTokens > budgetTokens) {
                flush();
                start(paragraph, paragraphTokens);
                return;
            }

            current.append(PARAGRAPH_SEPARATOR).append(paragraph);
            currentTokens = joinedTokens;
        }

        private void start(String paragraph, int paragraphTokens) {
            current.append(paragraph);
            currentTokens = paragraphTokens;
            section = path.current();
        }

        /** Whether {@code text} can join the open group, or start one, without going over the budget. */
        private boolean fits(String text) {
            String joined = current.isEmpty() ? text : current + PARAGRAPH_SEPARATOR + text;
            return TokenCounter.count(joined) <= budgetTokens;
        }

        /** Closes the open group, then stores {@code text} as a chunk of its own. */
        private void emitAlone(String text) {
            flush();
            start(text, 0);
            flush();
        }

        private void flush() {
            if (current.isEmpty()) {
                return;
            }
            Map<String, Object> metadata = new HashMap<>(sourceMetadata);
            metadata.put(ChunkMetadata.BLOCK_TYPE, ChunkMetadata.PROSE);
            if (section != null) {
                metadata.put(ChunkMetadata.SECTION, section);
            }
            out.add(new Document(current.toString(), metadata));
            current.setLength(0);
            currentTokens = 0;
            headingsOnly = true;
        }
    }
}

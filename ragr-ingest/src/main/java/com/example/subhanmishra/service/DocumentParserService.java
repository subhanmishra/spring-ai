package com.example.subhanmishra.service;

import com.example.subhanmishra.chunk.ChunkMetadata;
import com.example.subhanmishra.citation.CitationResolver;
import com.example.subhanmishra.config.IngestionProperties;
import com.example.subhanmishra.exception.DocumentProcessingException;
import com.example.subhanmishra.service.parse.ContentBlock;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     */
    private record SourceUnit(List<ContentBlock> blocks, Map<String, Object> metadata) {
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


            if (fileName.toLowerCase().endsWith(".pdf") || contentType.contains("pdf")) {
                return parsePdf(resource);
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

    private Stream<Document> chunk(List<SourceUnit> units) {
        try {
            // Submitted to documentProcessingPool as a whole, so the parallel stream runs on that pool.
            // A bare parallelStream() would run on the common pool and bypass the limit on parse threads.
            // Collected inside the pool so all the work is done there.
            List<Document> chunks = documentProcessingPool.submit(() ->
                    units.parallelStream()
                         .flatMap(unit -> coalesceBlocks(unit.blocks(), unit.metadata()).stream())
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
     * final: the splitter would cut a row in half and drop the header from the rest.
     */
    // Package-private so the chunking tests can drive the splitter path directly.
    Stream<Document> splitIfOverBudget(Document chunk) {
        if (ChunkMetadata.TABLE.equals(chunk.getMetadata().get(ChunkMetadata.BLOCK_TYPE))) {
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
     * Paragraphs are joined until the next one would go over the budget, or until a numbered section
     * heading starts a new section (see {@link ProseGroups#add}). Without joining, every short paragraph
     * was a chunk of its own.
     * <p>
     * A table closes the open prose group and becomes its own chunk, or several, split between rows with
     * the header repeated. Prose and tables never share a chunk.
     *
     * @param blocks         the blocks of one page or one Tika document, in reading order
     * @param sourceMetadata metadata carried onto every chunk produced from this unit
     */
    List<Document> coalesceBlocks(List<ContentBlock> blocks, Map<String, Object> sourceMetadata) {
        List<Document> chunks = new ArrayList<>();
        ProseGroups prose = new ProseGroups(chunks, sourceMetadata, ingestionProperties.chunkSize());
        int tableIndex = 0;

        for (ContentBlock block : blocks) {
            switch (block) {
                case ContentBlock.Prose paragraphs -> {
                    for (String paragraph : PARAGRAPH_PATTERN.split(paragraphs.text())) {
                        prose.add(paragraph);
                    }
                }
                case ContentBlock.Table table -> {
                    prose.flush();
                    chunks.addAll(renderTable(table, tableIndex++, sourceMetadata));
                }
            }
        }
        prose.flush();

        log.debug("Coalesced {} block(s) into {} chunk(s) on thread: {}",
                  blocks.size(), chunks.size(), Thread.currentThread().getName());
        return chunks;
    }

    /** Retained for the prose-only case: one flat document in, budgeted prose groups out. */
    List<Document> coalesceParagraphs(Document source) {
        return coalesceBlocks(List.of(new ContentBlock.Prose(source.getText())), source.getMetadata());
    }

    private List<Document> renderTable(ContentBlock.Table table, int tableIndex, Map<String, Object> sourceMetadata) {
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
            documents.add(new Document(piece.markdown(), metadata));
        }

        if (documents.size() > 1) {
            log.debug("Table {} of {} rows split into {} chunks, header repeated on each",
                      tableIndex, table.rows().size(), documents.size());
        }
        return documents;
    }

    /**
     * Collects paragraphs into prose chunks within the budget. A separate object because a table can
     * close the open group at any point.
     */
    private static final class ProseGroups {

        private final List<Document> out;
        private final Map<String, Object> sourceMetadata;
        private final int budgetTokens;

        private final StringBuilder current = new StringBuilder();
        private int currentTokens;

        private ProseGroups(List<Document> out, Map<String, Object> sourceMetadata, int budgetTokens) {
            this.out = out;
            this.sourceMetadata = sourceMetadata;
            this.budgetTokens = budgetTokens;
        }

        /**
         * A numbered section heading closes the open group, so each section starts its own chunk. An
         * embedding is the average of its chunk, so a section buried half-way into a chunk about
         * something else is hard to find.
         * <p>
         * Only once the open group has {@link #SECTION_BREAK_MIN_TOKENS}: a shorter tail would embed as
         * little more than noise, so the heading joins it instead.
         */
        private static final int SECTION_BREAK_MIN_TOKENS = 80;

        /**
         * The budget is measured on the <em>joined</em> text, never on the sum of the parts: the line
         * breaks between paragraphs add tokens too. Summing put groups just over the budget, and the
         * splitter then cut a small, useless piece off each.
         */
        private void add(String paragraph) {
            if (paragraph.isBlank()) {
                return;
            }
            int paragraphTokens = TokenCounter.count(paragraph);

            if (current.isEmpty()) {
                current.append(paragraph);
                currentTokens = paragraphTokens;
                return;
            }

            if (currentTokens >= SECTION_BREAK_MIN_TOKENS && CitationResolver.opensWithSectionHeading(paragraph)) {
                flush();
                current.append(paragraph);
                currentTokens = paragraphTokens;
                return;
            }

            int joinedTokens = TokenCounter.count(current + PARAGRAPH_SEPARATOR + paragraph);

            // Close the group rather than go over. A paragraph bigger than the whole budget gets a group
            // of its own, which the splitter then cuts.
            if (joinedTokens > budgetTokens) {
                flush();
                current.append(paragraph);
                currentTokens = paragraphTokens;
                return;
            }

            current.append(PARAGRAPH_SEPARATOR).append(paragraph);
            currentTokens = joinedTokens;
        }

        private void flush() {
            if (current.isEmpty()) {
                return;
            }
            Map<String, Object> metadata = new HashMap<>(sourceMetadata);
            metadata.put(ChunkMetadata.BLOCK_TYPE, ChunkMetadata.PROSE);
            out.add(new Document(current.toString(), metadata));
            current.setLength(0);
            currentTokens = 0;
        }
    }
}

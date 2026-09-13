package com.example.subhanmishra.service;

import com.example.subhanmishra.config.RagProperties;
import com.example.subhanmishra.exception.DocumentProcessingException;
import com.example.subhanmishra.service.parse.ChunkMetadata;
import com.example.subhanmishra.service.parse.ContentBlock;
import com.example.subhanmishra.service.parse.TableChunker;
import com.example.subhanmishra.service.parse.TokenCounter;
import com.example.subhanmishra.service.parse.XhtmlBlockHandler;
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

import java.io.IOException;
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

    private final TextSplitter textSplitter;
    private final ForkJoinPool documentProcessingPool;
    private final RagProperties ragProperties;

    public DocumentParserService(TextSplitter textSplitter, ForkJoinPool documentProcessingPool, RagProperties ragProperties) {
        this.textSplitter = textSplitter;
        this.documentProcessingPool = documentProcessingPool;
        this.ragProperties = ragProperties;
    }

    /**
     * One unit of source content that chunking may not span: a single PDF page, or a whole Tika document.
     * Its metadata is inherited by every chunk produced from it, which is why groups may never cross the
     * boundary - chunk metadata carries {@code pageNumber} and the system prompt asks the model to cite it.
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
     * PDFs still go through {@code PagePdfDocumentReader}, which yields flat page text with no structure,
     * so every page becomes a single prose block. Recovering tables from a PDF means reconstructing them
     * from glyph positions, which is separate work; routing PDFs through Tika instead would not help,
     * because Tika's default PDF handler has no table support either and its marked-content handler works
     * only on tagged PDFs.
     */
    private Map<String, Object> parsePdf(Resource resource) {

        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPageBottomMargin(0)
                .withPageTopMargin(0)
                .build();

        PagePdfDocumentReader documentReader = new PagePdfDocumentReader(resource, config);
        List<Document> pageDocs = documentReader.get();

        List<SourceUnit> units = pageDocs.stream()
                                         .map(page -> new SourceUnit(List.of(new ContentBlock.Prose(page.getText())),
                                                                     page.getMetadata()))
                                         .toList();

        return Map.of("documentStream", chunk(units), "totalPages", pageDocs.size());
    }

    /**
     * Everything that is not a PDF is read through Tika, but with our own SAX handler in place of the
     * {@code BodyContentHandler} that {@code TikaDocumentReader} defaults to. Tika already recovers table
     * structure from DOCX, XLSX, PPTX and HTML; the default handler simply discards the markup.
     */
    private Map<String, Object> parseGenericFile(Resource resource) {
        XhtmlBlockHandler blockHandler = new XhtmlBlockHandler();

        // Called for its side effect: get() runs Tika's AutoDetectParser and feeds the SAX events to our
        // handler. The Document it returns is built from handler.toString() and is of no use here - the
        // blocks the handler collected are what we want.
        new TikaDocumentReader(resource, blockHandler, ExtractedTextFormatter.defaults()).get();

        List<ContentBlock> blocks = blockHandler.blocks();
        long tableCount = blocks.stream().filter(ContentBlock.Table.class::isInstance).count();
        log.info("Tika produced {} block(s) for {}, {} of them tables", blocks.size(), resource.getFilename(), tableCount);

        Map<String, Object> sourceMetadata = Map.of(TikaDocumentReader.METADATA_SOURCE,
                                                    resource.getFilename() != null ? resource.getFilename() : "document");

        // Tika reads the file as a whole, so there is exactly one "page".
        return Map.of("documentStream", chunk(List.of(new SourceUnit(blocks, sourceMetadata))), "totalPages", 1);
    }

    private Stream<Document> chunk(List<SourceUnit> units) {
        try {
            // To correctly use the custom thread pool, the entire parallel stream operation
            // must be submitted as a task. Calling .parallelStream() by itself would use the
            // common ForkJoinPool, defeating the purpose of our bulkhead.
            // We collect the results into a list within the pool to ensure the stream is fully
            // processed before returning.
            List<Document> chunks = documentProcessingPool.submit(() ->
                    units.parallelStream() // This will now execute within the documentProcessingPool
                         .flatMap(unit -> coalesceBlocks(unit.blocks(), unit.metadata()).stream())
                         .flatMap(this::splitIfOverBudget)
                         .collect(Collectors.toList()) // Execute the stream and collect results
            ).get();

            return chunks.stream(); // Return a new stream over the collected chunks

        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt(); // Preserve the interrupted status
            throw new DocumentProcessingException("Failed to process document stream in parallel", e);
        }
    }

    /**
     * Only prose still needs the splitter, and only when coalescing could not keep it under budget. A table
     * chunk is already final: {@code TokenTextSplitter} would cut its Markdown at some sentence-like
     * boundary partway through a row and would not repeat the header on the remainder, which is precisely
     * the failure the table path exists to prevent.
     */
    private Stream<Document> splitIfOverBudget(Document chunk) {
        if (ChunkMetadata.TABLE.equals(chunk.getMetadata().get(ChunkMetadata.BLOCK_TYPE))) {
            return Stream.of(chunk);
        }
        return textSplitter.apply(List.of(chunk)).stream();
    }

    /**
     * Groups one source unit's blocks into chunks that fill {@code app.rag.chunk-size} tokens.
     * <p>
     * Prose is coalesced paragraph by paragraph until the next paragraph would overflow the budget -
     * without this, every short paragraph became its own chunk and the configured chunk size was never
     * reached. A table interrupts that run: the open prose group is closed first, then the table is emitted
     * as its own chunk, or its own run of chunks split between rows with the header repeated on each.
     * Prose and table content therefore never share a chunk, so a table is never truncated by the prose
     * that happened to follow it.
     *
     * @param blocks         the blocks of one page or one Tika document, in reading order
     * @param sourceMetadata metadata carried onto every chunk produced from this unit
     */
    List<Document> coalesceBlocks(List<ContentBlock> blocks, Map<String, Object> sourceMetadata) {
        List<Document> chunks = new ArrayList<>();
        ProseGroups prose = new ProseGroups(chunks, sourceMetadata, ragProperties.chunkSize());
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
                TableChunker.chunk(table, ragProperties.chunkSize(), ragProperties.maxEmbedTokens());

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
     * Accumulates paragraphs into budgeted prose chunks. A mutable helper rather than inline state because
     * a table can interrupt the run at any point and force the open group closed.
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

        private void add(String paragraph) {
            if (paragraph.isBlank()) {
                return;
            }
            int tokens = TokenCounter.count(paragraph);

            // Close the current group rather than overshoot. A paragraph bigger than the whole budget
            // lands in a group of its own, and textSplitter cuts it just as it did before.
            if (currentTokens > 0 && currentTokens + tokens > budgetTokens) {
                flush();
            }

            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(paragraph);
            currentTokens += tokens;
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

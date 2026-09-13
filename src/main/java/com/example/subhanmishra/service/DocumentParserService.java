package com.example.subhanmishra.service;

import com.example.subhanmishra.config.RagProperties;
import com.example.subhanmishra.exception.DocumentProcessingException;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
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

    /**
     * The same tokenizer TokenTextSplitter uses by default, so the budget counted here and the budget
     * the splitter enforces cannot drift apart. jtokkit encodings are stateless and safe to share
     * across the threads of documentProcessingPool.
     */
    private final static Encoding ENCODING =
            Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    private final TextSplitter textSplitter;
    private final ForkJoinPool documentProcessingPool;
    private final RagProperties ragProperties;

    public DocumentParserService(TextSplitter textSplitter, ForkJoinPool documentProcessingPool, RagProperties ragProperties) {
        this.textSplitter = textSplitter;
        this.documentProcessingPool = documentProcessingPool;
        this.ragProperties = ragProperties;
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

    private Map<String, Object> parsePdf(Resource resource) {

        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPageBottomMargin(0)
                .withPageTopMargin(0)
                .build();

        PagePdfDocumentReader documentReader = new PagePdfDocumentReader(resource, config);
        List<Document> pageDocs = documentReader.get();

        // Strategy: Split by paragraph, then by token, to preserve semantic context.
        Stream<Document> chunkStream = splitIntoParagraphsAndThenChunks(pageDocs);
        return Map.of("documentStream", chunkStream, "totalPages", pageDocs.size());
    }

    private Map<String, Object> parseGenericFile(Resource resource) {
        TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(resource);
        List<Document> documents = tikaDocumentReader.get();
        Stream<Document> chunkStream = splitIntoParagraphsAndThenChunks(documents);
        // For generic files, we consider it as a single "page"
        return Map.of("documentStream", chunkStream, "totalPages", documents.size());
    }

    private Stream<Document> splitIntoParagraphsAndThenChunks(List<Document> documents) {
        try {
            // To correctly use the custom thread pool, the entire parallel stream operation
            // must be submitted as a task. Calling .parallelStream() by itself would use the
            // common ForkJoinPool, defeating the purpose of our bulkhead.
            // We collect the results into a list within the pool to ensure the stream is fully
            // processed before returning.
            List<Document> chunks = documentProcessingPool.submit(() ->
                    documents.parallelStream() // This will now execute within the documentProcessingPool
                            // Join consecutive paragraphs until they fill the token budget. Splitting by
                            // paragraph alone left every short paragraph as its own chunk, so chunk-size
                            // was never reached and retrieval saw single-word fragments.
                            .flatMap(doc -> coalesceParagraphs(doc).stream())
                            .flatMap(groupDoc ->
                                    // Only groups that still exceed the budget get cut here; the rest pass through.
                                    textSplitter.apply(List.of(groupDoc)).stream()
                            )
                            .collect(Collectors.toList()) // Execute the stream and collect results
            ).get();

            return chunks.stream(); // Return a new stream over the collected chunks

        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt(); // Preserve the interrupted status
            throw new DocumentProcessingException("Failed to process document stream in parallel", e);
        }
    }

    /**
     * Groups the paragraphs of one source document into units that fill {@code app.rag.chunk-size} tokens.
     * <p>
     * Grouping never spans source documents: {@code PagePdfDocumentReader} emits one document per page, so
     * every group inherits exactly one page's metadata. That matters beyond tidiness - chunk metadata
     * carries {@code pageNumber}, and the system prompt asks the model to cite page numbers, so merging
     * across pages would produce wrong citations.
     *
     * @param source one page (or one Tika document) to group the paragraphs of
     * @return budgeted groups, in reading order, each carrying the source's metadata
     */
    List<Document> coalesceParagraphs(Document source) {
        List<Document> groups = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;

        for (String paragraph : PARAGRAPH_PATTERN.split(source.getText())) {
            if (paragraph.isBlank()) {
                continue;
            }
            int tokens = ENCODING.countTokens(paragraph);

            // Close the current group rather than overshoot. A paragraph bigger than the whole budget
            // lands in a group of its own, and textSplitter cuts it just as it did before.
            if (currentTokens > 0 && currentTokens + tokens > ragProperties.chunkSize()) {
                groups.add(new Document(current.toString(), source.getMetadata()));
                current.setLength(0);
                currentTokens = 0;
            }

            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(paragraph);
            currentTokens += tokens;
        }

        if (!current.isEmpty()) {
            groups.add(new Document(current.toString(), source.getMetadata()));
        }

        log.debug("Coalesced page into {} chunk group(s) on thread: {}", groups.size(), Thread.currentThread().getName());
        return groups;
    }
}

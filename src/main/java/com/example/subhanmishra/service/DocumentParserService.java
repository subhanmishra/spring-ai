package com.example.subhanmishra.service;

import com.example.subhanmishra.exception.DocumentProcessingException;
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
    private final TextSplitter textSplitter;
    private final ForkJoinPool documentProcessingPool;

    public DocumentParserService(TextSplitter textSplitter, ForkJoinPool documentProcessingPool) {
        this.textSplitter = textSplitter;
        this.documentProcessingPool = documentProcessingPool;
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
        // Use a regex for splitting by one or more blank lines (which separate paragraphs).
        final Pattern paragraphPattern = Pattern.compile("\\n\\s*\\n");

        try {
            // To correctly use the custom thread pool, the entire parallel stream operation
            // must be submitted as a task. Calling .parallelStream() by itself would use the
            // common ForkJoinPool, defeating the purpose of our bulkhead.
            // We collect the results into a list within the pool to ensure the stream is fully
            // processed before returning.
            List<Document> chunks = documentProcessingPool.submit(() ->
                    documents.parallelStream() // This will now execute within the documentProcessingPool
                            .flatMap(doc ->
                                    // For each page, create a stream of its paragraphs
                                    Stream.of(paragraphPattern.split(doc.getText()))
                                            // Filter out any empty strings that result from splitting
                                            .filter(p -> !p.isBlank())
                                            // Create a temporary Document for each paragraph, preserving metadata
                                            .map(p -> new Document(p, doc.getMetadata()))
                            )
                            .peek(doc -> log.info("Processing paragraph on thread: {}", Thread.currentThread().getName()))
                            // We now have a lazy stream of paragraph-level documents
                            .flatMap(paraDoc ->
                                    // Apply the token splitter to each paragraph and stream the resulting chunks
                                    textSplitter.apply(List.of(paraDoc)).stream()
                            )
                            .collect(Collectors.toList()) // Execute the stream and collect results
            ).get();

            return chunks.stream(); // Return a new stream over the collected chunks

        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt(); // Preserve the interrupted status
            throw new DocumentProcessingException("Failed to process document stream in parallel", e);
        }
    }
}

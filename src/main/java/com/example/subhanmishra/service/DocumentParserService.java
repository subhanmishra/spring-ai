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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DocumentParserService {

    private final static Logger log = LoggerFactory.getLogger(DocumentParserService.class);
    private final TextSplitter textSplitter;

    public DocumentParserService(TextSplitter textSplitter) {
        this.textSplitter = textSplitter;
    }

    public List<Document> parse(MultipartFile file) {

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

    private List<Document> parsePdf(Resource resource) {

        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPageBottomMargin(0)
                .withPageTopMargin(0)
                .build();

        PagePdfDocumentReader documentReader = new PagePdfDocumentReader(resource, config);
        List<Document> pageDocs = documentReader.get();

        // Strategy: Split by paragraph, then by token, to preserve semantic context.
        return splitIntoParagraphsAndThenChunks(pageDocs);
    }

    private List<Document> parseGenericFile(Resource resource) {
        TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(resource);
        return splitIntoParagraphsAndThenChunks(tikaDocumentReader.get());
    }

    private List<Document> splitIntoParagraphsAndThenChunks(List<Document> documents) {
        // Use a regex for splitting by one or more blank lines (which separate paragraphs).
        final Pattern paragraphPattern = Pattern.compile("\\n\\s*\\n");

        // 1. Split each document into paragraphs.
        List<Document> paragraphDocs = documents.stream().flatMap(doc -> {
            String[] paragraphs = paragraphPattern.split(doc.getText());
            return Stream.of(paragraphs).map(p -> new Document(p, doc.getMetadata()));
        }).toList();

        // 2. Apply the token splitter to the paragraph-level documents.
        return paragraphDocs.stream()
                .flatMap(paraDoc -> textSplitter.apply(List.of(paraDoc)).stream())
                .collect(Collectors.toList());
    }
}

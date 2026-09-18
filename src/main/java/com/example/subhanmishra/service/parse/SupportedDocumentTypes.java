package com.example.subhanmishra.service.parse;

import java.util.Set;

/**
 * The file types the parser can actually handle.
 *
 * <p>This lives beside the parser because it is a statement about {@code DocumentParserService}:
 * {@code parsePdf} handles the first entry and {@code parseGenericFile} hands the rest to Tika. DOCX,
 * XLSX, PPTX and HTML are on the list deliberately - the table-recovery path exists specifically to
 * read their {@code <table>} markup - even though the endpoint's documentation long claimed a
 * narrower set.
 *
 * <p>It is a constant rather than an {@code app.rag.*} property on purpose. The set is determined by
 * what the parser has been verified to carry through chunking and the table paths, not by deployment
 * preference; adding a format should require someone to check that it works, which a configuration
 * knob would let them skip.
 *
 * <p><strong>This is a type filter, not a content scanner.</strong> Renaming {@code payload.exe} to
 * {@code payload.pdf} gets it past this check and into PDFBox, which then rejects it as a corrupt
 * PDF. The point here is to refuse files that were never candidates before any database row is
 * written, not to validate that a file is what it claims to be.
 */
public final class SupportedDocumentTypes {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".pdf",
            ".docx",
            ".xlsx",
            ".pptx",
            ".html", ".htm",
            ".txt",
            ".md",
            ".csv");

    /**
     * Consulted only when the filename carries no extension to judge by. Clients routinely send
     * {@code application/octet-stream} for types they cannot guess, so a declared content-type is
     * treated as a fallback rather than as evidence.
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/html",
            "text/plain",
            "text/markdown",
            "text/csv");

    private SupportedDocumentTypes() {
    }

    public static boolean isSupported(String filename, String contentType) {
        String name = filename == null ? "" : filename.toLowerCase();

        if (hasExtension(name)) {
            // An extension that is present but not allowed is a rejection, whatever the content-type
            // claims - otherwise a client sending octet-stream could bypass the list entirely.
            return ALLOWED_EXTENSIONS.stream().anyMatch(name::endsWith);
        }

        String type = contentType == null ? "" : contentType.toLowerCase();
        // Strip any parameters, e.g. "text/plain;charset=UTF-8".
        int parameterStart = type.indexOf(';');
        if (parameterStart >= 0) {
            type = type.substring(0, parameterStart);
        }
        return ALLOWED_CONTENT_TYPES.contains(type.trim());
    }

    /** The allowed extensions, for error messages and documentation. */
    public static String describeAllowed() {
        return ALLOWED_EXTENSIONS.stream().sorted().reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static boolean hasExtension(String name) {
        int lastDot = name.lastIndexOf('.');
        int lastSeparator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return lastDot > lastSeparator + 1 && lastDot < name.length() - 1;
    }
}

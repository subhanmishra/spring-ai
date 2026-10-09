package com.example.subhanmishra.service.parse;

import java.util.Set;

/**
 * The file types {@code DocumentParserService} can handle: PDF itself, the rest through Tika.
 *
 * <p><b>A constant, not a setting, on purpose.</b> The list is what the parser has been checked to
 * handle, tables included. Adding a format should mean checking it works, which a setting would skip.
 *
 * <p><b>A type filter, not a content scanner.</b> {@code payload.exe} renamed to {@code payload.pdf}
 * passes, and is then rejected by the PDF reader as corrupt. The point is to refuse files that were never
 * candidates before anything is written to the database.
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
     * Used only when the filename has no extension. Clients often send {@code application/octet-stream}
     * for everything, so the declared type is a fallback, not evidence.
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

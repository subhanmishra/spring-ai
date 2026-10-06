package com.example.subhanmishra.chunk;

import org.jspecify.annotations.Nullable;

/**
 * The metadata keys every stored chunk carries, and how to read them back.
 *
 * <p>Ingestion writes these and the chat path, the retrieval diagnostic and evaluation read them, so
 * they live here rather than with any one of those. The key strings are persisted in the vector
 * store's {@code metadata} JSONB column: renaming one orphans every chunk already written under the old
 * name, so a rename means re-ingesting the corpus.
 */
public final class ChunkMetadata {

    /** The {@code document_metadata} id the chunk belongs to, as a string. */
    public static final String DOCUMENT_ID = "documentId";

    /** The uploaded file's name, as the citation header shows it. */
    public static final String FILE_NAME = "fileName";

    public static final String CONTENT_TYPE = "contentType";

    /** 1-based PDF page. Absent for Tika sources, which have no page attribution. */
    public static final String PAGE_NUMBER = "pageNumber";

    /** 0-based position of the chunk within its document. */
    public static final String CHUNK_INDEX = "chunkIndex";

    /**
     * {@link #PROSE} or {@link #TABLE}. Present on every chunk. Ingestion needs it to skip the text
     * splitter for tables, and it makes a retrieved table identifiable when debugging an answer.
     */
    public static final String BLOCK_TYPE = "blockType";

    public static final String PROSE = "prose";
    public static final String TABLE = "table";

    /** 0-based position of the table within its source unit (one PDF page, or one Tika document). */
    public static final String TABLE_INDEX = "tableIndex";

    /** The 1-based data rows this chunk covers, e.g. {@code "13-24"}, absent for a header-only table. */
    public static final String TABLE_ROWS = "tableRows";

    /**
     * The numbered section the chunk belongs to, as its heading line - {@code "9.2.6. Set the Active
     * Spring Profiles"}: the first heading inside the chunk, or else the last one before it in the same
     * document. Absent before the first heading and for documents with none. Evaluation groups metrics
     * and the human review view by it.
     */
    public static final String SECTION = "section";

    /**
     * A short hash of everything that decides what a chunk contains - the chunk-shaping settings, the
     * parser revision and the embedding model - stamped on every chunk at ingest. Chunks from before and
     * after a pipeline change can sit side by side until the corpus is re-ingested, and a turn carries the
     * versions of the chunks it was answered from, so evaluation can tell the two apart.
     */
    public static final String PIPELINE_VERSION = "pipelineVersion";

    private ChunkMetadata() {
    }

    public static @Nullable String asString(@Nullable Object value) {
        return value != null ? value.toString() : null;
    }

    /**
     * Metadata makes a round trip through a JSONB column, so a value written as an {@code int} can come
     * back as any {@link Number} subtype - or, for a page number a reader supplied as text, as a String.
     */
    public static @Nullable Integer asInteger(@Nullable Object value) {
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
}

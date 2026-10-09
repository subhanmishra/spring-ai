package com.example.subhanmishra.chunk;

import org.jspecify.annotations.Nullable;

/**
 * The metadata keys every stored chunk carries, and how to read them back.
 *
 * <p>Ingestion writes them; chat, the retrieval diagnostic and evaluation read them. The keys are
 * stored in each chunk's {@code metadata} column, so renaming one strands every chunk written under the
 * old name: a rename means re-ingesting.
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
     * {@link #PROSE} or {@link #TABLE}, on every chunk. Ingestion uses it to keep tables away from the
     * text splitter, and it shows which retrieved chunks are tables.
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
     * A short hash of everything that decides a chunk's content and vector - the chunk settings, the
     * parser revision, the embedding model and its task prefix - stamped on every chunk at ingest. A turn
     * carries the versions of its chunks, so evaluation can separate results from before and after a
     * pipeline change. It is a label, not permission to mix: a change still means re-ingesting everything.
     */
    public static final String PIPELINE_VERSION = "pipelineVersion";

    private ChunkMetadata() {
    }

    public static @Nullable String asString(@Nullable Object value) {
        return value != null ? value.toString() : null;
    }

    /**
     * Metadata goes through a JSON column, so an {@code int} can come back as any {@link Number} type,
     * or as a String when a caller supplied it as text.
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

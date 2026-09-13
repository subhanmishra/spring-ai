package com.example.subhanmishra.service.parse;

/**
 * Metadata keys the parser puts on every chunk so the rest of the pipeline can tell a table apart from
 * prose - the ingestion path needs it to skip the text splitter, and it makes a retrieved table
 * identifiable when debugging an answer.
 */
public final class ChunkMetadata {

    /** {@link #PROSE} or {@link #TABLE}. Present on every chunk. */
    public static final String BLOCK_TYPE = "blockType";

    public static final String PROSE = "prose";
    public static final String TABLE = "table";

    /** 0-based position of the table within its source unit (one PDF page, or one Tika document). */
    public static final String TABLE_INDEX = "tableIndex";

    /** The 1-based data rows this chunk covers, e.g. {@code "13-24"}, absent for a header-only table. */
    public static final String TABLE_ROWS = "tableRows";

    private ChunkMetadata() {
    }
}

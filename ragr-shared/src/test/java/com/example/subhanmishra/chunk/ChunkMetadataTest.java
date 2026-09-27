package com.example.subhanmishra.chunk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkMetadataTest {

    @Test
    void readsAnyNumberSubtypeAsAnInteger() {
        assertThat(ChunkMetadata.asInteger(12)).isEqualTo(12);
        assertThat(ChunkMetadata.asInteger(12L)).isEqualTo(12);
        assertThat(ChunkMetadata.asInteger(12.0)).isEqualTo(12);
    }

    @Test
    void readsANumericStringAsAnInteger() {
        assertThat(ChunkMetadata.asInteger(" 590 ")).isEqualTo(590);
    }

    @Test
    void readsAnythingElseAsAbsent() {
        assertThat(ChunkMetadata.asInteger("xii")).isNull();
        assertThat(ChunkMetadata.asInteger(null)).isNull();
        assertThat(ChunkMetadata.asInteger(true)).isNull();
    }

    @Test
    void readsAStringFromAnyValue() {
        assertThat(ChunkMetadata.asString(42)).isEqualTo("42");
        assertThat(ChunkMetadata.asString(null)).isNull();
    }
}

package com.example.subhanmishra.repository;

import com.example.subhanmishra.chunk.ChunkMetadata;
import com.example.subhanmishra.entity.KeywordChunk;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Keyword search's queries: case-sensitive exact-text matches in {@code vector_store}. Read-only - it
 * extends the bare {@code Repository}, so there is no save or delete; ragr-ingest owns the table.
 */
public interface KeywordChunkRepository extends org.springframework.data.repository.Repository<KeywordChunk, UUID> {

    /**
     * Chunks containing at least one of the names, counting only names found in at most
     * {@code maxMatches} chunks - a name in more is too common to point anywhere.
     *
     * @param documentId restricts the search to one document; null for all
     * @return each chunk with the chunk count of its rarest qualifying name
     */
    @Query("""
            WITH terms AS (SELECT DISTINCT unnest(ARRAY[:terms]::text[]) AS term),
                 matches AS (SELECT v.id, t.term
                             FROM vector_store v JOIN terms t ON strpos(v.content, t.term) > 0
                             WHERE CAST(:documentId AS text) IS NULL
                                OR v.metadata->>'""" + ChunkMetadata.DOCUMENT_ID + """
            ' = :documentId),
                 specific AS (SELECT term, count(*) AS chunks FROM matches GROUP BY term
                              HAVING count(*) <= :maxMatches)
            SELECT m.id, min(s.chunks) AS rarest
            FROM matches m JOIN specific s USING (term)
            GROUP BY m.id
            """)
    List<NameMatch> findSpecificMatches(@Param("terms") Collection<String> terms,
                                        @Param("maxMatches") int maxMatches,
                                        @Param("documentId") @Nullable String documentId);

    /**
     * The chunks, with their cosine distance to the question.
     *
     * @param queryEmbedding the question's embedding in pgvector's text form, {@code [0.1,0.2,...]}
     */
    @Query("""
            SELECT id, content, metadata::text AS metadata, embedding <=> CAST(:query AS vector) AS distance
            FROM vector_store WHERE id IN (:ids)
            """)
    List<KeywordChunk> findWithDistance(@Param("ids") Collection<UUID> ids, @Param("query") String queryEmbedding);

    /**
     * @param rarest how many chunks contain the rarest qualifying name this chunk holds
     */
    record NameMatch(UUID id, int rarest) {
    }
}

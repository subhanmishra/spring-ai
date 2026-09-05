package com.example.subhanmishra.repository;

import com.example.subhanmishra.entity.VectorStoreEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface VectorStoreRepository extends CrudRepository<VectorStoreEntity, UUID> {

    @Modifying
    @Query("DELETE FROM vector_store WHERE metadata->>'documentId' = :documentId")
    void deleteByDocumentId(@Param("documentId") String documentId);
}
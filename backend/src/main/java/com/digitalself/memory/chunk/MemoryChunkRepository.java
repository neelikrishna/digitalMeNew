package com.digitalself.memory.chunk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MemoryChunkRepository extends JpaRepository<MemoryChunk, UUID> {

    List<MemoryChunk> findByMemoryIdOrderByChunkIndexAsc(UUID memoryId);

    List<MemoryChunk> findByIdIn(Collection<UUID> ids);

    void deleteByMemoryId(UUID memoryId);

    /**
     * Memories with no chunks yet — those written before chunking existed, or
     * whose chunking failed. Drives the backfill.
     */
    @Query("""
            SELECT m.id FROM Memory m
            WHERE m.userId = :userId
              AND NOT EXISTS (SELECT 1 FROM MemoryChunk c WHERE c.memoryId = m.id)
            """)
    List<UUID> findMemoryIdsWithoutChunks(@Param("userId") UUID userId);
}

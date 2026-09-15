package com.digitalself.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemoryVersionRepository extends JpaRepository<MemoryVersion, UUID> {

    List<MemoryVersion> findByMemoryIdOrderByVersionNumberAsc(UUID memoryId);

    Optional<MemoryVersion> findFirstByMemoryIdOrderByVersionNumberDesc(UUID memoryId);

    /** Version counts for a batch of memories — used to label corrected memories in RAG context. */
    @Query("SELECT v.memoryId AS memoryId, COUNT(v) AS versionCount FROM MemoryVersion v "
            + "WHERE v.memoryId IN :memoryIds GROUP BY v.memoryId")
    List<VersionCount> countVersionsForMemories(@Param("memoryIds") Collection<UUID> memoryIds);

    interface VersionCount {
        UUID getMemoryId();

        long getVersionCount();
    }
}

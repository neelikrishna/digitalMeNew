package com.digitalself.files;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * The {@code memory_files} join table.
 *
 * <p>Direct SQL rather than an entity, for the same reason {@code EmbeddingStore}
 * uses it: this is a pure join table with no fields of its own, so entity
 * semantics would add a mapping to maintain and nothing else. Mapping it as a
 * {@code @ManyToMany} on {@code Memory} was the alternative, and was rejected
 * because it would load attachments on every memory read to serve a feature only
 * file ingestion uses.
 */
@Repository
public class MemoryFileLinkStore {

    private final JdbcTemplate jdbc;

    public MemoryFileLinkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: re-extraction must not fail on an edge that already exists. */
    public void link(UUID memoryId, UUID fileId) {
        jdbc.update("""
                INSERT INTO memory_files (memory_id, file_id)
                VALUES (?, ?)
                ON CONFLICT (memory_id, file_id) DO NOTHING
                """, memoryId, fileId);
    }

    public void unlinkMemory(UUID memoryId) {
        jdbc.update("DELETE FROM memory_files WHERE memory_id = ?", memoryId);
    }
}

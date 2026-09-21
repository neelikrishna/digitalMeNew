package com.digitalself.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Ranked full-text search for natural-language questions, using Postgres
 * tsvector so multi-word questions are stemmed and stop-worded properly.
 *
 * <p>Distinct from the {@code ILIKE} substring filter behind
 * {@code GET /api/memories?keyword=}: that one answers "which memories contain
 * this exact string", this one answers "which passages are relevant to this
 * question". Both are wanted, for different jobs.
 */
@Repository
public class MemoryTextSearch {

    private static final String MEMORY_TSVECTOR =
            "to_tsvector('english', coalesce(m.title, '') || ' ' || m.content)";
    private static final String CHUNK_TSVECTOR = "to_tsvector('english', c.content)";

    private final JdbcTemplate jdbc;

    public MemoryTextSearch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Passages matching the question, best first.
     *
     * <p>Returns chunks rather than memories so the result can be fused with
     * vector search, which also returns chunks, and so a citation can name the
     * passage. Chunks of sensitive memories have a null content column, so they
     * never match — the exclusion falls out of the storage model rather than
     * depending on a filter someone has to remember.
     */
    public List<UUID> searchChunks(UUID userId, String question, MemoryStatus status, int limit) {
        return jdbc.queryForList("""
                        SELECT c.id
                        FROM memory_chunks c
                        JOIN memories m ON m.id = c.memory_id, plainto_tsquery('english', ?) AS q
                        WHERE m.user_id = ?
                          AND m.status = ?
                          AND %s @@ q
                        ORDER BY ts_rank(%s, q) DESC
                        LIMIT ?
                        """.formatted(CHUNK_TSVECTOR, CHUNK_TSVECTOR),
                UUID.class, question, userId, status.name(), limit);
    }

    /**
     * Memory-level search, kept for callers that want whole memories rather than
     * passages — and as the fallback for memories that have no chunks yet.
     */
    public List<UUID> search(UUID userId, String question, MemoryStatus status, int limit) {
        return jdbc.queryForList("""
                        SELECT m.id
                        FROM memories m, plainto_tsquery('english', ?) AS q
                        WHERE m.user_id = ?
                          AND m.status = ?
                          AND %s @@ q
                        ORDER BY ts_rank(%s, q) DESC
                        LIMIT ?
                        """.formatted(MEMORY_TSVECTOR, MEMORY_TSVECTOR),
                UUID.class, question, userId, status.name(), limit);
    }
}

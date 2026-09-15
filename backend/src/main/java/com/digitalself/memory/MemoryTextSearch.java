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
 * this exact string", this one answers "which memories are relevant to this
 * question". Both are wanted, for different jobs.
 */
@Repository
public class MemoryTextSearch {

    private static final String TSVECTOR = "to_tsvector('english', coalesce(m.title, '') || ' ' || m.content)";

    private final JdbcTemplate jdbc;

    public MemoryTextSearch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> search(UUID userId, String question, MemoryStatus status, int limit) {
        return jdbc.queryForList("""
                        SELECT m.id
                        FROM memories m, plainto_tsquery('english', ?) AS q
                        WHERE m.user_id = ?
                          AND m.status = ?
                          AND %s @@ q
                        ORDER BY ts_rank(%s, q) DESC
                        LIMIT ?
                        """.formatted(TSVECTOR, TSVECTOR),
                UUID.class, question, userId, status.name(), limit);
    }
}

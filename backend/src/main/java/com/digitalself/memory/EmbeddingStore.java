package com.digitalself.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Direct SQL rather than JPA: Hibernate has no native mapping for pgvector's
 * `vector` type, and embeddings are write-once / read-by-similarity, so entity
 * semantics would add nothing. Vectors are passed as text and cast with
 * `::vector` on the server side.
 */
@Repository
public class EmbeddingStore {

    private final JdbcTemplate jdbc;

    public EmbeddingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void deleteForOwner(EmbeddingOwnerType ownerType, UUID ownerId) {
        jdbc.update("DELETE FROM embeddings WHERE owner_type = ? AND owner_id = ?", ownerType.name(), ownerId);
    }

    /**
     * Whether the embeddings table exists at all. It only does under the
     * {@code pgvector} profile, so callers that must not fail when semantic
     * search is unavailable — deletion paths especially — check first rather
     * than catching a SQL grammar error and guessing what it meant.
     */
    public boolean isAvailable() {
        try {
            return jdbc.queryForObject("SELECT to_regclass('embeddings') IS NOT NULL", Boolean.class);
        } catch (Exception e) {
            return false;
        }
    }

    public void replaceForOwner(EmbeddingOwnerType ownerType, UUID ownerId, List<float[]> chunks, String modelName) {
        deleteForOwner(ownerType, ownerId);
        for (int i = 0; i < chunks.size(); i++) {
            jdbc.update("""
                            INSERT INTO embeddings (owner_type, owner_id, chunk_index, embedding, model_name)
                            VALUES (?, ?, ?, ?::vector, ?)
                            """,
                    ownerType.name(), ownerId, i, toVectorLiteral(chunks.get(i)), modelName);
        }
    }

    /**
     * Nearest memories by cosine distance. Over-fetches because one memory can
     * own several chunks; results are collapsed to the best chunk per memory.
     */
    public List<ScoredMemory> searchMemories(UUID userId, float[] queryVector, MemoryStatus status, int limit) {
        String vector = toVectorLiteral(queryVector);
        List<ScoredMemory> rows = jdbc.query("""
                        SELECT m.id AS memory_id, (e.embedding <=> ?::vector) AS distance
                        FROM embeddings e
                        JOIN memories m ON m.id = e.owner_id
                        WHERE e.owner_type = 'MEMORY'
                          AND m.user_id = ?
                          AND m.status = ?
                        ORDER BY distance ASC
                        LIMIT ?
                        """,
                (rs, rowNum) -> new ScoredMemory(rs.getObject("memory_id", UUID.class), rs.getDouble("distance")),
                vector, userId, status.name(), limit * 3);

        Map<UUID, ScoredMemory> bestPerMemory = new LinkedHashMap<>();
        for (ScoredMemory row : rows) {
            bestPerMemory.merge(row.memoryId(), row,
                    (existing, candidate) -> candidate.distance() < existing.distance() ? candidate : existing);
        }
        return bestPerMemory.values().stream()
                .sorted((a, b) -> Double.compare(a.distance(), b.distance()))
                .limit(limit)
                .toList();
    }

    /** Memories that have no embedding yet — used to backfill after Ollama was unreachable. */
    public List<UUID> findMemoryIdsMissingEmbeddings(UUID userId, int limit) {
        return jdbc.queryForList("""
                        SELECT m.id
                        FROM memories m
                        WHERE m.user_id = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM embeddings e
                              WHERE e.owner_type = 'MEMORY' AND e.owner_id = m.id
                          )
                        LIMIT ?
                        """,
                UUID.class, userId, limit);
    }

    static String toVectorLiteral(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            joiner.add(Float.toString(value));
        }
        return joiner.toString();
    }
}

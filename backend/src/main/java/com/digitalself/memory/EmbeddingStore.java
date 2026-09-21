package com.digitalself.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
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
     * Nearest chunks by cosine distance.
     *
     * <p>Returns passages rather than memories because that is what a citation
     * needs to point at, and because full-text search returns the same unit —
     * fusing two ranked lists that identify different things would be
     * meaningless.
     *
     * <p>Joined through to {@code memories} so ownership and status are enforced
     * in the query. A chunk of a sensitive memory has no embedding at all, so
     * such memories cannot surface here regardless.
     */
    public List<ScoredChunk> searchChunks(UUID userId, float[] queryVector, MemoryStatus status, int limit) {
        return jdbc.query("""
                        SELECT c.id AS chunk_id, c.memory_id, (e.embedding <=> ?::vector) AS distance
                        FROM embeddings e
                        JOIN memory_chunks c ON c.id = e.owner_id
                        JOIN memories m ON m.id = c.memory_id
                        WHERE e.owner_type = 'CHUNK'
                          AND m.user_id = ?
                          AND m.status = ?
                        ORDER BY distance ASC
                        LIMIT ?
                        """,
                (rs, rowNum) -> new ScoredChunk(
                        rs.getObject("chunk_id", UUID.class),
                        rs.getObject("memory_id", UUID.class),
                        rs.getDouble("distance")),
                toVectorLiteral(queryVector), userId, status.name(), limit);
    }

    /** Chunks that have no embedding yet — drives the backfill. */
    public List<UUID> findChunkIdsMissingEmbeddings(UUID userId, int limit) {
        return jdbc.queryForList("""
                        SELECT c.id
                        FROM memory_chunks c
                        JOIN memories m ON m.id = c.memory_id
                        WHERE m.user_id = ?
                          AND m.sensitive = FALSE
                          AND NOT EXISTS (
                              SELECT 1 FROM embeddings e
                              WHERE e.owner_type = 'CHUNK' AND e.owner_id = c.id
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

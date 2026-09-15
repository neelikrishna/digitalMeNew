package com.digitalself.ai;

/**
 * Abstraction over the embedding model. `embeddings.model_name` in the schema
 * records which implementation/model produced a given vector, so switching
 * models later is a re-embedding migration, not a breaking change.
 * See docs/ai-architecture.md Section 7.
 */
public interface EmbeddingService {

    float[] embed(String text);

    String modelName();
}

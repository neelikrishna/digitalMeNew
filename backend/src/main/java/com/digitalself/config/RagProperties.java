package com.digitalself.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "digitalself.rag")
public class RagProperties {

    /** How many memories may be placed in the model's context for one answer. */
    private int maxContextMemories = 8;

    /**
     * Cosine distance ceiling for a vector hit to count as relevant. Above this,
     * a memory is treated as unrelated rather than weakly related — the system
     * would rather say "I don't have a memory of that" than stretch.
     */
    private double maxVectorDistance = 0.6;

    /**
     * Characters of any one memory placed in the model's context.
     *
     * <p>Needed since memories can be derived from whole documents: a 200-page
     * PDF becomes one memory, and eight of those would exceed the context window
     * of every model this is meant to run on. Truncation is visible in the
     * prompt rather than silent, so an answer drawn from a clipped memory can
     * say so. Retrieval itself is unaffected — search and embeddings still cover
     * the entire text.
     */
    private int maxCharsPerContextMemory = 2000;

    /**
     * Total tokens of retrieved passages allowed into one prompt.
     *
     * <p>A privacy control as much as a cost one: the requirement is that only
     * the minimum relevant context reaches the model, and an unbounded context
     * would send far more of someone's life than the question called for.
     *
     * <p>Retrieval stops at whole passages rather than clipping one in half — a
     * truncated passage is worse evidence than one fewer passage.
     */
    private int maxContextTokens = 3000;

    private int chunkSize = 1000;
    private int chunkOverlap = 100;

    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    public void setMaxContextTokens(int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
    }

    public int getMaxCharsPerContextMemory() {
        return maxCharsPerContextMemory;
    }

    public void setMaxCharsPerContextMemory(int maxCharsPerContextMemory) {
        this.maxCharsPerContextMemory = maxCharsPerContextMemory;
    }

    public int getMaxContextMemories() {
        return maxContextMemories;
    }

    public void setMaxContextMemories(int maxContextMemories) {
        this.maxContextMemories = maxContextMemories;
    }

    public double getMaxVectorDistance() {
        return maxVectorDistance;
    }

    public void setMaxVectorDistance(double maxVectorDistance) {
        this.maxVectorDistance = maxVectorDistance;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }
}

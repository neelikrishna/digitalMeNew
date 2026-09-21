package com.digitalself.memory.chunk;

import com.digitalself.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextChunkingStrategyTest {

    private TextChunkingStrategy strategyWith(int chunkSize, int overlap) {
        RagProperties properties = new RagProperties();
        properties.setChunkSize(chunkSize);
        properties.setChunkOverlap(overlap);
        return new TextChunkingStrategy(properties);
    }

    @Test
    void shortTextStaysOneChunk() {
        List<ChunkDraft> chunks = strategyWith(1000, 100).split("A short memory.");

        assertEquals(1, chunks.size());
        assertEquals("A short memory.", chunks.get(0).text());
        assertEquals(0, chunks.get(0).startOffset());
    }

    @Test
    void blankTextProducesNothing() {
        assertTrue(strategyWith(1000, 100).split("   ").isEmpty());
        assertTrue(strategyWith(1000, 100).split(null).isEmpty());
    }

    /**
     * The invariant citations depend on: a chunk must be exactly the span of the
     * original it claims, so a reader can be shown that span in the source.
     */
    @Test
    void everyChunkIsExactlyTheSpanItClaims() {
        String source = ("Paragraph one has some words in it.\n\n"
                + "Paragraph two is a little longer and says more things.\n\n"
                + "Paragraph three closes it out.\n\n").repeat(4);

        for (ChunkDraft chunk : strategyWith(120, 20).split(source)) {
            assertEquals(source.substring(chunk.startOffset(), chunk.endOffset()), chunk.text(),
                    "chunk text must match the source at its recorded offsets");
        }
    }

    @Test
    void splitsOnParagraphBoundariesRatherThanMidSentence() {
        String source = "First paragraph.\n\nSecond paragraph.\n\nThird paragraph.";

        List<ChunkDraft> chunks = strategyWith(25, 0).split(source);

        assertTrue(chunks.size() > 1);
        chunks.forEach(chunk -> assertFalse(chunk.text().isBlank()));
        // Nothing is lost: the last chunk reaches the end of the source.
        assertEquals(source.length(), chunks.get(chunks.size() - 1).endOffset());
    }

    @Test
    void breaksAnOversizedParagraphOnSentences() {
        String source = "One sentence here. Another sentence follows. A third one arrives. And a fourth.";

        List<ChunkDraft> chunks = strategyWith(40, 0).split(source);

        assertTrue(chunks.size() > 1);
        // Sentence-aligned splits mean chunks tend to end on terminal punctuation.
        assertTrue(chunks.get(0).text().strip().endsWith("."));
    }

    @Test
    void breaksOnWordsWhenASentenceIsItselfTooLong() {
        String source = "word ".repeat(100).strip();

        List<ChunkDraft> chunks = strategyWith(50, 0).split(source);

        assertTrue(chunks.size() > 1);
        chunks.forEach(chunk ->
                assertTrue(chunk.text().length() <= 50, "chunk exceeded the target size: " + chunk.text().length()));
    }

    @Test
    void consecutiveChunksOverlapSoABoundarySentenceStaysRetrievable() {
        String source = "Alpha one. Bravo two. Charlie three. Delta four. Echo five. Foxtrot six.";

        List<ChunkDraft> chunks = strategyWith(30, 15).split(source);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.get(1).startOffset() < chunks.get(0).endOffset(),
                "the second chunk should begin before the first one ends");
    }

    @Test
    void alwaysMakesForwardProgress() {
        // Overlap as large as the chunk would loop forever if progress were not forced.
        List<ChunkDraft> chunks = strategyWith(30, 30).split("word ".repeat(200).strip());

        assertFalse(chunks.isEmpty());
        for (int i = 1; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).startOffset() > chunks.get(i - 1).startOffset(),
                    "each chunk must start later than the one before it");
        }
    }

    @Test
    void chunksAreIndexedInOrderFromZero() {
        List<ChunkDraft> chunks = strategyWith(40, 5).split("Sentence one. Sentence two. Sentence three. Four.");

        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index());
        }
    }

    @Test
    void estimatesTokensForContextBudgeting() {
        ChunkDraft chunk = ChunkDraft.text(0, "x".repeat(400), 0, 400);

        assertEquals(100, chunk.estimateTokens(), "roughly four characters per token");
    }
}

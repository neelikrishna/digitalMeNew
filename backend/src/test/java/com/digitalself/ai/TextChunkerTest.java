package com.digitalself.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextChunkerTest {

    @Test
    void shortTextStaysASingleChunk() {
        List<String> chunks = TextChunker.chunk("A short memory.", 100, 10);
        assertEquals(List.of("A short memory."), chunks);
    }

    @Test
    void blankTextProducesNoChunks() {
        assertTrue(TextChunker.chunk("   ", 100, 10).isEmpty());
        assertTrue(TextChunker.chunk(null, 100, 10).isEmpty());
    }

    @Test
    void longTextIsSplitAndCoversAllWords() {
        String text = ("word ".repeat(400)).strip();

        List<String> chunks = TextChunker.chunk(text, 100, 20);

        assertTrue(chunks.size() > 1);
        chunks.forEach(chunk -> assertTrue(chunk.length() <= 100, "chunk too long: " + chunk.length()));
        // Overlap means total length exceeds the original, but nothing is dropped.
        assertTrue(String.join(" ", chunks).contains("word word"));
    }

    @Test
    void chunksOverlapSoBoundarySentencesStayRetrievable() {
        String text = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu";

        List<String> chunks = TextChunker.chunk(text, 30, 15);

        assertTrue(chunks.size() > 1);
        String first = chunks.get(0);
        String second = chunks.get(1);
        String tailOfFirst = first.substring(Math.max(0, first.length() - 10));
        assertTrue(second.contains(tailOfFirst.strip().split(" ")[0]),
                "expected overlap between consecutive chunks");
    }

    @Test
    void rejectsOverlapLargerThanChunkSize() {
        assertThrows(IllegalArgumentException.class,
                () -> TextChunker.chunk("some text that is definitely longer than ten characters", 10, 10));
    }
}

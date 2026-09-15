package com.digitalself.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits long content into overlapping chunks before embedding. Overlap keeps
 * a sentence that straddles a boundary retrievable from either side.
 */
public final class TextChunker {

    private TextChunker() {
    }

    public static List<String> chunk(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalised = text.strip();
        if (normalised.length() <= chunkSize) {
            return List.of(normalised);
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap must be smaller than chunkSize");
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalised.length()) {
            int end = Math.min(start + chunkSize, normalised.length());
            if (end < normalised.length()) {
                int lastSpace = normalised.lastIndexOf(' ', end);
                if (lastSpace > start) {
                    end = lastSpace;
                }
            }
            String chunk = normalised.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= normalised.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }
}

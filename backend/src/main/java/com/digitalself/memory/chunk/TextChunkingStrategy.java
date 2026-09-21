package com.digitalself.memory.chunk;

import com.digitalself.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits prose on natural boundaries rather than fixed character counts.
 *
 * <p>Paragraphs are packed into chunks up to a target size; a paragraph too
 * large to fit is broken on sentence boundaries, and a sentence too large for
 * that is broken on words. The point is that a retrieved passage should be a
 * readable unit — half a sentence retrieved out of a fixed-size window is worse
 * context for the model and worse evidence for the reader.
 *
 * <p>Every chunk satisfies {@code text.equals(source.substring(start, end))}.
 * That invariant is what lets a citation highlight the exact span in the
 * original document, and it is why chunks are cut from the source by offset
 * rather than reassembled from pieces.
 */
@Component
public class TextChunkingStrategy implements ChunkingStrategy {

    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    private final RagProperties properties;

    public TextChunkingStrategy(RagProperties properties) {
        this.properties = properties;
    }

    @Override
    public ChunkContentType contentType() {
        return ChunkContentType.TEXT;
    }

    @Override
    public List<ChunkDraft> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int targetSize = properties.getChunkSize();
        int overlap = Math.min(properties.getChunkOverlap(), Math.max(0, targetSize - 1));

        List<int[]> segments = segment(text, targetSize);
        if (segments.isEmpty()) {
            return List.of();
        }

        List<ChunkDraft> chunks = new ArrayList<>();
        int first = 0;
        while (first < segments.size()) {
            int last = first;
            int end = segments.get(first)[1];

            // Grow while the span from the first segment still fits. Measuring
            // from the first segment's start, not by summing lengths, keeps the
            // substring invariant true including the whitespace between them.
            while (last + 1 < segments.size()
                    && segments.get(last + 1)[1] - segments.get(first)[0] <= targetSize) {
                last++;
                end = segments.get(last)[1];
            }

            int start = segments.get(first)[0];
            String chunkText = text.substring(start, end);
            if (!chunkText.isBlank()) {
                chunks.add(ChunkDraft.text(chunks.size(), chunkText, start, end));
            }

            if (last + 1 >= segments.size()) {
                break;
            }
            first = nextStart(segments, first, last, end, overlap);
        }
        return chunks;
    }

    /**
     * Where the following chunk begins: far enough back to overlap by roughly the
     * configured amount, so a sentence straddling a boundary is retrievable from
     * either side — but always at least one segment forward, or the loop would
     * never terminate.
     */
    private int nextStart(List<int[]> segments, int first, int last, int end, int overlap) {
        int candidate = last + 1;
        for (int i = last; i > first; i--) {
            if (end - segments.get(i)[0] <= overlap) {
                candidate = i;
            } else {
                break;
            }
        }
        return Math.max(candidate, first + 1);
    }

    /**
     * Breaks the source into the smallest units that will be kept whole,
     * descending only as far as necessary: paragraphs, then sentences, then
     * words. Returns {start, end} offsets into the original.
     */
    private List<int[]> segment(String text, int targetSize) {
        List<int[]> segments = new ArrayList<>();
        for (int[] paragraph : splitOn(PARAGRAPH_BREAK, text, 0, text.length())) {
            if (paragraph[1] - paragraph[0] <= targetSize) {
                addIfNotBlank(segments, text, paragraph);
                continue;
            }
            for (int[] sentence : splitOn(SENTENCE_END, text, paragraph[0], paragraph[1])) {
                if (sentence[1] - sentence[0] <= targetSize) {
                    addIfNotBlank(segments, text, sentence);
                } else {
                    segments.addAll(splitOnWords(text, sentence[0], sentence[1], targetSize));
                }
            }
        }
        return segments;
    }

    private static List<int[]> splitOn(Pattern separator, String text, int from, int to) {
        List<int[]> parts = new ArrayList<>();
        Matcher matcher = separator.matcher(text).region(from, to);
        int cursor = from;
        while (matcher.find()) {
            parts.add(new int[]{cursor, matcher.start()});
            cursor = matcher.end();
        }
        parts.add(new int[]{cursor, to});
        return parts;
    }

    /**
     * Last resort for a single sentence longer than a whole chunk — a URL dump,
     * or text with no punctuation. Cuts at spaces so words survive intact.
     */
    private static List<int[]> splitOnWords(String text, int from, int to, int targetSize) {
        List<int[]> parts = new ArrayList<>();
        int cursor = from;
        while (cursor < to) {
            int end = Math.min(cursor + targetSize, to);
            if (end < to) {
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace > cursor) {
                    end = lastSpace;
                }
            }
            parts.add(new int[]{cursor, end});
            cursor = end;
            while (cursor < to && Character.isWhitespace(text.charAt(cursor))) {
                cursor++;
            }
        }
        return parts;
    }

    private static void addIfNotBlank(List<int[]> segments, String text, int[] span) {
        if (span[1] > span[0] && !text.substring(span[0], span[1]).isBlank()) {
            segments.add(span);
        }
    }
}

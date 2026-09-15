package com.digitalself.extraction;

import com.digitalself.ai.AIService;
import com.digitalself.memory.MemoryType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns free text into candidate memories using the local model.
 *
 * <p>Small local models produce malformed JSON regularly, so parsing is
 * deliberately forgiving and always falls back to keeping the owner's text as
 * a single low-confidence candidate. Losing what someone wrote because a model
 * emitted a stray comma is not an acceptable failure mode.
 */
@Component
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    private static final String SYSTEM_PROMPT = """
            You extract distinct personal memories from text the owner wrote about their own life.

            Return ONLY a JSON array. No prose, no markdown fences, no explanation.

            Each array element must be an object with exactly these fields:
              "type"       one of: EPISODIC, SEMANTIC, PREFERENCE, PROCEDURAL, EMOTIONAL, RELATIONSHIP, PROJECT
              "title"      a short label, at most 60 characters
              "content"    the memory as a single self-contained statement
              "eventDate"  "YYYY-MM-DD" if the text states or clearly implies a date, otherwise null
              "confidence" a number from 0 to 1 for how clearly the text supports this

            Rules:
            - Only extract what the text actually says. Never add facts, dates, or names that are not there.
            - Split genuinely separate memories into separate objects; do not merge unrelated things.
            - If the text contains no personal memory at all, return [].
            - Use EPISODIC for things that happened, SEMANTIC for beliefs or knowledge,
              PREFERENCE for likes and dislikes, PROCEDURAL for how the owner does things,
              EMOTIONAL for reflections and feelings, RELATIONSHIP for connections between people,
              PROJECT for work on a specific project.
            """;

    private final AIService aiService;
    private final ObjectMapper objectMapper;

    public MemoryExtractor(AIService aiService, ObjectMapper objectMapper) {
        this.aiService = aiService;
        this.objectMapper = objectMapper;
    }

    public List<ExtractedCandidate> extract(String text) {
        String response;
        try {
            response = aiService.complete(SYSTEM_PROMPT, text, List.of());
        } catch (Exception e) {
            log.warn("Extraction model call failed, keeping the raw text as a single candidate: {}", e.toString());
            return List.of(fallback(text));
        }

        List<ExtractedCandidate> candidates = parse(response);
        if (candidates.isEmpty() && !looksLikeDeliberateEmpty(response)) {
            log.warn("Could not parse extraction output, keeping the raw text as a single candidate.");
            return List.of(fallback(text));
        }
        return candidates;
    }

    private List<ExtractedCandidate> parse(String response) {
        String json = isolateJsonArray(response);
        if (json == null) {
            return List.of();
        }
        try {
            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray()) {
                return List.of();
            }
            List<ExtractedCandidate> candidates = new ArrayList<>();
            for (JsonNode node : array) {
                ExtractedCandidate candidate = toCandidate(node);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
            return candidates;
        } catch (Exception e) {
            return List.of();
        }
    }

    private ExtractedCandidate toCandidate(JsonNode node) {
        String content = node.path("content").asText(null);
        if (content == null || content.isBlank()) {
            return null;
        }
        return new ExtractedCandidate(
                parseType(node.path("type").asText(null)),
                trimToNull(node.path("title").asText(null)),
                content.strip(),
                parseDate(node.path("eventDate").asText(null)),
                clampConfidence(node.path("confidence").asDouble(0.5))
        );
    }

    private static MemoryType parseType(String raw) {
        if (raw == null) {
            return MemoryType.EPISODIC;
        }
        try {
            return MemoryType.valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MemoryType.EPISODIC;
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw.strip())) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static float clampConfidence(double value) {
        return (float) Math.max(0.0, Math.min(1.0, value));
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    /** Pulls the outermost JSON array out of a response that may be wrapped in prose or fences. */
    static String isolateJsonArray(String response) {
        if (response == null) {
            return null;
        }
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return response.substring(start, end + 1);
    }

    /** Distinguishes "the model found nothing" from "the model produced garbage". */
    private static boolean looksLikeDeliberateEmpty(String response) {
        String json = isolateJsonArray(response);
        return json != null && json.replaceAll("\\s", "").equals("[]");
    }

    private static ExtractedCandidate fallback(String text) {
        return new ExtractedCandidate(MemoryType.EPISODIC, null, text.strip(), null, 0.3f);
    }
}

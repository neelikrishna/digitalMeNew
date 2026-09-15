package com.digitalself.extraction;

import com.digitalself.ai.AIService;
import com.digitalself.memory.MemoryType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MemoryExtractorTest {

    private AIService aiService;
    private MemoryExtractor extractor;

    @BeforeEach
    void setUp() {
        aiService = mock(AIService.class);
        extractor = new MemoryExtractor(aiService, new ObjectMapper());
    }

    @Test
    void parsesWellFormedOutput() {
        stubModel("""
                [
                  {"type":"EPISODIC","title":"Met John","content":"I met John in college in 2018.",
                   "eventDate":"2018-09-01","confidence":0.9},
                  {"type":"PREFERENCE","title":"Java","content":"I prefer Java for backend work.",
                   "eventDate":null,"confidence":0.8}
                ]
                """);

        List<ExtractedCandidate> candidates = extractor.extract("some text");

        assertEquals(2, candidates.size());
        assertEquals(MemoryType.EPISODIC, candidates.get(0).type());
        assertEquals(LocalDate.of(2018, 9, 1), candidates.get(0).eventDate());
        assertEquals(MemoryType.PREFERENCE, candidates.get(1).type());
        assertNull(candidates.get(1).eventDate());
    }

    @Test
    void toleratesMarkdownFencesAndSurroundingProse() {
        stubModel("""
                Sure! Here is the JSON you asked for:
                ```json
                [{"type":"EPISODIC","title":"A day","content":"Something happened.","eventDate":null,"confidence":0.7}]
                ```
                Let me know if you need anything else.
                """);

        List<ExtractedCandidate> candidates = extractor.extract("some text");

        assertEquals(1, candidates.size());
        assertEquals("Something happened.", candidates.get(0).content());
    }

    @Test
    void keepsTheOwnersTextWhenOutputIsUnparseable() {
        stubModel("I'm sorry, I cannot do that.");

        List<ExtractedCandidate> candidates = extractor.extract("I went to Rome in 2019.");

        assertEquals(1, candidates.size(), "the owner's text must never be dropped");
        assertEquals("I went to Rome in 2019.", candidates.get(0).content());
        assertTrue(candidates.get(0).confidence() < 0.5f, "a fallback must not look confident");
    }

    @Test
    void keepsTheOwnersTextWhenTheModelIsUnreachable() {
        when(aiService.complete(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("ollama down"));

        List<ExtractedCandidate> candidates = extractor.extract("I went to Rome in 2019.");

        assertEquals(1, candidates.size());
        assertEquals("I went to Rome in 2019.", candidates.get(0).content());
    }

    @Test
    void anExplicitlyEmptyResultIsRespected() {
        stubModel("[]");

        assertTrue(extractor.extract("what time is it?").isEmpty(),
                "an empty array means the model found nothing, which is different from failing to parse");
    }

    @Test
    void unknownTypeFallsBackToEpisodicRatherThanFailing() {
        stubModel("""
                [{"type":"NONSENSE","title":"x","content":"Something.","eventDate":"not-a-date","confidence":5}]
                """);

        List<ExtractedCandidate> candidates = extractor.extract("text");

        assertEquals(MemoryType.EPISODIC, candidates.get(0).type());
        assertNull(candidates.get(0).eventDate(), "an unparseable date should be dropped, not guessed");
        assertEquals(1.0f, candidates.get(0).confidence(), "confidence must be clamped into range");
    }

    @Test
    void candidatesWithoutContentAreSkipped() {
        stubModel("""
                [{"type":"EPISODIC","title":"empty","content":"","confidence":0.9},
                 {"type":"EPISODIC","title":"real","content":"Actual memory.","confidence":0.9}]
                """);

        List<ExtractedCandidate> candidates = extractor.extract("text");

        assertEquals(1, candidates.size());
        assertEquals("Actual memory.", candidates.get(0).content());
    }

    private void stubModel(String response) {
        when(aiService.complete(anyString(), anyString(), any())).thenReturn(response);
    }
}

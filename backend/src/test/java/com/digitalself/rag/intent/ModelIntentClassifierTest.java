package com.digitalself.rag.intent;

import com.digitalself.ai.AIService;
import com.digitalself.config.IntentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelIntentClassifierTest {

    private AIService aiService;
    private IntentProperties properties;
    private ModelIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        aiService = mock(AIService.class);
        properties = new IntentProperties();
        classifier = new ModelIntentClassifier(aiService, properties);
    }

    @Test
    void readsACleanLabel() {
        stub("GENERAL_KNOWLEDGE");

        IntentDecision decision = classifier.classify("What is the chemical formula of water?");

        assertEquals(QuestionIntent.GENERAL_KNOWLEDGE, decision.intent());
        assertEquals(IntentDecision.Source.MODEL, decision.source());
        assertFalse(decision.requiresRetrieval(), "a general question must not search personal memory");
    }

    @Test
    void toleratesALabelWrappedInProse() {
        stub("Sure! I'd classify this as PERSONAL_MEMORY because it mentions the user.");

        IntentDecision decision = classifier.classify("What did I say about my Java project?");

        assertEquals(QuestionIntent.PERSONAL_MEMORY, decision.intent());
        assertTrue(decision.confidence() < 0.9,
                "ignoring the output format is itself evidence of a less reliable answer");
    }

    @Test
    void fallsBackToHeuristicsWhenTheModelIsUnreachable() {
        when(aiService.completeWith(any(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("ollama down"));

        IntentDecision personal = classifier.classify("What did I discuss with John?");
        IntentDecision general = classifier.classify("What is a hash map?");

        assertEquals(QuestionIntent.PERSONAL_MEMORY, personal.intent());
        assertEquals(QuestionIntent.GENERAL_KNOWLEDGE, general.intent());
        assertEquals(IntentDecision.Source.HEURISTIC, personal.source());
    }

    @Test
    void fallsBackWhenTheReplyIsUnintelligible() {
        stub("I'm not sure what you mean.");

        IntentDecision decision = classifier.classify("What did I say about my project?");

        assertEquals(IntentDecision.Source.HEURISTIC, decision.source());
        assertEquals(QuestionIntent.PERSONAL_MEMORY, decision.intent());
    }

    @Test
    void anAmbiguousQuestionWithNoSignalLandsOnTheStrictestBranch() {
        when(aiService.completeWith(any(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("down"));

        // No first-person marker, no factual opener — nothing to go on.
        IntentDecision decision = classifier.classify("Tirupati January");

        assertEquals(QuestionIntent.safestFallback(), decision.intent());
        assertEquals(QuestionIntent.PERSONAL_MEMORY, decision.intent(),
                "refusing to answer is recoverable; inventing a life is not");
    }

    @Test
    void firstPersonBeatsAFactualOpener() {
        when(aiService.completeWith(any(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("down"));

        // Looks like a factual lookup, but "I" makes it personal.
        IntentDecision decision = classifier.classify("What is my preferred database?");

        assertEquals(QuestionIntent.PERSONAL_MEMORY, decision.intent());
    }

    @Test
    void doesNotMistakeWordsContainingIForFirstPerson() {
        when(aiService.completeWith(any(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("down"));

        IntentDecision decision = classifier.classify("What is ice made of?");

        assertEquals(QuestionIntent.GENERAL_KNOWLEDGE, decision.intent(),
                "\"ice\" contains an i but is not first person");
    }

    @Test
    void theKillSwitchRestoresMemoryOnlyBehaviour() {
        properties.setEnabled(false);

        IntentDecision decision = classifier.classify("What is the chemical formula of water?");

        assertEquals(QuestionIntent.PERSONAL_MEMORY, decision.intent());
        assertEquals(IntentDecision.Source.DISABLED, decision.source());
        verifyNoInteractions(aiService);
    }

    @Test
    void aLowConfidenceModelAnswerIsNotActedOn() {
        properties.setMinConfidence(0.8);
        // Buried label scores 0.6, below the floor.
        stub("probably GENERAL_KNOWLEDGE I think");

        IntentDecision decision = classifier.classify("What did I do last week?");

        assertEquals(IntentDecision.Source.HEURISTIC, decision.source());
        assertEquals(QuestionIntent.PERSONAL_MEMORY, decision.intent());
    }

    @Test
    void usesTheConfiguredClassificationModelWhenOneIsSet() {
        properties.setModel("llama3.2:1b");
        stub("CASUAL_CONVERSATION");

        classifier.classify("Hello");

        verify(aiService).completeWith(eq("llama3.2:1b"), anyString(), anyString(), any());
    }

    private void stub(String reply) {
        when(aiService.completeWith(any(), anyString(), anyString(), any())).thenReturn(reply);
    }
}

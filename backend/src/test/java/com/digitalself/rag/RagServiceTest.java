package com.digitalself.rag;

import com.digitalself.ai.AIService;
import com.digitalself.audit.AuditService;
import com.digitalself.config.RagProperties;
import com.digitalself.conversation.ChatMessage;
import com.digitalself.conversation.ChatMessageRepository;
import com.digitalself.conversation.Conversation;
import com.digitalself.conversation.ConversationRepository;
import com.digitalself.crypto.TextCrypto;
import com.digitalself.memory.MemorySource;
import com.digitalself.rag.dto.ChatRequest;
import com.digitalself.rag.dto.ChatResponse;
import com.digitalself.rag.intent.IntentClassifier;
import com.digitalself.rag.intent.IntentDecision;
import com.digitalself.rag.intent.QuestionIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RagServiceTest {

    private IntentClassifier intentClassifier;
    private HybridRetriever retriever;
    private AIService aiService;
    private ChatMessageRepository messageRepository;
    private RagService ragService;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        intentClassifier = mock(IntentClassifier.class);
        retriever = mock(HybridRetriever.class);
        aiService = mock(AIService.class);
        messageRepository = mock(ChatMessageRepository.class);
        ConversationRepository conversationRepository = mock(ConversationRepository.class);

        when(conversationRepository.save(any(Conversation.class))).thenAnswer(i -> i.getArgument(0));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());

        // Pass-through crypto: these tests are about routing and grounding.
        TextCrypto textCrypto = mock(TextCrypto.class);
        when(textCrypto.encrypt(anyString(), any(), anyString()))
                .thenAnswer(i -> i.getArgument(2, String.class).getBytes(StandardCharsets.UTF_8));
        when(textCrypto.decrypt(anyString(), any(), any()))
                .thenAnswer(i -> new String(i.getArgument(2, byte[].class), StandardCharsets.UTF_8));

        ragService = new RagService(intentClassifier, retriever, new PromptBuilder(new RagProperties()),
                aiService, conversationRepository, messageRepository, mock(AuditService.class), textCrypto);
    }

    // ---------- general knowledge ----------

    /** The requirement this routing exists to satisfy. */
    @Test
    void aGeneralQuestionIsAnsweredWithoutTouchingPersonalMemory() {
        route(QuestionIntent.GENERAL_KNOWLEDGE);
        when(aiService.complete(anyString(), anyString(), any())).thenReturn("H2O.");

        ChatResponse response = ragService.ask(userId,
                new ChatRequest("What is the chemical formula of water?", null));

        assertEquals("H2O.", response.answer());
        assertEquals(QuestionIntent.GENERAL_KNOWLEDGE, response.intent());
        assertFalse(response.answeredFromMemory());
        assertTrue(response.usedGeneralKnowledge());
        assertTrue(response.groundedInMemories().isEmpty());
        verifyNoInteractions(retriever);
    }

    @Test
    void aGeneralAnswerIsToldNotToClaimItCameFromTheOwnersRecords() {
        route(QuestionIntent.GENERAL_KNOWLEDGE);
        when(aiService.complete(anyString(), anyString(), any())).thenReturn("An answer.");

        ragService.ask(userId, new ChatRequest("Who wrote Hamlet?", null));

        assertTrue(capturedSystemPrompt().contains("No personal memories were consulted."));
    }

    // ---------- personal memory ----------

    /** Unchanged guarantee: nothing retrieved means the model is never invoked. */
    @Test
    void aPersonalQuestionWithNoMatchingMemoryNeverReachesTheModel() {
        route(QuestionIntent.PERSONAL_MEMORY);
        when(retriever.retrieve(eq(userId), anyString())).thenReturn(List.of());

        ChatResponse response = ragService.ask(userId, new ChatRequest("What did I do in 1998?", null));

        assertEquals(RagService.NO_MEMORY_ANSWER, response.answer());
        assertFalse(response.answeredFromMemory());
        assertFalse(response.usedGeneralKnowledge());
        verifyNoInteractions(aiService);
    }

    @Test
    void aPersonalQuestionAnswersFromMemoriesAndCitesThem() {
        route(QuestionIntent.PERSONAL_MEMORY);
        RetrievedPassage passage = retrieved("College", "I met John in college in 2018.", MemorySource.USER_INPUT);
        when(retriever.retrieve(eq(userId), anyString())).thenReturn(List.of(passage));
        when(aiService.complete(anyString(), anyString(), any())).thenReturn("You met John in 2018.");

        ChatResponse response = ragService.ask(userId, new ChatRequest("When did I meet John?", null));

        assertTrue(response.answeredFromMemory());
        assertFalse(response.usedGeneralKnowledge(), "a strict answer must not draw on general knowledge");
        assertEquals(passage.memoryId(), response.groundedInMemories().get(0).memoryId());
        assertEquals(passage.chunkId(), response.groundedInMemories().get(0).chunkId(),
                "a citation must name the passage, not just the memory");
        assertTrue(capturedSystemPrompt().contains(RagService.NO_MEMORY_ANSWER));
    }

    // ---------- hybrid ----------

    @Test
    void aHybridQuestionUsesBothAndIsToldToKeepThemApart() {
        route(QuestionIntent.HYBRID);
        when(retriever.retrieve(eq(userId), anyString()))
                .thenReturn(List.of(retrieved("DB", "I chose PostgreSQL.", MemorySource.USER_INPUT)));
        when(aiService.complete(anyString(), anyString(), any())).thenReturn("From your records: ...");

        ChatResponse response = ragService.ask(userId,
                new ChatRequest("I chose PostgreSQL — should I add pgvector?", null));

        assertTrue(response.answeredFromMemory());
        assertTrue(response.usedGeneralKnowledge());
        assertEquals(1, response.groundedInMemories().size());

        String prompt = capturedSystemPrompt();
        assertTrue(prompt.contains("From your records:"), "the prompt must demand the two be labelled");
        assertTrue(prompt.contains("Never present general knowledge as something the owner said"));
    }

    /**
     * A hybrid question with nothing on record still answers the general half,
     * rather than refusing outright as the strict branch would.
     */
    @Test
    void aHybridQuestionStillAnswersWhenNoMemoriesMatch() {
        route(QuestionIntent.HYBRID);
        when(retriever.retrieve(eq(userId), anyString())).thenReturn(List.of());
        when(aiService.complete(anyString(), anyString(), any())).thenReturn("Generally: ...");

        ChatResponse response = ragService.ask(userId, new ChatRequest("Given my setup, is pgvector wise?", null));

        assertNotEquals(RagService.NO_MEMORY_ANSWER, response.answer());
        assertFalse(response.answeredFromMemory());
        assertTrue(response.usedGeneralKnowledge());
        assertTrue(capturedSystemPrompt().contains("No personal memories matched this question."));
    }

    // ---------- current information ----------

    @Test
    void aLiveDataQuestionIsRefusedHonestlyWithoutGuessing() {
        route(QuestionIntent.CURRENT_INFORMATION);

        ChatResponse response = ragService.ask(userId, new ChatRequest("What's the weather today?", null));

        assertEquals(RagService.NO_LIVE_DATA_ANSWER, response.answer());
        verifyNoInteractions(retriever);
        verifyNoInteractions(aiService);
    }

    // ---------- casual ----------

    @Test
    void smallTalkDoesNotSearchMemory() {
        route(QuestionIntent.CASUAL_CONVERSATION);
        when(aiService.complete(anyString(), anyString(), any())).thenReturn("Hello.");

        ChatResponse response = ragService.ask(userId, new ChatRequest("Hello", null));

        assertEquals("Hello.", response.answer());
        verifyNoInteractions(retriever);
        assertTrue(capturedSystemPrompt().contains("small talk"));
    }

    // ---------- conversation ----------

    @Test
    void bothTurnsAreStoredWhicheverBranchRan() {
        route(QuestionIntent.CURRENT_INFORMATION);

        ragService.ask(userId, new ChatRequest("What's the weather?", null));

        verify(messageRepository, times(2)).save(any(ChatMessage.class));
    }

    private void route(QuestionIntent intent) {
        when(intentClassifier.classify(anyString())).thenReturn(IntentDecision.model(intent, 0.9));
    }

    private String capturedSystemPrompt() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(aiService).complete(captor.capture(), anyString(), any());
        return captor.getValue();
    }

    private RetrievedPassage retrieved(String title, String content, MemorySource source) {
        return new RetrievedPassage(
                UUID.randomUUID(), UUID.randomUUID(), title, content,
                com.digitalself.memory.chunk.ChunkContentType.TEXT, source,
                LocalDate.of(2018, 6, 1), 1.0f, false,
                null, null, null, null,
                content.length() / 4, 0.9, 0.2, true);
    }

    @Test
    void aCitationCarriesTheLocationInsideTheSource() {
        route(QuestionIntent.PERSONAL_MEMORY);
        // A passage from 12:34 to 14:02 of a recording.
        RetrievedPassage passage = new RetrievedPassage(
                UUID.randomUUID(), UUID.randomUUID(), "Project discussion", "We agreed on Postgres.",
                com.digitalself.memory.chunk.ChunkContentType.TRANSCRIPT_SEGMENT, MemorySource.FILE_EXTRACTION,
                LocalDate.of(2026, 3, 1), 1.0f, false,
                UUID.randomUUID(), null, 754_000L, 842_000L, 10, 0.9, 0.2, false);

        when(retriever.retrieve(eq(userId), anyString())).thenReturn(List.of(passage));
        when(aiService.complete(anyString(), anyString(), any())).thenReturn("You agreed on Postgres.");

        ChatResponse response = ragService.ask(userId, new ChatRequest("What did we decide?", null));

        ChatResponse.CitedMemory citation = response.groundedInMemories().get(0);
        assertEquals("12:34–14:02", citation.location());
        assertEquals(passage.sourceFileId(), citation.sourceFileId());
        assertTrue(capturedSystemPrompt().contains("12:34–14:02"),
                "the model needs the location in order to cite it");
    }
}

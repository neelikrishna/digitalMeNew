package com.digitalself.rag;

import com.digitalself.ai.AIService;
import com.digitalself.audit.AuditService;
import com.digitalself.crypto.TextCrypto;
import com.digitalself.conversation.ChatMessage;
import com.digitalself.conversation.ChatMessageRepository;
import com.digitalself.conversation.Conversation;
import com.digitalself.conversation.ConversationRepository;
import com.digitalself.memory.*;
import com.digitalself.rag.dto.ChatRequest;
import com.digitalself.rag.dto.ChatResponse;
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

    private HybridRetriever retriever;
    private AIService aiService;
    private ChatMessageRepository messageRepository;
    private RagService ragService;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        retriever = mock(HybridRetriever.class);
        aiService = mock(AIService.class);
        messageRepository = mock(ChatMessageRepository.class);
        ConversationRepository conversationRepository = mock(ConversationRepository.class);

        when(conversationRepository.save(any(Conversation.class))).thenAnswer(i -> i.getArgument(0));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());

        // Pass-through crypto: these tests are about retrieval and grounding,
        // and the encryption itself is covered by EnvelopeEncryptionServiceTest
        // and the integration test.
        TextCrypto textCrypto = mock(TextCrypto.class);
        when(textCrypto.encrypt(anyString(), any(), anyString()))
                .thenAnswer(i -> i.getArgument(2, String.class).getBytes(StandardCharsets.UTF_8));
        when(textCrypto.decrypt(anyString(), any(), any()))
                .thenAnswer(i -> new String(i.getArgument(2, byte[].class), StandardCharsets.UTF_8));

        ragService = new RagService(retriever, new PromptBuilder(new com.digitalself.config.RagProperties()), aiService,
                conversationRepository, messageRepository, mock(AuditService.class), textCrypto);
    }

    @Test
    void neverCallsTheModelWhenNoMemoriesAreRetrieved() {
        when(retriever.retrieve(eq(userId), anyString())).thenReturn(List.of());

        ChatResponse response = ragService.ask(userId, new ChatRequest("What did I do in 1998?", null));

        assertEquals(RagService.NO_MEMORY_ANSWER, response.answer());
        assertFalse(response.answeredFromMemory());
        assertTrue(response.groundedInMemories().isEmpty());
        verifyNoInteractions(aiService);
    }

    @Test
    void answersFromRetrievedMemoriesAndCitesThem() {
        RetrievedMemory memory = retrieved("College", "I met John in college in 2018.", MemorySource.USER_INPUT);
        when(retriever.retrieve(eq(userId), anyString())).thenReturn(List.of(memory));
        when(aiService.complete(anyString(), anyString(), any())).thenReturn("You met John in college in 2018.");

        ChatResponse response = ragService.ask(userId, new ChatRequest("When did I meet John?", null));

        assertTrue(response.answeredFromMemory());
        assertEquals("You met John in college in 2018.", response.answer());
        assertEquals(1, response.groundedInMemories().size());
        assertEquals(memory.memoryId(), response.groundedInMemories().get(0).memoryId());
        assertEquals("stated by owner", response.groundedInMemories().get(0).provenance());
    }

    @Test
    void systemPromptCarriesProvenanceAndTheNoHallucinationRule() {
        when(retriever.retrieve(eq(userId), anyString())).thenReturn(List.of(
                retrieved("Preference", "Prefers Java for backend work.", MemorySource.AI_INFERENCE)));
        when(aiService.complete(anyString(), anyString(), any())).thenReturn("An answer.");

        ragService.ask(userId, new ChatRequest("What languages do I like?", null));

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiService).complete(systemPrompt.capture(), anyString(), any());

        assertTrue(systemPrompt.getValue().contains("I don't have a memory of that."));
        assertTrue(systemPrompt.getValue().contains("inferred by AI, not directly stated"));
        assertTrue(systemPrompt.getValue().contains("Prefers Java for backend work."));
    }

    @Test
    void storesBothTurnsOfTheConversation() {
        when(retriever.retrieve(eq(userId), anyString())).thenReturn(List.of());

        ragService.ask(userId, new ChatRequest("Anything about sailing?", null));

        verify(messageRepository, times(2)).save(any(ChatMessage.class));
    }

    private RetrievedMemory retrieved(String title, String content, MemorySource source) {
        return new RetrievedMemory(UUID.randomUUID(), title, content, source,
                LocalDate.of(2018, 6, 1), 1.0f, 0.9, 0.2, true, false);
    }
}

package com.digitalself.rag;

import com.digitalself.ai.AIService;
import com.digitalself.ai.Message;
import com.digitalself.audit.AuditService;
import com.digitalself.conversation.*;
import com.digitalself.crypto.TextCrypto;
import com.digitalself.rag.dto.ChatRequest;
import com.digitalself.rag.dto.ChatResponse;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class RagService {

    static final String NO_MEMORY_ANSWER = "I don't have a memory of that.";

    /** How many prior turns are replayed to the model for conversational context. */
    private static final int HISTORY_TURNS = 6;

    private final HybridRetriever retriever;
    private final PromptBuilder promptBuilder;
    private final AIService aiService;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final AuditService auditService;
    private final TextCrypto textCrypto;

    public RagService(HybridRetriever retriever,
                      PromptBuilder promptBuilder,
                      AIService aiService,
                      ConversationRepository conversationRepository,
                      ChatMessageRepository messageRepository,
                      AuditService auditService,
                      TextCrypto textCrypto) {
        this.retriever = retriever;
        this.promptBuilder = promptBuilder;
        this.aiService = aiService;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.auditService = auditService;
        this.textCrypto = textCrypto;
    }

    @Transactional
    public ChatResponse ask(UUID userId, ChatRequest request) {
        Conversation conversation = resolveConversation(userId, request);
        // Read history before storing the current turn, or the question would
        // appear twice: once in history and once as the user prompt.
        List<Message> history = recentHistory(conversation.getId());
        storeMessage(conversation.getId(), MessageRole.USER, request.question());

        List<RetrievedMemory> retrieved = retriever.retrieve(userId, request.question());

        // Structural guard, not just a prompt instruction: with nothing relevant
        // retrieved the model is never invoked, so it cannot invent a memory.
        if (retrieved.isEmpty()) {
            storeMessage(conversation.getId(), MessageRole.ASSISTANT, NO_MEMORY_ANSWER);
            auditService.record(userId, "CHAT_ANSWERED_WITHOUT_MEMORY", "conversation", conversation.getId(), null, null);
            return new ChatResponse(conversation.getId(), NO_MEMORY_ANSWER, false, List.of());
        }

        String systemPrompt = promptBuilder.buildSystemPrompt(retrieved);
        String answer = aiService.complete(systemPrompt, request.question(), history);

        storeMessage(conversation.getId(), MessageRole.ASSISTANT, answer);
        auditService.record(userId, "CHAT_ANSWERED_FROM_MEMORY", "conversation", conversation.getId(), null, null);

        List<ChatResponse.CitedMemory> cited = retrieved.stream()
                .map(r -> new ChatResponse.CitedMemory(r.memoryId(), r.title(), r.provenanceLabel()))
                .toList();

        return new ChatResponse(conversation.getId(), answer, true, cited);
    }

    private Conversation resolveConversation(UUID userId, ChatRequest request) {
        if (request.conversationId() == null) {
            return conversationRepository.save(new Conversation(userId, truncateTitle(request.question())));
        }
        return conversationRepository.findByIdAndUserId(request.conversationId(), userId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown conversation."));
    }

    private void storeMessage(UUID conversationId, MessageRole role, String content) {
        messageRepository.save(new ChatMessage(conversationId, role,
                textCrypto.encrypt(TextCrypto.CONVERSATION, conversationId, content)));
    }

    private List<Message> recentHistory(UUID conversationId) {
        List<ChatMessage> recent = messageRepository.findByConversationIdOrderByCreatedAtDesc(
                conversationId, Limit.of(HISTORY_TURNS));
        List<Message> history = new ArrayList<>(recent.size());
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatMessage message = recent.get(i);
            history.add(new Message(message.getRole().name().toLowerCase(),
                    textCrypto.decrypt(TextCrypto.CONVERSATION, conversationId, message.getContentEncrypted())));
        }
        return history;
    }

    private static String truncateTitle(String question) {
        return question.length() <= 80 ? question : question.substring(0, 80);
    }
}

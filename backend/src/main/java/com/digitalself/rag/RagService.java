package com.digitalself.rag;

import com.digitalself.ai.AIService;
import com.digitalself.ai.Message;
import com.digitalself.audit.AuditService;
import com.digitalself.conversation.*;
import com.digitalself.crypto.TextCrypto;
import com.digitalself.rag.dto.ChatRequest;
import com.digitalself.rag.dto.ChatResponse;
import com.digitalself.rag.intent.IntentClassifier;
import com.digitalself.rag.intent.IntentDecision;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Answers a question, choosing what the model is allowed to draw on.
 *
 * <p>Routing happens <em>before</em> retrieval. That ordering is the whole
 * design: a general question never touches personal memory, and a personal
 * question can still be refused outright when nothing relevant is stored, which
 * is what keeps invented personal history structurally impossible rather than
 * merely discouraged.
 */
@Service
public class RagService {

    static final String NO_MEMORY_ANSWER = "I don't have a memory of that.";

    /**
     * There is no internet access by design — nothing in this system reaches off
     * the machine. Saying so is better than a local model guessing at today's
     * weather from training data that is months old.
     */
    static final String NO_LIVE_DATA_ANSWER =
            "I can't look that up — I have no access to anything outside this machine, "
                    + "so I have no live information like weather, news or prices.";

    /** How many prior turns are replayed to the model for conversational context. */
    private static final int HISTORY_TURNS = 6;

    private final IntentClassifier intentClassifier;
    private final HybridRetriever retriever;
    private final PromptBuilder promptBuilder;
    private final AIService aiService;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final AuditService auditService;
    private final TextCrypto textCrypto;

    public RagService(IntentClassifier intentClassifier,
                      HybridRetriever retriever,
                      PromptBuilder promptBuilder,
                      AIService aiService,
                      ConversationRepository conversationRepository,
                      ChatMessageRepository messageRepository,
                      AuditService auditService,
                      TextCrypto textCrypto) {
        this.intentClassifier = intentClassifier;
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

        IntentDecision decision = intentClassifier.classify(request.question());

        ChatResponse response = switch (decision.intent()) {
            case CURRENT_INFORMATION -> withoutModel(conversation, decision, NO_LIVE_DATA_ANSWER);
            case CASUAL_CONVERSATION -> answerGenerally(conversation, decision, request, history, promptBuilder.casual());
            case GENERAL_KNOWLEDGE -> answerGenerally(conversation, decision, request, history, promptBuilder.general());
            case PERSONAL_MEMORY -> answerFromMemory(userId, conversation, decision, request, history);
            case HYBRID -> answerFromBoth(userId, conversation, decision, request, history);
        };

        // The classification is audited, never the question itself — an audit
        // table holding every question asked would be an unencrypted copy of the
        // most sensitive thing in the system.
        auditService.record(userId, "CHAT_" + decision.intent().name(), "conversation",
                conversation.getId(), null, null);
        return response;
    }

    /**
     * Strict branch, unchanged in behaviour. Nothing retrieved means the model is
     * never invoked, so it has no opportunity to fabricate a memory.
     */
    private ChatResponse answerFromMemory(UUID userId, Conversation conversation, IntentDecision decision,
                                           ChatRequest request, List<Message> history) {
        List<RetrievedPassage> retrieved = retriever.retrieve(userId, request.question());

        if (retrieved.isEmpty()) {
            storeMessage(conversation.getId(), MessageRole.ASSISTANT, NO_MEMORY_ANSWER);
            return ChatResponse.of(conversation.getId(), NO_MEMORY_ANSWER, decision, false, false, List.of());
        }

        String answer = aiService.complete(promptBuilder.strict(retrieved), request.question(), history);
        storeMessage(conversation.getId(), MessageRole.ASSISTANT, answer);
        return ChatResponse.of(conversation.getId(), answer, decision, true, false, cite(retrieved));
    }

    /**
     * Both sources. Unlike the strict branch this does not refuse when retrieval
     * is empty — the general half of the question is still answerable, and the
     * prompt requires the model to say it has nothing on record for the personal
     * half rather than filling it in.
     */
    private ChatResponse answerFromBoth(UUID userId, Conversation conversation, IntentDecision decision,
                                         ChatRequest request, List<Message> history) {
        List<RetrievedPassage> retrieved = retriever.retrieve(userId, request.question());

        String answer = aiService.complete(promptBuilder.hybrid(retrieved), request.question(), history);
        storeMessage(conversation.getId(), MessageRole.ASSISTANT, answer);
        return ChatResponse.of(conversation.getId(), answer, decision,
                !retrieved.isEmpty(), true, cite(retrieved));
    }

    /** No retrieval at all: personal memory is never read for these. */
    private ChatResponse answerGenerally(Conversation conversation, IntentDecision decision,
                                          ChatRequest request, List<Message> history, String systemPrompt) {
        String answer = aiService.complete(systemPrompt, request.question(), history);
        storeMessage(conversation.getId(), MessageRole.ASSISTANT, answer);
        return ChatResponse.of(conversation.getId(), answer, decision, false, true, List.of());
    }

    /** A fixed reply where invoking the model would only produce a plausible guess. */
    private ChatResponse withoutModel(Conversation conversation, IntentDecision decision, String answer) {
        storeMessage(conversation.getId(), MessageRole.ASSISTANT, answer);
        return ChatResponse.of(conversation.getId(), answer, decision, false, false, List.of());
    }

    private List<ChatResponse.CitedMemory> cite(List<RetrievedPassage> retrieved) {
        return retrieved.stream()
                .map(passage -> new ChatResponse.CitedMemory(
                        passage.memoryId(),
                        passage.chunkId(),
                        passage.memoryTitle(),
                        passage.provenanceLabel(),
                        passage.locationLabel(),
                        passage.sourceFileId()))
                .toList();
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

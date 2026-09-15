package com.digitalself.conversation;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    List<ChatMessage> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Limit limit);
}

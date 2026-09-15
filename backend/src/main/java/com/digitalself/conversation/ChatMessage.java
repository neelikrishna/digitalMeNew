package com.digitalself.conversation;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A turn in a conversation. Conversation history is deliberately NOT long-term
 * memory — a message only becomes a memory when it is explicitly promoted,
 * which is what {@code promotedToMemoryId} records. See docs/memory-engine.md.
 */
@Entity
@Table(name = "messages")
public class ChatMessage {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageRole role;

    /** Always encrypted, under the parent conversation's key. */
    @Column(name = "content_encrypted", nullable = false)
    private byte[] contentEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "promoted_to_memory_id")
    private UUID promotedToMemoryId;

    protected ChatMessage() {
        // JPA
    }

    public ChatMessage(UUID conversationId, MessageRole role, byte[] contentEncrypted) {
        this.conversationId = conversationId;
        this.role = role;
        this.contentEncrypted = contentEncrypted;
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public MessageRole getRole() {
        return role;
    }

    public byte[] getContentEncrypted() {
        return contentEncrypted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getPromotedToMemoryId() {
        return promotedToMemoryId;
    }
}

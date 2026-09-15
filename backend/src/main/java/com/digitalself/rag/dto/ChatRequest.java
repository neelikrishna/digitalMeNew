package com.digitalself.rag.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record ChatRequest(
        @NotBlank String question,
        UUID conversationId
) {
}

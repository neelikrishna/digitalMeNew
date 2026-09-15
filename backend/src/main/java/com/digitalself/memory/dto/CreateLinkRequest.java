package com.digitalself.memory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateLinkRequest(
        @NotNull UUID targetMemoryId,
        @NotBlank String linkType
) {
}

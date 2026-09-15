package com.digitalself.extraction.dto;

import jakarta.validation.constraints.NotBlank;

public record ExtractRequest(
        @NotBlank String text
) {
}

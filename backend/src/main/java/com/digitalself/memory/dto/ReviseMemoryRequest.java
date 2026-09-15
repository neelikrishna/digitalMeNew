package com.digitalself.memory.dto;

import com.digitalself.memory.ChangeReason;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

/**
 * A correction to what a memory says. Always produces a new version; the
 * previous version is retained and marked superseded.
 */
public record ReviseMemoryRequest(
        @NotBlank String content,
        LocalDate eventDate,
        @DecimalMin("0.0") @DecimalMax("1.0") Float confidence,
        ChangeReason changeReason
) {
}

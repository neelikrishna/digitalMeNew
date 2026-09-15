package com.digitalself.memory.dto;

import com.digitalself.memory.ChangeReason;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record MemoryVersionResponse(
        UUID id,
        int versionNumber,
        String content,
        LocalDate eventDate,
        float confidence,
        ChangeReason changeReason,
        Instant createdAt,
        Instant supersededAt,
        boolean current
) {
}

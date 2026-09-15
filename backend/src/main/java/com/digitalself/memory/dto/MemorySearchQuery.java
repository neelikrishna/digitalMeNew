package com.digitalself.memory.dto;

import com.digitalself.memory.MemorySource;
import com.digitalself.memory.MemoryStatus;
import com.digitalself.memory.MemoryType;

import java.time.LocalDate;

public record MemorySearchQuery(
        String keyword,
        MemoryType type,
        MemorySource source,
        MemoryStatus status,
        LocalDate from,
        LocalDate to,
        String tag
) {
}

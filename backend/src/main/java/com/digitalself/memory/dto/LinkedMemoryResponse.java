package com.digitalself.memory.dto;

import com.digitalself.memory.MemoryType;

import java.util.UUID;

/**
 * @param direction OUTGOING when this memory is the link's source, INCOMING when it is the target
 */
public record LinkedMemoryResponse(
        UUID linkId,
        String linkType,
        String direction,
        UUID memoryId,
        String title,
        MemoryType type
) {
}

package com.digitalself.extraction.dto;

import com.digitalself.extraction.MemoryProposal;
import com.digitalself.extraction.ProposalStatus;
import com.digitalself.memory.MemoryType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProposalResponse(
        UUID id,
        UUID rawInputId,
        MemoryType type,
        String title,
        String content,
        LocalDate eventDate,
        float confidence,
        ProposalStatus status,
        UUID createdMemoryId,
        Instant createdAt
) {

    /** Built by ExtractionService, which holds the key material needed to decrypt. */
    public static ProposalResponse of(MemoryProposal proposal, String title, String content) {
        return new ProposalResponse(
                proposal.getId(),
                proposal.getRawInputId(),
                proposal.getType(),
                title,
                content,
                proposal.getEventDate(),
                proposal.getConfidence(),
                proposal.getStatus(),
                proposal.getCreatedMemoryId(),
                proposal.getCreatedAt()
        );
    }
}

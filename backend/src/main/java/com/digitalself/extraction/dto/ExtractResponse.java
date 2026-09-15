package com.digitalself.extraction.dto;

import java.util.List;
import java.util.UUID;

/**
 * @param autoAccepted proposals confident enough to become memories immediately
 * @param pendingReview proposals waiting for the owner to confirm or reject
 */
public record ExtractResponse(
        UUID rawInputId,
        List<ProposalResponse> autoAccepted,
        List<ProposalResponse> pendingReview
) {
}

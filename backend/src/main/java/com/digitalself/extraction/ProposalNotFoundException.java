package com.digitalself.extraction;

import java.util.UUID;

public class ProposalNotFoundException extends RuntimeException {
    public ProposalNotFoundException(UUID id) {
        super("No proposal found with id " + id);
    }
}

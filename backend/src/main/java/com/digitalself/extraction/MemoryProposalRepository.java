package com.digitalself.extraction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemoryProposalRepository extends JpaRepository<MemoryProposal, UUID> {

    Optional<MemoryProposal> findByIdAndUserId(UUID id, UUID userId);

    List<MemoryProposal> findByUserIdAndStatusOrderByCreatedAtAsc(UUID userId, ProposalStatus status);
}

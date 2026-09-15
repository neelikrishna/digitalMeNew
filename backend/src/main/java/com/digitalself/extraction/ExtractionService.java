package com.digitalself.extraction;

import com.digitalself.audit.AuditService;
import com.digitalself.config.ExtractionProperties;
import com.digitalself.crypto.TextCrypto;
import com.digitalself.extraction.dto.ExtractResponse;
import com.digitalself.extraction.dto.ProposalResponse;
import com.digitalself.memory.MemorySource;
import com.digitalself.memory.MemoryService;
import com.digitalself.memory.PrivacyLevel;
import com.digitalself.memory.dto.CreateMemoryRequest;
import com.digitalself.memory.dto.MemoryResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ExtractionService {

    private final MemoryExtractor extractor;
    private final RawInputRepository rawInputRepository;
    private final MemoryProposalRepository proposalRepository;
    private final MemoryService memoryService;
    private final ExtractionProperties properties;
    private final AuditService auditService;
    private final TextCrypto textCrypto;

    public ExtractionService(MemoryExtractor extractor,
                             RawInputRepository rawInputRepository,
                             MemoryProposalRepository proposalRepository,
                             MemoryService memoryService,
                             ExtractionProperties properties,
                             AuditService auditService,
                             TextCrypto textCrypto) {
        this.extractor = extractor;
        this.rawInputRepository = rawInputRepository;
        this.proposalRepository = proposalRepository;
        this.memoryService = memoryService;
        this.properties = properties;
        this.auditService = auditService;
        this.textCrypto = textCrypto;
    }

    @Transactional
    public ExtractResponse extract(UUID userId, String text) {
        // The owner's words are stored first and unconditionally, before the
        // model is involved at all — extraction failing must never lose input.
        // Encrypted before it is ever persisted: RawInput assigns its own id, so
        // the row is written once, already populated.
        RawInput rawInput = new RawInput(userId, "TEXT_ENTRY");
        rawInput.setContentEncrypted(textCrypto.encrypt(TextCrypto.RAW_INPUT, rawInput.getId(), text));
        rawInputRepository.save(rawInput);
        auditService.record(userId, "RAW_INPUT_STORED", "raw_input", rawInput.getId(), null, null);

        List<ExtractedCandidate> candidates = extractor.extract(text);

        List<ProposalResponse> autoAccepted = new ArrayList<>();
        List<ProposalResponse> pending = new ArrayList<>();

        for (ExtractedCandidate candidate : candidates) {
            // Proposals reuse the raw input's key: they are derived from it, so
            // they should live and die with it.
            MemoryProposal proposal = proposalRepository.save(new MemoryProposal(
                    userId, rawInput.getId(), candidate.type(),
                    textCrypto.encrypt(TextCrypto.RAW_INPUT, rawInput.getId(), candidate.title()),
                    textCrypto.encrypt(TextCrypto.RAW_INPUT, rawInput.getId(), candidate.content()),
                    candidate.eventDate(), candidate.confidence()));

            if (candidate.confidence() >= properties.getAutoAcceptConfidence()) {
                proposal.accept(createMemoryFrom(userId, proposal).id());
                proposalRepository.save(proposal);
                autoAccepted.add(toResponse(proposal));
            } else {
                pending.add(toResponse(proposal));
            }
        }

        return new ExtractResponse(rawInput.getId(), autoAccepted, pending);
    }

    @Transactional(readOnly = true)
    public List<MemoryProposal> pendingProposals(UUID userId) {
        return proposalRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, ProposalStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<ProposalResponse> pendingProposalResponses(UUID userId) {
        return pendingProposals(userId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public MemoryProposal accept(UUID userId, UUID proposalId) {
        MemoryProposal proposal = requirePending(userId, proposalId);
        proposal.accept(createMemoryFrom(userId, proposal).id());
        auditService.record(userId, "PROPOSAL_ACCEPTED", "memory_proposal", proposalId, null, null);
        return proposalRepository.save(proposal);
    }

    @Transactional
    public MemoryProposal reject(UUID userId, UUID proposalId) {
        MemoryProposal proposal = requirePending(userId, proposalId);
        proposal.reject();
        auditService.record(userId, "PROPOSAL_REJECTED", "memory_proposal", proposalId, null, null);
        return proposalRepository.save(proposal);
    }

    private MemoryProposal requirePending(UUID userId, UUID proposalId) {
        MemoryProposal proposal = proposalRepository.findByIdAndUserId(proposalId, userId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));
        if (proposal.getStatus() != ProposalStatus.PENDING) {
            throw new IllegalArgumentException("This proposal has already been "
                    + proposal.getStatus().name().toLowerCase() + ".");
        }
        return proposal;
    }

    /** Decrypts a proposal for display; the ciphertext never leaves this layer. */
    public ProposalResponse toResponse(MemoryProposal proposal) {
        return ProposalResponse.of(proposal,
                textCrypto.decrypt(TextCrypto.RAW_INPUT, proposal.getRawInputId(), proposal.getTitleEncrypted()),
                textCrypto.decrypt(TextCrypto.RAW_INPUT, proposal.getRawInputId(), proposal.getContentEncrypted()));
    }

    /** Extracted memories are always marked AI_INFERENCE so they stay auditable as model output. */
    private MemoryResponse createMemoryFrom(UUID userId, MemoryProposal proposal) {
        return memoryService.create(userId, new CreateMemoryRequest(
                proposal.getType(),
                textCrypto.decrypt(TextCrypto.RAW_INPUT, proposal.getRawInputId(), proposal.getTitleEncrypted()),
                textCrypto.decrypt(TextCrypto.RAW_INPUT, proposal.getRawInputId(), proposal.getContentEncrypted()),
                MemorySource.AI_INFERENCE,
                proposal.getEventDate(),
                null,
                proposal.getConfidence(),
                PrivacyLevel.PRIVATE,
                // Not forced sensitive: an EMOTIONAL proposal still becomes a
                // sensitive memory, because MemoryService decides that by type.
                null,
                Set.of()
        ));
    }
}

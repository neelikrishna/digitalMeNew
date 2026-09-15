package com.digitalself.extraction;

import com.digitalself.audit.AuditService;
import com.digitalself.config.ExtractionProperties;
import com.digitalself.crypto.TextCrypto;
import com.digitalself.extraction.dto.ExtractResponse;
import com.digitalself.memory.*;
import com.digitalself.memory.dto.CreateMemoryRequest;
import com.digitalself.memory.dto.MemoryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ExtractionServiceTest {

    private MemoryExtractor extractor;
    private RawInputRepository rawInputRepository;
    private MemoryProposalRepository proposalRepository;
    private MemoryService memoryService;
    private ExtractionProperties properties;
    private ExtractionService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        extractor = mock(MemoryExtractor.class);
        rawInputRepository = mock(RawInputRepository.class);
        proposalRepository = mock(MemoryProposalRepository.class);
        memoryService = mock(MemoryService.class);
        properties = new ExtractionProperties();

        when(rawInputRepository.save(any(RawInput.class))).thenAnswer(i -> i.getArgument(0));
        when(rawInputRepository.saveAndFlush(any(RawInput.class))).thenAnswer(i -> i.getArgument(0));
        when(proposalRepository.save(any(MemoryProposal.class))).thenAnswer(i -> i.getArgument(0));
        when(memoryService.create(any(), any(CreateMemoryRequest.class))).thenAnswer(i -> stubMemoryResponse());

        TextCrypto textCrypto = mock(TextCrypto.class);
        when(textCrypto.encrypt(anyString(), any(), any()))
                .thenAnswer(i -> i.getArgument(2) == null
                        ? null
                        : i.getArgument(2, String.class).getBytes(StandardCharsets.UTF_8));
        when(textCrypto.decrypt(anyString(), any(), any()))
                .thenAnswer(i -> i.getArgument(2) == null
                        ? null
                        : new String(i.getArgument(2, byte[].class), StandardCharsets.UTF_8));

        service = new ExtractionService(extractor, rawInputRepository, proposalRepository,
                memoryService, properties, mock(AuditService.class), textCrypto);
    }

    @Test
    void rawInputIsStoredBeforeTheModelIsEvenCalled() {
        when(extractor.extract(anyString())).thenThrow(new RuntimeException("model exploded"));

        assertThrows(RuntimeException.class, () -> service.extract(userId, "Something I wrote."));

        ArgumentCaptor<RawInput> captor = ArgumentCaptor.forClass(RawInput.class);
        verify(rawInputRepository).save(captor.capture());
        assertEquals("Something I wrote.",
                new String(captor.getValue().getContentEncrypted(), StandardCharsets.UTF_8));
    }

    @Test
    void lowConfidenceCandidatesWaitForReview() {
        when(extractor.extract(anyString())).thenReturn(List.of(
                new ExtractedCandidate(MemoryType.EPISODIC, "Trip", "I went to Rome.", null, 0.4f)));

        ExtractResponse response = service.extract(userId, "I think I went to Rome once?");

        assertEquals(1, response.pendingReview().size());
        assertTrue(response.autoAccepted().isEmpty());
        verify(memoryService, never()).create(any(), any());
    }

    @Test
    void highConfidenceCandidatesBecomeMemoriesImmediately() {
        when(extractor.extract(anyString())).thenReturn(List.of(
                new ExtractedCandidate(MemoryType.EPISODIC, "Trip", "I went to Rome in 2019.",
                        LocalDate.of(2019, 5, 1), 0.95f)));

        ExtractResponse response = service.extract(userId, "I went to Rome in May 2019.");

        assertEquals(1, response.autoAccepted().size());
        assertTrue(response.pendingReview().isEmpty());
        verify(memoryService).create(any(), any(CreateMemoryRequest.class));
    }

    @Test
    void extractedMemoriesAreAlwaysMarkedAsAiInference() {
        when(extractor.extract(anyString())).thenReturn(List.of(
                new ExtractedCandidate(MemoryType.EPISODIC, "Trip", "I went to Rome.", null, 0.99f)));

        service.extract(userId, "I went to Rome.");

        ArgumentCaptor<CreateMemoryRequest> captor = ArgumentCaptor.forClass(CreateMemoryRequest.class);
        verify(memoryService).create(any(), captor.capture());
        assertEquals(MemorySource.AI_INFERENCE, captor.getValue().source());
        assertEquals(PrivacyLevel.PRIVATE, captor.getValue().privacyLevel());
    }

    @Test
    void raisingTheThresholdAboveOneForcesEverythingThroughReview() {
        properties.setAutoAcceptConfidence(1.01);
        when(extractor.extract(anyString())).thenReturn(List.of(
                new ExtractedCandidate(MemoryType.EPISODIC, "Trip", "I went to Rome.", null, 1.0f)));

        ExtractResponse response = service.extract(userId, "I went to Rome.");

        assertTrue(response.autoAccepted().isEmpty());
        assertEquals(1, response.pendingReview().size());
    }

    @Test
    void acceptingAProposalCreatesTheMemoryAndRecordsTheLink() {
        MemoryProposal proposal = pendingProposal();
        when(proposalRepository.findByIdAndUserId(any(), eq(userId))).thenReturn(Optional.of(proposal));

        MemoryProposal accepted = service.accept(userId, UUID.randomUUID());

        assertEquals(ProposalStatus.ACCEPTED, accepted.getStatus());
        assertNotNull(accepted.getCreatedMemoryId());
        assertNotNull(accepted.getReviewedAt());
    }

    @Test
    void rejectingAProposalCreatesNoMemory() {
        MemoryProposal proposal = pendingProposal();
        when(proposalRepository.findByIdAndUserId(any(), eq(userId))).thenReturn(Optional.of(proposal));

        MemoryProposal rejected = service.reject(userId, UUID.randomUUID());

        assertEquals(ProposalStatus.REJECTED, rejected.getStatus());
        assertNull(rejected.getCreatedMemoryId());
        verify(memoryService, never()).create(any(), any());
    }

    @Test
    void aProposalCannotBeReviewedTwice() {
        MemoryProposal proposal = pendingProposal();
        proposal.reject();
        when(proposalRepository.findByIdAndUserId(any(), eq(userId))).thenReturn(Optional.of(proposal));

        assertThrows(IllegalArgumentException.class, () -> service.accept(userId, UUID.randomUUID()));
    }

    @Test
    void anotherUsersProposalIsNotReachable() {
        when(proposalRepository.findByIdAndUserId(any(), eq(userId))).thenReturn(Optional.empty());

        assertThrows(ProposalNotFoundException.class, () -> service.accept(userId, UUID.randomUUID()));
    }

    private MemoryProposal pendingProposal() {
        return new MemoryProposal(userId, UUID.randomUUID(), MemoryType.EPISODIC,
                "Trip".getBytes(StandardCharsets.UTF_8),
                "I went to Rome.".getBytes(StandardCharsets.UTF_8), null, 0.5f);
    }

    private MemoryResponse stubMemoryResponse() {
        return new MemoryResponse(UUID.randomUUID(), MemoryType.EPISODIC, "Trip", "I went to Rome.",
                MemorySource.AI_INFERENCE, null, null, 0.9f, PrivacyLevel.PRIVATE,
                MemoryStatus.ACTIVE, false, Set.of(), Instant.now(), Instant.now());
    }
}

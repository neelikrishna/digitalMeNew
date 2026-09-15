package com.digitalself.memory;

import com.digitalself.audit.AuditService;
import com.digitalself.memory.dto.CreateMemoryRequest;
import com.digitalself.memory.dto.MemoryResponse;
import com.digitalself.memory.dto.MemorySearchQuery;
import com.digitalself.memory.dto.MemoryVersionResponse;
import com.digitalself.memory.dto.ReviseMemoryRequest;
import com.digitalself.memory.dto.UpdateMemoryMetadataRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class MemoryService {

    private final MemoryRepository memoryRepository;
    private final MemoryVersionRepository versionRepository;
    private final TagRepository tagRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final MemoryContentCrypto crypto;
    private final MemoryMapper mapper;

    public MemoryService(MemoryRepository memoryRepository,
                         MemoryVersionRepository versionRepository,
                         TagRepository tagRepository,
                         AuditService auditService,
                         ApplicationEventPublisher events,
                         MemoryContentCrypto crypto,
                         MemoryMapper mapper) {
        this.memoryRepository = memoryRepository;
        this.versionRepository = versionRepository;
        this.tagRepository = tagRepository;
        this.auditService = auditService;
        this.events = events;
        this.crypto = crypto;
        this.mapper = mapper;
    }

    /**
     * Entities are mapped to responses inside the transaction that loaded them.
     * open-in-view is disabled deliberately, so a caller that mapped an entity
     * after the transaction closed would fail on the lazy tags collection.
     * {@link #get} is the one entity-returning method, for ownership checks by
     * other services that are themselves transactional.
     */
    @Transactional
    public MemoryResponse create(UUID userId, CreateMemoryRequest request) {
        Memory memory = new Memory(
                userId,
                request.type(),
                request.title(),
                request.content(),
                request.source() == null ? MemorySource.USER_INPUT : request.source(),
                request.eventDate(),
                request.importance(),
                request.confidence() == null ? 1.0f : request.confidence(),
                request.privacyLevel() == null ? PrivacyLevel.PRIVATE : request.privacyLevel()
        );
        memory.replaceTags(resolveTags(userId, request.tags()));
        // Saved first so the row has an id: the encryption key is keyed by it.
        memoryRepository.saveAndFlush(memory);

        if (isSensitive(request)) {
            memory.storeEncrypted(
                    crypto.encrypt(memory.getId(), request.title()),
                    crypto.encrypt(memory.getId(), request.content()));
        }

        MemoryVersion initial = versionRepository.saveAndFlush(newVersion(
                memory, 1, request.content(), memory.getEventDate(),
                memory.getConfidence(), ChangeReason.INITIAL));
        memory.setCurrentVersionId(initial.getId());
        memoryRepository.save(memory);

        auditService.record(userId, "MEMORY_CREATED", "memory", memory.getId(), null, null);
        events.publishEvent(new MemoryContentChangedEvent(memory.getId()));
        return mapper.toResponse(memory);
    }

    /**
     * Reflective and emotional memories default to sensitive without being asked
     * for, since the spec singles them out as needing extra care. An explicit
     * flag can mark anything else sensitive too.
     */
    private static boolean isSensitive(CreateMemoryRequest request) {
        return Boolean.TRUE.equals(request.sensitive()) || request.type() == MemoryType.EMOTIONAL;
    }

    private MemoryVersion newVersion(Memory memory, int versionNumber, String content,
                                      java.time.LocalDate eventDate, float confidence, ChangeReason reason) {
        return memory.isSensitive()
                ? MemoryVersion.encrypted(memory.getId(), versionNumber,
                        crypto.encrypt(memory.getId(), content), eventDate, confidence, reason)
                : MemoryVersion.plaintext(memory.getId(), versionNumber,
                        content, eventDate, confidence, reason);
    }

    /** Entity access for other transactional services; not for the web layer. */
    @Transactional(readOnly = true)
    public Memory get(UUID userId, UUID memoryId) {
        return memoryRepository.findByIdAndUserId(memoryId, userId)
                .orElseThrow(() -> new MemoryNotFoundException(memoryId));
    }

    @Transactional(readOnly = true)
    public MemoryResponse getResponse(UUID userId, UUID memoryId) {
        return mapper.toResponse(get(userId, memoryId));
    }

    @Transactional(readOnly = true)
    public Page<MemoryResponse> search(UUID userId, MemorySearchQuery query, Pageable pageable) {
        Specification<Memory> spec = MemorySpecifications.ownedBy(userId);

        if (query.status() != null) {
            spec = spec.and(MemorySpecifications.hasStatus(query.status()));
        }
        if (query.type() != null) {
            spec = spec.and(MemorySpecifications.hasType(query.type()));
        }
        if (query.source() != null) {
            spec = spec.and(MemorySpecifications.hasSource(query.source()));
        }
        if (query.from() != null) {
            spec = spec.and(MemorySpecifications.eventDateFrom(query.from()));
        }
        if (query.to() != null) {
            spec = spec.and(MemorySpecifications.eventDateTo(query.to()));
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            spec = spec.and(MemorySpecifications.matchesKeyword(query.keyword()));
        }
        if (query.tag() != null && !query.tag().isBlank()) {
            spec = spec.and(MemorySpecifications.hasTag(query.tag()));
        }

        return memoryRepository.findAll(spec, pageable).map(mapper::toResponse);
    }

    /**
     * Records a correction. The prior version is preserved and stamped
     * superseded; the memory's content moves forward. Nothing is overwritten
     * in a way that loses the earlier state.
     */
    @Transactional
    public MemoryResponse revise(UUID userId, UUID memoryId, ReviseMemoryRequest request) {
        Memory memory = get(userId, memoryId);

        MemoryVersion previous = versionRepository.findFirstByMemoryIdOrderByVersionNumberDesc(memoryId)
                .orElse(null);
        int nextVersionNumber = 1;
        if (previous != null) {
            previous.markSuperseded();
            versionRepository.save(previous);
            nextVersionNumber = previous.getVersionNumber() + 1;
        }

        float confidence = request.confidence() == null ? memory.getConfidence() : request.confidence();
        MemoryVersion revision = versionRepository.saveAndFlush(newVersion(
                memory, nextVersionNumber, request.content(), request.eventDate(), confidence,
                request.changeReason() == null ? ChangeReason.USER_CORRECTION : request.changeReason()));

        memory.applyRevision(request.eventDate(), confidence);
        if (memory.isSensitive()) {
            memory.storeEncrypted(memory.getTitleEncrypted(), crypto.encrypt(memoryId, request.content()));
        } else {
            memory.storePlaintext(memory.getTitle(), request.content());
        }
        memory.setCurrentVersionId(revision.getId());
        memoryRepository.save(memory);

        auditService.record(userId, "MEMORY_REVISED", "memory", memoryId, null, null);
        events.publishEvent(new MemoryContentChangedEvent(memoryId));
        return mapper.toResponse(memory);
    }

    /**
     * Metadata edits can also flip sensitivity, which re-encrypts or decrypts the
     * stored text in place. Promoting to sensitive is the safety-improving
     * direction and is always allowed; demoting is permitted too, since the owner
     * is the only one who can judge what still needs protecting.
     */
    @Transactional
    public MemoryResponse updateMetadata(UUID userId, UUID memoryId, UpdateMemoryMetadataRequest request) {
        Memory memory = get(userId, memoryId);

        String currentTitle = mapper.title(memory);
        String currentContent = mapper.content(memory);
        String newTitle = request.title() == null ? currentTitle : request.title();
        boolean shouldBeSensitive = request.sensitive() == null ? memory.isSensitive() : request.sensitive();

        memory.updateMetadata(request.type(), request.importance(), request.privacyLevel());
        if (shouldBeSensitive) {
            memory.storeEncrypted(
                    crypto.encrypt(memoryId, newTitle),
                    crypto.encrypt(memoryId, currentContent));
        } else {
            memory.storePlaintext(newTitle, currentContent);
        }
        memory.replaceTags(resolveTags(userId, request.tags()));
        memoryRepository.save(memory);

        auditService.record(userId, "MEMORY_METADATA_UPDATED", "memory", memoryId, null, null);
        // Title is part of the embedded text, so a metadata edit can stale the embedding.
        events.publishEvent(new MemoryContentChangedEvent(memoryId));
        return mapper.toResponse(memory);
    }

    /**
     * Archive rather than delete: memories are retained and remain retrievable
     * by explicit status filter. There is deliberately no hard-delete endpoint.
     */
    @Transactional
    public MemoryResponse archive(UUID userId, UUID memoryId) {
        Memory memory = get(userId, memoryId);
        memory.archive();
        memoryRepository.save(memory);
        auditService.record(userId, "MEMORY_ARCHIVED", "memory", memoryId, null, null);
        return mapper.toResponse(memory);
    }

    @Transactional
    public MemoryResponse restore(UUID userId, UUID memoryId) {
        Memory memory = get(userId, memoryId);
        memory.restore();
        memoryRepository.save(memory);
        auditService.record(userId, "MEMORY_RESTORED", "memory", memoryId, null, null);
        return mapper.toResponse(memory);
    }

    @Transactional(readOnly = true)
    public List<MemoryVersionResponse> history(UUID userId, UUID memoryId) {
        Memory memory = get(userId, memoryId); // also the ownership check
        return versionRepository.findByMemoryIdOrderByVersionNumberAsc(memoryId).stream()
                .map(version -> mapper.toVersionResponse(
                        version, version.getId().equals(memory.getCurrentVersionId())))
                .toList();
    }

    private Set<Tag> resolveTags(UUID userId, Set<String> tagNames) {
        Set<Tag> tags = new HashSet<>();
        if (tagNames == null) {
            return tags;
        }
        for (String rawName : tagNames) {
            String name = rawName.trim().toLowerCase();
            if (name.isEmpty()) {
                continue;
            }
            tags.add(tagRepository.findByUserIdAndName(userId, name)
                    .orElseGet(() -> tagRepository.save(new Tag(userId, name))));
        }
        return tags;
    }
}

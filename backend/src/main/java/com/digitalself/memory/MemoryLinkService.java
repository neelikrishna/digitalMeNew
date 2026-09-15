package com.digitalself.memory;

import com.digitalself.audit.AuditService;
import com.digitalself.memory.dto.CreateLinkRequest;
import com.digitalself.memory.dto.LinkedMemoryResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MemoryLinkService {

    private final MemoryLinkRepository linkRepository;
    private final MemoryRepository memoryRepository;
    private final MemoryService memoryService;
    private final AuditService auditService;

    public MemoryLinkService(MemoryLinkRepository linkRepository,
                             MemoryRepository memoryRepository,
                             MemoryService memoryService,
                             AuditService auditService) {
        this.linkRepository = linkRepository;
        this.memoryRepository = memoryRepository;
        this.memoryService = memoryService;
        this.auditService = auditService;
    }

    @Transactional
    public LinkedMemoryResponse link(UUID userId, UUID sourceId, CreateLinkRequest request) {
        if (sourceId.equals(request.targetMemoryId())) {
            throw new IllegalArgumentException("A memory cannot be linked to itself.");
        }
        // Ownership of both ends is checked before any edge is written, so a link
        // can never bridge into another user's data.
        memoryService.get(userId, sourceId);
        Memory target = memoryService.get(userId, request.targetMemoryId());

        MemoryLink link = linkRepository
                .findBySourceMemoryIdAndTargetMemoryIdAndLinkType(sourceId, request.targetMemoryId(), request.linkType())
                .orElseGet(() -> {
                    MemoryLink saved = linkRepository.save(
                            new MemoryLink(sourceId, request.targetMemoryId(), request.linkType()));
                    auditService.record(userId, "MEMORY_LINKED", "memory", sourceId, null, null);
                    return saved;
                });

        return new LinkedMemoryResponse(link.getId(), link.getLinkType(), "OUTGOING",
                target.getId(), target.getTitle(), target.getType());
    }

    @Transactional
    public void unlink(UUID userId, UUID memoryId, UUID linkId) {
        memoryService.get(userId, memoryId);
        MemoryLink link = linkRepository.findById(linkId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown link."));
        if (!link.getSourceMemoryId().equals(memoryId) && !link.getTargetMemoryId().equals(memoryId)) {
            throw new IllegalArgumentException("That link does not belong to this memory.");
        }
        linkRepository.delete(link);
        auditService.record(userId, "MEMORY_UNLINKED", "memory", memoryId, null, null);
    }

    @Transactional(readOnly = true)
    public List<LinkedMemoryResponse> links(UUID userId, UUID memoryId) {
        memoryService.get(userId, memoryId);
        List<MemoryLink> links = linkRepository.findBySourceMemoryIdOrTargetMemoryId(memoryId, memoryId);

        List<UUID> otherIds = links.stream()
                .map(link -> link.getSourceMemoryId().equals(memoryId)
                        ? link.getTargetMemoryId()
                        : link.getSourceMemoryId())
                .toList();

        Map<UUID, Memory> others = new HashMap<>();
        memoryRepository.findAllById(otherIds).forEach(memory -> others.put(memory.getId(), memory));

        List<LinkedMemoryResponse> responses = new ArrayList<>();
        for (MemoryLink link : links) {
            boolean outgoing = link.getSourceMemoryId().equals(memoryId);
            UUID otherId = outgoing ? link.getTargetMemoryId() : link.getSourceMemoryId();
            Memory other = others.get(otherId);
            if (other == null) {
                continue;
            }
            responses.add(new LinkedMemoryResponse(
                    link.getId(),
                    link.getLinkType(),
                    outgoing ? "OUTGOING" : "INCOMING",
                    otherId,
                    other.getTitle(),
                    other.getType()));
        }
        return responses;
    }
}

package com.digitalself.memory;

import com.digitalself.audit.AuditService;
import com.digitalself.memory.dto.CreateLinkRequest;
import com.digitalself.memory.dto.LinkedMemoryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemoryLinkServiceTest {

    private MemoryLinkRepository linkRepository;
    private MemoryRepository memoryRepository;
    private MemoryService memoryService;
    private MemoryLinkService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        linkRepository = mock(MemoryLinkRepository.class);
        memoryRepository = mock(MemoryRepository.class);
        memoryService = mock(MemoryService.class);

        when(linkRepository.save(any(MemoryLink.class))).thenAnswer(i -> i.getArgument(0));
        service = new MemoryLinkService(linkRepository, memoryRepository, memoryService, mock(AuditService.class));
    }

    @Test
    void rejectsSelfLinks() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> service.link(userId, id, new CreateLinkRequest(id, "relates_to")));
    }

    @Test
    void checksOwnershipOfBothEndsBeforeWritingAnEdge() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(memoryService.get(userId, source)).thenReturn(memoryWithId(source));
        when(memoryService.get(userId, target)).thenThrow(new MemoryNotFoundException(target));

        assertThrows(MemoryNotFoundException.class,
                () -> service.link(userId, source, new CreateLinkRequest(target, "relates_to")));
        verify(linkRepository, never()).save(any());
    }

    @Test
    void linkingTwiceDoesNotCreateADuplicateEdge() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(memoryService.get(userId, source)).thenReturn(memoryWithId(source));
        when(memoryService.get(userId, target)).thenReturn(memoryWithId(target));
        when(linkRepository.findBySourceMemoryIdAndTargetMemoryIdAndLinkType(source, target, "relates_to"))
                .thenReturn(Optional.of(new MemoryLink(source, target, "relates_to")));

        service.link(userId, source, new CreateLinkRequest(target, "relates_to"));

        verify(linkRepository, never()).save(any());
    }

    @Test
    void listsBothIncomingAndOutgoingEdges() {
        UUID subject = UUID.randomUUID();
        UUID outgoingTarget = UUID.randomUUID();
        UUID incomingSource = UUID.randomUUID();

        when(memoryService.get(userId, subject)).thenReturn(memoryWithId(subject));
        when(linkRepository.findBySourceMemoryIdOrTargetMemoryId(subject, subject)).thenReturn(List.of(
                new MemoryLink(subject, outgoingTarget, "follows"),
                new MemoryLink(incomingSource, subject, "contradicts")));
        when(memoryRepository.findAllById(any()))
                .thenReturn(List.of(memoryWithId(outgoingTarget), memoryWithId(incomingSource)));

        List<LinkedMemoryResponse> links = service.links(userId, subject);

        assertEquals(2, links.size());
        assertEquals("OUTGOING", links.get(0).direction());
        assertEquals(outgoingTarget, links.get(0).memoryId());
        assertEquals("INCOMING", links.get(1).direction());
        assertEquals(incomingSource, links.get(1).memoryId());
    }

    @Test
    void cannotRemoveALinkThatBelongsToAnotherMemory() {
        UUID subject = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        when(memoryService.get(userId, subject)).thenReturn(memoryWithId(subject));
        when(linkRepository.findById(linkId))
                .thenReturn(Optional.of(new MemoryLink(UUID.randomUUID(), UUID.randomUUID(), "follows")));

        assertThrows(IllegalArgumentException.class, () -> service.unlink(userId, subject, linkId));
        verify(linkRepository, never()).delete(any());
    }

    private Memory memoryWithId(UUID id) {
        Memory memory = new Memory(userId, MemoryType.EPISODIC, "Title", "Content.",
                MemorySource.USER_INPUT, null, null, 1.0f, PrivacyLevel.PRIVATE);
        try {
            var field = Memory.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(memory, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return memory;
    }
}

package com.digitalself.memory;

import com.digitalself.audit.AuditService;
import com.digitalself.memory.dto.CreateMemoryRequest;
import com.digitalself.memory.dto.MemoryResponse;
import com.digitalself.memory.dto.ReviseMemoryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemoryServiceTest {

    private MemoryRepository memoryRepository;
    private MemoryVersionRepository versionRepository;
    private TagRepository tagRepository;
    private ApplicationEventPublisher events;
    private MemoryService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        memoryRepository = mock(MemoryRepository.class);
        versionRepository = mock(MemoryVersionRepository.class);
        tagRepository = mock(TagRepository.class);
        events = mock(ApplicationEventPublisher.class);
        MemoryContentCrypto crypto = mock(MemoryContentCrypto.class);
        service = new MemoryService(memoryRepository, versionRepository, tagRepository,
                mock(AuditService.class), events, crypto, new MemoryMapper(crypto),
                mock(com.digitalself.memory.chunk.MemoryChunker.class));

        when(memoryRepository.saveAndFlush(any(Memory.class))).thenAnswer(i -> i.getArgument(0));
        when(memoryRepository.save(any(Memory.class))).thenAnswer(i -> i.getArgument(0));
        when(versionRepository.saveAndFlush(any(MemoryVersion.class))).thenAnswer(i -> i.getArgument(0));
        when(versionRepository.save(any(MemoryVersion.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void createWritesAnInitialVersion() {
        CreateMemoryRequest request = new CreateMemoryRequest(
                MemoryType.EPISODIC, "College", "I met John in college.", null,
                LocalDate.of(2018, 6, 1), (short) 3, null, null, null, Set.of());

        service.create(userId, request);

        ArgumentCaptor<MemoryVersion> captor = ArgumentCaptor.forClass(MemoryVersion.class);
        verify(versionRepository).saveAndFlush(captor.capture());
        MemoryVersion initial = captor.getValue();

        assertEquals(1, initial.getVersionNumber());
        assertEquals(ChangeReason.INITIAL, initial.getChangeReason());
        assertEquals("I met John in college.", initial.getContent());
    }

    @Test
    void createDefaultsToUserInputSourceAndPrivateLevel() {
        CreateMemoryRequest request = new CreateMemoryRequest(
                MemoryType.SEMANTIC, null, "I prefer Java for backend work.", null,
                null, null, null, null, null, null);

        MemoryResponse memory = service.create(userId, request);

        assertEquals(MemorySource.USER_INPUT, memory.source());
        assertEquals(PrivacyLevel.PRIVATE, memory.privacyLevel());
        assertEquals(1.0f, memory.confidence());
    }

    @Test
    void reviseSupersedesPreviousVersionAndKeepsIt() {
        UUID memoryId = UUID.randomUUID();
        Memory memory = existingMemory(memoryId, "Event happened in 2020.", LocalDate.of(2020, 1, 1));
        MemoryVersion existingVersion = MemoryVersion.plaintext(
                memoryId, 1, "Event happened in 2020.", LocalDate.of(2020, 1, 1), 1.0f, ChangeReason.INITIAL);

        when(memoryRepository.findByIdAndUserId(memoryId, userId)).thenReturn(Optional.of(memory));
        when(versionRepository.findFirstByMemoryIdOrderByVersionNumberDesc(memoryId))
                .thenReturn(Optional.of(existingVersion));

        service.revise(userId, memoryId, new ReviseMemoryRequest(
                "Event happened in 2019.", LocalDate.of(2019, 1, 1), null, null));

        // Old version retained, stamped superseded — never deleted.
        assertNotNull(existingVersion.getSupersededAt());
        verify(versionRepository, never()).delete(any());

        ArgumentCaptor<MemoryVersion> captor = ArgumentCaptor.forClass(MemoryVersion.class);
        verify(versionRepository).saveAndFlush(captor.capture());
        MemoryVersion revision = captor.getValue();

        assertEquals(2, revision.getVersionNumber());
        assertEquals(ChangeReason.USER_CORRECTION, revision.getChangeReason());
        assertEquals("Event happened in 2019.", revision.getContent());
        assertEquals(LocalDate.of(2019, 1, 1), revision.getEventDate());
        assertEquals("Event happened in 2019.", memory.getContent());
    }

    @Test
    void contentChangesQueueReindexing() {
        CreateMemoryRequest request = new CreateMemoryRequest(
                MemoryType.EPISODIC, null, "Something worth remembering.", null,
                null, null, null, null, null, null);

        MemoryResponse memory = service.create(userId, request);

        verify(events).publishEvent(new MemoryContentChangedEvent(memory.id()));
    }

    @Test
    void archiveDoesNotDelete() {
        UUID memoryId = UUID.randomUUID();
        Memory memory = existingMemory(memoryId, "Something.", null);
        when(memoryRepository.findByIdAndUserId(memoryId, userId)).thenReturn(Optional.of(memory));

        service.archive(userId, memoryId);

        assertEquals(MemoryStatus.ARCHIVED, memory.getStatus());
        verify(memoryRepository, never()).delete(any(Memory.class));
        verify(memoryRepository, never()).deleteById(any());
    }

    @Test
    void anotherUsersMemoryIsNotReachable() {
        UUID memoryId = UUID.randomUUID();
        when(memoryRepository.findByIdAndUserId(memoryId, userId)).thenReturn(Optional.empty());

        assertThrows(MemoryNotFoundException.class, () -> service.get(userId, memoryId));
    }

    @Test
    void historyChecksOwnershipBeforeReturningVersions() {
        UUID memoryId = UUID.randomUUID();
        when(memoryRepository.findByIdAndUserId(memoryId, userId)).thenReturn(Optional.empty());

        assertThrows(MemoryNotFoundException.class, () -> service.history(userId, memoryId));
        verify(versionRepository, never()).findByMemoryIdOrderByVersionNumberAsc(any());
    }

    @Test
    void tagsAreNormalisedAndReused() {
        UUID memoryId = UUID.randomUUID();
        Tag existing = new Tag(userId, "college");
        when(tagRepository.findByUserIdAndName(userId, "college")).thenReturn(Optional.of(existing));
        when(tagRepository.save(any(Tag.class))).thenAnswer(i -> i.getArgument(0));

        CreateMemoryRequest request = new CreateMemoryRequest(
                MemoryType.EPISODIC, null, "Something from college.", null,
                null, null, null, null, null, Set.of("  College  "));

        MemoryResponse memory = service.create(userId, request);

        assertEquals(Set.of("college"), memory.tags());
        verify(tagRepository, never()).save(any(Tag.class));
    }

    private Memory existingMemory(UUID id, String content, LocalDate eventDate) {
        Memory memory = new Memory(userId, MemoryType.EPISODIC, "Title", content,
                MemorySource.USER_INPUT, eventDate, (short) 3, 1.0f, PrivacyLevel.PRIVATE);
        setId(memory, id);
        return memory;
    }

    private void setId(Memory memory, UUID id) {
        try {
            var field = Memory.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(memory, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

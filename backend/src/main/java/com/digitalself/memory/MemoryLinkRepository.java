package com.digitalself.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemoryLinkRepository extends JpaRepository<MemoryLink, UUID> {

    List<MemoryLink> findBySourceMemoryIdOrTargetMemoryId(UUID sourceId, UUID targetId);

    Optional<MemoryLink> findBySourceMemoryIdAndTargetMemoryIdAndLinkType(
            UUID sourceMemoryId, UUID targetMemoryId, String linkType);
}

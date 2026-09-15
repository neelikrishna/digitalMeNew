package com.digitalself.memory;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface MemoryRepository extends JpaRepository<Memory, UUID>, JpaSpecificationExecutor<Memory> {

    /**
     * Ownership is enforced here rather than only in the controller: any lookup
     * of a single memory must prove the requesting user owns it.
     */
    Optional<Memory> findByIdAndUserId(UUID id, UUID userId);

    Page<Memory> findByUserIdAndStatus(UUID userId, MemoryStatus status, Pageable pageable);
}

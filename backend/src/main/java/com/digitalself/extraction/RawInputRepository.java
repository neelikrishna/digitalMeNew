package com.digitalself.extraction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RawInputRepository extends JpaRepository<RawInput, UUID> {

    Optional<RawInput> findByIdAndUserId(UUID id, UUID userId);
}

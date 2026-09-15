package com.digitalself.files;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    Optional<StoredFile> findByIdAndUserId(UUID id, UUID userId);

    Optional<StoredFile> findByUserIdAndContentHash(UUID userId, String contentHash);

    List<StoredFile> findByUserIdOrderByUploadedAtDesc(UUID userId);
}

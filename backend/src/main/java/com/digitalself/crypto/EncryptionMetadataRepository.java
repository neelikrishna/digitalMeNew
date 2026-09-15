package com.digitalself.crypto;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EncryptionMetadataRepository extends JpaRepository<EncryptionMetadata, UUID> {

    Optional<EncryptionMetadata> findBySubjectTypeAndSubjectId(String subjectType, UUID subjectId);

    void deleteBySubjectTypeAndSubjectId(String subjectType, UUID subjectId);
}

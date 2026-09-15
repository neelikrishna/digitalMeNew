package com.digitalself.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TagRepository extends JpaRepository<Tag, UUID> {

    Optional<Tag> findByUserIdAndName(UUID userId, String name);

    List<Tag> findByUserIdOrderByNameAsc(UUID userId);
}

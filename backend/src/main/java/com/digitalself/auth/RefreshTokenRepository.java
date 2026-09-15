package com.digitalself.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);
    List<RefreshToken> findByUserId(UUID userId);
    List<RefreshToken> findByRevokedAtIsNullAndExpiresAtBefore(Instant cutoff);
}

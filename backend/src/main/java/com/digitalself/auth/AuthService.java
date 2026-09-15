package com.digitalself.auth;

import com.digitalself.audit.AuditService;
import com.digitalself.auth.dto.AuthResponse;
import com.digitalself.auth.dto.LoginRequest;
import com.digitalself.auth.dto.RegisterRequest;
import com.digitalself.config.JwtProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final LoginRateLimiter rateLimiter;
    private final AuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(UserRepository userRepository,
                        RefreshTokenRepository refreshTokenRepository,
                        PasswordEncoder passwordEncoder,
                        JwtService jwtService,
                        JwtProperties jwtProperties,
                        LoginRateLimiter rateLimiter,
                        AuditService auditService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request, String ipAddress) {
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("An account with this email already exists.");
        }
        User user = new User(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.displayName(),
                UserRole.OWNER
        );
        userRepository.save(user);
        auditService.record(user.getId(), "USER_REGISTERED", "user", user.getId(), user.getEmail(), ipAddress);
        return issueTokenPair(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress) {
        String rateLimitKey = request.email().toLowerCase();
        if (!rateLimiter.isAllowed(rateLimitKey)) {
            throw new AuthException("Too many login attempts. Try again later.");
        }
        rateLimiter.recordAttempt(rateLimitKey);

        Optional<User> maybeUser = userRepository.findByEmail(request.email());
        if (maybeUser.isEmpty() || !maybeUser.get().isActive()
                || !passwordEncoder.matches(request.password(), maybeUser.get().getPasswordHash())) {
            auditService.record(null, "LOGIN_FAILED", "user", null, request.email(), ipAddress);
            throw new AuthException("Invalid email or password.");
        }

        User user = maybeUser.get();
        rateLimiter.reset(rateLimitKey);
        auditService.record(user.getId(), "LOGIN_SUCCEEDED", "user", user.getId(), user.getEmail(), ipAddress);
        return issueTokenPair(user);
    }

    @Transactional
    public AuthResponse refresh(String presentedToken, String ipAddress) {
        String presentedHash = hash(presentedToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(presentedHash)
                .orElseThrow(() -> new AuthException("Invalid refresh token."));

        if (stored.isRevoked()) {
            // A revoked token being presented again is a replay/theft signal:
            // revoke every active session for this user, not just this token.
            revokeAllForUser(stored.getUserId());
            auditService.record(stored.getUserId(), "REFRESH_TOKEN_REUSE_DETECTED", "user", stored.getUserId(), null, ipAddress);
            throw new AuthException("Refresh token has already been used. All sessions have been revoked.");
        }
        if (stored.isExpired()) {
            throw new AuthException("Refresh token has expired.");
        }

        User user = userRepository.findById(stored.getUserId())
                .filter(User::isActive)
                .orElseThrow(() -> new AuthException("Account is no longer active."));

        AuthResponse response = issueTokenPair(user);
        // Link the rotation chain: mark the old token replaced by the new one.
        RefreshToken newToken = refreshTokenRepository.findByTokenHash(hash(response.refreshToken())).orElseThrow();
        stored.revoke(newToken.getId());
        refreshTokenRepository.save(stored);

        auditService.record(user.getId(), "TOKEN_REFRESHED", "user", user.getId(), user.getEmail(), ipAddress);
        return response;
    }

    private void revokeAllForUser(UUID userId) {
        List<RefreshToken> active = refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId);
        Instant now = Instant.now();
        active.forEach(t -> {
            if (!t.isRevoked()) {
                t.revoke(null);
            }
        });
        refreshTokenRepository.saveAll(active);
    }

    private AuthResponse issueTokenPair(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String rawRefreshToken = generateOpaqueToken();
        Instant expiresAt = Instant.now().plus(jwtProperties.getRefreshTokenTtlDays(), ChronoUnit.DAYS);
        refreshTokenRepository.save(new RefreshToken(user.getId(), hash(rawRefreshToken), expiresAt));
        return new AuthResponse(accessToken, rawRefreshToken, jwtProperties.getAccessTokenTtlMinutes() * 60L);
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

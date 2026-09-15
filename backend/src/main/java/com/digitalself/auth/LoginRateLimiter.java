package com.digitalself.auth;

import com.digitalself.config.LoginRateLimitProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Minimal in-memory fixed-window limiter for login attempts, keyed by email.
 * Enough to blunt naive credential-stuffing against a single-user instance
 * without pulling in a dedicated rate-limiting library. Not distributed —
 * fine as long as the backend runs as one process, which is the Phase 1 model.
 */
@Component
public class LoginRateLimiter {

    private final LoginRateLimitProperties properties;
    private final ConcurrentHashMap<String, Deque<Instant>> attemptsByKey = new ConcurrentHashMap<>();

    public LoginRateLimiter(LoginRateLimitProperties properties) {
        this.properties = properties;
    }

    public boolean isAllowed(String key) {
        Deque<Instant> attempts = attemptsByKey.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        Instant windowStart = Instant.now().minusSeconds(properties.getLoginRateLimit().getWindowMinutes() * 60L);
        attempts.removeIf(t -> t.isBefore(windowStart));
        return attempts.size() < properties.getLoginRateLimit().getMaxAttempts();
    }

    public void recordAttempt(String key) {
        attemptsByKey.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>()).add(Instant.now());
    }

    public void reset(String key) {
        attemptsByKey.remove(key);
    }
}

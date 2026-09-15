package com.digitalself.auth;

import com.digitalself.config.JwtProperties;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private final JwtProperties properties = new JwtProperties();

    JwtServiceTest() {
        properties.setSecret("unit-test-secret-please-do-not-reuse-0123456789");
        properties.setAccessTokenTtlMinutes(15);
        properties.setRefreshTokenTtlDays(30);
    }

    @Test
    void generatesTokenThatEncodesUserIdAndRole() {
        JwtService jwtService = new JwtService(properties);
        User user = new User(UUID.randomUUID(), "test@example.com", "hash", "Test User", UserRole.OWNER);

        String token = jwtService.generateAccessToken(user);

        assertTrue(jwtService.isValid(token));
        assertEquals(user.getId(), jwtService.extractUserId(token));
        assertEquals("OWNER", jwtService.extractRole(token));
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        JwtService jwtService = new JwtService(properties);
        User user = new User(UUID.randomUUID(), "test@example.com", "hash", "Test User", UserRole.OWNER);
        String token = jwtService.generateAccessToken(user);

        JwtProperties otherProperties = new JwtProperties();
        otherProperties.setSecret("a-completely-different-secret-9876543210");
        JwtService otherJwtService = new JwtService(otherProperties);

        assertFalse(otherJwtService.isValid(token));
    }

    @Test
    void constructorFailsFastWhenSecretIsBlank() {
        JwtProperties blank = new JwtProperties();
        blank.setSecret("");
        assertThrows(IllegalStateException.class, () -> new JwtService(blank));
    }
}

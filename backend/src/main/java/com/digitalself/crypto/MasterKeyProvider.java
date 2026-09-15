package com.digitalself.crypto;

import com.digitalself.config.CryptoProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * Supplies the master key that wraps every per-file data-encryption key.
 *
 * <p>The key is read from configuration (environment in practice) and never
 * from source control or the database. Losing it means the encrypted files are
 * unrecoverable, which is the intended property: the ciphertext on disk and the
 * wrapped keys in the database are both useless without it.
 */
@Component
public class MasterKeyProvider {

    private static final int REQUIRED_KEY_BYTES = 32; // AES-256

    private final SecretKey masterKey;
    private final String label;

    public MasterKeyProvider(CryptoProperties properties) {
        String configured = properties.getMasterKey();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "DIGITALSELF_MASTER_KEY is not set. File encryption cannot start without it. "
                            + "Generate 32 random bytes, base64-encode them, and set the variable.");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(configured.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("DIGITALSELF_MASTER_KEY must be base64-encoded.", e);
        }
        if (keyBytes.length != REQUIRED_KEY_BYTES) {
            throw new IllegalStateException(
                    "DIGITALSELF_MASTER_KEY must decode to exactly 32 bytes (got " + keyBytes.length + ").");
        }
        this.masterKey = new SecretKeySpec(keyBytes, "AES");
        this.label = properties.getMasterKeyLabel();
    }

    public SecretKey masterKey() {
        return masterKey;
    }

    /** Identifies which master key wrapped a given DEK, so keys can be rotated. */
    public String label() {
        return label;
    }
}

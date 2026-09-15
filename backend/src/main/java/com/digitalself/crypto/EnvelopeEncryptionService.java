package com.digitalself.crypto;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * AES-256-GCM envelope encryption.
 *
 * <p>Every payload gets its own data-encryption key, and that key is stored
 * only in wrapped form. Rotating the master key therefore means re-wrapping
 * small keys rather than re-encrypting every file, and destroying a single DEK
 * renders one payload permanently unrecoverable — the crypto-shredding
 * behaviour described in docs/security.md.
 *
 * <p>GCM is authenticated, so tampering with stored ciphertext is detected on
 * decryption rather than silently returning corrupted data.
 */
@Service
public class EnvelopeEncryptionService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final int DEK_BITS = 256;

    private final MasterKeyProvider masterKeyProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public EnvelopeEncryptionService(MasterKeyProvider masterKeyProvider) {
        this.masterKeyProvider = masterKeyProvider;
    }

    public SecretKey newDataKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(DEK_BITS, secureRandom);
            return generator.generateKey();
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate a data encryption key.", e);
        }
    }

    public WrappedKey wrap(SecretKey dataKey) {
        byte[] iv = randomIv();
        byte[] wrapped = transform(Cipher.ENCRYPT_MODE, masterKeyProvider.masterKey(), iv, dataKey.getEncoded());
        return new WrappedKey(wrapped, iv, masterKeyProvider.label());
    }

    public SecretKey unwrap(WrappedKey wrappedKey) {
        byte[] raw = transform(Cipher.DECRYPT_MODE, masterKeyProvider.masterKey(),
                wrappedKey.iv(), wrappedKey.wrappedKey());
        return new SecretKeySpec(raw, "AES");
    }

    /** Returns IV-prefixed ciphertext, so a payload carries what it needs to be decrypted. */
    public byte[] encrypt(SecretKey dataKey, byte[] plaintext) {
        byte[] iv = randomIv();
        byte[] ciphertext = transform(Cipher.ENCRYPT_MODE, dataKey, iv, plaintext);
        byte[] result = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
        return result;
    }

    public byte[] decrypt(SecretKey dataKey, byte[] ivAndCiphertext) {
        if (ivAndCiphertext.length <= GCM_IV_BYTES) {
            throw new IllegalArgumentException("Encrypted payload is too short to contain an IV.");
        }
        byte[] iv = new byte[GCM_IV_BYTES];
        System.arraycopy(ivAndCiphertext, 0, iv, 0, GCM_IV_BYTES);
        byte[] ciphertext = new byte[ivAndCiphertext.length - GCM_IV_BYTES];
        System.arraycopy(ivAndCiphertext, GCM_IV_BYTES, ciphertext, 0, ciphertext.length);
        return transform(Cipher.DECRYPT_MODE, dataKey, iv, ciphertext);
    }

    private byte[] randomIv() {
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        return iv;
    }

    private byte[] transform(int mode, SecretKey key, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(input);
        } catch (Exception e) {
            // Deliberately vague: the cause can reveal whether a key or an
            // authentication tag was wrong, which is not information to leak.
            throw new EncryptionException(mode == Cipher.ENCRYPT_MODE
                    ? "Encryption failed."
                    : "Decryption failed — the data may have been tampered with or the key is wrong.");
        }
    }
}

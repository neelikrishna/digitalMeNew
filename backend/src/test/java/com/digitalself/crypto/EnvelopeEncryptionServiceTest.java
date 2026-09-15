package com.digitalself.crypto;

import com.digitalself.config.CryptoProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EnvelopeEncryptionServiceTest {

    private final EnvelopeEncryptionService service = serviceWithRandomMasterKey();

    @Test
    void roundTripsContent() {
        SecretKey dek = service.newDataKey();
        byte[] plaintext = "A private memory.".getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = service.encrypt(dek, plaintext);
        assertArrayEquals(plaintext, service.decrypt(dek, encrypted));
    }

    @Test
    void ciphertextDoesNotContainThePlaintext() {
        SecretKey dek = service.newDataKey();
        byte[] encrypted = service.encrypt(dek, "SECRET-MARKER".getBytes(StandardCharsets.UTF_8));

        assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains("SECRET-MARKER"));
    }

    @Test
    void encryptingTheSameContentTwiceProducesDifferentCiphertext() {
        SecretKey dek = service.newDataKey();
        byte[] plaintext = "Same input every time.".getBytes(StandardCharsets.UTF_8);

        // A fresh IV per operation: identical files must not be identifiable
        // as identical just by looking at what is stored.
        assertFalse(java.util.Arrays.equals(
                service.encrypt(dek, plaintext), service.encrypt(dek, plaintext)));
    }

    @Test
    void wrappedKeysRoundTripThroughTheMasterKey() {
        SecretKey dek = service.newDataKey();
        WrappedKey wrapped = service.wrap(dek);

        assertArrayEquals(dek.getEncoded(), service.unwrap(wrapped).getEncoded());
        assertFalse(java.util.Arrays.equals(dek.getEncoded(), wrapped.wrappedKey()),
                "the stored form must not be the raw key");
    }

    @Test
    void aDifferentMasterKeyCannotUnwrap() {
        SecretKey dek = service.newDataKey();
        WrappedKey wrapped = service.wrap(dek);

        EnvelopeEncryptionService otherInstance = serviceWithRandomMasterKey();

        assertThrows(EncryptionException.class, () -> otherInstance.unwrap(wrapped));
    }

    @Test
    void tamperedCiphertextIsRejectedRatherThanReturnedCorrupted() {
        SecretKey dek = service.newDataKey();
        byte[] encrypted = service.encrypt(dek, "Authentic content.".getBytes(StandardCharsets.UTF_8));

        encrypted[encrypted.length - 1] ^= 0x01; // flip one bit of the GCM tag

        assertThrows(EncryptionException.class, () -> service.decrypt(dek, encrypted));
    }

    @Test
    void theWrongDataKeyCannotDecrypt() {
        byte[] encrypted = service.encrypt(service.newDataKey(), "Content.".getBytes(StandardCharsets.UTF_8));

        assertThrows(EncryptionException.class, () -> service.decrypt(service.newDataKey(), encrypted));
    }

    @Test
    void dataKeysAreDistinctPerCall() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            assertTrue(seen.add(Base64.getEncoder().encodeToString(service.newDataKey().getEncoded())),
                    "every payload must get its own key");
        }
    }

    @Test
    void rejectsAMasterKeyOfTheWrongLength() {
        CryptoProperties properties = new CryptoProperties();
        properties.setMasterKey(Base64.getEncoder().encodeToString(new byte[16])); // 128-bit, too short

        assertThrows(IllegalStateException.class, () -> new MasterKeyProvider(properties));
    }

    @Test
    void refusesToStartWithoutAMasterKey() {
        CryptoProperties properties = new CryptoProperties();
        properties.setMasterKey("");

        assertThrows(IllegalStateException.class, () -> new MasterKeyProvider(properties));
    }

    @Test
    void rejectsAMasterKeyThatIsNotBase64() {
        CryptoProperties properties = new CryptoProperties();
        properties.setMasterKey("this is not base64 !!!");

        assertThrows(IllegalStateException.class, () -> new MasterKeyProvider(properties));
    }

    private static EnvelopeEncryptionService serviceWithRandomMasterKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        CryptoProperties properties = new CryptoProperties();
        properties.setMasterKey(Base64.getEncoder().encodeToString(key));
        return new EnvelopeEncryptionService(new MasterKeyProvider(properties));
    }
}

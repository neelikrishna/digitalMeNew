package com.digitalself.memory;

import com.digitalself.crypto.TextCrypto;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Memory-shaped view of {@link TextCrypto}. A memory's versions share its key,
 * so shredding that one key destroys the memory and its entire history.
 */
@Component
public class MemoryContentCrypto {

    private final TextCrypto textCrypto;

    public MemoryContentCrypto(TextCrypto textCrypto) {
        this.textCrypto = textCrypto;
    }

    public byte[] encrypt(UUID memoryId, String plaintext) {
        return textCrypto.encrypt(TextCrypto.MEMORY, memoryId, plaintext);
    }

    public String decrypt(UUID memoryId, byte[] ciphertext) {
        return textCrypto.decrypt(TextCrypto.MEMORY, memoryId, ciphertext);
    }

    public void shredKey(UUID memoryId) {
        textCrypto.shredKey(TextCrypto.MEMORY, memoryId);
    }
}

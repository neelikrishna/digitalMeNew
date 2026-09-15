package com.digitalself.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Encrypts text belonging to some subject — a memory, a raw input, a
 * conversation — under a data key of its own, wrapped by the master key.
 *
 * <p>Rows can deliberately share a subject: a memory's versions share the
 * memory's key, and a conversation's messages share the conversation's key, so
 * destroying one key shreds a whole coherent unit rather than leaving fragments
 * behind.
 */
@Component
public class TextCrypto {

    public static final String MEMORY = "memory";
    public static final String RAW_INPUT = "raw_input";
    public static final String CONVERSATION = "conversation";
    /**
     * Files. Text extracted from a sensitive file is encrypted under the file's
     * own key rather than a new one, which is what makes crypto-shredding the
     * file also destroy everything read out of it.
     */
    public static final String FILE = "file";

    private final EnvelopeEncryptionService encryption;
    private final EncryptionMetadataRepository metadataRepository;

    public TextCrypto(EnvelopeEncryptionService encryption,
                      EncryptionMetadataRepository metadataRepository) {
        this.encryption = encryption;
        this.metadataRepository = metadataRepository;
    }

    public byte[] encrypt(String subjectType, UUID subjectId, String plaintext) {
        if (plaintext == null) {
            return null;
        }
        return encryption.encrypt(dataKeyFor(subjectType, subjectId),
                plaintext.getBytes(StandardCharsets.UTF_8));
    }

    public String decrypt(String subjectType, UUID subjectId, byte[] ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        SecretKey dataKey = encryption.unwrap(
                metadataRepository.findBySubjectTypeAndSubjectId(subjectType, subjectId)
                        .orElseThrow(() -> new IllegalStateException(
                                "No encryption key on record for " + subjectType + " " + subjectId))
                        .toWrappedKey());
        return new String(encryption.decrypt(dataKey, ciphertext), StandardCharsets.UTF_8);
    }

    /** Destroys the key, rendering everything encrypted under it unrecoverable. */
    public void shredKey(String subjectType, UUID subjectId) {
        metadataRepository.deleteBySubjectTypeAndSubjectId(subjectType, subjectId);
    }

    private SecretKey dataKeyFor(String subjectType, UUID subjectId) {
        return metadataRepository.findBySubjectTypeAndSubjectId(subjectType, subjectId)
                .map(metadata -> encryption.unwrap(metadata.toWrappedKey()))
                .orElseGet(() -> {
                    SecretKey dataKey = encryption.newDataKey();
                    metadataRepository.save(new EncryptionMetadata(
                            subjectType, subjectId, encryption.wrap(dataKey)));
                    return dataKey;
                });
    }
}

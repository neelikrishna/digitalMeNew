package com.digitalself.memory;

import com.digitalself.memory.dto.MemoryResponse;
import com.digitalself.memory.dto.MemoryVersionResponse;
import org.springframework.stereotype.Component;

import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Builds responses from entities, decrypting sensitive text on the way out.
 *
 * <p>A component rather than a static factory because decryption needs the key
 * material. Must be called inside the transaction that loaded the entity — the
 * tags collection is lazy and open-in-view is disabled.
 */
@Component
public class MemoryMapper {

    private final MemoryContentCrypto crypto;

    public MemoryMapper(MemoryContentCrypto crypto) {
        this.crypto = crypto;
    }

    public MemoryResponse toResponse(Memory memory) {
        return new MemoryResponse(
                memory.getId(),
                memory.getType(),
                title(memory),
                content(memory),
                memory.getSource(),
                memory.getEventDate(),
                memory.getImportance(),
                memory.getConfidence(),
                memory.getPrivacyLevel(),
                memory.getStatus(),
                memory.isSensitive(),
                memory.getTags().stream().map(Tag::getName).collect(Collectors.toCollection(TreeSet::new)),
                memory.getCreatedAt(),
                memory.getUpdatedAt()
        );
    }

    public MemoryVersionResponse toVersionResponse(MemoryVersion version, boolean current) {
        String content = version.isEncrypted()
                ? crypto.decrypt(version.getMemoryId(), version.getContentEncrypted())
                : version.getContent();

        return new MemoryVersionResponse(
                version.getId(),
                version.getVersionNumber(),
                content,
                version.getEventDate(),
                version.getConfidence(),
                version.getChangeReason(),
                version.getCreatedAt(),
                version.getSupersededAt(),
                current
        );
    }

    public String title(Memory memory) {
        return memory.isSensitive()
                ? crypto.decrypt(memory.getId(), memory.getTitleEncrypted())
                : memory.getTitle();
    }

    public String content(Memory memory) {
        return memory.isSensitive()
                ? crypto.decrypt(memory.getId(), memory.getContentEncrypted())
                : memory.getContent();
    }
}

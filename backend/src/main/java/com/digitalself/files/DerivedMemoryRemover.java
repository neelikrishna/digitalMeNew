package com.digitalself.files;

import com.digitalself.memory.EmbeddingOwnerType;
import com.digitalself.memory.EmbeddingStore;
import com.digitalself.memory.MemoryRepository;
import com.digitalself.memory.MemoryVersionRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Deletes the memory that file extraction derived from a file, when that file is
 * shredded.
 *
 * <p><b>This is the one hard delete in the system, and it is deliberate.</b>
 * Everywhere else, memories are archived and never destroyed, because they are
 * things you said and losing them would be a betrayal of the whole point. A
 * derived memory is different on both counts: you did not write it, and it
 * contains a copy of the file's text in the clear.
 *
 * <p>Leaving it behind would make {@code DELETE /api/files/{id}} a lie.
 * Crypto-shredding destroys the file's key so the ciphertext on disk is
 * unrecoverable — but if the same words remain readable in a memory row, nothing
 * has actually been deleted. Given the choice between breaking the no-hard-delete
 * rule for machine-derived rows and breaking the permanent-deletion promise for
 * everything, this breaks the first.
 *
 * <p>What survives: any memory <i>you</i> wrote that merely had the file
 * attached. Only the memory extraction itself created is removed, which is why
 * {@code file_metadata.derived_memory_id} records which one that is rather than
 * inferring it from {@code memory_files}.
 */
@Component
public class DerivedMemoryRemover {

    private final MemoryRepository memoryRepository;
    private final MemoryVersionRepository versionRepository;
    private final MemoryFileLinkStore linkStore;
    private final EmbeddingStore embeddingStore;

    public DerivedMemoryRemover(MemoryRepository memoryRepository,
                                MemoryVersionRepository versionRepository,
                                MemoryFileLinkStore linkStore,
                                EmbeddingStore embeddingStore) {
        this.memoryRepository = memoryRepository;
        this.versionRepository = versionRepository;
        this.linkStore = linkStore;
        this.embeddingStore = embeddingStore;
    }

    public void remove(UUID memoryId) {
        if (memoryId == null) {
            return;
        }

        // Embeddings first, and not best-effort. A vector is a lossy but real
        // representation of the text it came from; leaving one behind after a
        // shred would preserve a readable trace of what was meant to be gone.
        // Absence of the table is fine — it means no embedding was ever written.
        if (embeddingStore.isAvailable()) {
            embeddingStore.deleteForOwner(EmbeddingOwnerType.MEMORY, memoryId);
        }

        linkStore.unlinkMemory(memoryId);

        // memories.current_version_id points into memory_versions, which points
        // back at memories with ON DELETE CASCADE. Clearing the forward
        // reference first means the delete never depends on which side of that
        // cycle the database unwinds.
        memoryRepository.findById(memoryId).ifPresent(memory -> {
            memory.setCurrentVersionId(null);
            memoryRepository.saveAndFlush(memory);
            versionRepository.deleteAll(versionRepository.findByMemoryIdOrderByVersionNumberAsc(memoryId));
            memoryRepository.delete(memory);
        });
    }
}

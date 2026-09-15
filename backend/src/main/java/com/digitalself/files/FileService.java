package com.digitalself.files;

import com.digitalself.audit.AuditService;
import com.digitalself.crypto.EncryptionMetadata;
import com.digitalself.crypto.EncryptionMetadataRepository;
import com.digitalself.crypto.EnvelopeEncryptionService;
import com.digitalself.crypto.TextCrypto;
import com.digitalself.crypto.WrappedKey;
import com.digitalself.files.dto.FileResponse;
import org.apache.tika.Tika;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FileService {

    private static final String SUBJECT_TYPE = TextCrypto.FILE;

    /**
     * Types that would be dangerous to hand back to a browser or shell later.
     * The check runs against the sniffed type, so renaming a .exe to .jpg does
     * not get past it.
     */
    private static final Set<String> BLOCKED_MIME_TYPES = Set.of(
            "application/x-msdownload",
            "application/x-dosexec",
            "application/x-executable",
            "application/x-sharedlib",
            "application/x-mach-binary",
            "application/x-msi",
            "application/vnd.microsoft.portable-executable"
    );

    private final StoredFileRepository fileRepository;
    private final FileMetadataRepository metadataRepository;
    private final MediaMetadataRepository mediaRepository;
    private final EncryptionMetadataRepository encryptionMetadataRepository;
    private final EnvelopeEncryptionService encryption;
    private final EncryptedFileStore store;
    private final AuditService auditService;
    private final DerivedMemoryRemover derivedMemoryRemover;
    private final ApplicationEventPublisher events;
    private final Tika tika = new Tika();

    public FileService(StoredFileRepository fileRepository,
                       FileMetadataRepository metadataRepository,
                       MediaMetadataRepository mediaRepository,
                       EncryptionMetadataRepository encryptionMetadataRepository,
                       EnvelopeEncryptionService encryption,
                       EncryptedFileStore store,
                       AuditService auditService,
                       DerivedMemoryRemover derivedMemoryRemover,
                       ApplicationEventPublisher events) {
        this.fileRepository = fileRepository;
        this.metadataRepository = metadataRepository;
        this.mediaRepository = mediaRepository;
        this.encryptionMetadataRepository = encryptionMetadataRepository;
        this.encryption = encryption;
        this.store = store;
        this.auditService = auditService;
        this.derivedMemoryRemover = derivedMemoryRemover;
        this.events = events;
    }

    /**
     * @param sensitive encrypt this file's extracted text and derive a sensitive
     *                  memory from it, keeping both out of search. The file's
     *                  bytes are encrypted either way — this governs what
     *                  extraction does with what it reads. See
     *                  docs/file-ingestion.md Section 4.
     */
    @Transactional
    public StoredFile upload(UUID userId, String originalFilename, byte[] content, boolean sensitive) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("The uploaded file is empty.");
        }

        String detectedType = tika.detect(content, originalFilename);
        if (BLOCKED_MIME_TYPES.contains(detectedType)) {
            throw new IllegalArgumentException("Files of type " + detectedType + " are not accepted.");
        }

        String hash = sha256(content);
        // Same bytes from the same owner: keep one copy rather than a second
        // encrypted blob. Originals are never modified either way.
        var existing = fileRepository.findByUserIdAndContentHash(userId, hash);
        if (existing.isPresent()) {
            return existing.get();
        }

        StoredFile file = fileRepository.save(new StoredFile(
                userId, "pending", hash, detectedType, sanitiseFilename(originalFilename), sensitive));

        SecretKey dataKey = encryption.newDataKey();
        WrappedKey wrapped = encryption.wrap(dataKey);
        EncryptionMetadata metadata = encryptionMetadataRepository.save(
                new EncryptionMetadata(SUBJECT_TYPE, file.getId(), wrapped));

        file.setStoragePath(store.write(file.getId(), dataKey, content));
        file.setEncryptionKeyId(metadata.getKeyId());
        fileRepository.save(file);

        // The extraction row exists from upload onwards, so a file is never in a
        // state where nothing records that its text is still owed.
        metadataRepository.save(new FileMetadata(file.getId()));

        auditService.record(userId, "FILE_UPLOADED", SUBJECT_TYPE, file.getId(), null, null);
        // Extraction runs after this transaction commits: parsing a large PDF
        // must not hold a database transaction open.
        events.publishEvent(new FileUploadedEvent(userId, file.getId()));
        return file;
    }

    @Transactional(readOnly = true)
    public StoredFile get(UUID userId, UUID fileId) {
        return fileRepository.findByIdAndUserId(fileId, userId)
                .orElseThrow(() -> new FileNotFoundException(fileId));
    }

    @Transactional(readOnly = true)
    public List<StoredFile> list(UUID userId) {
        return fileRepository.findByUserIdOrderByUploadedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public byte[] download(UUID userId, UUID fileId) {
        StoredFile file = get(userId, fileId);
        byte[] content = readContent(file);
        auditService.record(userId, "FILE_DOWNLOADED", SUBJECT_TYPE, fileId, null, null);
        return content;
    }

    /**
     * Decrypts a file for internal processing, with the same integrity check as
     * a download but without the audit entry: text extraction reading a file is
     * not the owner downloading it, and recording it as one would make the audit
     * log describe something that never happened.
     */
    byte[] readContent(StoredFile file) {
        SecretKey dataKey = encryption.unwrap(wrappedKeyFor(file));
        byte[] content = store.read(file.getStoragePath(), dataKey);

        if (!sha256(content).equals(file.getContentHash())) {
            throw new IllegalStateException("Stored file failed its integrity check.");
        }
        return content;
    }

    /**
     * Crypto-shredding: destroying the wrapped key makes the ciphertext
     * permanently unrecoverable, which is more reliable than trying to
     * overwrite bytes on an SSD. The blob is removed too.
     */
    @Transactional
    public void shred(UUID userId, UUID fileId) {
        StoredFile file = get(userId, fileId);

        // The memory derived from this file's text holds a readable copy of it.
        // Destroying the key while leaving that behind would make "permanent,
        // irreversible" untrue. See DerivedMemoryRemover for why this is the one
        // place a memory is hard-deleted.
        metadataRepository.findById(fileId)
                .ifPresent(metadata -> derivedMemoryRemover.remove(metadata.getDerivedMemoryId()));

        encryptionMetadataRepository.deleteBySubjectTypeAndSubjectId(SUBJECT_TYPE, fileId);
        store.delete(file.getStoragePath());
        // file_metadata cascades from files, taking any extracted text with it.
        fileRepository.delete(file);
        auditService.record(userId, "FILE_SHREDDED", SUBJECT_TYPE, fileId, null, null);
    }

    @Transactional(readOnly = true)
    public FileResponse getResponse(UUID userId, UUID fileId) {
        StoredFile file = get(userId, fileId);
        return FileResponse.from(file,
                metadataRepository.findById(fileId).orElse(null),
                mediaRepository.findById(fileId).orElse(null));
    }

    /**
     * Files with their extraction and capture metadata. Both are fetched in one
     * query each rather than per file: a listing of a hundred uploads should not
     * cost two hundred round trips to report whether each one was parsed.
     */
    @Transactional(readOnly = true)
    public List<FileResponse> listResponses(UUID userId) {
        List<StoredFile> files = list(userId);
        List<UUID> ids = files.stream().map(StoredFile::getId).toList();

        Map<UUID, FileMetadata> metadata = metadataRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(FileMetadata::getFileId, Function.identity()));
        Map<UUID, MediaMetadata> media = mediaRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(MediaMetadata::getFileId, Function.identity()));

        return files.stream()
                .map(file -> FileResponse.from(file, metadata.get(file.getId()), media.get(file.getId())))
                .toList();
    }

    private WrappedKey wrappedKeyFor(StoredFile file) {
        return encryptionMetadataRepository
                .findBySubjectTypeAndSubjectId(SUBJECT_TYPE, file.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "No encryption key on record for file " + file.getId() + "; it cannot be decrypted."))
                .toWrappedKey();
    }

    /** Strips any directory component so a crafted name cannot influence storage. */
    private static String sanitiseFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unnamed";
        }
        String name = filename.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        String base = lastSlash >= 0 ? name.substring(lastSlash + 1) : name;
        return base.isBlank() ? "unnamed" : base;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

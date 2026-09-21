package com.digitalself;

import com.digitalself.auth.User;
import com.digitalself.auth.UserRepository;
import com.digitalself.auth.UserRole;
import com.digitalself.extraction.*;
import com.digitalself.files.ExtractionStatus;
import com.digitalself.files.FileMetadata;
import com.digitalself.files.MediaMetadata;
import com.digitalself.files.MediaType;
import com.digitalself.files.StoredFile;
import com.digitalself.memory.*;
import com.digitalself.memory.chunk.MemoryChunk;
import com.digitalself.memory.dto.CreateMemoryRequest;
import com.digitalself.memory.dto.CreateLinkRequest;
import com.digitalself.memory.dto.MemoryResponse;
import com.digitalself.memory.dto.MemorySearchQuery;
import com.digitalself.memory.dto.ReviseMemoryRequest;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the real schema against a real PostgreSQL.
 *
 * <p>Starting the Spring context with {@code ddl-auto: validate} means every JPA
 * entity mapping is checked against the Flyway-built schema before a single test
 * body runs — a mismatch fails the context, not one assertion.
 *
 * <p>Postgres runs as a temporary subprocess on a random port and is discarded
 * afterwards; nothing is installed and the developer's own database is untouched.
 * The pgvector-dependent migration is excluded, since the extension is not part
 * of a stock server.
 */
@SpringBootTest
class SchemaIntegrationTest {

    // Static initialiser, not @BeforeAll: the database must be listening before
    // Spring evaluates the datasource properties below.
    private static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // currentSchema must be set here too: the native SQL in MemoryTextSearch
        // uses unqualified table names, which resolve through search_path.
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://localhost:" + POSTGRES.getPort() + "/postgres?currentSchema=digitalself");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("digitalself.jwt.secret", () -> "integration-test-secret-not-for-real-use-0123456789");
        registry.add("digitalself.crypto.master-key", () -> TEST_MASTER_KEY);
        registry.add("digitalself.crypto.storage-path", () -> STORAGE_DIR.toString());
        // Unroutable port: the post-commit indexer should fail fast and be
        // swallowed rather than reaching out to a real model during tests.
        registry.add("digitalself.ollama.base-url", () -> "http://localhost:1");
    }

    private static final String TEST_MASTER_KEY = generateTestMasterKey();
    private static final Path STORAGE_DIR = createTempStorageDir();

    private static String generateTestMasterKey() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static Path createTempStorageDir() {
        try {
            return Files.createTempDirectory("digitalself-test-files");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MemoryService memoryService;
    @Autowired
    private MemoryVersionRepository versionRepository;
    @Autowired
    private MemoryTextSearch textSearch;
    @Autowired
    private MemoryLinkService linkService;
    @Autowired
    private ExtractionService extractionService;
    @Autowired
    private MemoryProposalRepository proposalRepository;
    @Autowired
    private com.digitalself.files.FileService fileService;
    @Autowired
    private com.digitalself.files.FileIngestionService fileIngestionService;
    @Autowired
    private com.digitalself.files.FileMetadataRepository fileMetadataRepository;
    @Autowired
    private com.digitalself.files.MediaMetadataRepository mediaMetadataRepository;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired
    private com.digitalself.memory.chunk.MemoryChunkRepository memoryChunkRepository;

    private UUID userId;

    @BeforeEach
    void createUser() {
        User user = userRepository.save(new User(
                "owner-" + UUID.randomUUID() + "@example.com", "hash", "Owner", UserRole.OWNER));
        userId = user.getId();
    }

    @Test
    void migrationsApplyAndEntitiesMapToTheSchema() {
        // Reaching this point means Flyway ran and Hibernate validated every
        // entity against the resulting schema.
        assertNotNull(userId);
    }

    /**
     * Also guards the lazy-loading regression this test originally caught: with
     * open-in-view disabled, reading tags from a response returned outside the
     * service transaction used to throw LazyInitializationException.
     */
    @Test
    void memorySurvivesARoundTripWithTagsAndMetadata() {
        MemoryResponse created = memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "College", "I met John in college in 2018.",
                MemorySource.USER_INPUT, LocalDate.of(2018, 9, 1), (short) 4, 0.95f,
                PrivacyLevel.PRIVATE, null, Set.of("college", "people")));

        assertEquals(Set.of("college", "people"), created.tags());

        MemoryResponse loaded = memoryService.getResponse(userId, created.id());

        assertEquals("I met John in college in 2018.", loaded.content());
        assertEquals(LocalDate.of(2018, 9, 1), loaded.eventDate());
        assertEquals((short) 4, loaded.importance());
        assertEquals(0.95f, loaded.confidence(), 0.0001);
        assertEquals(MemoryStatus.ACTIVE, loaded.status());
        assertEquals(Set.of("college", "people"), loaded.tags());

        assertNotNull(memoryService.get(userId, created.id()).getCurrentVersionId(),
                "creating a memory must write version 1");
    }

    @Test
    void revisionKeepsTheSupersededVersionInTheDatabase() {
        MemoryResponse created = memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "Event", "Event happened in 2020.", MemorySource.USER_INPUT,
                LocalDate.of(2020, 1, 1), null, null, null, null, Set.of()));

        memoryService.revise(userId, created.id(), new ReviseMemoryRequest(
                "Event happened in 2019.", LocalDate.of(2019, 1, 1), null, ChangeReason.USER_CORRECTION));

        List<MemoryVersion> history = versionRepository.findByMemoryIdOrderByVersionNumberAsc(created.id());

        assertEquals(2, history.size(), "both the original and the correction must persist");
        assertEquals("Event happened in 2020.", history.get(0).getContent());
        assertNotNull(history.get(0).getSupersededAt(), "the original must be marked superseded");
        assertEquals("Event happened in 2019.", history.get(1).getContent());
        assertNull(history.get(1).getSupersededAt());

        Memory current = memoryService.get(userId, created.id());
        assertEquals("Event happened in 2019.", current.getContent());
        assertEquals(history.get(1).getId(), current.getCurrentVersionId());
    }

    @Test
    void fullTextSearchFindsMemoriesByStemmedWords() {
        memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "Sailing trip", "I went sailing around the Greek islands.",
                MemorySource.USER_INPUT, null, null, null, null, null, Set.of()));

        // "sailed" stems to the same root as "sailing" — a LIKE query would miss this.
        List<UUID> hits = textSearch.search(userId, "where have I sailed?", MemoryStatus.ACTIVE, 10);

        assertEquals(1, hits.size(), "tsvector search should match on stemmed words");
    }

    @Test
    void fullTextSearchDoesNotLeakAcrossUsers() {
        memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "Private", "A deeply personal sailing memory.",
                MemorySource.USER_INPUT, null, null, null, null, null, Set.of()));

        User other = userRepository.save(new User(
                "other-" + UUID.randomUUID() + "@example.com", "hash", "Other", UserRole.OWNER));

        assertTrue(textSearch.search(other.getId(), "sailing", MemoryStatus.ACTIVE, 10).isEmpty());
    }

    @Test
    void metadataSearchFiltersByTypeAndDateRange() {
        memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "Old", "Something in 2015.", MemorySource.USER_INPUT,
                LocalDate.of(2015, 6, 1), null, null, null, null, Set.of()));
        memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.SEMANTIC, "Belief", "I prefer Java.", MemorySource.USER_INPUT,
                LocalDate.of(2022, 6, 1), null, null, null, null, Set.of()));

        var episodicOnly = memoryService.search(userId,
                new MemorySearchQuery(null, MemoryType.EPISODIC, null, MemoryStatus.ACTIVE, null, null, null),
                PageRequest.of(0, 10));
        assertEquals(1, episodicOnly.getTotalElements());

        var recent = memoryService.search(userId,
                new MemorySearchQuery(null, null, null, MemoryStatus.ACTIVE, LocalDate.of(2020, 1, 1), null, null),
                PageRequest.of(0, 10));
        assertEquals(1, recent.getTotalElements());
    }

    @Test
    void archivedMemoriesDropOutOfActiveSearchButStillExist() {
        MemoryResponse created = memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "Old thought", "Something I archived.", MemorySource.USER_INPUT,
                null, null, null, null, null, Set.of()));

        memoryService.archive(userId, created.id());

        var active = memoryService.search(userId,
                new MemorySearchQuery(null, null, null, MemoryStatus.ACTIVE, null, null, null),
                PageRequest.of(0, 10));
        assertEquals(0, active.getTotalElements());

        assertEquals(MemoryStatus.ARCHIVED, memoryService.getResponse(userId, created.id()).status(),
                "archiving must not delete the row");
    }

    @Test
    void linksPersistInBothDirections() {
        MemoryResponse first = memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "First", "The first thing.", MemorySource.USER_INPUT,
                null, null, null, null, null, Set.of()));
        MemoryResponse second = memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "Second", "The thing that followed.", MemorySource.USER_INPUT,
                null, null, null, null, null, Set.of()));

        linkService.link(userId, first.id(), new CreateLinkRequest(second.id(), "followed_by"));

        assertEquals("OUTGOING", linkService.links(userId, first.id()).get(0).direction());
        assertEquals("INCOMING", linkService.links(userId, second.id()).get(0).direction());
    }

    @Test
    void sensitiveMemoryTextIsNotReadableInTheDatabase() {
        MemoryResponse created = memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "Therapy SECRET-TITLE", "A private reflection, SECRET-BODY.",
                MemorySource.USER_INPUT, null, null, null, null, true, Set.of()));

        assertTrue(created.sensitive());
        // The API still shows it to its owner.
        assertEquals("A private reflection, SECRET-BODY.", created.content());
        assertEquals("Therapy SECRET-TITLE", created.title());

        // But the plaintext columns hold nothing, and a raw scan of the table
        // finds neither the body nor the title.
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT content, title, sensitive FROM memories WHERE id = ?", created.id());
        assertNull(row.get("content"));
        assertNull(row.get("title"));
        assertEquals(true, row.get("sensitive"));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM memories WHERE content LIKE '%SECRET-BODY%' OR title LIKE '%SECRET-TITLE%'",
                Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM memory_versions WHERE content LIKE '%SECRET-BODY%'", Integer.class),
                "version history must not leak the plaintext either");
    }

    @Test
    void emotionalMemoriesAreTreatedAsSensitiveWithoutBeingAsked() {
        MemoryResponse created = memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EMOTIONAL, "Feelings", "Something I feel strongly about.",
                MemorySource.USER_INPUT, null, null, null, null, null, Set.of()));

        assertTrue(created.sensitive(), "reflective memories should default to encrypted");
    }

    @Test
    void sensitiveMemoriesAreExcludedFromTextSearch() {
        memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "Ordinary", "A memory about kayaking.",
                MemorySource.USER_INPUT, null, null, null, null, false, Set.of()));
        memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "Hidden", "Another memory about kayaking.",
                MemorySource.USER_INPUT, null, null, null, null, true, Set.of()));

        assertEquals(1, textSearch.search(userId, "kayaking", MemoryStatus.ACTIVE, 10).size(),
                "only the non-sensitive memory should be reachable by text search");

        // Still findable without reading its text.
        assertEquals(2, memoryService.search(userId,
                new MemorySearchQuery(null, MemoryType.EPISODIC, null, MemoryStatus.ACTIVE, null, null, null),
                PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void revisingASensitiveMemoryKeepsEveryVersionEncrypted() {
        MemoryResponse created = memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "Private", "First version, SHOULD-NOT-APPEAR.",
                MemorySource.USER_INPUT, null, null, null, null, true, Set.of()));

        memoryService.revise(userId, created.id(), new ReviseMemoryRequest(
                "Second version, ALSO-HIDDEN.", null, null, ChangeReason.USER_CORRECTION));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM memory_versions WHERE content IS NOT NULL AND memory_id = ?",
                Integer.class, created.id()),
                "no version of a sensitive memory may be stored as plaintext");

        // History still reads correctly through the API.
        var history = memoryService.history(userId, created.id());
        assertEquals(2, history.size());
        assertEquals("First version, SHOULD-NOT-APPEAR.", history.get(0).content());
        assertEquals("Second version, ALSO-HIDDEN.", history.get(1).content());
    }

    @Test
    void promotingAMemoryToSensitiveEncryptsWhatWasAlreadyStored() {
        MemoryResponse created = memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "Was public", "Plainly stored, LATER-HIDDEN.",
                MemorySource.USER_INPUT, null, null, null, null, false, Set.of()));

        assertFalse(created.sensitive());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM memories WHERE content LIKE '%LATER-HIDDEN%'", Integer.class));

        MemoryResponse promoted = memoryService.updateMetadata(userId, created.id(),
                new com.digitalself.memory.dto.UpdateMemoryMetadataRequest(
                        "Was public", MemoryType.EPISODIC, null, PrivacyLevel.PRIVATE, true, Set.of()));

        assertTrue(promoted.sensitive());
        assertEquals("Plainly stored, LATER-HIDDEN.", promoted.content(), "content must survive the transition");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM memories WHERE content LIKE '%LATER-HIDDEN%'", Integer.class),
                "the former plaintext must be gone from the table");
    }

    @Test
    void uploadedFilesAreUnreadableOnDiskButRoundTripThroughTheApi() throws IOException {
        byte[] content = "Dear diary, this is a deeply private note. MARKER-TEXT".getBytes();

        StoredFile stored = fileService.upload(userId, "diary.txt", content, false);

        // What actually landed on disk must not contain the plaintext.
        Path onDisk = STORAGE_DIR.resolve(stored.getStoragePath());
        assertTrue(Files.exists(onDisk), "an encrypted blob should have been written");
        String rawBytes = new String(Files.readAllBytes(onDisk), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(rawBytes.contains("MARKER-TEXT"), "plaintext must never be written to disk");
        assertFalse(rawBytes.contains("Dear diary"), "plaintext must never be written to disk");

        assertArrayEquals(content, fileService.download(userId, stored.getId()));
        assertNotNull(stored.getEncryptionKeyId(), "the file must be linked to a wrapped key");
    }

    @Test
    void fileTypeIsDetectedFromContentNotFromTheFilename() {
        // PNG magic bytes, deliberately mislabelled as a text file.
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0};

        StoredFile stored = fileService.upload(userId, "totally-a-text-file.txt", png, false);

        assertEquals("image/png", stored.getMimeType());
    }

    @Test
    void reuploadingIdenticalContentDoesNotStoreASecondCopy() {
        byte[] content = "The same bytes twice.".getBytes();

        StoredFile first = fileService.upload(userId, "a.txt", content, false);
        StoredFile second = fileService.upload(userId, "renamed.txt", content, false);

        assertEquals(first.getId(), second.getId());
    }

    @Test
    void shreddingAFileMakesItUnrecoverable() {
        StoredFile stored = fileService.upload(userId, "secret.txt", "Burn after reading.".getBytes(), false);
        UUID fileId = stored.getId();
        Path onDisk = STORAGE_DIR.resolve(stored.getStoragePath());

        fileService.shred(userId, fileId);

        assertFalse(Files.exists(onDisk), "the ciphertext blob should be gone");
        assertThrows(com.digitalself.files.FileNotFoundException.class,
                () -> fileService.download(userId, fileId));
    }

    @Test
    void filesAreNotReadableByAnotherUser() {
        StoredFile stored = fileService.upload(userId, "mine.txt", "Private.".getBytes(), false);

        User other = userRepository.save(new User(
                "other-" + UUID.randomUUID() + "@example.com", "hash", "Other", UserRole.OWNER));

        assertThrows(com.digitalself.files.FileNotFoundException.class,
                () -> fileService.download(other.getId(), stored.getId()));
    }

    @Test
    void extractionStoresRawInputAndQueuesProposalsForReview() {
        // The model is unreachable in this test, so extraction falls back to
        // preserving the text — exactly the behaviour that must not lose data.
        var response = extractionService.extract(userId, "I visited Kyoto in the spring of 2017.");

        assertNotNull(response.rawInputId(), "the owner's words must be stored even when the model fails");
        assertEquals(1, response.pendingReview().size());
        assertTrue(response.autoAccepted().isEmpty(), "a fallback candidate is low confidence and must not auto-accept");

        MemoryProposal proposal = proposalRepository
                .findByUserIdAndStatusOrderByCreatedAtAsc(userId, ProposalStatus.PENDING).get(0);
        assertEquals("I visited Kyoto in the spring of 2017.",
                extractionService.toResponse(proposal).content());

        // The owner's words are readable through the API but not in the table.
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM raw_inputs WHERE encode(content_encrypted, 'escape') LIKE '%Kyoto%'",
                Integer.class), "raw input must not be readable in the database");

        MemoryProposal accepted = extractionService.accept(userId, proposal.getId());
        assertEquals(ProposalStatus.ACCEPTED, accepted.getStatus());

        assertEquals(MemorySource.AI_INFERENCE,
                memoryService.getResponse(userId, accepted.getCreatedMemoryId()).source(),
                "anything the model produced must stay marked as inference");
    }

    // ============================================================
    // File text extraction (Phase 4)
    // ============================================================

    /**
     * The claim this whole feature exists to make: a document you upload becomes
     * something the system can find. Extraction runs on the post-commit listener,
     * so by the time upload() returns and its transaction has committed, the
     * derived memory exists.
     */
    @Test
    void anUploadedDocumentBecomesSearchableText() {
        byte[] document = ("Roof repair quote from Hendricks Roofing. "
                + "The scaffolding alone is quoted at 1,400 pounds.").getBytes();

        StoredFile stored = fileService.upload(userId, "roof-quote.txt", document, false);

        FileMetadata metadata = fileMetadataRepository.findById(stored.getId()).orElseThrow();
        assertEquals(ExtractionStatus.EXTRACTED, metadata.getExtractionStatus());
        assertNotNull(metadata.getDerivedMemoryId(), "extraction must derive a memory to be retrievable through");

        MemoryResponse derived = memoryService.getResponse(userId, metadata.getDerivedMemoryId());
        assertEquals(MemorySource.FILE_EXTRACTION, derived.source());
        assertTrue(derived.content().contains("Hendricks Roofing"));

        // The point of all of it: full-text search now reaches inside the file.
        assertTrue(textSearch.search(userId, "scaffolding quote", MemoryStatus.ACTIVE, 10)
                        .contains(metadata.getDerivedMemoryId()),
                "an uploaded document must be findable by its contents, not just its name");
    }

    @Test
    void theDerivedMemoryIsAttachedToItsFile() {
        StoredFile stored = fileService.upload(
                userId, "notes.txt", "A note long enough to be worth indexing.".getBytes(), false);

        UUID memoryId = fileMetadataRepository.findById(stored.getId()).orElseThrow().getDerivedMemoryId();

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM memory_files WHERE memory_id = ? AND file_id = ?",
                Integer.class, memoryId, stored.getId()));
    }

    /**
     * Crypto-shredding is advertised as permanent and irreversible. It would be
     * neither if the text extracted from the file stayed readable in a memory
     * row — see DerivedMemoryRemover for why this is the one hard delete.
     */
    @Test
    void shreddingAFileAlsoDestroysTheMemoryDerivedFromIt() {
        StoredFile stored = fileService.upload(
                userId, "confession.txt", "The unmistakable phrase SHRED-MARKER lives here.".getBytes(), false);
        UUID memoryId = fileMetadataRepository.findById(stored.getId()).orElseThrow().getDerivedMemoryId();
        assertNotNull(memoryId, "precondition: the file must have produced a memory");

        fileService.shred(userId, stored.getId());

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM memories WHERE content LIKE '%SHRED-MARKER%'", Integer.class),
                "shredding a file must not leave its text readable in a memory");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM memory_versions WHERE content LIKE '%SHRED-MARKER%'", Integer.class),
                "version history holds the same text and must go too");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM file_metadata WHERE file_id = ?", Integer.class, stored.getId()),
                "the extraction record cascades with the file");
    }

    /**
     * A memory the owner wrote is not extraction's to delete, even when it has
     * the shredded file attached. Only the derived one goes.
     */
    @Test
    void shreddingAFileLeavesTheOwnersOwnMemoriesAlone() {
        StoredFile stored = fileService.upload(
                userId, "receipt.txt", "A receipt with enough words to index.".getBytes(), false);
        MemoryResponse mine = memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "My note", "I remember buying this myself.",
                MemorySource.USER_INPUT, null, null, null, null, null, Set.of()));
        jdbcTemplate.update("INSERT INTO memory_files (memory_id, file_id) VALUES (?, ?)",
                mine.id(), stored.getId());

        fileService.shred(userId, stored.getId());

        assertEquals("I remember buying this myself.",
                memoryService.getResponse(userId, mine.id()).content(),
                "a memory the owner wrote must survive the file being shredded");
    }

    @Test
    void aSensitiveFileLeavesNoExtractedPlaintextInTheDatabase() {
        byte[] document = "A private note containing CONFIDENTIAL-MARKER in the body.".getBytes();

        StoredFile stored = fileService.upload(userId, "private.txt", document, true);

        FileMetadata metadata = fileMetadataRepository.findById(stored.getId()).orElseThrow();
        assertEquals(ExtractionStatus.EXTRACTED, metadata.getExtractionStatus());
        assertNull(metadata.getExtractedText(), "sensitive text must not sit in a plaintext column");
        assertNotNull(metadata.getExtractedTextEncrypted());

        // The real check: scan the raw tables the way a database dump would.
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM file_metadata WHERE extracted_text LIKE '%CONFIDENTIAL-MARKER%'",
                Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM memories WHERE content LIKE '%CONFIDENTIAL-MARKER%'", Integer.class),
                "the derived memory must be sensitive, so its content is encrypted too");

        assertTrue(memoryService.getResponse(userId, metadata.getDerivedMemoryId()).sensitive());
    }

    /**
     * Photos are not a failure, they are a later phase. Marking them
     * UNSUPPORTED keeps them out of the retry queue forever.
     */
    @Test
    void imagesAreRecordedAsUnsupportedRatherThanFailed() {
        StoredFile stored = fileService.upload(userId, "photo.png", onePixelPng(), false);

        FileMetadata metadata = fileMetadataRepository.findById(stored.getId()).orElseThrow();
        assertEquals(ExtractionStatus.UNSUPPORTED, metadata.getExtractionStatus());
        assertNull(metadata.getDerivedMemoryId());

        // And the backfill must not pick them up on every run.
        assertEquals(0, fileIngestionService.reindex(userId));
    }

    /**
     * A photo is processed even though it has no text: the media row records
     * that its metadata was read. Also the first thing to run the JSONB mapping
     * on {@code media.exif_json} against a real Postgres.
     */
    @Test
    void aPhotoGetsAMediaRowRecordingThatItWasRead() {
        StoredFile stored = fileService.upload(userId, "photo.png", onePixelPng(), false);

        MediaMetadata media = mediaMetadataRepository.findById(stored.getId()).orElseThrow();
        assertEquals(MediaType.PHOTO, media.getMediaType());
        assertNotNull(media.getExtractedAt(),
                "\"we looked and found nothing\" must be distinguishable from \"we never looked\"");
    }

    /**
     * The guarantee from docs/media-ingestion.md Section 3. A photo's EXIF is
     * metadata, not something the owner asserted — turning it into a memory
     * would put a sentence nobody said into the pool the assistant answers from.
     */
    @Test
    void aPhotoNeverBecomesAMemory() {
        long before = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM memories WHERE user_id = ?", Long.class, userId);

        fileService.upload(userId, "photo.png", onePixelPng(), false);

        assertEquals(before, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM memories WHERE user_id = ?", Long.class, userId),
                "uploading a photo must not create a memory");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM memories WHERE user_id = ? AND source = 'FILE_EXTRACTION'",
                Integer.class, userId));
    }

    /**
     * The gallery must not hide EXIF-stripped images. Most photos shared through
     * messaging apps have no capture date, so a listing that only returned dated
     * ones would silently omit much of a real library.
     */
    @Test
    void photoListingIncludesImagesThatCarryNoCaptureDate() {
        fileService.upload(userId, "stripped.png", onePixelPng(), false);

        com.digitalself.files.FileService.PhotoListing listing = fileService.photos(userId, null, null);

        assertEquals(1, listing.photos().size(), "an undated photo must still be listed");
        assertNull(listing.photos().get(0).photo().takenAt());
        assertEquals(0, listing.undatedExcluded(), "nothing is excluded when no window is given");
    }

    /**
     * Asking for a date window necessarily excludes undated photos. The count is
     * reported so a short list cannot be mistaken for the whole library.
     */
    @Test
    void aDateWindowReportsHowManyUndatedPhotosItLeftOut() {
        fileService.upload(userId, "stripped.png", onePixelPng(), false);

        com.digitalself.files.FileService.PhotoListing listing = fileService.photos(
                userId, Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2021-01-01T00:00:00Z"));

        assertTrue(listing.photos().isEmpty());
        assertEquals(1, listing.undatedExcluded(),
                "the caller must be told photos were omitted for lack of a date");
    }

    @Test
    void photoListingDoesNotLeakAcrossUsers() {
        fileService.upload(userId, "mine.png", onePixelPng(), false);

        User other = userRepository.save(new User(
                "other-" + UUID.randomUUID() + "@example.com", "hash", "Other", UserRole.OWNER));

        assertTrue(fileService.photos(other.getId(), null, null).photos().isEmpty());
    }

    @Test
    void photoListingExcludesNonImageUploads() {
        fileService.upload(userId, "notes.txt", "Just some text.".getBytes(), false);

        assertTrue(fileService.photos(userId, null, null).photos().isEmpty(),
                "a document is not a photo and must not appear in the gallery");
    }

    // ---------- chunking ----------

    @Test
    void aMemoryIsSplitIntoRetrievablePassages() {
        String longText = ("This paragraph describes a day in some detail, at enough length "
                + "that the chunker has something to divide.\n\n").repeat(12);

        MemoryResponse created = memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "A long entry", longText, MemorySource.USER_INPUT,
                null, null, null, null, false, Set.of()));

        List<MemoryChunk> chunks = memoryChunkRepository
                .findByMemoryIdOrderByChunkIndexAsc(created.id());

        assertTrue(chunks.size() > 1, "a long memory should produce several passages");
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).getChunkIndex());
            assertFalse(chunks.get(i).isEncrypted());
            assertNotNull(chunks.get(i).getContent());
        }
    }

    /**
     * The rule that keeps chunking from becoming a hole through the encryption:
     * a passage of a sensitive memory must be no more readable than its parent.
     */
    @Test
    void chunksOfASensitiveMemoryAreEncryptedAndUnsearchable() {
        MemoryResponse created = memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "Private", "A reflection containing CHUNK-SECRET-MARKER within it.",
                MemorySource.USER_INPUT, null, null, null, null, true, Set.of()));

        List<MemoryChunk> chunks = memoryChunkRepository.findByMemoryIdOrderByChunkIndexAsc(created.id());
        assertFalse(chunks.isEmpty(), "a sensitive memory is still chunked so the owner can read it back");

        for (MemoryChunk chunk : chunks) {
            assertTrue(chunk.isEncrypted(), "every passage of a sensitive memory must be ciphertext");
            assertNull(chunk.getContent());
        }

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM memory_chunks WHERE content LIKE '%CHUNK-SECRET-MARKER%'",
                Integer.class), "no passage may leak the plaintext of a sensitive memory");

        assertTrue(textSearch.searchChunks(userId, "CHUNK-SECRET-MARKER", MemoryStatus.ACTIVE, 10).isEmpty(),
                "passages of sensitive memories must be unreachable by full-text search");
    }

    @Test
    void revisingAMemoryReplacesItsPassagesRatherThanAddingToThem() {
        MemoryResponse created = memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "Note", "The original wording, OLD-TEXT-MARKER.",
                MemorySource.USER_INPUT, null, null, null, null, false, Set.of()));

        memoryService.revise(userId, created.id(), new ReviseMemoryRequest(
                "The corrected wording, NEW-TEXT-MARKER.", null, null, ChangeReason.USER_CORRECTION));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM memory_chunks WHERE memory_id = ? AND content LIKE '%OLD-TEXT-MARKER%'",
                Integer.class, created.id()),
                "superseded text must not linger in the passages retrieval reads");
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM memory_chunks WHERE memory_id = ? AND content LIKE '%NEW-TEXT-MARKER%'",
                Integer.class, created.id()) > 0);
    }

    @Test
    void chunkSearchDoesNotLeakAcrossUsers() {
        memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.EPISODIC, "Mine", "A memory about kayaking rivers.",
                MemorySource.USER_INPUT, null, null, null, null, false, Set.of()));

        User other = userRepository.save(new User(
                "other-" + UUID.randomUUID() + "@example.com", "hash", "Other", UserRole.OWNER));

        assertFalse(textSearch.searchChunks(userId, "kayaking", MemoryStatus.ACTIVE, 10).isEmpty());
        assertTrue(textSearch.searchChunks(other.getId(), "kayaking", MemoryStatus.ACTIVE, 10).isEmpty());
    }

    /** Valid 1x1 PNG. Carries no EXIF, which is the common case for a stripped image. */
    private static byte[] onePixelPng() {
        return Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    }
}

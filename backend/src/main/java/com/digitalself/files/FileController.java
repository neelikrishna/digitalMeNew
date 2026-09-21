package com.digitalself.files;

import com.digitalself.files.dto.FileResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.format.annotation.DateTimeFormat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;
    private final FileIngestionService ingestionService;

    public FileController(FileService fileService, FileIngestionService ingestionService) {
        this.fileService = fileService;
        this.ingestionService = ingestionService;
    }

    /**
     * @param sensitive encrypt this file's extracted text, keeping it out of
     *                  search. Defaults to false: text that cannot be searched
     *                  would defeat the point of extracting it.
     */
    @PostMapping
    public ResponseEntity<FileResponse> upload(@AuthenticationPrincipal UUID userId,
                                                @RequestParam("file") MultipartFile file,
                                                @RequestParam(value = "sensitive", defaultValue = "false")
                                                boolean sensitive) {
        try {
            StoredFile stored = fileService.upload(userId, file.getOriginalFilename(), file.getBytes(), sensitive);
            // Re-read rather than mapping the entity we just saved: extraction
            // runs on the post-commit listener, so by now it has been attempted
            // and the response would otherwise always claim PENDING.
            return ResponseEntity.ok(fileService.getResponse(userId, stored.getId()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file.", e);
        }
    }

    /**
     * Retries extraction for files that were never parsed or whose parse failed.
     * Mirrors {@code POST /api/memories/reindex}. Files with no text layer are
     * left alone — that is a settled answer, not pending work.
     */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Integer>> reindex(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(Map.of("extracted", ingestionService.reindex(userId)));
    }

    @GetMapping
    public ResponseEntity<List<FileResponse>> list(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(fileService.listResponses(userId));
    }

    /**
     * Photos, newest first — the "what was I doing that summer" view EXIF exists
     * to serve. Both bounds are optional and ISO-8601 instants.
     *
     * <p>With no bounds, photos carrying no capture date are included last. With
     * bounds they cannot match, so the response reports how many were left out
     * rather than letting a short list look like the whole library.
     */
    @GetMapping("/photos")
    public ResponseEntity<FileService.PhotoListing> photos(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(fileService.photos(userId, from, to));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FileResponse> get(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok(fileService.getResponse(userId, id));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        StoredFile file = fileService.get(userId, id);
        byte[] content = fileService.download(userId, id);

        // Attachment rather than inline: the browser must not render stored
        // content in the app's origin.
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getOriginalFilename().replace("\"", "") + "\"")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .header("X-Content-Type-Options", "nosniff")
                .body(new ByteArrayResource(content));
    }

    /** Permanent, irreversible deletion by destroying the file's encryption key. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> shred(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        fileService.shred(userId, id);
        return ResponseEntity.noContent().build();
    }
}

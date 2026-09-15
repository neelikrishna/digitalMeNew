package com.digitalself.memory;

import com.digitalself.memory.dto.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private final MemoryService memoryService;
    private final MemoryLinkService linkService;

    public MemoryController(MemoryService memoryService, MemoryLinkService linkService) {
        this.memoryService = memoryService;
        this.linkService = linkService;
    }

    @PostMapping
    public ResponseEntity<MemoryResponse> create(@AuthenticationPrincipal UUID userId,
                                                  @Valid @RequestBody CreateMemoryRequest request) {
        return ResponseEntity.ok(memoryService.create(userId, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MemoryResponse> get(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok(memoryService.getResponse(userId, id));
    }

    @GetMapping
    public ResponseEntity<Page<MemoryResponse>> search(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) MemoryType type,
            @RequestParam(required = false) MemorySource source,
            @RequestParam(required = false, defaultValue = "ACTIVE") MemoryStatus status,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        MemorySearchQuery query = new MemorySearchQuery(keyword, type, source, status, from, to, tag);
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 100),
                Sort.by(Sort.Direction.DESC, "eventDate", "createdAt"));

        return ResponseEntity.ok(memoryService.search(userId, query, pageRequest));
    }

    @PutMapping("/{id}/revision")
    public ResponseEntity<MemoryResponse> revise(@AuthenticationPrincipal UUID userId,
                                                  @PathVariable UUID id,
                                                  @Valid @RequestBody ReviseMemoryRequest request) {
        return ResponseEntity.ok(memoryService.revise(userId, id, request));
    }

    @PatchMapping("/{id}/metadata")
    public ResponseEntity<MemoryResponse> updateMetadata(@AuthenticationPrincipal UUID userId,
                                                          @PathVariable UUID id,
                                                          @Valid @RequestBody UpdateMemoryMetadataRequest request) {
        return ResponseEntity.ok(memoryService.updateMetadata(userId, id, request));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<MemoryVersionResponse>> history(@AuthenticationPrincipal UUID userId,
                                                                @PathVariable UUID id) {
        return ResponseEntity.ok(memoryService.history(userId, id));
    }

    @PostMapping("/{id}/links")
    public ResponseEntity<LinkedMemoryResponse> link(@AuthenticationPrincipal UUID userId,
                                                      @PathVariable UUID id,
                                                      @Valid @RequestBody CreateLinkRequest request) {
        return ResponseEntity.ok(linkService.link(userId, id, request));
    }

    @GetMapping("/{id}/links")
    public ResponseEntity<List<LinkedMemoryResponse>> links(@AuthenticationPrincipal UUID userId,
                                                             @PathVariable UUID id) {
        return ResponseEntity.ok(linkService.links(userId, id));
    }

    @DeleteMapping("/{id}/links/{linkId}")
    public ResponseEntity<Void> unlink(@AuthenticationPrincipal UUID userId,
                                        @PathVariable UUID id,
                                        @PathVariable UUID linkId) {
        linkService.unlink(userId, id, linkId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<MemoryResponse> archive(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok(memoryService.archive(userId, id));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<MemoryResponse> restore(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok(memoryService.restore(userId, id));
    }
}

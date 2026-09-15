package com.digitalself.extraction;

import com.digitalself.extraction.dto.ExtractRequest;
import com.digitalself.extraction.dto.ExtractResponse;
import com.digitalself.extraction.dto.ProposalResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ExtractionController {

    private final ExtractionService extractionService;

    public ExtractionController(ExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    @PostMapping("/extract")
    public ResponseEntity<ExtractResponse> extract(@AuthenticationPrincipal UUID userId,
                                                    @Valid @RequestBody ExtractRequest request) {
        return ResponseEntity.ok(extractionService.extract(userId, request.text()));
    }

    @GetMapping("/proposals")
    public ResponseEntity<List<ProposalResponse>> pending(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(extractionService.pendingProposalResponses(userId));
    }

    @PostMapping("/proposals/{id}/accept")
    public ResponseEntity<ProposalResponse> accept(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok(extractionService.toResponse(extractionService.accept(userId, id)));
    }

    @PostMapping("/proposals/{id}/reject")
    public ResponseEntity<ProposalResponse> reject(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok(extractionService.toResponse(extractionService.reject(userId, id)));
    }
}

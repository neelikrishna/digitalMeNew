package com.digitalself.rag;

import com.digitalself.memory.MemoryIndexer;
import com.digitalself.rag.dto.ChatRequest;
import com.digitalself.rag.dto.ChatResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final RagService ragService;
    private final MemoryIndexer memoryIndexer;

    public ChatController(RagService ragService, MemoryIndexer memoryIndexer) {
        this.ragService = ragService;
        this.memoryIndexer = memoryIndexer;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@AuthenticationPrincipal UUID userId,
                                              @Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(ragService.ask(userId, request));
    }

    /**
     * Indexes memories that have no embedding — needed after the embedding
     * model was unreachable when they were written.
     */
    @PostMapping("/memories/reindex")
    public ResponseEntity<Map<String, Integer>> reindex(@AuthenticationPrincipal UUID userId,
                                                         @RequestParam(defaultValue = "200") int limit) {
        int indexed = memoryIndexer.backfill(userId, Math.min(limit, 1000));
        return ResponseEntity.ok(Map.of("indexed", indexed));
    }
}

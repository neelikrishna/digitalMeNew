package com.digitalself.health;

import com.digitalself.ai.OllamaProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final RestClient ollamaClient;

    public HealthController(JdbcTemplate jdbcTemplate, OllamaProperties ollamaProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.ollamaClient = RestClient.builder().baseUrl(ollamaProperties.getBaseUrl()).build();
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("database", checkDatabase());
        status.put("pgvector", checkPgvector());
        status.put("ollama", checkOllama());
        return ResponseEntity.ok(status);
    }

    private String checkDatabase() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return "up";
        } catch (Exception e) {
            return "down";
        }
    }

    /**
     * Semantic search silently degrades to full-text without pgvector, so the
     * state is reported explicitly rather than being left to guesswork.
     */
    private String checkPgvector() {
        try {
            Boolean installed = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')", Boolean.class);
            if (Boolean.TRUE.equals(installed)) {
                return "installed";
            }
            Boolean available = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector')", Boolean.class);
            return Boolean.TRUE.equals(available)
                    ? "available but not enabled - start with the pgvector profile"
                    : "not installed - semantic search disabled, full-text search only";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String checkOllama() {
        try {
            ollamaClient.get().uri("/api/tags").retrieve().toBodilessEntity();
            return "up";
        } catch (Exception e) {
            return "down";
        }
    }
}

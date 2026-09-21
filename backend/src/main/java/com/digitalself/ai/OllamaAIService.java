package com.digitalself.ai;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class OllamaAIService implements AIService {

    private final RestClient restClient;
    private final OllamaProperties properties;

    public OllamaAIService(OllamaProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, List<Message> history) {
        return completeWith(properties.getChatModel(), systemPrompt, userPrompt, history);
    }

    @Override
    public String completeWith(String model, String systemPrompt, String userPrompt, List<Message> history) {
        String resolvedModel = model == null || model.isBlank() ? properties.getChatModel() : model;

        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        if (history != null) {
            history.forEach(m -> messages.add(Map.of("role", m.role(), "content", m.content())));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> request = Map.of(
                "model", resolvedModel,
                "messages", messages,
                "stream", false
        );

        ChatResponse response = restClient.post()
                .uri("/api/chat")
                .body(request)
                .retrieve()
                .body(ChatResponse.class);

        if (response == null || response.message() == null) {
            throw new OllamaUnavailableException("Ollama returned an empty response for model " + resolvedModel);
        }
        return response.message().content();
    }

    private record ChatResponse(ChatMessage message) {
    }

    private record ChatMessage(String role, String content) {
    }
}

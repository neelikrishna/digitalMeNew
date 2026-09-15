package com.digitalself.ai;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class OllamaEmbeddingService implements EmbeddingService {

    private final RestClient restClient;
    private final OllamaProperties properties;

    public OllamaEmbeddingService(OllamaProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    }

    @Override
    public float[] embed(String text) {
        Map<String, Object> request = Map.of(
                "model", properties.getEmbeddingModel(),
                "prompt", text
        );

        EmbeddingResponse response = restClient.post()
                .uri("/api/embeddings")
                .body(request)
                .retrieve()
                .body(EmbeddingResponse.class);

        if (response == null || response.embedding() == null) {
            throw new OllamaUnavailableException("Ollama returned no embedding for model " + properties.getEmbeddingModel());
        }

        float[] result = new float[response.embedding().size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = response.embedding().get(i).floatValue();
        }
        return result;
    }

    @Override
    public String modelName() {
        return properties.getEmbeddingModel();
    }

    private record EmbeddingResponse(java.util.List<Double> embedding) {
    }
}

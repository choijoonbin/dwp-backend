package com.dwp.services.synapsex.service.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Aura Platform에 쿼리 임베딩 요청
 * POST /aura/rag/embed → 1536차원 float[]
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagEmbeddingService {

    @Value("${aura.platform.base-url:http://localhost:9000}")
    private String auraPlatformBaseUrl;

    private final RestTemplate restTemplate;

    /**
     * Aura에 쿼리 임베딩 요청
     * @param queryText 검색 쿼리
     * @return 1536차원 임베딩 벡터 (실패 시 null)
     */
    public float[] getQueryEmbedding(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return null;
        }

        String url = auraPlatformBaseUrl + "/aura/rag/embed";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> requestBody = Map.of("text", queryText);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<EmbedResponse> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, EmbedResponse.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Double> embeddingList = response.getBody().embedding();
                if (embeddingList != null && !embeddingList.isEmpty()) {
                    float[] result = new float[embeddingList.size()];
                    for (int i = 0; i < embeddingList.size(); i++) {
                        result[i] = embeddingList.get(i).floatValue();
                    }
                    log.debug("Query embedding obtained: dimensions={}", result.length);
                    return result;
                }
            }
            log.warn("Aura embed API returned empty embedding for query: {}", queryText.substring(0, Math.min(50, queryText.length())));
            return null;
        } catch (Exception e) {
            log.error("Aura embed API error: {}", e.getMessage());
            return null;
        }
    }

    private record EmbedResponse(List<Double> embedding, String model, Integer dimensions) {}
}

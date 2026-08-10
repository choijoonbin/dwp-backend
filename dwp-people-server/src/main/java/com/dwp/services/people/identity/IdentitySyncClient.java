package com.dwp.services.people.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class IdentitySyncClient {

    private static final String TOKEN_HEADER = "X-DWP-Identity-Sync-Token";

    private final RestClient auth;
    private final ObjectMapper objectMapper;
    private final String token;

    public IdentitySyncClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${dwp.identity-sync.auth-url:http://localhost:8001}") String authUrl,
            @Value("${dwp.identity-sync.token:}") String token) {
        this.auth = builder.baseUrl(authUrl).build();
        this.objectMapper = objectMapper;
        this.token = token == null ? "" : token.strip();
    }

    public void publish(IdentitySyncOutboxRepository.PendingEvent event) {
        if (token.isBlank()) {
            throw new IllegalStateException("The identity sync service token is not configured.");
        }
        auth.post()
                .uri("/internal/identity/v1/workforce-events")
                .header(TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request(event))
                .retrieve()
                .toBodilessEntity();
    }

    private ObjectNode request(IdentitySyncOutboxRepository.PendingEvent event) {
        try {
            ObjectNode payload = (ObjectNode) objectMapper.readTree(event.payload());
            payload.put("eventId", event.eventId().toString());
            payload.put("providerTenantId", event.providerTenantId().toString());
            return payload;
        } catch (JsonProcessingException | ClassCastException exception) {
            throw new IllegalStateException("The workforce identity event payload is invalid.", exception);
        }
    }
}

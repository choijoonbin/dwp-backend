package com.dwp.services.people.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.function.Function;

@Component
public class HrisCredentialResolver {

    private final ObjectMapper objectMapper;
    private final Function<String, String> environment;

    @Autowired
    public HrisCredentialResolver(ObjectMapper objectMapper) {
        this(objectMapper, System::getenv);
    }

    HrisCredentialResolver(ObjectMapper objectMapper, Function<String, String> environment) {
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    public Credential resolve(String authMode, String reference) {
        if ("NONE".equals(authMode)) return Credential.none();
        if (reference == null || !reference.startsWith("env://")) {
            throw new HrisConnectorBlockedException(
                    "SECRET_PROVIDER_UNAVAILABLE",
                    "The configured secret provider is not available in this environment.");
        }
        String variable = reference.substring("env://".length());
        String secretDocument = environment.apply(variable);
        if (secretDocument == null || secretDocument.isBlank()) {
            throw new HrisConnectorBlockedException(
                    "SECRET_NOT_RESOLVED",
                    "The connector credential reference could not be resolved.");
        }
        try {
            JsonNode value = objectMapper.readTree(secretDocument);
            return switch (authMode) {
                case "BASIC" -> new Credential(
                        authMode, required(value, "username"), required(value, "password"),
                        null, null, null);
                case "OAUTH2_CLIENT_CREDENTIALS" -> new Credential(
                        authMode, null, null, required(value, "tokenUri"),
                        required(value, "clientId"), required(value, "clientSecret"),
                        optional(value, "scope"));
                default -> throw new HrisConnectorBlockedException(
                        "AUTH_MODE_UNAVAILABLE",
                        "The connector authentication mode requires infrastructure that is not configured.");
            };
        } catch (JsonProcessingException exception) {
            throw new HrisConnectorBlockedException(
                    "SECRET_DOCUMENT_INVALID",
                    "The connector credential document is not valid JSON.");
        }
    }

    private String required(JsonNode node, String field) {
        String value = optional(node, field);
        if (value == null) {
            throw new HrisConnectorBlockedException(
                    "SECRET_DOCUMENT_INVALID",
                    "The connector credential document is missing a required field.");
        }
        return value;
    }

    private String optional(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null
                : value.asText().trim();
    }

    public record Credential(
            String authMode,
            String username,
            String password,
            String tokenUri,
            String clientId,
            String clientSecret,
            String scope) {

        private Credential(
                String authMode,
                String username,
                String password,
                String tokenUri,
                String clientId,
                String clientSecret) {
            this(authMode, username, password, tokenUri, clientId, clientSecret, null);
        }

        static Credential none() {
            return new Credential("NONE", null, null, null, null, null, null);
        }
    }
}

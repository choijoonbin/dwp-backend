package com.dwp.services.approval.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.http.OutboundHttpHeaders;
import com.dwp.services.approval.integration.ApprovalFormUserProofIssuer.SignedProof;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Fixed registered source only. Single-use proofs are never automatically retried. */
@Component
public class AuthApprovalFormUserDirectory implements ApprovalFormUserDirectory {
    private static final String PREFIX = "/internal/auth/v1/approval-form-user-directory/";
    private static final Set<String> RESPONSE_KEYS = Set.of("people", "proofId", "requestDigest", "authRevision", "policyRevision");
    private static final Set<String> PERSON_KEYS = Set.of("tenantId", "subjectId", "personPublicId", "displayName", "identityPlane", "status");
    private final RestClient auth;
    private final String token;
    private final ApprovalFormUserProofIssuer proofs;
    private final ObjectMapper mapper;

    public AuthApprovalFormUserDirectory(RestClient.Builder builder, ObjectMapper mapper, ApprovalFormUserProofIssuer proofs,
            @Value("${dwp.identity-sync.auth-url:http://localhost:8001}") String authUrl,
            @Value("${dwp.auth.approval-form-user-token:}") String token) {
        this.auth = builder.clone().defaultHeaders(org.springframework.http.HttpHeaders::clear).baseUrl(authUrl).build();
        this.token = token == null ? "" : token;
        this.proofs = proofs;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    @Bulkhead(name = "authApprovalFormUserDirectory", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "authApprovalFormUserDirectory")
    public Result search(Authority authority, String query, int size) {
        configured();
        SignedProof proof = proofs.search(authority, query, size);
        return call(authority, proof, "search", Map.of("sourceProof", proof.token(), "query", query, "size", size), size, null);
    }

    @Override
    @Bulkhead(name = "authApprovalFormUserDirectory", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "authApprovalFormUserDirectory")
    public Result resolve(Authority authority, List<UUID> personPublicIds) {
        configured();
        SignedProof proof = proofs.resolve(authority, personPublicIds);
        List<UUID> ids = List.copyOf(personPublicIds);
        return call(authority, proof, "resolve", Map.of("sourceProof", proof.token(),
                "personPublicIds", ids.stream().map(UUID::toString).toList()), ids.size(), Set.copyOf(ids));
    }

    private Result call(Authority authority, SignedProof proof, String operation, Map<String, Object> body,
            int limit, Set<UUID> expectedIds) {
        try {
            String json = auth.post().uri(PREFIX + operation).headers(headers -> {
                headers.clear();
                OutboundHttpHeaders.propagateObservability(headers);
                headers.set("X-DWP-Approval-Form-User-Token", token);
                headers.set("X-DWP-Service-Identity", "dwp-approval-server");
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                headers.setAccept(List.of(org.springframework.http.MediaType.APPLICATION_JSON));
            }).body(body).retrieve().body(String.class);
            if (json == null || json.length() > 65536) throw unavailable();
            JsonNode response;
            try (JsonParser parser = mapper.createParser(json)) {
                response = mapper.readTree(parser);
                if (parser.nextToken() != null) throw unavailable();
            }
            exact(response, RESPONSE_KEYS);
            if (!proof.proofId().toString().equals(text(response, "proofId"))
                    || !proof.requestDigest().equals(text(response, "requestDigest"))) throw unavailable();
            text(response, "authRevision");
            text(response, "policyRevision");
            JsonNode raw = response.get("people");
            if (!raw.isArray() || raw.size() > limit) throw unavailable();
            var people = new ArrayList<Person>();
            var ids = new HashSet<UUID>();
            var subjects = new HashSet<Long>();
            for (JsonNode item : raw) {
                exact(item, PERSON_KEYS);
                long tenant = number(item, "tenantId"), subject = number(item, "subjectId");
                String publicId = text(item, "personPublicId");
                UUID id = UUID.fromString(publicId);
                if (!id.toString().equals(publicId) || tenant != authority.form().tenantId() || !ids.add(id)
                        || !subjects.add(subject) || !"TENANT".equals(text(item, "identityPlane"))
                        || !"ACTIVE".equals(text(item, "status"))) throw unavailable();
                people.add(new Person(tenant, subject, id, text(item, "displayName"), "TENANT", "ACTIVE"));
            }
            if (expectedIds != null && !expectedIds.equals(ids)) throw forbidden();
            if (!java.time.Instant.now().isBefore(authority.validUntil().toInstant())) throw unavailable();
            // Source Auth rechecks its actual revisions; do not mint a replacement owner expiry here.
            return new Result(authority, people);
        } catch (BaseException exception) { throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.FORBIDDEN || exception.getStatusCode() == HttpStatus.NOT_FOUND) throw forbidden();
            if (exception.getStatusCode() == HttpStatus.CONFLICT) throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                    "The person source decision changed; refresh before retrying.");
            throw unavailable();
        } catch (RestClientException | java.io.IOException | IllegalArgumentException exception) { throw unavailable(); }
    }

    private void configured() {
        if (token.isBlank() || token.length() > 8192 || !token.equals(token.strip())
                || token.codePoints().anyMatch(Character::isISOControl)) throw unavailable();
    }
    private void exact(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw unavailable();
        var keys = new HashSet<String>();
        node.fieldNames().forEachRemaining(keys::add);
        if (!expected.equals(keys)) throw unavailable();
    }
    private String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 200
                || !value.textValue().equals(value.textValue().strip())
                || value.textValue().codePoints().anyMatch(Character::isISOControl)) throw unavailable();
        return value.textValue();
    }
    private long number(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) throw unavailable();
        return value.longValue();
    }
    private BaseException forbidden() { return new BaseException(ErrorCode.FORBIDDEN, "The current tenant person source selection is unavailable."); }
    private BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Approval person source lookup is unavailable."); }
}

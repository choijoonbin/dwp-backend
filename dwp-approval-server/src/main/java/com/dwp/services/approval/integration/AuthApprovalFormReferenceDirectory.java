package com.dwp.services.approval.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.http.OutboundHttpHeaders;
import com.dwp.services.approval.integration.ApprovalFormReferenceDirectory.MutationPins;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Authority;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Person;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Result;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Only the existing fixed resolve endpoint accepts mutation proofs; no bootstrap or automatic replay. */
@Component
public class AuthApprovalFormReferenceDirectory implements ApprovalFormReferenceDirectory {
    private static final String PATH = "/internal/auth/v1/approval-form-user-directory/resolve";
    private final RestClient auth;
    private final ObjectMapper mapper;
    private final ApprovalFormReferenceProofIssuer proofs;
    private final String token;

    public AuthApprovalFormReferenceDirectory(RestClient.Builder builder, ObjectMapper mapper, ApprovalFormReferenceProofIssuer proofs,
            @Value("${dwp.identity-sync.auth-url:http://localhost:8001}") String authUrl,
            @Value("${dwp.auth.approval-form-user-token:}") String token) {
        this.auth = builder.clone().defaultHeaders(HttpHeaders::clear).baseUrl(authUrl).build();
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.proofs = proofs;
        this.token = token == null ? "" : token;
    }

    @Override
    @Bulkhead(name = "authApprovalFormReferenceDirectory", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "authApprovalFormReferenceDirectory")
    public Result resolve(Authority authority, MutationPins pins, List<UUID> personPublicIds) {
        if (token.isBlank() || token.length() > 8192 || !token.equals(token.strip())
                || token.codePoints().anyMatch(Character::isISOControl)) throw unavailable();
        var proof = proofs.resolve(authority, pins, personPublicIds);
        var expected = Set.copyOf(personPublicIds);
        try {
            String json = auth.post().uri(PATH).headers(headers -> {
                headers.clear();
                OutboundHttpHeaders.propagateObservability(headers);
                headers.set("X-DWP-Approval-Form-User-Token", token);
                headers.set("X-DWP-Service-Identity", "dwp-approval-server");
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            }).body(Map.of("sourceProof", proof.token(), "personPublicIds", personPublicIds.stream().map(UUID::toString).toList()))
                    .exchange((request, response) -> {
                        if (response.getStatusCode() == HttpStatus.FORBIDDEN || response.getStatusCode() == HttpStatus.NOT_FOUND) throw denied();
                        if (response.getStatusCode() == HttpStatus.CONFLICT) throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                                "The approval reference authority changed; refresh before retrying.");
                        if (!response.getStatusCode().is2xxSuccessful()) throw unavailable();
                        byte[] bytes = response.getBody().readNBytes(65537);
                        if (bytes.length > 65536) throw unavailable();
                        return new String(bytes, StandardCharsets.UTF_8);
                    });
            JsonNode raw;
            try (JsonParser parser = mapper.createParser(json)) {
                raw = mapper.readTree(parser);
                if (parser.nextToken() != null) throw unavailable();
            }
            exact(raw, Set.of("people", "proofId", "requestDigest", "authRevision", "policyRevision"));
            if (!proof.proofId().toString().equals(text(raw, "proofId")) || !proof.requestDigest().equals(text(raw, "requestDigest"))) throw unavailable();
            text(raw, "authRevision"); text(raw, "policyRevision");
            JsonNode rows = raw.get("people");
            if (!rows.isArray() || rows.size() > 30) throw unavailable();
            var people = new ArrayList<Person>();
            var found = new HashSet<UUID>();
            var subjects = new HashSet<Long>();
            for (JsonNode row : rows) {
                exact(row, Set.of("tenantId", "subjectId", "personPublicId", "displayName", "identityPlane", "status"));
                long tenant = number(row, "tenantId"), subject = number(row, "subjectId");
                String value = text(row, "personPublicId");
                UUID id = UUID.fromString(value);
                if (!id.toString().equals(value) || tenant != authority.form().tenantId() || !found.add(id)
                        || !subjects.add(subject) || !"ACTIVE".equals(text(row, "status")) || !"TENANT".equals(text(row, "identityPlane"))) throw denied();
                people.add(new Person(tenant, subject, id, text(row, "displayName"), "TENANT", "ACTIVE"));
            }
            if (!expected.equals(found)) throw denied();
            if (!Instant.now().isBefore(authority.validUntil().toInstant())) throw unavailable();
            return new Result(authority, people);
        } catch (BaseException exception) { throw exception;
        } catch (RestClientException | java.io.IOException | IllegalArgumentException exception) { throw unavailable(); }
    }

    private void exact(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw unavailable();
        var keys = new HashSet<String>(); node.fieldNames().forEachRemaining(keys::add);
        if (!expected.equals(keys)) throw unavailable();
    }
    private String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 200
                || !value.textValue().equals(value.textValue().strip()) || value.textValue().codePoints().anyMatch(Character::isISOControl)) throw unavailable();
        return value.textValue();
    }
    private long number(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) throw unavailable();
        return value.longValue();
    }
    private BaseException denied() { return new BaseException(ErrorCode.FORBIDDEN, "The selected current tenant reference is not authorized."); }
    private BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Approval reference source lookup is unavailable."); }
}

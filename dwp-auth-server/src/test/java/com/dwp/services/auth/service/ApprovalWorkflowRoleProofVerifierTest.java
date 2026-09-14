package com.dwp.services.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApprovalWorkflowRoleProofVerifierTest {
    static RSAKey roleKey;
    static RSAKey userKey;
    static RSAKey transportKey;
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final Instant NOW = Instant.parse("2026-09-14T03:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final UUID REQUEST = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    ApprovalWorkflowRoleProofVerifier verifier;

    @BeforeAll static void keys() throws Exception {
        roleKey = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).keyID("workflow-role-1").generate();
        userKey = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).keyID("user-source-1").generate();
        transportKey = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).keyID("workflow-role-transport-1").generate();
    }

    @BeforeEach void initialize() {
        verifier = new ApprovalWorkflowRoleProofVerifier(JSON, publicKeys(roleKey), publicKeys(userKey), publicKeys(transportKey), CLOCK);
    }

    static String publicKeys(RSAKey key) { return new JWKSet(key.toPublicJWK()).toString(); }

    static Map<String, Object> binding() {
        var stage = Map.of("key", "REVIEW", "name", "Review", "candidateRole", "APPROVAL_REVIEWER",
                "quorum", Map.of("mode", "ALL"), "slaMinutes", 30, "predecessors", List.of());
        var definition = Map.of("schemaContract", "DWP_APPROVAL_WORKFLOW_QUORUM_V2", "schemaVersion", 2,
                "slaMinutes", 30, "stages", List.of(stage));
        String published = ApprovalWorkflowRoleBinding.json(JSON, ApprovalWorkflowRoleBinding.canonical(JSON.valueToTree(definition)));
        var result = new TreeMap<String, Object>();
        result.put("tenantId", 42L); result.put("actorId", 100L); result.put("personPublicId", UUID.randomUUID().toString());
        result.put("requestId", REQUEST.toString()); result.put("requestVersion", 0);
        result.put("workflowVersionId", UUID.randomUUID().toString()); result.put("workflowVersion", 7);
        result.put("workflowDefinitionSha256", ApprovalWorkflowRoleBinding.sha256(published));
        result.put("formVersionId", UUID.randomUUID().toString()); result.put("formSchemaSha256", "a".repeat(64));
        result.put("payloadRevision", 1); result.put("payloadSha256", "b".repeat(64));
        result.put("policyVersion", 1); result.put("policySha256", "c".repeat(64));
        result.put("contextKey", "context"); result.put("contextScopeKey", "own");
        result.put("decisionRevision", "psr-" + "d".repeat(64)); result.put("accessMode", "NORMAL");
        result.put("routeContractKey", "route.approvals.work.request-submit.action");
        result.put("managementResourceSetKey", "RS_APPROVALS"); result.put("roleCodes", List.of("APPROVAL_REVIEWER"));
        result.put("publishedDefinition", published); result.put("method", "POST");
        result.put("path", "/v1/requests/" + REQUEST + "/submit"); result.put("idempotencyKey", "submit-1");
        return result;
    }

    static Map<String, Object> claims(Map<String, Object> binding) {
        var result = new TreeMap<String, Object>();
        result.put("iss", ApprovalWorkflowRoleProofVerifier.ISSUER); result.put("aud", ApprovalWorkflowRoleProofVerifier.AUDIENCE);
        result.put("purpose", ApprovalWorkflowRoleProofVerifier.PURPOSE); result.put("jti", UUID.randomUUID().toString());
        result.put("iat", NOW.getEpochSecond()); result.put("nbf", NOW.getEpochSecond()); result.put("exp", NOW.plusSeconds(30).getEpochSecond());
        result.put("operation", "ROLE_BINDINGS"); result.put("bindings", binding);
        result.put("requestDigest", ApprovalWorkflowRoleBinding.sha256(ApprovalWorkflowRoleBinding.json(JSON,
                new TreeMap<>(Map.of("operation", "ROLE_BINDINGS", "bindings", binding)))));
        return result;
    }

    static String sign(Map<String, Object> claims, RSAKey key) throws Exception {
        var value = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),
                new Payload(JSON.writeValueAsString(claims)));
        value.sign(new RSASSASigner(key)); return value.serialize();
    }

    static String body(String token, Map<String, Object> binding) throws Exception {
        return JSON.writeValueAsString(Map.of("operation", "ROLE_BINDINGS", "sourceProof", token, "bindings", binding));
    }

    static Map<String, Object> workloadClaims(String rawBody) throws Exception {
        var body = JSON.readTree(rawBody);
        var result = new TreeMap<String, Object>();
        result.put("iss", ApprovalWorkflowRoleWorkloadVerifier.ISSUER); result.put("aud", ApprovalWorkflowRoleWorkloadVerifier.AUDIENCE);
        result.put("purpose", ApprovalWorkflowRoleWorkloadVerifier.PURPOSE); result.put("jti", UUID.randomUUID().toString());
        result.put("iat", NOW.getEpochSecond()); result.put("nbf", NOW.getEpochSecond()); result.put("exp", NOW.plusSeconds(30).getEpochSecond());
        result.put("operation", "ROLE_BINDINGS"); result.put("httpMethod", "POST");
        result.put("httpPath", com.dwp.services.auth.config.ApprovalWorkflowRoleSecurityConfig.PATH);
        result.put("bodySha256", ApprovalWorkflowRoleBinding.sha256(ApprovalWorkflowRoleBinding.json(JSON, ApprovalWorkflowRoleBinding.canonical(body))));
        result.put("sourceProofSha256", ApprovalWorkflowRoleBinding.sha256(body.get("sourceProof").textValue()));
        return result;
    }

    static String transport(String rawBody) throws Exception { return sign(workloadClaims(rawBody), transportKey); }

    void denied(String token, String raw) throws Exception {
        String header = "borrowed".equals(token) ? token : transport(raw);
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BaseException.class, () -> verifier.verify(header, raw)).getErrorCode());
    }

    @Test void exactPurposeThirtySecondProofPreservesAllPinsAndDerivesOnlyPublishedRoleCodes() throws Exception {
        var binding = binding(); String token = sign(claims(binding), roleKey);
        String raw = body(token, binding);
        var actual = verifier.verify(transport(raw), raw);
        assertEquals(42L, actual.binding().tenantId()); assertEquals(REQUEST, actual.binding().requestId());
        assertEquals(List.of("APPROVAL_REVIEWER"), actual.binding().roleCodes());
        assertEquals(NOW.plusSeconds(30), actual.expiresAt());
        assertEquals(JSON.writeValueAsString(binding), JSON.writeValueAsString(actual.binding().sealed()));
        assertThrows(UnsupportedOperationException.class, () -> actual.binding().sealed().put("roleIds", List.of(1)));
    }

    @Test void fabricatedRecoveryAliasFailsWhileExactCanonicalActionPathSucceeds() throws Exception {
        var binding = binding(); binding.put("routeContractKey", "route.approvals.work.request-draft-recover.action");
        binding.put("path", "/v1/requests/" + REQUEST + "/draft/recover");
        String canonical = sign(claims(binding), roleKey); String raw = body(canonical, binding); verifier.verify(transport(raw), raw);
        binding.put("routeContractKey", "route.approvals.work.drafts.recover.action");
        String fabricated = sign(claims(binding), roleKey); denied(fabricated, body(fabricated, binding));
    }

    @Test void cannotInventRoleIdsOrChooseRoleOutsidePublishedDefinition() throws Exception {
        var binding = binding(); binding.put("roleIds", List.of(1));
        String token = sign(claims(binding), roleKey); denied(token, body(token, binding));
        binding.remove("roleIds"); binding.put("roleCodes", List.of("APPROVAL_ADMIN"));
        token = sign(claims(binding), roleKey); denied(token, body(token, binding));
    }

    @Test void bodyMutationOrBorrowedUserPurposeAndKeyCannotAuthorizeRoleBinding() throws Exception {
        var binding = binding(); var claims = claims(binding); String token = sign(claims, roleKey);
        binding.put("payloadRevision", 2); denied(token, body(token, binding));
        claims = claims(binding); claims.put("purpose", "APPROVAL_FORM_TENANT_PEOPLE_V1");
        token = sign(claims, roleKey); denied(token, body(token, binding));
        token = sign(claims(binding), userKey); denied(token, body(token, binding));
    }

    @Test void ttlExpiredFutureAndNotBeforeDriftAllFailClosed() throws Exception {
        var binding = binding();
        for (var change : List.of(Map.of("exp", NOW.plusSeconds(31).getEpochSecond()),
                Map.of("exp", NOW.getEpochSecond()), Map.of("iat", NOW.plusSeconds(1).getEpochSecond()),
                Map.of("nbf", NOW.minusSeconds(1).getEpochSecond()))) {
            var claims = claims(binding); claims.putAll(change); String token = sign(claims, roleKey); denied(token, body(token, binding));
        }
    }

    @Test void numericCoercionNoncanonicalUuidHashAndActionAliasAreNotAccepted() throws Exception {
        for (var change : List.of(Map.of("requestVersion", "0"), Map.of("personPublicId", REQUEST.toString().toUpperCase()),
                Map.of("workflowDefinitionSha256", "f".repeat(64)), Map.of("path", "/v1/requests/" + REQUEST + "/submit;alias=1"),
                Map.of("method", "HEAD"), Map.of("accessMode", "PROVIDER_SUPPORT"))) {
            var binding = binding(); binding.putAll(change); String token = sign(claims(binding), roleKey); denied(token, body(token, binding));
        }
    }

    @Test void duplicateUnknownBodyOrJwtFieldsAndHeaderBodyMismatchAreRejected() throws Exception {
        var binding = binding(); var claims = claims(binding); String token = sign(claims, roleKey);
        denied(token, body(token, binding).replaceFirst("\\{", "{\"operation\":\"ROLE_BINDINGS\","));
        var raw = new LinkedHashMap<String, Object>(Map.of("operation", "ROLE_BINDINGS", "sourceProof", token, "bindings", binding));
        raw.put("query", Map.of("roleIds", List.of(1))); denied(token, JSON.writeValueAsString(raw));
        denied("borrowed", body(token, binding));
        claims.put("population", 100); token = sign(claims, roleKey); denied(token, body(token, binding));
    }

    @Test void reusedUserSigningKeyOrMissingRoleTrustIsUnavailableNotPermissive() throws Exception {
        var binding = binding(); String token = sign(claims(binding), roleKey); String body = body(token, binding);
        String header = transport(body);
        var missing = new ApprovalWorkflowRoleProofVerifier(JSON, "", publicKeys(userKey), publicKeys(transportKey), CLOCK);
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, assertThrows(BaseException.class, () -> missing.verify(header, body)).getErrorCode());
        var reused = new ApprovalWorkflowRoleProofVerifier(JSON, publicKeys(roleKey), publicKeys(roleKey), publicKeys(transportKey), CLOCK);
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, assertThrows(BaseException.class, () -> reused.verify(header, body)).getErrorCode());
    }
}

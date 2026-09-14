package com.dwp.services.auth.approvalpolicyimpact;

import static org.assertj.core.api.Assertions.*;

import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.policyimpactsource.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure cryptographic interoperability, not proof of installed v9 or current tenant duty authorization. */
class PolicyImpactWireInteropTest {
    final PolicyImpactProtocolFixture fixture = new PolicyImpactProtocolFixture();
    final PolicyImpactSourceJson ownerJson = new PolicyImpactSourceJson(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    PolicyImpactSourceKeys ownerKeys() {
        return new PolicyImpactSourceKeys(ownerJson, fixture.owner.toJSONString(), fixture.transport.toJSONString(),
                PolicyImpactProtocolFixture.jwks(fixture.attestation), List.of());
    }
    PolicyImpactSourceProofIssuer.Exchange wireExchange(PolicyImpactProtocolFixture.Exchange fixtureExchange) throws Exception {
        // Test-only transport fixture. The production private constructor remains inaccessible to callers.
        var constructor = PolicyImpactSourceProofIssuer.Exchange.class.getDeclaredConstructor(byte[].class, String.class,
                String.class, String.class, String.class, JsonNode.class, Instant.class);
        constructor.setAccessible(true);
        return constructor.newInstance(fixtureExchange.body(), fixtureExchange.token(), fixtureExchange.sourceJti(), fixtureExchange.transportJti(),
                fixture.json.digest(fixture.binding()), fixture.json.tree(fixture.binding()), PolicyImpactProtocolFixture.NOW.plusSeconds(30));
    }
    PolicyImpactAuthorityPort.Current current() {
        var expiry = PolicyImpactProtocolFixture.NOW.plusSeconds(7);
        var grants = PolicyImpactProtocol.REQUIRED.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new PolicyImpactAuthorityPort.Grant(entry.getKey(), entry.getValue(), "RS_APPROVALS", "opaque-original", expiry)).toList();
        return new PolicyImpactAuthorityPort.Current("auth-" + "c".repeat(64), "policy-9-1-" + "d".repeat(64),
                "apia-" + "e".repeat(64), "e".repeat(64), PolicyImpactProtocolFixture.NOW, expiry, grants);
    }
    String token(PolicyImpactProtocolFixture.Exchange exchange) {
        var proof = fixture.verifier().verify(exchange.body(), exchange.token());
        return new PolicyImpactAuthorityIssuer(fixture.json, fixture::keys, fixture.clock).issue(proof, current());
    }
    @Test void actualAuthIssuerAndIndependentApprovalVerifierAgreeOnAllCardinalitiesAndInstantStrings() throws Exception {
        var fixtureExchange = fixture.exchange(fixture.binding()); String token = token(fixtureExchange);
        var jwt = com.nimbusds.jwt.SignedJWT.parse(token); var claims = fixture.json.parse(jwt.getPayload().toBytes());
        assertThat(claims.size()).isEqualTo(15); assertThat(claims.get("bindings").size()).isEqualTo(17);
        assertThat(claims.get("authority").size()).isEqualTo(6); assertThat(claims.get("grants").size()).isEqualTo(3);
        claims.get("grants").forEach(grant -> { assertThat(grant.size()).isEqualTo(5); assertThat(grant.get("expiresAt").isTextual()).isTrue(); });
        assertThat(claims.get("iat").isIntegralNumber()).isTrue(); assertThat(claims.get("authority").get("evaluatedAt").isTextual()).isTrue();
        var verifier = new PolicyImpactSourceAttestationVerifier(ownerKeys(), ownerJson, fixture.clock);
        var verified = verifier.verify(ownerJson.bytes(Map.of("sourceAttestation", token)), wireExchange(fixtureExchange));
        assertThat(verified.window().decisionRevision()).isEqualTo(fixture.binding().get("decisionRevision"));
        assertThat(verified.window().decisionRevision()).isNotEqualTo(current().ownerAuthRevision());
        assertThat(verified.window().validUntil()).isEqualTo(current().expiresAt()); assertThat(verified.window().grants()).hasSize(3);
        assertThat(PolicyImpactSourceProtocol.REQUIRED).isEqualTo(PolicyImpactProtocol.REQUIRED);
        assertThat(ownerJson.digest(fixture.binding())).isEqualTo(fixture.json.digest(fixture.binding()));
    }
    @Test void independentlySignedWrongContextGrantScopeBodyOrCorrelatorIsRejected() throws Exception {
        var exchange = fixture.exchange(fixture.binding()); var verifier = new PolicyImpactSourceAttestationVerifier(ownerKeys(), ownerJson, fixture.clock);
        JsonNode original = fixture.json.parse(com.nimbusds.jwt.SignedJWT.parse(token(exchange)).getPayload().toBytes());
        for (String mutation : List.of("bindings", "grants", "body", "jti", "expiry", "purpose", "additional")) {
            var claims = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
            switch (mutation) {
                case "bindings" -> ((com.fasterxml.jackson.databind.node.ObjectNode) claims.get("bindings")).put("decisionRevision", "psr-" + "f".repeat(64));
                case "grants" -> ((com.fasterxml.jackson.databind.node.ObjectNode) claims.get("grants").get(0)).put("resourceSetKey", "RS_OTHER");
                case "body" -> claims.put("bodySha256", "f".repeat(64));
                case "jti" -> claims.put("sourceProofJti", java.util.UUID.randomUUID().toString());
                case "expiry" -> ((com.fasterxml.jackson.databind.node.ObjectNode) claims.get("authority")).put("expiresAt", PolicyImpactProtocolFixture.NOW.plusSeconds(8).toString());
                case "purpose" -> claims.put("purpose", "APPROVAL_WORKFLOW_RUNTIME_ATTESTATION_V1");
                case "additional" -> claims.put("operation", "APPROVE");
            }
            String wrong = fixture.token(fixture.attestation, claims); var wire = wireExchange(exchange);
            assertThatThrownBy(() -> verifier.verify(ownerJson.bytes(Map.of("sourceAttestation", wrong)), wire)).isInstanceOf(BaseException.class);
        }
    }
    @Test void decimalEpochMillisecondsDuplicatesOrTamperedSignatureCannotCreateAWindow() throws Exception {
        var exchange = fixture.exchange(fixture.binding()); var wire = wireExchange(exchange);
        var verifier = new PolicyImpactSourceAttestationVerifier(ownerKeys(), ownerJson, fixture.clock);
        String token = token(exchange); String payload = com.nimbusds.jwt.SignedJWT.parse(token).getPayload().toString();
        String duplicate = PolicyImpactProtocolFixture.rawToken(fixture.attestation, payload.substring(0, payload.length() - 1) + ",\"iss\":\"duplicate\"}");
        assertThatThrownBy(() -> verifier.verify(ownerJson.bytes(Map.of("sourceAttestation", duplicate)), wire)).isInstanceOf(BaseException.class);
        var claims = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.json.parse(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        claims.put("iat", PolicyImpactProtocolFixture.NOW.getEpochSecond() + 0.5); String fraction = fixture.token(fixture.attestation, claims);
        assertThatThrownBy(() -> verifier.verify(ownerJson.bytes(Map.of("sourceAttestation", fraction)), wire)).isInstanceOf(BaseException.class);
        String[] parts = token.split("\\."); byte[] signature = java.util.Base64.getUrlDecoder().decode(parts[2]); signature[0] ^= 1;
        String tampered = parts[0] + "." + parts[1] + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        assertThatThrownBy(() -> verifier.verify(ownerJson.bytes(Map.of("sourceAttestation", tampered)), wire)).isInstanceOf(BaseException.class);
    }
    @Test void configuredRuntimePemOrKidReuseIsUnavailableOnBothSides() throws Exception {
        String pem = "-----BEGIN PRIVATE KEY-----\n" + java.util.Base64.getEncoder().encodeToString(fixture.owner.toRSAPrivateKey().getEncoded()) + "\n-----END PRIVATE KEY-----";
        assertThatThrownBy(() -> new PolicyImpactSourceKeys(ownerJson, fixture.owner.toJSONString(), fixture.transport.toJSONString(),
                PolicyImpactProtocolFixture.jwks(fixture.attestation), List.of(pem))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> new PolicyImpactKeys(fixture.json, PolicyImpactProtocolFixture.jwks(fixture.owner), PolicyImpactProtocolFixture.jwks(fixture.transport),
                fixture.attestation.toJSONString(), PolicyImpactProtocolFixture.jwks(fixture.attestation), List.of(pem))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> new PolicyImpactSourceKeys(ownerJson, fixture.owner.toJSONString(), fixture.transport.toJSONString(),
                PolicyImpactProtocolFixture.jwks(fixture.attestation), List.of("kid:" + fixture.transport.getKeyID()))).isInstanceOf(BaseException.class);
    }
    @Test void canonicalIntegerEqualityDoesNotPermitFractionsExponentOverflowNullOrAdditionalFields() throws Exception {
        var exchange = fixture.exchange(fixture.binding()); var wire = wireExchange(exchange);
        var verifier = new PolicyImpactSourceAttestationVerifier(ownerKeys(), ownerJson, fixture.clock);
        String payload = com.nimbusds.jwt.SignedJWT.parse(token(exchange)).getPayload().toString();
        for (String value : List.of("42.0", "42e0", "9007199254740992", "null")) {
            String altered = payload.replace("\"tenantId\":42", "\"tenantId\":" + value);
            assertThat(altered).isNotEqualTo(payload);
            String wrong = PolicyImpactProtocolFixture.rawToken(fixture.attestation, altered);
            assertThatThrownBy(() -> verifier.verify(ownerJson.bytes(Map.of("sourceAttestation", wrong)), wire)).isInstanceOf(BaseException.class);
        }
        var extra = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.json.parse(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ((com.fasterxml.jackson.databind.node.ObjectNode) extra.get("bindings")).put("actorAlias", 99);
        String wrong = fixture.token(fixture.attestation, extra);
        assertThatThrownBy(() -> verifier.verify(ownerJson.bytes(Map.of("sourceAttestation", wrong)), wire)).isInstanceOf(BaseException.class);
    }
}

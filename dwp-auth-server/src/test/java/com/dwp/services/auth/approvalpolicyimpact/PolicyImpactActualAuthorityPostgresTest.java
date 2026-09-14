package com.dwp.services.auth.approvalpolicyimpact;

import static org.assertj.core.api.Assertions.*;

import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.service.PolicyImpactActualAuthHarness;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

/** Real final9 Auth authority and nonce admission; the signed test owner PSR is not an installed Gateway proof. */
class PolicyImpactActualAuthorityPostgresTest {
    static final PolicyImpactProtocolFixture fixture = new PolicyImpactProtocolFixture();
    static final HttpClient http = HttpClient.newHttpClient();
    static PolicyImpactActualAuthHarness auth;
    static PolicyImpactActualEmbeddedServer server;
    @BeforeAll static void start() throws Exception {
        auth = new PolicyImpactActualAuthHarness(fixture.owner, fixture.transport, fixture.attestation);
        try { server = new PolicyImpactActualEmbeddedServer(auth.service(), true); }
        catch (Exception error) { auth.close(); throw error; }
    }
    @AfterAll static void close() { if (server != null) server.close(); if (auth != null) auth.close(); }

    @Test void genuineFinalNineRegistryLoadsWithoutAnUnwrappedConstraintFailure() throws Exception {
        assertThat(auth.queryConstraintsAreBothAbsent()).isTrue();
        var load = auth.bridge().getClass().getDeclaredMethod("validatedRegistry"); load.setAccessible(true);
        assertThatCode(() -> {
            try { load.invoke(auth.bridge()); }
            catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
        }).doesNotThrowAnyException();
    }

    @Test void mismatchedNullAndEmptyQueryConstraintsCannotConsumeNonce() {
        var subject = auth.subject(); var exchange = exchange(subject, auth.current(subject)); var before = auth.redis().keys("*");
        auth.withMismatchedQueryConstraints(() -> {
            assertThat(auth.queryConstraintsAreBothAbsent()).isFalse();
            assertThatThrownBy(() -> auth.service().evaluate(auth.service().preverify(exchange.body(), exchange.token())))
                    .isInstanceOf(com.dwp.core.exception.BaseException.class);
            assertThat(auth.redis().keys("*")).isEqualTo(before);
        });
        assertThat(auth.queryConstraintsAreBothAbsent()).isTrue(); auth.bridge().requireRegistered();
    }

    @Test void genuineFinalNineActualThreeScopeGrantsCanIssueTheBoundAttestation() throws Exception {
        var subject = auth.subject(); var current = auth.current(subject);
        assertThat(current.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(current.policyRevision()).contains(PolicyImpactActualAuthHarness.CHECKSUM);
        assertThat(current.effectiveGrants().stream().filter(value -> value instanceof ProductSurfaceAuthorityDtos.CapabilityGrant)
                .map(value -> ((ProductSurfaceAuthorityDtos.CapabilityGrant) value).capabilityContractKey()).toList())
                .containsExactlyInAnyOrderElementsOf(PolicyImpactProtocol.REQUIRED.keySet());
        assertThat(auth.duties(subject)).hasSize(3);
        assertThat(auth.duties(subject).stream().map(value -> value.legacyRoleCode()).toList())
                .containsExactlyInAnyOrder("APPROVAL_DESIGNER", "APPROVAL_DESIGNER", "APPROVAL_OPERATOR");
        assertThat(current.effectiveGrants().stream().filter(value -> value instanceof ProductSurfaceAuthorityDtos.CapabilityGrant)
                .map(value -> ((ProductSurfaceAuthorityDtos.CapabilityGrant) value).responsibility().code()).toList())
                .containsOnly("APP_CONFIG_ADMIN");
        var exchange = exchange(subject, current); var response = post(server.endpoint(), exchange);
        assertThat(response.statusCode()).isEqualTo(200);
        var result = fixture.json.parse(response.body()); PolicyImpactJson.keys(result, Set.of("sourceAttestation"));
        var claims = fixture.json.parse(SignedJWT.parse(result.path("sourceAttestation").textValue()).getPayload().toBytes());
        assertThat(claims.size()).isEqualTo(15); assertThat(claims.path("grants").size()).isEqualTo(3);
        assertThat(claims.path("bindings").path("decisionRevision").textValue()).isEqualTo(exchange.bindings().get("decisionRevision"));
        assertThat(claims.path("authority").path("ownerAuthRevision").textValue()).isEqualTo(current.authRevision());
        assertThat(claims.path("bindings").path("decisionRevision").textValue()).isNotEqualTo(current.authRevision());
        for (var grant : claims.path("grants")) {
            assertThat(grant.path("resourceSetKey").textValue()).isEqualTo("RS_APPROVALS");
            assertThat(grant.path("contextScopeKey").textValue()).isEqualTo(exchange.bindings().get("contextScopeKey"));
        }
        assertThat(post(server.endpoint(), exchange).statusCode()).isBetween(400, 409);
    }

    @Test void revokedActualSourceDutyRejectsBeforeNonceConsumption() throws Exception {
        var subject = auth.subject(); var current = auth.current(subject);
        assertThat(current.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        var exchange = exchange(subject, current); var before = auth.redis().keys("*");
        auth.revoke(subject, "APPROVAL_OPERATIONS_EXECUTE");
        assertThat(auth.duties(subject)).hasSize(2);
        assertThat(post(server.endpoint(), exchange).statusCode()).isEqualTo(403);
        assertThat(auth.redis().keys("*")).isEqualTo(before);
    }

    @Test void defaultClosedHttpDoesNotAdmitNonceEvenWithRealFinalNineAndValidProof() throws Exception {
        var subject = auth.subject(); var exchange = exchange(subject, auth.current(subject)); var before = auth.redis().keys("*");
        try (var disabled = new PolicyImpactActualEmbeddedServer(auth.service(), false)) {
            assertThat(post(disabled.endpoint(), exchange).statusCode()).isEqualTo(503);
        }
        assertThat(auth.redis().keys("*")).isEqualTo(before);
    }

    @Test void actualSameCountDutyReplacementInvalidatesTheBoundContextBeforeNonceConsumption() {
        var subject = auth.subject(); var initial = auth.current(subject);
        var exchange = exchange(subject, initial); var before = auth.redis().keys("*");
        var original = auth.duties(subject).stream().map(value -> value.assignmentId()).toList();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var observed = auth.observing(new PolicyImpactAuthorityPort() {
            @Override public void requireRegistered() { auth.bridge().requireRegistered(); }
            @Override public Current requireCurrent(PolicyImpactProofVerifier.Verified proof) {
                var current = auth.bridge().requireCurrent(proof);
                if (reads.incrementAndGet() == 1) auth.replaceDuty(subject, "APPROVAL_OPERATIONS_EXECUTE");
                return current;
            }
        });
        assertThatThrownBy(() -> observed.evaluate(observed.preverify(exchange.body(), exchange.token())))
                .isInstanceOfSatisfying(com.dwp.core.exception.BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(com.dwp.core.common.ErrorCode.FORBIDDEN));
        assertThat(reads.get()).isEqualTo(1);
        var fresh = auth.current(subject);
        assertThat(fresh.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(fresh.contextKey()).isNotEqualTo(initial.contextKey());
        assertThat(fresh.authRevision()).isNotEqualTo(initial.authRevision());
        assertThat(auth.duties(subject)).hasSize(original.size());
        assertThat(auth.duties(subject).stream().map(value -> value.assignmentId()).toList()).isNotEqualTo(original);
        assertThat(auth.redis().keys("*")).isEqualTo(before);
    }

    @Test void expiredActualLinkedConfigurationResponsibilityRejectsBeforeNonceConsumption() throws Exception {
        var subject = auth.subject(); var exchange = exchange(subject, auth.current(subject)); var before = auth.redis().keys("*");
        auth.expireResponsibility(subject);
        assertThat(post(server.endpoint(), exchange).statusCode()).isEqualTo(403);
        assertThat(auth.redis().keys("*")).isEqualTo(before);
    }

    @Test void validSignaturesCannotSelectAnUnownedOpaqueScope() throws Exception {
        var subject = auth.subject(); var exchange = exchange(subject, auth.current(subject), bindings ->
                bindings.put("contextScopeKey", "scope-" + "c".repeat(64))); var before = auth.redis().keys("*");
        assertThat(post(server.endpoint(), exchange).statusCode()).isEqualTo(403);
        assertThat(auth.redis().keys("*")).isEqualTo(before);
    }

    static Exchange exchange(PolicyImpactActualAuthHarness.Subject subject, ProductSurfaceAuthorityDtos.AuthorityResult current) {
        return exchange(subject, current, bindings -> { });
    }
    static Exchange exchange(PolicyImpactActualAuthHarness.Subject subject, ProductSurfaceAuthorityDtos.AuthorityResult current,
            java.util.function.Consumer<Map<String, Object>> customize) {
        var now = Instant.now(); var bindings = new LinkedHashMap<>(fixture.binding());
        bindings.put("tenantId", auth.tenantId()); bindings.put("actorId", subject.userId()); bindings.put("personPublicId", subject.personPublicId().toString());
        bindings.put("contextKey", current.contextKey()); bindings.put("contextScopeKey", current.scopes().getFirst().key());
        bindings.put("authorityValidUntil", now.plusSeconds(40).toString());
        customize.accept(bindings);
        String sourceJti = UUID.randomUUID().toString(), transportJti = UUID.randomUUID().toString();
        var owner = standard(PolicyImpactProtocol.OWNER_ISSUER, PolicyImpactProtocol.OWNER_AUDIENCE, PolicyImpactProtocol.OWNER_PURPOSE, sourceJti, now);
        owner.put("bindings", bindings); owner.put("bindingsSha256", fixture.json.digest(bindings));
        byte[] body = fixture.json.bytes(Map.of("sourceProof", fixture.token(fixture.owner, owner), "bindings", bindings));
        var transport = standard(PolicyImpactProtocol.TRANSPORT_ISSUER, PolicyImpactProtocol.TRANSPORT_AUDIENCE, PolicyImpactProtocol.TRANSPORT_PURPOSE, transportJti, now);
        transport.put("method", "POST"); transport.put("path", PolicyImpactProtocol.PATH); transport.put("sourceProofJti", sourceJti);
        transport.put("bodySha256", PolicyImpactJson.sha(body)); transport.put("bindingsSha256", fixture.json.digest(bindings));
        return new Exchange(body, fixture.token(fixture.transport, transport), Map.copyOf(bindings));
    }
    static Map<String, Object> standard(String issuer, String audience, String purpose, String jti, Instant now) {
        var result = new LinkedHashMap<String, Object>(); result.put("iss", issuer); result.put("aud", audience); result.put("sub", "dwp-approval-server");
        result.put("jti", jti); result.put("iat", now.getEpochSecond()); result.put("nbf", now.getEpochSecond());
        result.put("exp", now.plusSeconds(30).getEpochSecond()); result.put("purpose", purpose); return result;
    }
    static HttpResponse<byte[]> post(URI uri, Exchange exchange) throws Exception {
        return http.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .header("X-DWP-Service-Identity", "dwp-approval-server").header(PolicyImpactProtocol.HEADER, exchange.token())
                .POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body())).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    record Exchange(byte[] body, String token, Map<String, Object> bindings) { }
}

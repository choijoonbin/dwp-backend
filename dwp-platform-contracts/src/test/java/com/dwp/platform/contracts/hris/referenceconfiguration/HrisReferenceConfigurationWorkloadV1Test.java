package com.dwp.platform.contracts.hris.referenceconfiguration;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.*;
import static org.assertj.core.api.Assertions.*;

class HrisReferenceConfigurationWorkloadV1Test {
    static final JsonMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static KeyPair key;
    @BeforeAll static void key() throws Exception { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key = generator.generateKeyPair(); }

    @Test void exactSevenEndpointContractsAreIndependentFromApprovalAndAdmitOnlySignedBindings() throws Exception {
        for (Endpoint endpoint : Endpoint.values()) {
            String path = endpoint.path(UUID.randomUUID());
            byte[] body = "{\"synthetic\":true}".getBytes(StandardCharsets.UTF_8);
            var claims = claims(endpoint, path, body);
            var admission = verify(MAPPER, CLOCK, endpoint, path, headers(endpoint.callerWorkloadId(), sign(claims)), body,
                    kid -> new VerifiedSigningKey(key.getPublic(), WorkloadAuthentication.CLIENT_CREDENTIAL_JWT, endpoint.callerWorkloadId()),
                    (e, p, caller, tenant) -> tenant == 42, (id, nonce, exp) -> true);
            assertThat(admission.tenantId()).isEqualTo(42);
            assertThat(admission.claims().bindingId()).isEqualTo(endpoint.bindingId());
            assertThat(endpoint.matchesPath(path + "?tenant=42")).isFalse();
            assertThat(endpoint.matchesPath(path + "/")).isFalse();
        }
        assertThat(Endpoint.FINALIZE.matchesPath("/internal/hris-performance/v1/configuration-artifacts/ABC:finalize")).isFalse();
    }

    @Test void wrongClaimsHeadersBodyAudienceAuthenticationAndReplayFailBeforeTenantUse() throws Exception {
        Endpoint endpoint = Endpoint.CREATE_LOOKUP; String path = endpoint.path(null); byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        for (String field : new String[]{"iss", "aud", "sub", "bindingId", "purpose", "method", "path", "requestBodySha256", "authn"}) {
            var c = claims(endpoint, path, body); c.put(field, "substitution");
            assertThatThrownBy(() -> verified(endpoint, path, body, sign(c))).isInstanceOf(AdmissionFailure.class);
        }
        for (String field : new String[]{"iat", "nbf", "exp"}) {
            var c = claims(endpoint, path, body); c.put(field, NOW.minusSeconds(60).getEpochSecond());
            assertThatThrownBy(() -> verified(endpoint, path, body, sign(c))).isInstanceOf(AdmissionFailure.class);
        }
        var c = claims(endpoint, path, body); String compact = sign(c);
        for (String name : new String[]{"Authorization", "Cookie", "Origin", "Referer", "X-DWP-Tenant-ID",
                "X-DWP-Authority-Unknown-Suffix", "X-DWP-Scope", "X-DWP-Service-Identity",
                "X-User-ID", "X-Tenant-ID", "X-Roles", "X-Permissions", "X-Purpose-Code"}) {
            var headers = new ArrayList<>(headers(endpoint.callerWorkloadId(), compact)); headers.add(new Header(name, "spoofed"));
            assertThatThrownBy(() -> verify(MAPPER, CLOCK, endpoint, path, headers, body,
                    kid -> new VerifiedSigningKey(key.getPublic(), WorkloadAuthentication.CLIENT_CREDENTIAL_JWT, endpoint.callerWorkloadId()),
                    (e,p,caller,tenant) -> { throw new AssertionError("tenant use before rejection"); }, (id,nonce,exp) -> true))
                    .isInstanceOf(AdmissionFailure.class);
        }
        var ids = new HashSet<UUID>();
        ReplayStore replay = (id, nonce, exp) -> ids.add(id) && ids.add(nonce);
        var headers = headers(endpoint.callerWorkloadId(), compact);
        SigningKeyResolver resolver = kid -> new VerifiedSigningKey(key.getPublic(), WorkloadAuthentication.CLIENT_CREDENTIAL_JWT, endpoint.callerWorkloadId());
        verify(MAPPER, CLOCK, endpoint, path, headers, body, resolver, (e,p,caller,tenant) -> true, replay);
        assertThatThrownBy(() -> verify(MAPPER, CLOCK, endpoint, path, headers, body, resolver,
                (e,p,caller,tenant) -> true, replay)).isInstanceOf(AdmissionFailure.class);
        assertThatThrownBy(() -> verified(endpoint, path, "{\"tampered\":true}".getBytes(StandardCharsets.UTF_8), compact)).isInstanceOf(AdmissionFailure.class);
        String duplicate = MAPPER.writeValueAsString(c).replaceFirst("\\{", "{\"tenantId\":99,");
        assertThatThrownBy(() -> verified(endpoint, path, body, signText(duplicate))).isInstanceOf(AdmissionFailure.class);
    }

    private VerifiedAdmission verified(Endpoint e, String path, byte[] body, String proof) {
        return verify(MAPPER, CLOCK, e, path, headers(e.callerWorkloadId(), proof), body,
                kid -> new VerifiedSigningKey(key.getPublic(), WorkloadAuthentication.CLIENT_CREDENTIAL_JWT, e.callerWorkloadId()),
                (endpoint,p,caller,tenant) -> true, (id,nonce,exp) -> true);
    }
    static Map<String,Object> claims(Endpoint e, String path, byte[] body) {
        var c = new LinkedHashMap<String,Object>();
        c.put("iss", e.callerWorkloadId()); c.put("sub", e.callerWorkloadId()); c.put("aud", e.ownerWorkloadId());
        c.put("iat", NOW.getEpochSecond()); c.put("nbf", NOW.getEpochSecond()); c.put("exp", NOW.plusSeconds(30).getEpochSecond());
        c.put("jti", UUID.randomUUID()); c.put("requestNonce", UUID.randomUUID()); c.put("bindingId", e.bindingId());
        c.put("purpose", e.purpose()); c.put("method", "POST"); c.put("path", path); c.put("tenantId", 42);
        c.put("requestBodySha256", sha256(body)); c.put("authn", "CLIENT_CREDENTIAL_JWT"); return c;
    }
    static String sign(Map<String,Object> c) throws Exception { return signText(MAPPER.writeValueAsString(c)); }
    static String signText(String claims) throws Exception {
        String head = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"per004-test\"}".getBytes(StandardCharsets.UTF_8));
        String content = head + "." + encode(claims.getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(key.getPrivate()); signer.update(content.getBytes(StandardCharsets.US_ASCII));
        return content + "." + encode(signer.sign());
    }
    static String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
}

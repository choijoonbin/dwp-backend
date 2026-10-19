package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.services.platform.security.PlatformSecurityFilter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.Per004TestFixtures.*;
import static org.assertj.core.api.Assertions.*;

class Per004WorkloadSecurityFilterTest {
    static KeyPair key;
    static final Endpoint ENDPOINT = Endpoint.PROJECTION;
    static final byte[] BODY = ("{\"asOf\":\"2026-10-19T08:00:00Z\",\"targets\":[{\"configurationId\":\""
            + UUID.randomUUID() + "\",\"scopeDigest\":\"" + SCOPE + "\"}]}").getBytes(StandardCharsets.UTF_8);
    @BeforeAll static void key() throws Exception { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key = generator.generateKeyPair(); }

    @Test void exactVerifiedWorkloadAdmissionPassesPlatformFilterWithoutGatewayServiceToken() throws Exception {
        var filter = filter((id, nonce, exp) -> true);
        var request = request(BODY); var response = new MockHttpServletResponse(); var calls = new AtomicInteger();
        filter.doFilter(request, response, (req, res) -> {
            assertThat(Per004WorkloadSecurityFilter.admitted((jakarta.servlet.http.HttpServletRequest) req)).isTrue();
            assertThat(req.getInputStream().readAllBytes()).isEqualTo(BODY);
            new PlatformSecurityFilter("", "", false, MAPPER).doFilter(req, res, (r,s) -> calls.incrementAndGet());
        });
        assertThat(response.getStatus()).isEqualTo(200); assertThat(calls).hasValue(1);
    }

    @Test void defaultUnboundAndUnverifiedInternalPathsNeverBypassPlatformSecurity() throws Exception {
        var filter = new Per004WorkloadSecurityFilter(MAPPER, null, null, (id,nonce,exp) -> true, CLOCK);
        var response = new MockHttpServletResponse(); var calls = new AtomicInteger();
        filter.doFilter(request(BODY), response, (r,s) -> calls.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(403); assertThat(calls).hasValue(0);
        for (String path : new String[]{ENDPOINT.path(null), "/internal/hris-reference-configuration/v1/unregistered"}) {
            var unverified = new MockHttpServletRequest("POST", path); var result = new MockHttpServletResponse();
            new PlatformSecurityFilter("", "", false, MAPPER).doFilter(unverified, result, (r,s) -> calls.incrementAndGet());
            assertThat(result.getStatus()).isNotEqualTo(200);
        }
        assertThat(calls).hasValue(0);
    }

    @Test void duplicateBrowserAuthorityBodyMethodQueryAndReplayViolationsMakeZeroApplicationCalls() throws Exception {
        for (String violation : new String[]{"duplicate", "browser", "unknownDwp", "legacyAuthority", "body", "method", "query", "replay", "duplicateBody"}) {
            var request = request(BODY); var response = new MockHttpServletResponse(); var calls = new AtomicInteger();
            switch (violation) {
                case "duplicate" -> request.addHeader(SERVICE_IDENTITY_HEADER, ENDPOINT.callerWorkloadId());
                case "browser" -> request.addHeader("Authorization", "Bearer browser");
                case "unknownDwp" -> request.addHeader("X-DWP-Unknown-Authority", "spoofed");
                case "legacyAuthority" -> request.addHeader("X-Purpose-Code", "HRIS_SYSTEM_ADMIN");
                case "body" -> request.setContent("{}".getBytes(StandardCharsets.UTF_8));
                case "method" -> request.setMethod("GET");
                case "query" -> request.setQueryString("tenant=43");
                case "duplicateBody" -> {
                    byte[] duplicate = new String(BODY, StandardCharsets.UTF_8).replaceFirst("\\{", "{\"asOf\":\"2026-10-19T08:00:00Z\",").getBytes(StandardCharsets.UTF_8);
                    request = request(duplicate);
                }
                default -> { }
            }
            filter((id,nonce,exp) -> !violation.equals("replay")).doFilter(request, response, (r,s) -> calls.incrementAndGet());
            assertThat(response.getStatus()).as(violation).isEqualTo(403); assertThat(calls).hasValue(0);
        }
    }

    private Per004WorkloadSecurityFilter filter(ReplayStore replays) {
        return new Per004WorkloadSecurityFilter(MAPPER,
                kid -> new VerifiedSigningKey(key.getPublic(), WorkloadAuthentication.CLIENT_CREDENTIAL_JWT, ENDPOINT.callerWorkloadId()),
                (endpoint,path,caller,tenant) -> tenant == 42, replays, CLOCK);
    }
    private MockHttpServletRequest request(byte[] body) throws Exception {
        var request = new MockHttpServletRequest("POST", ENDPOINT.path(null)); request.setContent(body);
        request.setContentType("application/json"); request.addHeader("Accept", "application/json");
        request.addHeader(SERVICE_IDENTITY_HEADER, ENDPOINT.callerWorkloadId()); request.addHeader(WORKLOAD_PROOF_HEADER, sign(body));
        return request;
    }
    private String sign(byte[] body) throws Exception {
        var c = new LinkedHashMap<String,Object>();
        c.put("iss", ENDPOINT.callerWorkloadId()); c.put("aud", ENDPOINT.ownerWorkloadId()); c.put("sub", ENDPOINT.callerWorkloadId());
        c.put("iat", NOW.toEpochSecond()); c.put("nbf", NOW.toEpochSecond()); c.put("exp", NOW.plusSeconds(30).toEpochSecond());
        c.put("jti", UUID.randomUUID()); c.put("requestNonce", UUID.randomUUID()); c.put("bindingId", ENDPOINT.bindingId());
        c.put("purpose", ENDPOINT.purpose()); c.put("method", "POST"); c.put("path", ENDPOINT.path(null)); c.put("tenantId", 42);
        c.put("requestBodySha256", sha256(body)); c.put("authn", "CLIENT_CREDENTIAL_JWT");
        String content = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"per004-test\"}".getBytes(StandardCharsets.UTF_8)) + "." + encode(MAPPER.writeValueAsBytes(c));
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(key.getPrivate()); signer.update(content.getBytes(StandardCharsets.US_ASCII));
        return content + "." + encode(signer.sign());
    }
    private String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
}

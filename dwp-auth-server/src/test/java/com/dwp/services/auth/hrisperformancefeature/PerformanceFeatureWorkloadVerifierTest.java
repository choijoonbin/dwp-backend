package com.dwp.services.auth.hrisperformancefeature;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static com.dwp.services.auth.hrisperformancefeature.PerformanceWorkloadFixture.*;
import static org.assertj.core.api.Assertions.*;

class PerformanceFeatureWorkloadVerifierTest {
    @Test void exactSignedPlatformProofBindsTenantBodyAndFreshDistinctReplayIdentities() throws Exception {
        var reservations = new AtomicInteger(); byte[] body = body();
        var verifier = verifier((jti, nonce, tenant, expiry) -> {
            assertThat(jti).isNotEqualTo(nonce); assertThat(tenant).isEqualTo(7);
            assertThat(expiry).isEqualTo(NOW.plusSeconds(30)); reservations.incrementAndGet(); return true;
        });
        assertThat(verifier.verify("POST", PerformanceFeatureContract.PATH, null, headers(token(claims(body))), body))
                .isEqualTo(REQUEST);
        assertThat(reservations).hasValue(1);
    }

    @Test void wrongCallerAudiencePurposeBindingMethodPathTenantBodyAndAuthenticationNeverReserve() throws Exception {
        var reservations = new AtomicInteger(); var verifier = verifier((a, b, c, d) -> { reservations.incrementAndGet(); return true; });
        byte[] body = body();
        for (var mutation : Map.<String, Object>ofEntries(Map.entry("iss", "dwp-people-server"),
                Map.entry("sub", "dwp-people-server"), Map.entry("aud", "dwp-people-server"),
                Map.entry("purpose", "APPROVAL_BRIDGE"), Map.entry("bindingId", "OTHER"),
                Map.entry("method", "GET"), Map.entry("path", PerformanceFeatureContract.PATH + '/'),
                Map.entry("tenantId", 8), Map.entry("requestBodySha256", "b".repeat(64)),
                Map.entry("authn", "MTLS_SPIFFE")).entrySet()) {
            var claims = claims(body); claims.put(mutation.getKey(), mutation.getValue());
            String token = token(claims);
            assertThatThrownBy(() -> verifier.verify("POST", PerformanceFeatureContract.PATH, null, headers(token), body))
                    .as(mutation.getKey()).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(reservations).hasValue(0);
    }

    @Test void staleFutureOverlongAndConflictingLifetimeOrNonceNeverReserve() throws Exception {
        byte[] body = body(); var reservations = new AtomicInteger();
        var verifier = verifier((a, b, c, d) -> { reservations.incrementAndGet(); return true; });
        for (String scenario : List.of("expired", "future", "long", "nbf", "sameNonce", "noncanonical")) {
            var claims = claims(body);
            switch (scenario) {
                case "expired" -> claims.put("exp", NOW.getEpochSecond());
                case "future" -> claims.put("iat", NOW.plusSeconds(1).getEpochSecond());
                case "long" -> claims.put("exp", NOW.plusSeconds(31).getEpochSecond());
                case "nbf" -> claims.put("nbf", NOW.minusSeconds(1).getEpochSecond());
                case "sameNonce" -> claims.put("requestNonce", claims.get("jti"));
                case "noncanonical" -> claims.put("jti", "1-1-1-1-1");
                default -> throw new IllegalStateException();
            }
            String token = token(claims);
            assertThatThrownBy(() -> verifier.verify("POST", PerformanceFeatureContract.PATH, null, headers(token), body))
                    .as(scenario).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(reservations).hasValue(0);
    }

    @Test void browserUnknownAuthorityAndDuplicateHeadersNeverReserve() throws Exception {
        byte[] body = body(); String token = token(claims(body));
        var reservations = new AtomicInteger(); var verifier = verifier((a, b, c, d) -> { reservations.incrementAndGet(); return true; });
        for (String name : List.of("Authorization", "Cookie", "Origin", "Referer", "X-DWP-Tenant-Id",
                "X-DWP-Unknown-Authority", "X-DWP-Service-Identity", "X-DWP-Workload-Proof", "X-User-Id")) {
            var headers = headers(token); headers.add(new PerformanceFeatureWorkloadVerifier.Header(name, "spoof"));
            assertThatThrownBy(() -> verifier.verify("POST", PerformanceFeatureContract.PATH, null, headers, body))
                    .as(name).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(reservations).hasValue(0);
    }

    @Test void duplicateUnknownOrCoercedJsonClaimsAndBodyAreRejected() throws Exception {
        byte[] body = body(); var verifier = verifier((a, b, c, d) -> true);
        String raw = JSON.writeValueAsString(claims(body));
        String duplicate = sign(raw.substring(0, raw.length() - 1) + ",\"aud\":\"dwp-auth-server\"}");
        assertThatThrownBy(() -> verifier.verify("POST", PerformanceFeatureContract.PATH, null, headers(duplicate), body))
                .isInstanceOf(IllegalArgumentException.class);
        for (String suffix : List.of(",\"tenantId\":7}", ",\"unknown\":1}")) {
            byte[] changed = (new String(body, StandardCharsets.UTF_8).replaceFirst("}$", suffix)).getBytes(StandardCharsets.UTF_8);
            String token = token(claims(changed));
            assertThatThrownBy(() -> verifier.verify("POST", PerformanceFeatureContract.PATH, null, headers(token), changed))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        byte[] coerced = new String(body, StandardCharsets.UTF_8).replace("\"tenantId\":7", "\"tenantId\":\"7\"")
                .getBytes(StandardCharsets.UTF_8);
        String token = token(claims(coerced));
        assertThatThrownBy(() -> verifier.verify("POST", PerformanceFeatureContract.PATH, null, headers(token), coerced))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void changedBytesAliasQueryMissingTrustAndReplayOutageFailClosed() throws Exception {
        byte[] body = body(); String token = token(claims(body)); var verifier = verifier((a, b, c, d) -> true);
        assertThatThrownBy(() -> verifier.verify("POST", PerformanceFeatureContract.PATH + '/', null, headers(token), body))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verifier.verify("POST", PerformanceFeatureContract.PATH, "tenantId=7", headers(token), body))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verifier.verify("GET", PerformanceFeatureContract.PATH, null, headers(token), body))
                .isInstanceOf(IllegalArgumentException.class);
        var unbound = new PerformanceFeatureWorkloadVerifier(JSON, CLOCK, kid -> null, (a, b, c, d) -> true);
        assertThatThrownBy(() -> unbound.verify("POST", PerformanceFeatureContract.PATH, null, headers(token), body))
                .isInstanceOf(IllegalArgumentException.class);
        var unavailable = verifier((a, b, c, d) -> { throw new IllegalStateException(); });
        assertThatThrownBy(() -> unavailable.verify("POST", PerformanceFeatureContract.PATH, null, headers(token), body))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] changed = (new String(body, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verifier.verify("POST", PerformanceFeatureContract.PATH, null, headers(token), changed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void replayedJtiOrNonceCannotReachASecondEvaluation() throws Exception {
        var used = new HashSet<String>();
        var verifier = verifier((jti, nonce, tenant, expiry) -> {
            if (used.contains(jti.toString()) || used.contains(nonce.toString())) return false;
            used.add(jti.toString()); used.add(nonce.toString()); return true;
        });
        byte[] body = body(); var first = claims(body); String firstToken = token(first);
        verifier.verify("POST", PerformanceFeatureContract.PATH, null, headers(firstToken), body);
        assertThatThrownBy(() -> verifier.verify("POST", PerformanceFeatureContract.PATH, null, headers(firstToken), body))
                .isInstanceOf(IllegalArgumentException.class);
        var second = claims(body); second.put("requestNonce", first.get("requestNonce")); String secondToken = token(second);
        assertThatThrownBy(() -> verifier.verify("POST", PerformanceFeatureContract.PATH, null, headers(secondToken), body))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

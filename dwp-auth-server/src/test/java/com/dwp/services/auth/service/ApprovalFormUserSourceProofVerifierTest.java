package com.dwp.services.auth.service;

import static com.dwp.services.auth.service.ApprovalFormUserProofTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.nimbusds.jose.jwk.JWKSet;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApprovalFormUserSourceProofVerifierTest {
    private final ApprovalFormUserSourceProofVerifier verifier = verifier();
    private final String digest = verifier.requestDigest("SEARCH", Map.of("query", "Kim", "size", 2));

    @Test
    void goldenDigestsBindOnlyTheExactOperationAndOrderedCanonicalRequest() {
        assertThat(digest).isEqualTo("28f9d9611c7498e02c7e5f86fa2a2cb1cd50074de0f2ea7c23dd0b1a08f1f331");
        assertThat(verifier.requestDigest("RESOLVE", Map.of("personPublicIds", List.of(PERSON))))
                .isEqualTo("5d317f655012c2911444d91e451bc4b68c7577f38dc4b2bc8bcf994be073430e");
        assertThat(verifier.requestDigest("SEARCH", Map.of("size", 2, "query", "Kim"))).isEqualTo(digest);
        assertThat(verifier.requestDigest("SEARCH", Map.of("query", "Kim", "size", 3))).isNotEqualTo(digest);
        var pretty = new ApprovalFormUserSourceProofVerifier(MAPPER.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT),
                new JWKSet(KEY.toPublicJWK()).toString(), CLOCK);
        assertThat(pretty.requestDigest("SEARCH", Map.of("query", "Kim", "size", 2))).isEqualTo(digest);
    }

    @Test
    void verifiesActualOwnerSignatureWithExactClosedContextBindings() {
        var proof = verifier.verify(sign(claims("SEARCH", digest)), "SEARCH", digest);
        assertThat(proof.tenantId()).isEqualTo(10); assertThat(proof.actorId()).isEqualTo(20);
        assertThat(proof.routeContractKey()).isEqualTo(ApprovalFormUserCurrentAuthorityAdapter.WORK_ROUTE);
        assertThat(proof.expiresAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void rejectsBodyTamperCrossOperationAndUnknownSigner() {
        String token = sign(claims("SEARCH", digest));
        reject(() -> verifier.verify(token, "SEARCH", "c".repeat(64)), ErrorCode.FORBIDDEN);
        reject(() -> verifier.verify(token, "RESOLVE", digest), ErrorCode.FORBIDDEN);
        reject(() -> verifier.verify(sign(claims("SEARCH", digest), key("unknown")), "SEARCH", digest), ErrorCode.FORBIDDEN);
        String[] parts = token.split("\\.");
        String altered = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).replace("\"actorId\":20", "\"actorId\":21");
        reject(() -> verifier.verify(parts[0] + '.' + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(altered.getBytes(StandardCharsets.UTF_8)) + '.' + parts[2], "SEARCH", digest), ErrorCode.FORBIDDEN);
    }

    @Test
    void rejectsWrongPurposeIssuerAudienceProviderAndExtraOrMissingClaimsEvenWhenSigned() {
        for (var mutation : Map.<String, Object>of("purpose", "APPPROVAL_FORM_TENANT_PEOPLE_V1",
                "iss", "dwp-gateway", "aud", List.of(ApprovalFormUserSourceProofVerifier.AUDIENCE),
                "accessMode", "PROVIDER_SUPPORT", "referencePurpose", "CREATE", "schemaSha256", "A".repeat(64)).entrySet()) {
            var claims = claims("SEARCH", digest); claims.put(mutation.getKey(), mutation.getValue());
            reject(() -> verifier.verify(sign(claims), "SEARCH", digest), ErrorCode.FORBIDDEN);
        }
        var extra = claims("SEARCH", digest); extra.put("permissions", List.of("VIEW"));
        reject(() -> verifier.verify(sign(extra), "SEARCH", digest), ErrorCode.FORBIDDEN);
        var missing = claims("SEARCH", digest); missing.remove("contextScopeKey");
        reject(() -> verifier.verify(sign(missing), "SEARCH", digest), ErrorCode.FORBIDDEN);
    }

    @Test
    void rejectsExpiredFutureAndOverlongWindowsAndNonCanonicalIds() {
        for (var mutation : Map.<String, Object>of("exp", NOW.getEpochSecond(), "iat", NOW.plusSeconds(1).getEpochSecond(),
                "nbf", NOW.minusSeconds(1).getEpochSecond(), "tenantId", 10.1, "jti", "not-a-uuid",
                "formId", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA").entrySet()) {
            var claims = claims("SEARCH", digest); claims.put(mutation.getKey(), mutation.getValue());
            reject(() -> verifier.verify(sign(claims), "SEARCH", digest), ErrorCode.FORBIDDEN);
        }
        var overlong = claims("SEARCH", digest); overlong.put("exp", NOW.plusSeconds(61).getEpochSecond());
        reject(() -> verifier.verify(sign(overlong), "SEARCH", digest), ErrorCode.FORBIDDEN);
    }

    @Test
    void configuredPublicKeyRotationIsExactAndNeverAcceptsPrivateOrMissingKeyConfiguration() {
        var next = key("approval-source-test-2");
        var rotated = new ApprovalFormUserSourceProofVerifier(MAPPER,
                new JWKSet(List.of(KEY.toPublicJWK(), next.toPublicJWK())).toString(), CLOCK);
        assertThat(rotated.verify(sign(claims("SEARCH", digest), next), "SEARCH", digest)).isNotNull();
        for (String config : List.of("", "{}", new JWKSet(KEY).toString(false),
                new JWKSet(List.of(KEY.toPublicJWK(), KEY.toPublicJWK())).toString())) {
            var unavailable = new ApprovalFormUserSourceProofVerifier(MAPPER, config, CLOCK);
            reject(() -> unavailable.verify(sign(claims("SEARCH", digest)), "SEARCH", digest), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        }
    }

    private void reject(Runnable task, ErrorCode expected) {
        assertThatThrownBy(task::run).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}

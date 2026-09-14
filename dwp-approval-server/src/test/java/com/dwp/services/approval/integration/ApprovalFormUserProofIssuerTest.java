package com.dwp.services.approval.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Authority;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.FormBinding;
import com.dwp.services.approval.security.ApprovalFormUserCurrentAuthority;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApprovalFormUserProofIssuerTest {
    static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    static final UUID PERSON = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    static RSAKey key;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll static void keys() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("forms-2026").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
    }

    @Test void finalGoldenDigestsMatchAuthWithoutWhitespaceOrOrderedIdSorting() {
        var issuer = issuer(key.toJSONString());
        assertThat(issuer.search(authority(90), "Kim", 2).requestDigest())
                .isEqualTo("28f9d9611c7498e02c7e5f86fa2a2cb1cd50074de0f2ea7c23dd0b1a08f1f331");
        assertThat(issuer.resolve(authority(90), List.of(PERSON)).requestDigest())
                .isEqualTo("5d317f655012c2911444d91e451bc4b68c7577f38dc4b2bc8bcf994be073430e");
        UUID other = UUID.randomUUID();
        assertThat(issuer.resolve(authority(90), List.of(PERSON, other)).requestDigest())
                .isNotEqualTo(issuer.resolve(authority(90), List.of(other, PERSON)).requestDigest());
    }

    @Test void signsActualRs256ExactTwentyClaimsAndThreeHeaderKeys() throws Exception {
        var proof = issuer(key.toJSONString()).search(authority(90), "Kim", 2);
        SignedJWT jwt = SignedJWT.parse(proof.token());
        assertThat(jwt.verify(new RSASSAVerifier(key.toPublicJWK()))).isTrue();
        assertThat(jwt.getHeader().toJSONObject().keySet()).containsExactlyInAnyOrder("alg", "typ", "kid");
        JsonNode claims = mapper.readTree(jwt.getPayload().toString());
        assertThat(claims.size()).isEqualTo(20);
        assertThat(claims.has("authRevision")).isFalse();
        assertThat(claims.has("policyRevision")).isFalse();
        assertThat(claims.has("fieldPath")).isFalse();
        assertThat(claims.get("aud").textValue()).isEqualTo(ApprovalFormUserProofIssuer.AUDIENCE);
        assertThat(claims.get("tenantId").isIntegralNumber()).isTrue();
        assertThat(claims.get("actorId").longValue()).isEqualTo(99);
        assertThat(claims.get("jti").textValue()).isEqualTo(proof.proofId().toString());
        assertThat(claims.get("exp").longValue()).isEqualTo(NOW.getEpochSecond() + 30);
        assertThat(claims.get("nbf").longValue()).isEqualTo(claims.get("iat").longValue());
    }

    @Test void clampsToOriginalOwnerExpiryAndNewAttemptAlwaysGetsNewJti() throws Exception {
        var issuer = issuer(key.toJSONString());
        var original = authority(12);
        var first = issuer.search(original, "Kim", 2);
        var second = issuer.search(original, "Kim", 2);
        assertThat(first.proofId()).isNotEqualTo(second.proofId());
        assertThat(first.requestDigest()).isEqualTo(second.requestDigest());
        assertThat(SignedJWT.parse(first.token()).getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(NOW.plusSeconds(12));
        assertThat(original.validUntil().toInstant()).isEqualTo(NOW.plusSeconds(12));
    }

    @Test void adminPreviewBindsItsOwnExactRoutePurpose() throws Exception {
        var old = authority(90);
        var admin = new Authority(old.form(), old.sourcePolicyKey(), old.contextKey(), old.contextScopeKey(),
                old.decisionRevision(), ApprovalFormUserCurrentAuthority.ADMIN_ROUTE, old.validUntil(), "ELEVATED", "FORM_PREVIEW");
        var jwt = SignedJWT.parse(issuer(key.toJSONString()).resolve(admin, List.of(PERSON)).token());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("routeContractKey")).isEqualTo(ApprovalFormUserCurrentAuthority.ADMIN_ROUTE);
        assertThat(jwt.getJWTClaimsSet().getStringClaim("referencePurpose")).isEqualTo("FORM_PREVIEW");
    }

    @ParameterizedTest @ValueSource(strings = {"", "{}", "not-json"})
    void missingOrInvalidKeyIs503OnlyWhenSourceUsed(String value) {
        assertThatThrownBy(() -> issuer(value).search(authority(90), "Kim", 2)).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    @Test void publicOnlyKeyAndExpiredAuthorityCannotIssueProof() {
        assertThatThrownBy(() -> issuer(key.toPublicJWK().toJSONString()).search(authority(90), "Kim", 2)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> issuer(key.toJSONString()).search(authority(0), "Kim", 2)).isInstanceOf(BaseException.class);
        var old = authority(90);
        var alias = new Authority(old.form(), old.sourcePolicyKey(), old.contextKey(), old.contextScopeKey(), old.decisionRevision(),
                "approval.form-user-candidates.read", old.validUntil());
        assertThatThrownBy(() -> issuer(key.toJSONString()).search(alias, "Kim", 2)).isInstanceOf(BaseException.class);
    }

    @Test void validatesClosedRequestBoundsBeforeIssuing() {
        var issuer = issuer(key.toJSONString());
        for (String query : List.of("", "K", " Kim", "Kim ", "K\nm", "K".repeat(101))) {
            assertThatThrownBy(() -> issuer.search(authority(90), query, 2)).isInstanceOf(BaseException.class);
        }
        assertThatThrownBy(() -> issuer.search(authority(90), "Kim", 31)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> issuer.resolve(authority(90), List.of(PERSON, PERSON))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> issuer.resolve(authority(90), List.of())).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> issuer.requestDigest("SEARCH", Map.of("url", "https://example.invalid"))).isInstanceOf(BaseException.class);
    }

    static Authority authority(int remainingSeconds) {
        return new Authority(new FormBinding(42, 99, UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"), "a".repeat(64)),
                ApprovalFormUserDirectory.SOURCE_POLICY, "ctx-exact", "scope-exact", "psr-" + "b".repeat(64),
                ApprovalFormUserCurrentAuthority.WORK_ROUTE, OffsetDateTime.ofInstant(NOW.plusSeconds(remainingSeconds), ZoneOffset.UTC));
    }
    private ApprovalFormUserProofIssuer issuer(String json) {
        return new ApprovalFormUserProofIssuer(mapper, json, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}

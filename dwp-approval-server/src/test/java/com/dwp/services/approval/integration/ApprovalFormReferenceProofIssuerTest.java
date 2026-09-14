package com.dwp.services.approval.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalFormReferenceDirectory.MutationPins;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Authority;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApprovalFormReferenceProofIssuerTest {
    private static RSAKey key;
    private final UUID target = UUID.fromString("33333333-3333-3333-3333-333333333333");
    @BeforeAll static void keys() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("reference-2026").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
    }

    @Test void realSignatureSealsExact26ClaimsActualActionAndFinalDigest() throws Exception {
        var authority = authority("request-create.action");
        var pins = pins("POST", "/v1/requests", 0);
        var proof = issuer().resolve(authority, pins, List.of(ApprovalFormUserProofIssuerTest.PERSON));
        var jwt = SignedJWT.parse(proof.token());
        assertThat(jwt.verify(new RSASSAVerifier(key.toPublicJWK()))).isTrue();
        var json = new ObjectMapper().readTree(jwt.getPayload().toString());
        assertThat(json.size()).isEqualTo(26);
        assertThat(json.get("purpose").textValue()).isEqualTo("APPROVAL_FORM_REFERENCE_RESOLVE_V1");
        assertThat(json.get("iss").textValue()).isEqualTo(ApprovalFormReferenceDirectory.ISSUER);
        assertThat(json.get("aud").textValue()).isEqualTo(ApprovalFormReferenceDirectory.AUDIENCE);
        assertThat(json.get("targetRequestId").textValue()).isEqualTo(target.toString());
        assertThat(json.get("targetRequestVersion").longValue()).isZero();
        assertThat(json.get("mutationPayloadSha256").textValue()).isEqualTo("c".repeat(64));
        assertThat(json.get("routeContractKey").textValue()).isEqualTo(authority.routeContractKey());
        assertThat(json.get("routeContractKey").textValue()).doesNotContain("field-candidates");
        assertThat(proof.requestDigest()).isEqualTo("5d317f655012c2911444d91e451bc4b68c7577f38dc4b2bc8bcf994be073430e");
        assertThat(json.get("exp").longValue()).isEqualTo(ApprovalFormUserProofIssuerTest.NOW.getEpochSecond() + 12);
    }

    @ParameterizedTest @ValueSource(strings = {"request-draft-update.action", "request-submit.action", "request-information-response.action", "request-draft-recover.action"})
    void legitimateExistingVersionZeroUsesItsOwnCanonicalPath(String route) {
        String suffix = switch (route) { case "request-draft-update.action" -> "/draft"; case "request-submit.action" -> "/submit";
            case "request-information-response.action" -> "/information-response"; default -> "/draft/recover"; };
        var proof = issuer().resolve(authority(route), pins(route.equals("request-draft-update.action") ? "PUT" : "POST",
                "/v1/requests/" + target + suffix, 0), List.of(ApprovalFormUserProofIssuerTest.PERSON));
        assertThat(proof.token()).isNotBlank();
    }

    @Test void candidateProfileOrUnlistedActionAndCrossTargetMethodPathCannotMintReference() {
        assertThatThrownBy(() -> issuer().resolve(ApprovalFormUserProofIssuerTest.authority(12), pins("POST", "/v1/requests", 0),
                List.of(ApprovalFormUserProofIssuerTest.PERSON))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> issuer().resolve(authority("request-withdraw.action"), pins("POST", "/v1/requests", 0),
                List.of(ApprovalFormUserProofIssuerTest.PERSON))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> issuer().resolve(authority("request-submit.action"), pins("POST", "/v1/requests/" + UUID.randomUUID() + "/submit", 0),
                List.of(ApprovalFormUserProofIssuerTest.PERSON))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> issuer().resolve(authority("request-create.action"), pins("PUT", "/v1/requests", 0),
                List.of(ApprovalFormUserProofIssuerTest.PERSON))).isInstanceOf(BaseException.class);
    }

    @Test void missingPinsUnsafeVersionAndCreatedNonzeroVersionAreRejected() {
        assertThatThrownBy(() -> pins("POST", "/v1/requests", 9007199254740992L)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> new MutationPins(target, 0, "c".repeat(64), "", "POST", "/v1/requests")).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> issuer().resolve(authority("request-create.action"), pins("POST", "/v1/requests", 1),
                List.of(ApprovalFormUserProofIssuerTest.PERSON))).isInstanceOf(BaseException.class);
    }

    @Test void fabricatedFixtureRecoverAliasCannotMintAnActionReferenceProof() {
        assertThatThrownBy(() -> issuer().resolve(authority("drafts.recover.action"),
                pins("POST", "/v1/requests/" + target + "/draft/recover", 0), List.of(ApprovalFormUserProofIssuerTest.PERSON)))
                .isInstanceOf(BaseException.class);
    }

    @Test void actionProfilesConsumeTheImmutableV7RouteAndServiceBindings() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        String relative = "contracts/product-authorization/product-surfaces-v1.bundle-v7.json";
        while (root != null && !Files.isRegularFile(root.resolve(relative))) root = root.getParent();
        assertThat(root).as("actual immutable v7 bundle, not a fixture alias").isNotNull();
        var bundle = new ObjectMapper().readTree(root.resolve(relative).toFile());
        assertThat(bundle.path("version").intValue()).isEqualTo(7);
        Set<String> actions = Set.of("request-create.action", "request-draft-update.action", "request-submit.action",
                "request-information-response.action", "request-draft-recover.action");
        int matched = 0;
        for (var route : bundle.path("routes")) {
            String key = route.path("routeContractKey").textValue();
            assertThat(key).isNotEqualTo("route.approvals.work.drafts.recover.action");
            if (key == null || !key.startsWith("route.approvals.work.")) continue;
            String suffix = key.substring("route.approvals.work.".length());
            if (!actions.contains(suffix)) continue;
            assertThat(route.path("routeKind").textValue()).isEqualTo("ACTION");
            var bindings = route.path("servicePepBindings");
            assertThat(bindings.size()).isEqualTo(1);
            var binding = bindings.get(0);
            assertThat(binding.path("serviceKey").textValue()).isEqualTo("approval");
            String method = binding.path("method").textValue();
            String path = binding.path("path").textValue().replace("{requestId}", target.toString());
            var proof = issuer().resolve(authority(suffix), pins(method, path, 0),
                    List.of(ApprovalFormUserProofIssuerTest.PERSON));
            assertThat(SignedJWT.parse(proof.token()).getJWTClaimsSet().getStringClaim("routeContractKey")).isEqualTo(key);
            matched++;
        }
        assertThat(matched).isEqualTo(5);
    }

    @Test void missingPrivateKeyIsFailClosedAndEachAttemptGetsDifferentJti() {
        var authority = authority("request-create.action");
        var pins = pins("POST", "/v1/requests", 0);
        assertThatThrownBy(() -> new ApprovalFormReferenceProofIssuer(new ObjectMapper(), "").resolve(authority, pins,
                List.of(ApprovalFormUserProofIssuerTest.PERSON))).isInstanceOf(BaseException.class);
        assertThat(issuer().resolve(authority, pins, List.of(ApprovalFormUserProofIssuerTest.PERSON)).proofId())
                .isNotEqualTo(issuer().resolve(authority, pins, List.of(ApprovalFormUserProofIssuerTest.PERSON)).proofId());
    }

    private Authority authority(String route) {
        var old = ApprovalFormUserProofIssuerTest.authority(12);
        return new Authority(old.form(), old.sourcePolicyKey(), old.contextKey(), old.contextScopeKey(), old.decisionRevision(),
                "route.approvals.work." + route, old.validUntil(), "NORMAL", "MUTATION_REFERENCE");
    }
    private MutationPins pins(String method, String path, long version) { return new MutationPins(target, version, "c".repeat(64), "original-key", method, path); }
    private ApprovalFormReferenceProofIssuer issuer() {
        return new ApprovalFormReferenceProofIssuer(new ObjectMapper(), key.toJSONString(), Clock.fixed(ApprovalFormUserProofIssuerTest.NOW, ZoneOffset.UTC));
    }
}

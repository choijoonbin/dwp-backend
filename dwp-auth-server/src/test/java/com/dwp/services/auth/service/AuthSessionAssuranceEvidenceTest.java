package com.dwp.services.auth.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthSessionAssuranceEvidenceTest {

    @Test
    void exactAcrFreshnessAlsoRequiresCanonicalMfaEvidence() {
        Instant now = Instant.parse("2026-08-24T05:10:00Z");
        AuthSessionService.AssuranceEvidence webauthn =
                new AuthSessionService.AssuranceEvidence(
                        "OIDC_STEP_UP", now.minusSeconds(30), "urn:dwp:acr:mfa",
                        List.of("mfa", "webauthn"));
        AuthSessionService.AssuranceEvidence uncanonicalizedWebauthn =
                new AuthSessionService.AssuranceEvidence(
                        "OIDC_STEP_UP", now.minusSeconds(30), "urn:dwp:acr:mfa",
                        List.of("webauthn"));
        AuthSessionService.AssuranceEvidence normalLoginLiteralMfa =
                new AuthSessionService.AssuranceEvidence(
                        "OIDC", now.minusSeconds(30), "urn:dwp:acr:mfa",
                        List.of("mfa"));
        AuthSessionService.AssuranceEvidence localPassword =
                new AuthSessionService.AssuranceEvidence(
                        "LOCAL", now.minusSeconds(30), "urn:dwp:acr:password",
                        List.of("pwd"));
        AuthSessionService.AssuranceEvidence emptyAmr =
                new AuthSessionService.AssuranceEvidence(
                        "OIDC_STEP_UP", now.minusSeconds(30), "urn:dwp:acr:mfa", List.of());

        assertThat(webauthn.freshAt(now, "urn:dwp:acr:mfa", 600)).isTrue();
        assertThat(uncanonicalizedWebauthn.freshAt(now, "urn:dwp:acr:mfa", 600)).isFalse();
        assertThat(normalLoginLiteralMfa.freshAt(now, "urn:dwp:acr:mfa", 600)).isFalse();
        assertThat(localPassword.freshAt(now, "urn:dwp:acr:mfa", 600)).isFalse();
        assertThat(emptyAmr.freshAt(now, "urn:dwp:acr:mfa", 600)).isFalse();
    }
}

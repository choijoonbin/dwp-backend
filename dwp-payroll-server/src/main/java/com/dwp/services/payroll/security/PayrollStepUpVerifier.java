package com.dwp.services.payroll.security;

import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@ConditionalOnProperty(
        name = "dwp.hris.payroll.calculation.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollStepUpVerifier {

    private final ProductSurfaceStepUpChallengeVerifier delegate;

    PayrollStepUpVerifier(
            ObjectMapper mapper,
            @Value("${dwp.payroll.step-up.public-key-pem:}") String publicKeyPem,
            @Value("${dwp.payroll.step-up.issuer:}") String issuer,
            @Value("${dwp.payroll.step-up.audience:dwp-payroll-server}") String audience,
            @Value("${dwp.payroll.step-up.key-id:}") String keyId,
            @Value("${dwp.payroll.step-up.required-acr:urn:dwp:acr:mfa}") String requiredAcr,
            @Value("${dwp.payroll.step-up.maximum-authentication-age-seconds:600}")
            long maximumAuthenticationAgeSeconds,
            @Value("${dwp.payroll.step-up.maximum-challenge-ttl-seconds:900}")
            long maximumChallengeTtlSeconds) {
        this(mapper, Clock.systemUTC(), publicKeyPem, issuer, audience, keyId,
                requiredAcr, maximumAuthenticationAgeSeconds, maximumChallengeTtlSeconds);
    }

    PayrollStepUpVerifier(
            ObjectMapper mapper,
            Clock clock,
            String publicKeyPem,
            String issuer,
            String audience,
            String keyId,
            String requiredAcr,
            long maximumAuthenticationAgeSeconds,
            long maximumChallengeTtlSeconds) {
        this.delegate = new ProductSurfaceStepUpChallengeVerifier(
                mapper, clock,
                ProductSurfaceStepUpChallengeVerifier.parsePublicKey(publicKeyPem),
                new ProductSurfaceStepUpChallengeVerifier.Policy(
                        "payroll", issuer, audience, keyId, requiredAcr,
                        maximumAuthenticationAgeSeconds, maximumChallengeTtlSeconds));
    }

    ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge verify(
            String challenge,
            ProductSurfaceStepUpChallengeVerifier.CommandBinding binding) {
        return delegate.verify(challenge, binding);
    }

    String payloadSha256(Object payload) {
        return delegate.payloadSha256(payload);
    }
}

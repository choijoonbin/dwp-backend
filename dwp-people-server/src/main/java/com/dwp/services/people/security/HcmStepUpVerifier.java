package com.dwp.services.people.security;

import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** People policy wrapper around the shared command-bound challenge verifier. */
@Component
public final class HcmStepUpVerifier {

    private static final String OWNER_SERVICE_KEY = "people";

    private final ProductSurfaceStepUpChallengeVerifier delegate;

    @org.springframework.beans.factory.annotation.Autowired
    public HcmStepUpVerifier(
            ObjectMapper objectMapper,
            @Value("${dwp.people.step-up.public-key-pem:}") String publicKeyPem,
            @Value("${dwp.people.step-up.issuer:}") String issuer,
            @Value("${dwp.people.step-up.audience:dwp-people-server}") String audience,
            @Value("${dwp.people.step-up.key-id:}") String keyId,
            @Value("${dwp.people.step-up.required-acr:urn:dwp:acr:mfa}") String requiredAcr,
            @Value("${dwp.people.step-up.maximum-authentication-age-seconds:600}")
            long maximumAuthenticationAgeSeconds,
            @Value("${dwp.people.step-up.maximum-challenge-ttl-seconds:900}")
            long maximumChallengeTtlSeconds) {
        this(objectMapper, Clock.systemUTC(), publicKeyPem, issuer, audience, keyId,
                requiredAcr, maximumAuthenticationAgeSeconds, maximumChallengeTtlSeconds);
    }

    HcmStepUpVerifier(
            ObjectMapper objectMapper,
            Clock clock,
            String publicKeyPem,
            String issuer,
            String audience,
            String keyId,
            String requiredAcr,
            long maximumAuthenticationAgeSeconds,
            long maximumChallengeTtlSeconds) {
        delegate = new ProductSurfaceStepUpChallengeVerifier(
                objectMapper, clock,
                ProductSurfaceStepUpChallengeVerifier.parsePublicKey(publicKeyPem),
                new ProductSurfaceStepUpChallengeVerifier.Policy(
                        OWNER_SERVICE_KEY, issuer, audience, keyId, requiredAcr,
                        maximumAuthenticationAgeSeconds, maximumChallengeTtlSeconds));
    }

    ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge verify(
            String token,
            ProductSurfaceStepUpChallengeVerifier.CommandBinding binding) {
        return delegate.verify(token, binding);
    }

    String payloadSha256(Object payload) {
        return delegate.payloadSha256(payload);
    }
}

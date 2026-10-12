package com.dwp.services.time.clockingestion;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockAction;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockStepUpHeaders;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VerifiedClockRequest;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VoidClockCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** TIM policy wrapper for Auth-signed, command-bound RS256 step-up challenges. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.clock-ingestion.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class AuthSignedClockStepUpVerifier implements ClockStepUpVerifier {

    static final String OWNER_SERVICE_KEY = "time";
    static final String AUDIENCE = "dwp-time-server";
    static final String ROUTE_CONTRACT_KEY =
            "route.hcm.operations.time-clock-event-void.action";
    static final String ACTIVATION_POLICY = "STEPUP-MGMT-HIGH-V1";
    static final String CAPABILITY_CONTRACT_KEY = "hcm.operations.time.update";
    static final String TARGET_TYPE = "CLOCK_EVENT";
    static final String COMMAND_METHOD = "POST";
    static final String PUBLIC_PATH_PREFIX = "/api/time/v1/clock-events/";

    private final ProductSurfaceStepUpChallengeVerifier delegate;

    @Autowired
    AuthSignedClockStepUpVerifier(
            ObjectMapper objectMapper,
            @Value("${dwp.time.step-up.public-key-pem:}") String publicKeyPem,
            @Value("${dwp.time.step-up.issuer:}") String issuer,
            @Value("${dwp.time.step-up.audience:dwp-time-server}") String audience,
            @Value("${dwp.time.step-up.key-id:}") String keyId,
            @Value("${dwp.time.step-up.required-acr:urn:dwp:acr:mfa}") String requiredAcr,
            @Value("${dwp.time.step-up.maximum-authentication-age-seconds:600}")
            long maximumAuthenticationAgeSeconds,
            @Value("${dwp.time.step-up.maximum-challenge-ttl-seconds:900}")
            long maximumChallengeTtlSeconds) {
        this(objectMapper, Clock.systemUTC(), publicKeyPem, issuer, audience, keyId,
                requiredAcr, maximumAuthenticationAgeSeconds, maximumChallengeTtlSeconds);
    }

    AuthSignedClockStepUpVerifier(
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
                Objects.requireNonNull(objectMapper, "objectMapper must not be null"),
                Objects.requireNonNull(clock, "clock must not be null"),
                ProductSurfaceStepUpChallengeVerifier.parsePublicKey(publicKeyPem),
                new ProductSurfaceStepUpChallengeVerifier.Policy(
                        OWNER_SERVICE_KEY, issuer, audience, keyId, requiredAcr,
                        maximumAuthenticationAgeSeconds, maximumChallengeTtlSeconds));
    }

    @Override
    public ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge verifyVoid(
            VerifiedClockRequest authority,
            UUID eventId,
            long eventVersion,
            UUID idempotencyKey,
            VoidClockCommand command,
            ClockStepUpHeaders headers) {
        if (authority == null
                || eventId == null
                || idempotencyKey == null
                || command == null
                || eventVersion <= 0
                || !authority.stepUpSatisfied()
                || !authority.actions().contains(ClockAction.CORRECT)
                || !ROUTE_CONTRACT_KEY.equals(authority.routeContractKey())
                || authority.contextKey() == null
                || !authority.contextKey().matches("psc-[0-9a-f]{64}")
                || !HcmEligibilityScopeKey.isCanonical(authority.gatewayScopeKey())) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Exact TIM corrective-void authority is unavailable.");
        }
        if (headers == null
                || headers.challenge() == null
                || headers.challenge().isBlank()
                || headers.decisionRevision() == null
                || headers.decisionRevision().isBlank()
                || headers.expectedObjectVersion() == null) {
            throw new BaseException(
                    ErrorCode.STEP_UP_REQUIRED,
                    "Command-bound TIM step-up headers are required.");
        }
        if (headers.challenge().length() > 16_384
                || headers.decisionRevision().length() > 200
                || headers.expectedObjectVersion() != eventVersion) {
            throw new BaseException(
                    ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "TIM step-up headers do not match the current clock event version.");
        }
        if (!authority.decisionRevision().equals(headers.decisionRevision())) {
            throw new BaseException(
                    ErrorCode.DECISION_REVISION_CONFLICT,
                    "TIM authority changed after challenge issuance.");
        }

        String publicPath = PUBLIC_PATH_PREFIX + eventId + "/voids";
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding =
                new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                        authority.actorId(),
                        authority.tenantId(),
                        ROUTE_CONTRACT_KEY,
                        authority.contextKey(),
                        ACTIVATION_POLICY,
                        CAPABILITY_CONTRACT_KEY,
                        authority.gatewayScopeKey(),
                        TARGET_TYPE,
                        eventId.toString(),
                        eventVersion,
                        COMMAND_METHOD,
                        publicPath,
                        idempotencyKey.toString(),
                        delegate.payloadSha256(command),
                        authority.decisionRevision());
        return delegate.verify(headers.challenge(), binding);
    }
}

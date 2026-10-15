package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;

/** Exact HCM Platform authority plus Auth-signed command-bound step-up verification. */
@Component
public final class NavigationPolicyStepUpEvidenceVerifier
        implements StepUpEvidenceVerifier {

    private static final String OWNER = "platform";
    private static final String AUDIENCE = "dwp-platform-server";
    private static final String CAPABILITY =
            "hcm.configuration.navigation-policy.publish";
    private static final String ACTIVATION_POLICY = "STEPUP-MGMT-HIGH-V1";
    private static final String TARGET_TYPE = "HRIS_NAVIGATION_POLICY_REVISION";
    private static final Map<String, String> ROUTES = Map.of(
            "APPROVE", "route.hcm.configuration.navigation-policy-revision-approve.action",
            "PUBLISH", "route.hcm.configuration.navigation-policy-revision-publish.action",
            "RESTORE", "route.hcm.configuration.navigation-policy-revision-restore.action");
    private static final Map<String, String> SERVICE_PATHS = Map.of(
            "APPROVE", "/v1/admin/hris/navigation-policies/revisions/{revisionId}/approve",
            "PUBLISH", "/v1/admin/hris/navigation-policies/revisions/{revisionId}/publish",
            "RESTORE", "/v1/admin/hris/navigation-policies/revisions/{revisionId}/restore");
    private static final Map<String, String> PUBLIC_PATHS = Map.of(
            "APPROVE", "/api/platform/v1/admin/hris/navigation-policies/revisions/{revisionId}/approve",
            "PUBLISH", "/api/platform/v1/admin/hris/navigation-policies/revisions/{revisionId}/publish",
            "RESTORE", "/api/platform/v1/admin/hris/navigation-policies/revisions/{revisionId}/restore");

    private final ProductSurfaceStepUpChallengeVerifier verifier;
    private final NavigationPolicyStepUpReplayRepository replay;

    @Autowired
    public NavigationPolicyStepUpEvidenceVerifier(
            ObjectMapper objectMapper,
            NavigationPolicyStepUpReplayRepository replay,
            @Value("${dwp.platform.step-up.public-key-pem:}") String publicKeyPem,
            @Value("${dwp.platform.step-up.issuer:}") String issuer,
            @Value("${dwp.platform.step-up.audience:dwp-platform-server}") String audience,
            @Value("${dwp.platform.step-up.key-id:}") String keyId,
            @Value("${dwp.platform.step-up.required-acr:urn:dwp:acr:mfa}") String requiredAcr,
            @Value("${dwp.platform.step-up.maximum-authentication-age-seconds:600}")
            long maximumAuthenticationAgeSeconds,
            @Value("${dwp.platform.step-up.maximum-challenge-ttl-seconds:900}")
            long maximumChallengeTtlSeconds) {
        this(objectMapper, replay, Clock.systemUTC(), publicKeyPem, issuer, audience, keyId,
                requiredAcr, maximumAuthenticationAgeSeconds,
                maximumChallengeTtlSeconds);
    }

    NavigationPolicyStepUpEvidenceVerifier(
            ObjectMapper objectMapper,
            NavigationPolicyStepUpReplayRepository replay,
            Clock clock,
            String publicKeyPem,
            String issuer,
            String audience,
            String keyId,
            String requiredAcr,
            long maximumAuthenticationAgeSeconds,
            long maximumChallengeTtlSeconds) {
        this.replay = replay;
        this.verifier = new ProductSurfaceStepUpChallengeVerifier(
                objectMapper, clock,
                ProductSurfaceStepUpChallengeVerifier.parsePublicKey(publicKeyPem),
                new ProductSurfaceStepUpChallengeVerifier.Policy(
                        OWNER, issuer, audience, keyId, requiredAcr,
                        maximumAuthenticationAgeSeconds,
                        maximumChallengeTtlSeconds));
    }

    @Override
    public boolean verify(StepUpEvidence evidence) {
        HcmPlatformPepRegistry.RouteAuthority authority = requireAuthority(evidence);
        String serviceTemplate = SERVICE_PATHS.get(evidence.action());
        String servicePath = serviceTemplate.replace(
                "{revisionId}", evidence.revisionId().toString());
        String publicPath = PUBLIC_PATHS.get(evidence.action()).replace(
                "{revisionId}", evidence.revisionId().toString());
        if (!"POST".equals(evidence.commandMethod())
                || !servicePath.equals(evidence.commandPath())) {
            throw mismatch("NAV policy step-up request path mismatch.");
        }
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding =
                new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                        evidence.actorUserId(), evidence.tenantId(),
                        authority.routeContractKey(), evidence.contextKey(),
                        authority.activationPolicy(), authority.capabilityContractKey(),
                        evidence.contextScopeKey(), TARGET_TYPE,
                        evidence.revisionId().toString(), evidence.expectedObjectVersion(),
                        authority.method(), publicPath, evidence.idempotencyKey(),
                        verifier.payloadSha256(evidence.canonicalPayload()),
                        evidence.decisionRevision());
        ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge verified =
                verifier.verify(evidence.proof(), binding);
        replay.consume(verified);
        return true;
    }

    private HcmPlatformPepRegistry.RouteAuthority requireAuthority(
            StepUpEvidence evidence) {
        if (evidence == null || evidence.action() == null
                || evidence.revisionId() == null || evidence.actorUserId() < 1
                || evidence.tenantId() < 1 || evidence.expectedObjectVersion() < 0
                || blank(evidence.contextKey()) || blank(evidence.contextScopeKey())
                || blank(evidence.idempotencyKey()) || blank(evidence.decisionRevision())) {
            throw mismatch("NAV policy step-up command evidence is incomplete.");
        }
        HcmPlatformPepRegistry.RouteAuthority authority = evidence.requestAuthority();
        HcmPlatformPepRegistry.StepUpCommandBinding stepUp =
                authority == null ? null : authority.stepUpCommandBinding();
        String expectedRoute = ROUTES.get(evidence.action());
        String expectedPath = SERVICE_PATHS.get(evidence.action());
        if (authority == null || expectedRoute == null || expectedPath == null
                || !authority.highRisk()
                || !"ACTION".equals(authority.routeKind())
                || !expectedRoute.equals(authority.routeContractKey())
                || !"POST".equals(authority.method())
                || !expectedPath.equals(authority.servicePath())
                || !CAPABILITY.equals(authority.capabilityContractKey())
                || !ACTIVATION_POLICY.equals(authority.activationPolicy())
                || stepUp == null
                || !TARGET_TYPE.equals(stepUp.targetType())
                || !"revisionId".equals(stepUp.targetIdPathParameter())
                || !"COMMAND_BODY".equals(stepUp.expectedObjectVersionSource())
                || !"expectedVersion".equals(stepUp.expectedObjectVersionName())
                || !OWNER.equals(stepUp.ownerServiceKey())
                || !AUDIENCE.equals(stepUp.audience())) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Exact HCM Platform NAV policy command authority is required.");
        }
        return authority;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private BaseException mismatch(String message) {
        return new BaseException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH, message);
    }
}

package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.Hrm012OwnerReceiptAuthorityContract;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import com.dwp.services.auth.dto.Hrm012ReceiptFirstStepUpDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

/** Current-authority and target-bound step-up verification after People reserved a receipt. */
@Service
public final class Hrm012ReceiptFirstStepUpVerificationService {

    private static final String CAPABILITY = "hcm.integration.workforce-ingestion.execute";
    private static final String DUTY = "HRIS_DUTY_INTEGRATION_EXECUTOR";
    private static final String PURPOSE = "HRIS_INTEGRATION";

    private final ProductSurfaceAuthorityService authorityService;
    private final ProductSurfaceStepUpChallengeVerifier verifier;

    @Autowired
    public Hrm012ReceiptFirstStepUpVerificationService(
            ProductSurfaceAuthorityService authorityService,
            ObjectMapper mapper,
            @Value("${dwp.auth.step-up.public-key-pem:}") String publicKeyPem,
            @Value("${dwp.auth.step-up.issuer:}") String issuer,
            @Value("${dwp.auth.step-up.key-id:}") String keyId,
            @Value("${dwp.auth.step-up.required-acr:urn:dwp:acr:mfa}") String requiredAcr,
            @Value("${dwp.auth.step-up.maximum-authentication-age-seconds:600}") long maxAge,
            @Value("${dwp.auth.step-up.challenge-ttl-seconds:900}") long maxTtl) {
        this(authorityService, new ProductSurfaceStepUpChallengeVerifier(
                mapper, Clock.systemUTC(),
                ProductSurfaceStepUpChallengeVerifier.parsePublicKey(publicKeyPem),
                new ProductSurfaceStepUpChallengeVerifier.Policy(
                        "people", issuer, "dwp-people-server", keyId, requiredAcr,
                        maxAge, maxTtl)));
    }

    Hrm012ReceiptFirstStepUpVerificationService(
            ProductSurfaceAuthorityService authorityService,
            ProductSurfaceStepUpChallengeVerifier verifier) {
        this.authorityService = authorityService;
        this.verifier = verifier;
    }

    public Hrm012ReceiptFirstStepUpDtos.VerifyResponse verify(
            Hrm012ReceiptFirstStepUpDtos.VerifyRequest request) {
        long actorId = validateFixedEnvelope(request);
        ProductSurfaceAuthorityDtos.AuthorityResult preflight =
                authorityService.evaluateHrm012PreflightForPostReceipt(
                        new ProductSurfaceAuthorityDtos.EvaluateRequest(
                                request.tenantId(), actorId, "hcm", "hcm.operations",
                                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                                Hrm012OwnerReceiptAuthorityContract.ROUTE,
                                null, null, null, null, List.of(),
                                request.correlationId(), sha256(request.idempotencyKey()),
                                request.opaqueStepUpChallengeRef(), "POST",
                                Hrm012OwnerReceiptAuthorityContract.SERVICE_PATH));
        requireExactPreflight(preflight);
        ProductSurfaceAuthorityDtos.AuthorityResult authority = authorityService.evaluate(
                new ProductSurfaceAuthorityDtos.EvaluateRequest(
                        request.tenantId(), actorId, "hcm", "hcm.operations",
                        ProductSurfaceAuthorityDtos.AccessMode.ELEVATED,
                        Hrm012OwnerReceiptAuthorityContract.ROUTE,
                        request.contextKey(), request.scopeRef(), null, null, List.of()));
        requireExactAuthority(authority, preflight, request);
        if (!PURPOSE.equals(request.purposeCode())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "HRM-012 fixed integration purpose is denied.");
        }
        String currentDecisionRevision = decisionRevision(
                request.tenantId(), actorId, authority.authRevision(), authority.policyRevision());
        if (!currentDecisionRevision.equals(request.decisionRevision())) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT);
        }
        long version = strongVersion(request.ifMatch());
        ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge challenge = verifier.verify(
                request.opaqueStepUpChallengeRef(),
                new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                        actorId, request.tenantId(), Hrm012OwnerReceiptAuthorityContract.ROUTE,
                        request.contextKey(), "STEPUP-MGMT-HIGH-V1", CAPABILITY,
                        request.scopeRef(), "WORKFORCE_INGESTION_OBJECT", request.objectRef(),
                        version, "POST", Hrm012OwnerReceiptAuthorityContract.PUBLIC_PATH,
                        request.idempotencyKey(), request.payloadSha256(),
                        request.decisionRevision()));
        return new Hrm012ReceiptFirstStepUpDtos.VerifyResponse(
                "ALLOWED", request.decisionRevision(), authority.authRevision(),
                preflight.tenantControlRevision(), challenge.challengeId(),
                OffsetDateTime.ofInstant(challenge.expiresAt(), ZoneOffset.UTC));
    }

    private long validateFixedEnvelope(Hrm012ReceiptFirstStepUpDtos.VerifyRequest request) {
        if (!Hrm012OwnerReceiptAuthorityContract.ROUTE.equals(request.routeContractKey())
                || !"POST".equals(request.commandMethod())
                || !Hrm012OwnerReceiptAuthorityContract.PUBLIC_PATH.equals(
                        request.publicCommandPath())
                || !Hrm012OwnerReceiptAuthorityContract.SERVICE_PATH.equals(
                        request.serviceCommandPath())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "HRM-012 receipt-first verification envelope is invalid.");
        }
        try {
            long actor = Long.parseLong(request.onBehalfOfSubject());
            if (actor <= 0 || !Long.toString(actor).equals(request.onBehalfOfSubject())) {
                throw new NumberFormatException();
            }
            return actor;
        } catch (NumberFormatException exception) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "HRM-012 on-behalf-of subject is invalid.");
        }
    }

    private void requireExactPreflight(ProductSurfaceAuthorityDtos.AuthorityResult value) {
        if (value == null
                || value.decision()
                        != ProductSurfaceAuthorityDtos.Decision.OWNER_RECEIPT_AUTH_REQUIRED
                || value.accessMode() != ProductSurfaceAuthorityDtos.AccessMode.NORMAL
                || value.accessSource() != ProductSurfaceAuthorityDtos.AccessSource.ENTITLEMENT
                || !java.util.Set.of("APP.HCM", "APP.HRIS")
                        .contains(value.appResourceKey())
                || value.effectiveGrants() == null || !value.effectiveGrants().isEmpty()
                || value.scopes() == null || !value.scopes().isEmpty()
                || value.tenantControlRevision() == null
                || value.tenantControlRevision().isBlank()) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Current HRM-012 app and feature authority is denied.");
        }
    }

    private void requireExactAuthority(
            ProductSurfaceAuthorityDtos.AuthorityResult authority,
            ProductSurfaceAuthorityDtos.AuthorityResult preflight,
            Hrm012ReceiptFirstStepUpDtos.VerifyRequest request) {
        boolean exactGrant = authority != null
                && authority.decision() == ProductSurfaceAuthorityDtos.Decision.ALLOWED
                && authority.accessMode() == ProductSurfaceAuthorityDtos.AccessMode.ELEVATED
                && preflight.tenantControlRevision().equals(
                        authority.tenantControlRevision())
                && request.contextKey().equals(authority.contextKey())
                && authority.scopes().stream().anyMatch(scope ->
                        request.scopeRef().equals(scope.key()))
                && authority.effectiveGrants().stream().anyMatch(grant ->
                        grant instanceof ProductSurfaceAuthorityDtos.CapabilityGrant capability
                        && CAPABILITY.equals(capability.capabilityContractKey())
                        && capability.responsibility() != null
                        && DUTY.equals(capability.responsibility().code())
                        && capability.scopeKeys().contains(request.scopeRef())
                        && !capability.readOnly());
        if (!exactGrant) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Current HRM-012 capability and duty evidence is denied.");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "HRM-012 idempotency digest is unavailable.", exception);
        }
    }

    private long strongVersion(String ifMatch) {
        if (ifMatch == null || !ifMatch.matches("\"(?:0|[1-9][0-9]{0,15})\"")) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT);
        }
        long value = Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        if (value > 9_007_199_254_740_991L) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT);
        }
        return value;
    }

    private String decisionRevision(long tenantId, long actorId, String auth, String policy) {
        try {
            String material = String.join("\n", Long.toString(tenantId), Long.toString(actorId),
                    "NORMAL", auth, policy, "", "", "");
            return "psr-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

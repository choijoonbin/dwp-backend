package com.dwp.services.auth.service;

import com.dwp.core.security.Hrm012OwnerReceiptAuthorityContract;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import com.dwp.services.auth.dto.Hrm012ReceiptFirstStepUpDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Hrm012ReceiptFirstStepUpVerificationServiceTest {

    @Test
    void evaluatesAppAndFeatureBeforeCapabilityDutyPurposeAndStepUp() throws Exception {
        ProductSurfaceAuthorityService authority = mock(ProductSurfaceAuthorityService.class);
        ProductSurfaceStepUpChallengeVerifier verifier =
                mock(ProductSurfaceStepUpChallengeVerifier.class);
        var service = new Hrm012ReceiptFirstStepUpVerificationService(authority, verifier);
        String revision = revision("auth-57", "policy-57");
        var request = request(revision);
        when(authority.evaluateHrm012PreflightForPostReceipt(any()))
                .thenReturn(preflight());
        when(authority.evaluate(any())).thenReturn(allowed());
        when(verifier.verify(anyString(), any())).thenReturn(
                new ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge(
                        "challenge-1", "nonce-1", "issuer", null,
                        Instant.parse("2026-10-22T02:00:00Z")));

        var result = service.verify(request);

        assertThat(result.decision()).isEqualTo("ALLOWED");
        assertThat(result.authorizationRevision()).isEqualTo(revision);
        assertThat(result.dutyEvidenceRevision()).isEqualTo("auth-57");
        assertThat(result.featureDecisionRevision()).isEqualTo("tenant-control-57");
        InOrder order = inOrder(authority, verifier);
        order.verify(authority).evaluateHrm012PreflightForPostReceipt(any());
        order.verify(authority).evaluate(any());
        order.verify(verifier).verify(anyString(), any());
        ArgumentCaptor<ProductSurfaceAuthorityDtos.EvaluateRequest> finalEvaluation =
                ArgumentCaptor.forClass(ProductSurfaceAuthorityDtos.EvaluateRequest.class);
        org.mockito.Mockito.verify(authority).evaluate(finalEvaluation.capture());
        assertThat(finalEvaluation.getValue().activeAccessMode())
                .isEqualTo(ProductSurfaceAuthorityDtos.AccessMode.ELEVATED);
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult preflight() {
        return new ProductSurfaceAuthorityDtos.AuthorityResult(
                ProductSurfaceAuthorityDtos.Decision.OWNER_RECEIPT_AUTH_REQUIRED,
                Hrm012OwnerReceiptAuthorityContract.STATE,
                "auth-57", "policy-57", "tenant-control-57", null,
                "hcm", "hcm.operations", "work",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                ProductSurfaceAuthorityDtos.AccessSource.ENTITLEMENT,
                "APP.HCM", List.of(), List.of(), null, true, false,
                null, null, null, null, OffsetDateTime.now().plusMinutes(1), null, null);
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult allowed() {
        var grant = new ProductSurfaceAuthorityDtos.CapabilityGrant(
                "hcm.integration.workforce-ingestion.execute",
                "ACTION.HRIS_INTEGRATION_EXECUTE:EXECUTE",
                ProductSurfaceAuthorityDtos.CapabilityAuthorityMode
                        .PERMISSION_AND_RELATIONSHIP,
                List.of("predicate.hcm-workforce-ingestion-scope.v1"),
                ProductSurfaceAuthorityDtos.ResponsibilityRequirement.REQUIRED,
                new ProductSurfaceAuthorityDtos.Responsibility(
                        "HRIS_DUTY_INTEGRATION_EXECUTOR", "RS_CONNECTORS"),
                List.of("scope-1"), true, false,
                ProductSurfaceAuthorityDtos.ActivationState.ACTIVE, null);
        var scope = new ProductSurfaceAuthorityDtos.EffectiveScope(
                "scope-1", "RESOURCE_SET", "Assigned scope", true, false, null);
        return new ProductSurfaceAuthorityDtos.AuthorityResult(
                ProductSurfaceAuthorityDtos.Decision.ALLOWED, null,
                "auth-57", "policy-57", "tenant-control-57", "context-1",
                "hcm", "hcm.operations", "work",
                ProductSurfaceAuthorityDtos.AccessMode.ELEVATED,
                ProductSurfaceAuthorityDtos.AccessSource.ENTITLEMENT,
                "APP.HCM", List.of(grant), List.of(scope), "route-grant-1",
                false, true, null, null, null, null,
                OffsetDateTime.now().plusMinutes(1), "evidence-1", null);
    }

    private Hrm012ReceiptFirstStepUpDtos.VerifyRequest request(String revision) {
        return new Hrm012ReceiptFirstStepUpDtos.VerifyRequest(
                7L, "11", Hrm012OwnerReceiptAuthorityContract.ROUTE, "POST",
                Hrm012OwnerReceiptAuthorityContract.PUBLIC_PATH,
                Hrm012OwnerReceiptAuthorityContract.SERVICE_PATH,
                "connector-1", "\"3\"", "HRIS_INTEGRATION", "challenge-token",
                "idempotency-1", "context-1", "scope-1", "a".repeat(64),
                revision, "receipt-1", "b".repeat(64), "correlation-1");
    }

    private String revision(String auth, String policy) throws Exception {
        String material = String.join("\n", "7", "11", "NORMAL", auth, policy, "", "", "");
        return "psr-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8)));
    }
}

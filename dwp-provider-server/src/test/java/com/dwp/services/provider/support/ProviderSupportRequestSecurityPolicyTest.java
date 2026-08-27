package com.dwp.services.provider.support;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.security.ProviderRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderSupportRequestSecurityPolicyTest {

    private static final UUID TARGET_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");

    private final ProviderAuditService auditService = mock(ProviderAuditService.class);
    private final ProviderSupportRequestSecurityPolicy policy =
            new ProviderSupportRequestSecurityPolicy(auditService);
    private final ProviderRequestContext.Actor supportActor = new ProviderRequestContext.Actor(
            12L, 120L, 1L, "Support operator", Set.of("PROVIDER_SUPPORT"),
            Set.of("SUPPORT_SESSION_WRITE"), UUID.randomUUID());

    @Test
    void activationUsesTheSameOpaqueDenialForMissingAndUnauthorizedTargets() {
        when(auditService.opaqueReference(TARGET_ID.toString())).thenReturn("sha256:opaque");
        ProviderSupportRequestRepository.SupportAccessRequestRecord anotherOwnersRequest =
                requestRecord(99L);

        Throwable missing = catchThrowable(() -> policy.requireActivationTarget(
                Optional.empty(), TARGET_ID, supportActor, "corr"));
        Throwable unauthorized = catchThrowable(() -> policy.requireActivationTarget(
                Optional.of(anotherOwnersRequest), TARGET_ID, supportActor, "corr"));

        assertSameClosedResponse(missing, unauthorized);
        verify(auditService, times(2)).denied(
                "provider.support-access.activation-denied", "SUPPORT_ACCESS_REQUEST",
                "sha256:opaque", null, null, "corr",
                java.util.Map.of("reasonCode", "TARGET_UNAVAILABLE_OR_UNAUTHORIZED"));
    }

    @Test
    void cancellationUsesTheSameOpaqueDenialForMissingAndUnauthorizedTargets() {
        when(auditService.opaqueReference(TARGET_ID.toString())).thenReturn("sha256:opaque");

        Throwable missing = catchThrowable(() -> policy.requireCancellationTarget(
                Optional.empty(), TARGET_ID, supportActor, "corr"));
        Throwable unauthorized = catchThrowable(() -> policy.requireCancellationTarget(
                Optional.of(requestRecord(99L)), TARGET_ID, supportActor, "corr"));

        assertSameClosedResponse(missing, unauthorized);
        verify(auditService, times(2)).denied(
                "provider.support-access.cancel-denied", "SUPPORT_ACCESS_REQUEST",
                "sha256:opaque", null, null, "corr",
                java.util.Map.of("reasonCode", "TARGET_UNAVAILABLE_OR_UNAUTHORIZED"));
    }

    @Test
    void revocationUsesTheSameOpaqueDenialForMissingAndUnauthorizedTargets() {
        when(auditService.opaqueReference(TARGET_ID.toString())).thenReturn("sha256:opaque");
        Instant expiresAt = Instant.now().plusSeconds(300);
        ProviderSupportSessionRepository.SupportSessionRecord anotherOwnersSession =
                new ProviderSupportSessionRepository.SupportSessionRecord(
                        TARGET_ID, TENANT_ID, 99L, "ACTIVE", "hash", "STANDARD",
                        expiresAt, Instant.now(), expiresAt, 3, UUID.randomUUID());

        Throwable missing = catchThrowable(() -> policy.requireRevocationTarget(
                Optional.empty(), TARGET_ID, supportActor, "corr"));
        Throwable unauthorized = catchThrowable(() -> policy.requireRevocationTarget(
                Optional.of(anotherOwnersSession), TARGET_ID, supportActor, "corr"));

        assertSameClosedResponse(missing, unauthorized);
        verify(auditService, times(2)).denied(
                "provider.support-session.revoke-denied", "SUPPORT_SESSION",
                "sha256:opaque", null, null, "corr",
                java.util.Map.of("reasonCode", "TARGET_UNAVAILABLE_OR_UNAUTHORIZED"));
    }

    private ProviderSupportRequestRepository.SupportAccessRequestRecord requestRecord(Long ownerId) {
        return new ProviderSupportRequestRepository.SupportAccessRequestRecord(
                TARGET_ID, TENANT_ID, ownerId, UUID.randomUUID(), "APPROVED", "STANDARD",
                "Bounded support purpose", List.of("TENANT_EXPERIENCE_PREVIEW"), 15,
                "CUSTOMER-APPROVAL", true, "L1", "request-key", "f".repeat(64),
                Instant.now().plusSeconds(3600), 88L, null, "NOT_REQUIRED", 1);
    }

    private void assertSameClosedResponse(Throwable missing, Throwable unauthorized) {
        assertThat(missing).isInstanceOf(BaseException.class);
        assertThat(unauthorized).isInstanceOf(BaseException.class);
        BaseException missingError = (BaseException) missing;
        BaseException unauthorizedError = (BaseException) unauthorized;
        assertThat(missingError.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(unauthorizedError.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(unauthorizedError.getMessage()).isEqualTo(missingError.getMessage());
    }
}

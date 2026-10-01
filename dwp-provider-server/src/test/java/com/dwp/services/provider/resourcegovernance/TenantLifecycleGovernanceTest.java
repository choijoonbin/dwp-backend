package com.dwp.services.provider.resourcegovernance;

import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.governance.DataPolicyRepository;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateTenantLifecycleRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.VersionedReasonRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.TenantLifecycleRequestRow;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantLifecycleGovernanceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ResourceGovernanceRepository repository = mock(ResourceGovernanceRepository.class);
    private final DataPolicyRepository dataPolicyRepository = mock(DataPolicyRepository.class);
    private final ProviderAuditService audit = mock(ProviderAuditService.class);
    private final TenantLifecycleGovernance governance = new TenantLifecycleGovernance(
            repository,
            dataPolicyRepository,
            audit,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setContext() {
        ProviderRequestContext.setForTest(7L, 1L);
    }

    @AfterEach
    void clearContext() {
        ProviderRequestContext.clear();
    }

    @Test
    void futureGlobalLegalHoldDoesNotBlockLifecycleCreation() {
        assertLifecycleCreationForHoldWindow(
                NOW.plusSeconds(1), null,
                "DRAFT", "OWNER_VERIFICATION_REQUIRED", List.of());
    }

    @Test
    void globalLegalHoldBlocksAtEffectiveFromBoundary() {
        UUID policyId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        String evidenceRef = "data-policy://" + policyId + "/revisions/" + revisionId;

        assertLifecycleCreationForHoldWindow(
                policyId, revisionId, NOW, NOW.plusSeconds(60),
                "BLOCKED_BY_HOLD", "ACTIVE_GLOBAL_LEGAL_HOLD", List.of(evidenceRef));
    }

    @Test
    void globalLegalHoldDoesNotBlockAtEffectiveToBoundary() {
        assertLifecycleCreationForHoldWindow(
                NOW.minusSeconds(60), NOW,
                "DRAFT", "OWNER_VERIFICATION_REQUIRED", List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "BLOCKED_BY_HOLD", "PENDING_APPROVAL"})
    void requestOwnerCanCancelEveryEligiblePreDecisionState(String lifecycleState) {
        UUID requestId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        TenantLifecycleRequestRow current = lifecycleRequest(
                requestId, tenantId, lifecycleState, 7L, 4L, null);
        TenantLifecycleRequestRow cancelled = lifecycleRequest(
                requestId, tenantId, "CANCELLED", 7L, 5L, "No longer required");
        when(repository.lockLifecycleRequest(requestId)).thenReturn(java.util.Optional.of(current));
        when(repository.cancelLifecycleRequest(
                requestId, 4L, 7L, "No longer required")).thenReturn(true);
        when(repository.lifecycleRequest(requestId)).thenReturn(java.util.Optional.of(cancelled));

        var result = governance.cancelLifecycleRequest(
                requestId,
                new VersionedReasonRequest(4L, "  No longer required  "),
                "cancel-owner-request");

        assertThat(result.lifecycleState()).isEqualTo("CANCELLED");
        assertThat(result.executionState()).isEqualTo("NOT_REQUIRED");
        assertThat(result.decisionReason()).isEqualTo("No longer required");
        assertThat(result.version()).isEqualTo(5L);
        verify(repository).cancelLifecycleRequest(
                requestId, 4L, 7L, "No longer required");
    }

    @Test
    void nonOwnerCannotCancelLifecycleRequest() {
        UUID requestId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(repository.lockLifecycleRequest(requestId)).thenReturn(java.util.Optional.of(
                lifecycleRequest(requestId, tenantId, "DRAFT", 7L, 0L, null)));
        ProviderRequestContext.setForTest(8L, 1L);

        assertThatThrownBy(() -> governance.cancelLifecycleRequest(
                requestId,
                new VersionedReasonRequest(0L, "Not my request"),
                "cancel-non-owner"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("request owner");

        verify(repository, never()).cancelLifecycleRequest(any(), any(Long.class), any(), any());
    }

    @Test
    void ownerCannotCancelTerminalLifecycleRequest() {
        UUID requestId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(repository.lockLifecycleRequest(requestId)).thenReturn(java.util.Optional.of(
                lifecycleRequest(
                        requestId, tenantId, "APPROVED_FOR_HANDOFF", 7L, 2L,
                        "Approved")));

        assertThatThrownBy(() -> governance.cancelLifecycleRequest(
                requestId,
                new VersionedReasonRequest(2L, "Too late"),
                "cancel-terminal"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("pre-decision");

        verify(repository, never()).cancelLifecycleRequest(any(), any(Long.class), any(), any());
    }

    @Test
    void cancellationFailsClosedWhenVersionChanges() {
        UUID requestId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(repository.lockLifecycleRequest(requestId)).thenReturn(java.util.Optional.of(
                lifecycleRequest(requestId, tenantId, "DRAFT", 7L, 4L, null)));

        assertThatThrownBy(() -> governance.cancelLifecycleRequest(
                requestId,
                new VersionedReasonRequest(3L, "Stale cancellation"),
                "cancel-stale"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("changed");

        verify(repository).cancelLifecycleRequest(
                requestId, 3L, 7L, "Stale cancellation");
    }

    private void assertLifecycleCreationForHoldWindow(
            Instant effectiveFrom,
            Instant effectiveTo,
            String expectedLifecycleState,
            String expectedHoldState,
            List<String> expectedEvidence) {
        assertLifecycleCreationForHoldWindow(
                UUID.randomUUID(), UUID.randomUUID(), effectiveFrom, effectiveTo,
                expectedLifecycleState, expectedHoldState, expectedEvidence);
    }

    private void assertLifecycleCreationForHoldWindow(
            UUID policyId,
            UUID revisionId,
            Instant effectiveFrom,
            Instant effectiveTo,
            String expectedLifecycleState,
            String expectedHoldState,
            List<String> expectedEvidence) {
        UUID tenantId = UUID.randomUUID();
        CreateTenantLifecycleRequest request = new CreateTenantLifecycleRequest(
                "PURGE", "Contract ended");
        var holdRule = objectMapper.createObjectNode().put("active", true);
        when(repository.tenantExists(tenantId)).thenReturn(true);
        when(dataPolicyRepository.activePolicies("LEGAL_HOLD")).thenReturn(List.of(
                new DataPolicyRepository.ScopedActivePolicy(
                        policyId, "LEGAL_HOLD", "GLOBAL", null, revisionId,
                        holdRule, effectiveFrom, effectiveTo)));
        when(repository.createLifecycleRequest(
                any(UUID.class), eq(tenantId), eq(request),
                eq(expectedLifecycleState), eq(expectedHoldState),
                eq(expectedEvidence), eq(7L)))
                .thenAnswer(invocation -> lifecycleRequest(
                        invocation.getArgument(0), tenantId, request,
                        invocation.getArgument(3), invocation.getArgument(4),
                        invocation.getArgument(5)));

        var result = governance.createLifecycleRequest(tenantId, request, "hold-window");

        assertThat(result.lifecycleState()).isEqualTo(expectedLifecycleState);
        assertThat(result.holdEvaluationState()).isEqualTo(expectedHoldState);
        assertThat(result.holdEvidenceRefs()).isEqualTo(expectedEvidence);
        verify(repository).createLifecycleRequest(
                any(UUID.class), eq(tenantId), eq(request),
                eq(expectedLifecycleState), eq(expectedHoldState),
                eq(expectedEvidence), eq(7L));
    }

    private TenantLifecycleRequestRow lifecycleRequest(
            UUID requestId,
            UUID tenantId,
            CreateTenantLifecycleRequest request,
            String lifecycleState,
            String holdState,
            List<String> evidence) {
        return new TenantLifecycleRequestRow(
                requestId, tenantId, "tenant-a", "Tenant A", request.requestedAction(),
                lifecycleState, holdState, evidence, "OWNER_HANDOFF_REQUIRED",
                request.justification(), 7L, null, null, null, null, null,
                0L, NOW, NOW);
    }

    private TenantLifecycleRequestRow lifecycleRequest(
            UUID requestId,
            UUID tenantId,
            String lifecycleState,
            long requestedBy,
            long version,
            String decisionReason) {
        return new TenantLifecycleRequestRow(
                requestId, tenantId, "tenant-a", "Tenant A", "PURGE",
                lifecycleState, "OWNER_VERIFICATION_REQUIRED", List.of(),
                "CANCELLED".equals(lifecycleState) ? "NOT_REQUIRED" : "OWNER_HANDOFF_REQUIRED",
                "Contract ended", requestedBy,
                "PENDING_APPROVAL".equals(lifecycleState) ? requestedBy : null,
                null,
                "PENDING_APPROVAL".equals(lifecycleState) ? NOW : null,
                null, decisionReason, version, NOW, NOW);
    }
}

package com.dwp.services.provider.resourcegovernance;

import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.governance.DataPolicyRepository;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AppendArtifactEvidenceRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AppendLedgerEntryRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactReviewDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactRolloutDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateArtifactRolloutPlanRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateResourceCommitmentChangeRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ResourceCommitmentChangeDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.TenantLifecycleDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AssessArtifactCompatibilityRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.UpsertCommitmentRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.VersionedReasonRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.ArtifactRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.ArtifactEvidenceRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.ArtifactReviewRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.CommitmentRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.InternalEvidenceFreshnessRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.LedgerRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.LedgerTotalsRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.PlanRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.ResourceCommitmentChangeRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.TenantLifecycleRequestRow;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceGovernanceServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ResourceGovernanceRepository repository = mock(ResourceGovernanceRepository.class);
    private final DataPolicyRepository dataPolicyRepository = mock(DataPolicyRepository.class);
    private final ProviderAuditService audit = mock(ProviderAuditService.class);
    private final ResourceGovernanceService service =
            new ResourceGovernanceService(repository, dataPolicyRepository, audit);
    private static final Instant PERIOD_START = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2100-01-01T00:00:00Z");

    @BeforeEach
    void setContext() {
        ProviderRequestContext.set(actor(
                7L,
                Set.of(
                        "ESTATE_READ",
                        ResourceGovernanceService.RESOURCE_READ,
                        ResourceGovernanceService.RESOURCE_WRITE,
                        ResourceGovernanceService.RESOURCE_APPROVE,
                        ResourceGovernanceService.ARTIFACT_READ,
                        ResourceGovernanceService.ARTIFACT_WRITE,
                        ResourceGovernanceService.ARTIFACT_APPROVE,
                        ResourceGovernanceService.TENANT_LIFECYCLE_APPROVE)));
    }

    @AfterEach
    void clearContext() {
        ProviderRequestContext.clear();
    }

    @Test
    void allocationCannotExceedTheInternalCommittedQuota() {
        UUID tenantId = UUID.randomUUID();
        String resourceKey = "workspace.seats";
        when(repository.lockCommitment(tenantId, resourceKey)).thenReturn(Optional.of(
                commitment(tenantId, resourceKey, "ACTIVE", BigDecimal.TEN, null, null)));
        when(repository.ledgerByIdempotency(tenantId, "allocate-2")).thenReturn(Optional.empty());
        when(repository.ledgerTotals(tenantId, resourceKey, PERIOD_START, PERIOD_END))
                .thenReturn(new LedgerTotalsRow(
                BigDecimal.valueOf(9), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThatThrownBy(() -> service.appendLedger(
                tenantId,
                resourceKey,
                new AppendLedgerEntryRequest(
                        "ALLOCATE", BigDecimal.valueOf(2), "SEAT", null,
                        "internal://capacity-review/42", "allocate-2", "Increase pilot allocation",
                        Instant.parse("2026-09-29T00:00:00Z")),
                "resource-over-quota"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("exceed the committed quota");

        verify(repository, never()).appendLedger(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ledgerReportsWhenMoreRowsExistBeyondTheBoundedResponse() {
        UUID tenantId = UUID.randomUUID();
        String resourceKey = "workspace.seats";
        LedgerRow row = new LedgerRow(
                UUID.randomUUID(), tenantId, resourceKey, "METER", BigDecimal.ONE,
                "SEAT", null, "internal://meter/1", "meter-1", "Internal meter",
                Instant.now(), PERIOD_START, PERIOD_END, 7L, Instant.now());
        when(repository.commitment(tenantId, resourceKey)).thenReturn(Optional.of(
                commitment(tenantId, resourceKey, "ACTIVE", BigDecimal.TEN, null, null)));
        when(repository.ledger(tenantId, resourceKey, 251))
                .thenReturn(Collections.nCopies(251, row));

        var result = service.ledger(tenantId, resourceKey, 500);

        assertThat(result.items()).hasSize(250);
        assertThat(result.limit()).isEqualTo(250);
        assertThat(result.hasMore()).isTrue();
        verify(repository).ledger(tenantId, resourceKey, 251);
    }

    @Test
    void everyProviderGovernanceCollectionReportsItsBoundedCoverage() {
        UUID tenantId = UUID.randomUUID();
        CommitmentRow commitment = commitment(
                tenantId, "workspace.seats", "ACTIVE", BigDecimal.TEN, null, null);
        ResourceCommitmentChangeRow change = resourceChange(
                UUID.randomUUID(), 17L);
        TenantLifecycleRequestRow lifecycle = lifecycleRequest(
                UUID.randomUUID(), 18L, null);
        ArtifactRow artifact = typedArtifact(
                UUID.randomUUID(), "APPROVED", "COMPATIBLE");
        PlanRow plan = plan(UUID.randomUUID(), "APPROVED", 19L);
        ArtifactReviewRow review = new ArtifactReviewRow(
                UUID.randomUUID(), artifact.artifactId(), "APPROVED", "Reviewed",
                objectMapper.createObjectNode(), 20L, Instant.now());
        ArtifactEvidenceRow evidence = new ArtifactEvidenceRow(
                UUID.randomUUID(), plan.planId(), "PRE_FLIGHT", "PASSED",
                objectMapper.createObjectNode(), "INTERNAL", 21L, Instant.now());

        when(repository.commitments(tenantId, 101))
                .thenReturn(Collections.nCopies(101, commitment));
        when(repository.ledgerTotals(
                tenantId, commitment.resourceKey(), PERIOD_START, PERIOD_END))
                .thenReturn(new LedgerTotalsRow(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(repository.internalEvidenceFreshness(
                tenantId, commitment.resourceKey(), PERIOD_START, PERIOD_END))
                .thenReturn(new InternalEvidenceFreshnessRow(0, null, null));
        when(repository.resourceChanges(tenantId, 101))
                .thenReturn(Collections.nCopies(101, change));
        when(repository.lifecycleRequests(tenantId, 101))
                .thenReturn(Collections.nCopies(101, lifecycle));
        when(repository.artifacts(101)).thenReturn(Collections.nCopies(101, artifact));
        when(repository.reviews(artifact.artifactId(), 51))
                .thenReturn(Collections.nCopies(51, review));
        when(repository.plans(101)).thenReturn(Collections.nCopies(101, plan));
        when(repository.evidence(plan.planId(), 51))
                .thenReturn(Collections.nCopies(51, evidence));
        when(repository.readinessEvidence(plan.planId())).thenReturn(List.of(evidence));

        var commitments = service.commitments(tenantId);
        var changes = service.resourceChanges(tenantId);
        var lifecycles = service.lifecycleRequests(tenantId);
        var artifacts = service.artifacts();
        var plans = service.plans();

        assertThat(commitments.items()).hasSize(100);
        assertThat(commitments.hasMore()).isTrue();
        assertThat(changes.items()).hasSize(100);
        assertThat(changes.hasMore()).isTrue();
        assertThat(lifecycles.items()).hasSize(100);
        assertThat(lifecycles.hasMore()).isTrue();
        assertThat(artifacts.items()).hasSize(100);
        assertThat(artifacts.hasMore()).isTrue();
        assertThat(artifacts.items().getFirst().reviews()).hasSize(50);
        assertThat(artifacts.items().getFirst().reviewsHasMore()).isTrue();
        assertThat(plans.items()).hasSize(100);
        assertThat(plans.hasMore()).isTrue();
        assertThat(plans.items().getFirst().evidence()).hasSize(50);
        assertThat(plans.items().getFirst().evidenceHasMore()).isTrue();
    }

    @Test
    void resourceWriterCannotSelectATenantWithoutEstateReadPermission() {
        ProviderRequestContext.set(actor(7L, Set.of(ResourceGovernanceService.RESOURCE_WRITE)));

        assertThatThrownBy(() -> service.upsertCommitment(
                UUID.randomUUID(),
                "workspace.seats",
                new UpsertCommitmentRequest(
                        "SEAT", BigDecimal.TEN, null, null,
                        PERIOD_START, PERIOD_END, "HARD_BLOCK", "ACTIVE", null),
                "tenant-selection"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("ESTATE_READ");

        verify(repository, never()).lockCommitment(any(), any());
    }

    @Test
    void resourceChangeRequesterCannotApproveTheirOwnProposal() {
        ProviderRequestContext.set(actor(1_000L, ProviderRequestContext.require().permissions()));
        UUID changeId = UUID.randomUUID();
        ResourceCommitmentChangeRow row = resourceChange(changeId, 1_000L);
        when(repository.lockResourceChange(changeId)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.decideResourceChange(
                changeId,
                new ResourceCommitmentChangeDecisionRequest(
                        0L, "APPROVED", "Independent commercial review"),
                "resource-self-approval"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("cannot independently decide");

        verify(repository, never()).decideResourceChange(any(), any(), anyLong());
    }

    @Test
    void temporaryResourceOverrideMustHaveABoundedTtl() {
        UUID tenantId = UUID.randomUUID();
        CommitmentRow baseline = commitment(
                tenantId, "workspace.seats", "ACTIVE", BigDecimal.TEN, null, null);
        when(repository.tenantExists(tenantId)).thenReturn(true);
        when(repository.hasActiveTemporaryOverride(tenantId, "workspace.seats")).thenReturn(false);
        when(repository.lockCommitment(tenantId, "workspace.seats"))
                .thenReturn(Optional.of(baseline));

        assertThatThrownBy(() -> service.createResourceChange(
                tenantId,
                "workspace.seats",
                new CreateResourceCommitmentChangeRequest(
                        "TEMPORARY_OVERRIDE", 0L,
                        new UpsertCommitmentRequest(
                                "SEAT", BigDecimal.valueOf(20), null, null,
                                PERIOD_START, PERIOD_END, "SOFT_ALERT", "ACTIVE", 0L),
                        null, Instant.now().plusSeconds(31L * 24L * 60L * 60L),
                        "override-too-long", "Temporary capacity review"),
                "override-too-long"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("between 15 minutes and 30 days");

        verify(repository, never()).createResourceChange(
                any(), any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void artifactAuthorCannotApproveTheirOwnManifest() {
        ProviderRequestContext.set(actor(1_001L, ProviderRequestContext.require().permissions()));
        UUID artifactId = UUID.randomUUID();
        when(repository.lockArtifact(artifactId)).thenReturn(Optional.of(
                artifact(artifactId, 1_001L, "REVIEW_REQUIRED", "COMPATIBLE")));

        assertThatThrownBy(() -> service.decideArtifact(
                artifactId,
                new ArtifactReviewDecisionRequest(
                        0L, "APPROVED", "Need independent review",
                        objectMapper.createObjectNode().put("check", "manual")),
                "self-review"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("cannot independently review");

        verify(repository, never()).decideArtifact(any(), any(), any());
    }

    @Test
    void rolloutPlanRequesterCannotApproveTheirOwnPlan() {
        ProviderRequestContext.set(actor(1_002L, ProviderRequestContext.require().permissions()));
        UUID planId = UUID.randomUUID();
        when(repository.lockPlan(planId)).thenReturn(Optional.of(plan(planId, "PENDING_APPROVAL", 1_002L)));

        assertThatThrownBy(() -> service.decidePlan(
                planId,
                new ArtifactRolloutDecisionRequest(0L, "APPROVED", "Independent approval required"),
                "self-plan-approval"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("cannot independently decide");

        verify(repository, never()).decidePlan(any(), anyLong(), any(), any(), any());
    }

    @Test
    void lifecycleSubmitterCannotApproveTheirOwnRequestOutsideTheLongCache() {
        ProviderRequestContext.set(actor(1_003L, ProviderRequestContext.require().permissions()));
        UUID requestId = UUID.randomUUID();
        when(repository.lockLifecycleRequest(requestId)).thenReturn(Optional.of(
                lifecycleRequest(requestId, 999L, Long.valueOf(1_003L))));

        assertThatThrownBy(() -> service.decideLifecycleRequest(
                requestId,
                new TenantLifecycleDecisionRequest(0L, "APPROVED", "Independent approval required"),
                "lifecycle-self-approval"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("cannot independently decide");

        verify(repository, never()).decideLifecycleRequest(any(), any(), anyLong());
    }

    @Test
    void budgetEvidenceUsesCurrencyMinorRatherThanTheCapacityUnit() {
        UUID tenantId = UUID.randomUUID();
        String resourceKey = "workspace.seats";
        when(repository.lockCommitment(tenantId, resourceKey)).thenReturn(Optional.of(
                commitment(tenantId, resourceKey, "ACTIVE", BigDecimal.TEN,
                        BigDecimal.valueOf(10_000), "USD")));
        when(repository.ledgerByIdempotency(tenantId, "budget-seat-unit")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.appendLedger(
                tenantId,
                resourceKey,
                new AppendLedgerEntryRequest(
                        "BUDGET_RESERVE", BigDecimal.valueOf(500), "SEAT", "USD",
                        "internal://budget-review/42", "budget-seat-unit", "Reserve internal budget",
                        Instant.parse("2026-09-29T00:00:00Z")),
                "budget-unit"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("CURRENCY_MINOR");

        verify(repository, never()).appendLedger(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rolloutObservationCannotClaimDeploymentWhenExecutorIsUnavailable() {
        UUID planId = UUID.randomUUID();
        when(repository.lockPlan(planId)).thenReturn(Optional.of(plan(planId, "READY", 9L)));

        assertThatThrownBy(() -> service.appendPlanEvidence(
                planId,
                new AppendArtifactEvidenceRequest(
                        "OBSERVATION", "PASSED",
                        objectMapper.createObjectNode().put("deployment", "claimed")),
                "no-executor"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("Deployment observation cannot be asserted");

        verify(repository, never()).appendEvidence(any(), any(), any(), any());
    }

    @Test
    void rolloutPlanCannotBeCreatedFromAnUnapprovedArtifact() throws Exception {
        UUID artifactId = UUID.randomUUID();
        when(repository.lockArtifact(artifactId)).thenReturn(Optional.of(
                artifact(artifactId, 19L, "DRAFT", "COMPATIBLE")));

        assertThatThrownBy(() -> service.createPlan(
                new CreateArtifactRolloutPlanRequest(
                        artifactId,
                        "September controlled release",
                        objectMapper.readTree("""
                                {"environmentKey":"production","tenantKeys":["pilot-a"],
                                 "cohortKeys":[],"targetPercentage":10}
                                """),
                        objectMapper.readTree("""
                                [{"stageKey":"pilot","targetPercentage":10,
                                  "minimumObservationMinutes":30,"approvalGate":true}]
                                """),
                        objectMapper.readTree("""
                                {"strategy":"TRAFFIC_REVERT","targetVersion":"1.0.0",
                                 "dataHandling":"PRESERVE_CURRENT_SCHEMA",
                                 "validationChecks":["health"],"manualSteps":[]}
                                """),
                        "DECLARED",
                        "Prepare a controlled internal plan"),
                "unapproved-artifact"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("independently approved compatible artifact");

        verify(repository, never()).createPlan(any(), any(), any());
    }

    @Test
    void softAlertRecordsOverLimitEvidenceWithoutPretendingItWasBlocked() {
        UUID tenantId = UUID.randomUUID();
        String resourceKey = "workspace.seats";
        CommitmentRow commitment = commitment(
                tenantId, resourceKey, "ACTIVE", BigDecimal.TEN, null, null, "SOFT_ALERT");
        AppendLedgerEntryRequest request = new AppendLedgerEntryRequest(
                "ALLOCATE", BigDecimal.valueOf(2), "SEAT", null,
                "internal://capacity-review/soft", "soft-overage", "Record reviewed overage",
                Instant.parse("2026-09-29T00:00:00Z"));
        LedgerRow saved = new LedgerRow(
                UUID.randomUUID(), tenantId, resourceKey, "ALLOCATE", BigDecimal.valueOf(2),
                "SEAT", null, request.evidenceRef(), request.idempotencyKey(), request.reason(),
                request.occurredAt(), PERIOD_START, PERIOD_END, 7L, Instant.now());
        when(repository.lockCommitment(tenantId, resourceKey)).thenReturn(Optional.of(commitment));
        when(repository.ledgerByIdempotency(tenantId, request.idempotencyKey()))
                .thenReturn(Optional.empty(), Optional.of(saved));
        when(repository.ledgerTotals(tenantId, resourceKey, PERIOD_START, PERIOD_END))
                .thenReturn(new LedgerTotalsRow(
                        BigDecimal.valueOf(9), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(repository.appendLedger(any(), any(), any(), any(), any(), any())).thenReturn(true);

        var result = service.appendLedger(tenantId, resourceKey, request, "soft-overage");

        assertThat(result.ledgerEntryId()).isEqualTo(saved.ledgerEntryId());
        verify(repository).appendLedger(any(), any(), any(), any(), any(), any());
    }

    @Test
    void compatibilityDecisionMustMatchTypedEvidence() throws Exception {
        UUID artifactId = UUID.randomUUID();
        ArtifactRow artifact = typedArtifact(artifactId, "DRAFT", "NOT_EVALUATED");
        when(repository.artifact(artifactId)).thenReturn(Optional.of(artifact));
        var evidence = objectMapper.readTree("""
                {
                  "schema": {
                    "state": "PASSED",
                    "currentVersion": "2.4.2",
                    "targetVersion": "2.5.0",
                    "migrationState": "ADDITIVE_ONLY"
                  },
                  "clients": [{
                    "clientType": "WEB", "minimumVersion": "2.4", "state": "PASSED"
                  }],
                  "dependencies": [{
                    "dependencyKey": "idp.adapter", "requiredVersion": "2.1",
                    "observedVersion": "2.3.4", "state": "PASSED"
                  }],
                  "capabilities": {"added": [], "removed": [], "increased": []},
                  "rollbackReadiness": {
                    "state": "READY", "reasons": [], "executionBoundary": "INTERNAL_PLAN_ONLY"
                  }
                }
                """);

        assertThatThrownBy(() -> service.assessCompatibility(
                artifactId,
                new AssessArtifactCompatibilityRequest(
                        0L, "BLOCKED", evidence, "Typed review completed"),
                "typed-compatibility"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("must match the typed compatibility evidence: COMPATIBLE");

        verify(repository, never()).assessCompatibility(any(), any(), any());
    }

    @Test
    void compatibilityEvidenceCannotDriftFromTheDeclaredClientContract() throws Exception {
        UUID artifactId = UUID.randomUUID();
        ArtifactRow artifact = typedArtifact(artifactId, "DRAFT", "NOT_EVALUATED");
        when(repository.artifact(artifactId)).thenReturn(Optional.of(artifact));
        var evidence = objectMapper.readTree("""
                {
                  "schema": {
                    "state": "PASSED",
                    "currentVersion": "2.4.2",
                    "targetVersion": "2.5.0",
                    "migrationState": "ADDITIVE_ONLY"
                  },
                  "clients": [{
                    "clientType": "WEB", "minimumVersion": "1.0", "state": "PASSED"
                  }],
                  "dependencies": [{
                    "dependencyKey": "idp.adapter", "requiredVersion": "2.1",
                    "observedVersion": "2.3.4", "state": "PASSED"
                  }],
                  "capabilities": {"added": [], "removed": [], "increased": []},
                  "rollbackReadiness": {
                    "state": "READY", "reasons": [], "executionBoundary": "INTERNAL_PLAN_ONLY"
                  }
                }
                """);

        assertThatThrownBy(() -> service.assessCompatibility(
                artifactId,
                new AssessArtifactCompatibilityRequest(
                        0L, "COMPATIBLE", evidence, "Typed review completed"),
                "typed-client-drift"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("changed the declared client version");

        verify(repository, never()).assessCompatibility(any(), any(), any());
    }

    @Test
    void activeGlobalLegalHoldBlocksLifecycleSubmission() {
        UUID requestId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        TenantLifecycleRequestRow row = new TenantLifecycleRequestRow(
                requestId, tenantId, "tenant-a", "Tenant A", "PURGE", "DRAFT",
                "OWNER_VERIFICATION_REQUIRED", List.of(), "OWNER_HANDOFF_REQUIRED",
                "Contract ended", 7L, 7L, null, Instant.now(), null, null,
                0L, Instant.now(), Instant.now());
        when(repository.lockLifecycleRequest(requestId)).thenReturn(Optional.of(row));
        when(dataPolicyRepository.activePolicies("LEGAL_HOLD")).thenReturn(List.of(
                new DataPolicyRepository.ScopedActivePolicy(
                        UUID.randomUUID(), "LEGAL_HOLD", "GLOBAL", null, UUID.randomUUID(),
                        objectMapper.createObjectNode().put("active", true), null, null)));
        when(repository.refreshLifecycleHold(
                any(), anyLong(), any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.submitLifecycleRequest(
                requestId, new VersionedReasonRequest(0L, "Ready for independent review"),
                "global-hold"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("active global legal hold");

        verify(repository, never()).submitLifecycleRequest(any(), anyLong(), anyLong());
    }

    @Test
    void internallyReadyPlanRequiresPassedPreflightEvidence() {
        UUID planId = UUID.randomUUID();
        PlanRow plan = plan(planId, "APPROVED", 9L);
        ArtifactRow artifact = typedArtifact(plan.artifactId(), "APPROVED", "COMPATIBLE");
        when(repository.lockPlan(planId)).thenReturn(Optional.of(plan));
        when(repository.lockArtifact(plan.artifactId())).thenReturn(Optional.of(artifact));
        when(repository.readinessEvidence(planId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.markPlanReady(
                planId, new VersionedReasonRequest(0L, "Ready"), "missing-preflight"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("pre-flight evidence");

        verify(repository, never()).markPlanReady(any(), anyLong());
    }

    private ProviderRequestContext.Actor actor(long operatorId, Set<String> permissions) {
        return new ProviderRequestContext.Actor(
                operatorId,
                900001L,
                1L,
                "Provider governance operator",
                Set.of("PROVIDER_ADMIN"),
                permissions);
    }

    private CommitmentRow commitment(
            UUID tenantId,
            String resourceKey,
            String lifecycle,
            BigDecimal quota,
            BigDecimal budget,
            String currency) {
        return commitment(tenantId, resourceKey, lifecycle, quota, budget, currency, "HARD_BLOCK");
    }

    private CommitmentRow commitment(
            UUID tenantId,
            String resourceKey,
            String lifecycle,
            BigDecimal quota,
            BigDecimal budget,
            String currency,
            String controlMode) {
        return new CommitmentRow(
                tenantId, "tenant-a", "Tenant A", resourceKey, "SEAT", quota, budget, currency,
                lifecycle, "INTERNAL_CONTROL_PLANE", "UNAVAILABLE", controlMode,
                "INTERNAL_LEDGER_ONLY", PERIOD_START, PERIOD_END, null, null, 0L, Instant.now());
    }

    private ResourceCommitmentChangeRow resourceChange(UUID changeId, long requesterId) {
        var definition = objectMapper.createObjectNode()
                .put("unit", "SEAT")
                .put("quotaLimit", 10)
                .putNull("budgetLimit")
                .putNull("currencyCode")
                .put("controlPeriodStartsAt", PERIOD_START.toString())
                .put("controlPeriodEndsAt", PERIOD_END.toString())
                .put("controlMode", "HARD_BLOCK")
                .put("lifecycleState", "ACTIVE");
        return new ResourceCommitmentChangeRow(
                changeId, UUID.randomUUID(), "tenant-a", "Tenant A", "workspace.seats",
                "CONTRACT_CHANGE", 0L, definition, definition.deepCopy(), UUID.randomUUID(), null,
                "PENDING_APPROVAL", "UNAVAILABLE", "Contract-backed quota change",
                Instant.now().plusSeconds(3600), requesterId, Instant.now(), null, null, null,
                null, null, 0L, Instant.now());
    }

    private ArtifactRow typedArtifact(
            UUID artifactId,
            String lifecycleState,
            String compatibilityState) throws RuntimeException {
        try {
            return new ArtifactRow(
                    artifactId,
                    "workspace.shell",
                    "2.5.0",
                    "WEB_APP",
                    1,
                    objectMapper.createObjectNode().put("entrypoint", "internal"),
                    objectMapper.readTree("""
                            {
                              "schema": {
                                "currentVersion": "2.4.2", "targetVersion": "2.5.0",
                                "migrationState": "ADDITIVE_ONLY"
                              },
                              "clients": [{"clientType": "WEB", "minimumVersion": "2.4"}],
                              "dependencies": [{
                                "dependencyKey": "idp.adapter", "requiredVersion": "2.1"
                              }],
                              "capabilities": {
                                "allowAdded": true, "allowRemoved": false, "allowIncreased": false
                              },
                              "rollback": {"required": true, "strategy": "TRAFFIC_REVERT"}
                            }
                            """),
                    null,
                    lifecycleState,
                    compatibilityState,
                    objectMapper.createObjectNode(),
                    "UNAVAILABLE",
                    "UNAVAILABLE",
                    19L,
                    19L,
                    Instant.now(),
                    Instant.now(),
                    0L);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ArtifactRow artifact(
            UUID artifactId,
            long createdBy,
            String lifecycleState,
            String compatibilityState) {
        return new ArtifactRow(
                artifactId,
                "workspace.shell",
                "2026.09.29",
                "WEB_APP",
                1,
                objectMapper.createObjectNode().put("name", "shell"),
                objectMapper.createObjectNode().put("minimum", "2026.09"),
                null,
                lifecycleState,
                compatibilityState,
                objectMapper.createObjectNode(),
                "UNAVAILABLE",
                "UNAVAILABLE",
                createdBy,
                createdBy,
                Instant.now(),
                Instant.now(),
                0L);
    }

    private PlanRow plan(UUID planId, String lifecycleState, long requestedBy) {
        return new PlanRow(
                planId,
                UUID.randomUUID(),
                "workspace.shell",
                "2026.09.29",
                "September release",
                objectMapper.createObjectNode(),
                objectMapper.createArrayNode().add(objectMapper.createObjectNode()),
                null,
                "UNAVAILABLE",
                lifecycleState,
                "UNAVAILABLE",
                "Internal preparation",
                requestedBy,
                null,
                null,
                null,
                null,
                0L,
                Instant.now(),
                Instant.now());
    }

    private TenantLifecycleRequestRow lifecycleRequest(
            UUID requestId,
            long requestedBy,
            Long submittedBy) {
        return new TenantLifecycleRequestRow(
                requestId,
                UUID.randomUUID(),
                "tenant-a",
                "Tenant A",
                "RETIRE",
                "PENDING_APPROVAL",
                "CLEAR",
                List.of(),
                "UNAVAILABLE",
                "Retire the tenant after independent review",
                requestedBy,
                submittedBy,
                null,
                Instant.now(),
                null,
                null,
                0L,
                Instant.now(),
                Instant.now());
    }
}

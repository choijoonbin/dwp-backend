package com.dwp.services.provider.governance;

import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataPolicyServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final DataPolicyRepository repository = mock(DataPolicyRepository.class);
    private final DataGovernanceService governance = mock(DataGovernanceService.class);
    private final ProviderAuditService audit = mock(ProviderAuditService.class);
    private final DataPolicyService service =
            new DataPolicyService(repository, governance, audit, objectMapper);

    @BeforeEach
    void setContext() {
        ProviderRequestContext.setForTest(7L, 1L);
    }

    @AfterEach
    void clearContext() {
        ProviderRequestContext.clear();
    }

    @Test
    void tenantRlsPreviewBlocksMissingTenantColumn() {
        var rule = objectMapper.createObjectNode();
        rule.putArray("tenantColumns").add("organization_tenant_id");
        rule.put("enforcement", "REQUIRED");
        DataPolicyRepository.PolicyRow policy = policy("TENANT_RLS", "DATABASE", "people");
        DataPolicyRepository.RevisionRow revision = revision(policy.policyId(), rule, 19L);
        when(governance.snapshot()).thenReturn(snapshot(List.of(asset(
                "people.public.ppl_workers", "people", true,
                List.of(column("tenant_id"), column("worker_number"))))));

        DataPolicyDtos.ImpactPreview impact = service.computeImpact(policy, revision);

        assertThat(impact.publishable()).isFalse();
        assertThat(impact.blockers()).containsExactly(
                "TENANT_COLUMN_MISSING:people.public.ppl_workers");
    }

    @Test
    void deletionPreviewStopsWhenActiveLegalHoldOverlaps() {
        var rule = objectMapper.createObjectNode();
        rule.put("deletionSlaDays", 30);
        rule.put("mode", "ANONYMIZE");
        DataPolicyRepository.PolicyRow policy = policy(
                "DELETION", "ASSET", "people.public.ppl_workers");
        DataPolicyRepository.RevisionRow revision = revision(policy.policyId(), rule, 19L);
        var holdRule = objectMapper.createObjectNode();
        holdRule.put("active", true);
        when(repository.activePolicies("LEGAL_HOLD")).thenReturn(List.of(
                new DataPolicyRepository.ScopedActivePolicy(
                        UUID.randomUUID(), "LEGAL_HOLD", "DATABASE", "people",
                        UUID.randomUUID(), holdRule)));
        when(governance.snapshot()).thenReturn(snapshot(List.of(asset(
                "people.public.ppl_workers", "people", true,
                List.of(column("tenant_id"))))));

        DataPolicyDtos.ImpactPreview impact = service.computeImpact(policy, revision);

        assertThat(impact.publishable()).isFalse();
        assertThat(impact.blockers()).singleElement()
                .asString().startsWith("ACTIVE_LEGAL_HOLD:");
        assertThat(impact.warnings()).contains(
                "DELETION_WORKER_REQUIRES_APPROVED_INFRASTRUCTURE");
    }

    @Test
    void requesterCannotApproveOwnPolicyRevision() {
        UUID revisionId = UUID.randomUUID();
        DataPolicyRepository.PolicyRow policy = policy("RETENTION", "GLOBAL", null);
        DataPolicyRepository.RevisionRow revision = revision(
                policy.policyId(), objectMapper.createObjectNode().put("retentionDays", 365), 7L);
        when(repository.revision(revisionId)).thenReturn(Optional.of(new DataPolicyRepository.RevisionRow(
                revisionId, revision.policyId(), revision.revisionNumber(),
                "PENDING_APPROVAL", revision.rule(), revision.effectiveFrom(),
                revision.effectiveTo(), revision.justification(), revision.previousRevisionId(),
                revision.rollbackOfRevisionId(), revision.impact(), revision.impactHash(),
                revision.impactPreviewedAt(), revision.requestedBy(), revision.approvedBy(),
                revision.submittedAt(), revision.approvedAt(), revision.publishedAt(),
                revision.version())));

        assertThatThrownBy(() -> service.decide(
                revisionId,
                new DataPolicyDtos.ApprovalDecisionRequest(
                        0, "APPROVED", "Self approval is forbidden."),
                "corr-self"))
                .isInstanceOf(BaseException.class);

        verify(repository, never()).decide(any(), any(Long.class), any(), any(), any());
    }

    @Test
    void staleRevisionCannotRequestRollback() {
        UUID revisionId = UUID.randomUUID();
        UUID previousRevisionId = UUID.randomUUID();
        DataPolicyRepository.PolicyRow policy = policy("RETENTION", "GLOBAL", null);
        DataPolicyRepository.RevisionRow active = new DataPolicyRepository.RevisionRow(
                revisionId, policy.policyId(), 2, "ACTIVE",
                objectMapper.createObjectNode().put("retentionDays", 365),
                null, null, "Published policy", previousRevisionId, null,
                null, null, null, 7L, 9L, null, null, Instant.now(), 4L);
        when(repository.revision(revisionId)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.requestRollback(
                revisionId,
                new DataPolicyDtos.VersionedReasonRequest(3L, "Restore prior contract."),
                "corr-stale"))
                .isInstanceOf(BaseException.class);

        verify(repository, never()).lockPolicy(any());
    }

    private DataPolicyRepository.PolicyRow policy(
            String type,
            String scopeType,
            String scopeRef) {
        return new DataPolicyRepository.PolicyRow(
                UUID.randomUUID(), "governance.test-policy", "Test policy",
                "Test policy", type, scopeType, scopeRef,
                "dwp-provider-server", "ACTIVE", 0L);
    }

    private DataPolicyRepository.RevisionRow revision(
            UUID policyId,
            com.fasterxml.jackson.databind.JsonNode rule,
            Long requester) {
        return new DataPolicyRepository.RevisionRow(
                UUID.randomUUID(), policyId, 1, "DRAFT", rule,
                null, null, "Governed policy", null, null, null, null,
                null, requester, null, null, null, null, 0L);
    }

    private DataGovernanceDtos.Snapshot snapshot(List<DataGovernanceDtos.DataAsset> assets) {
        return new DataGovernanceDtos.Snapshot(
                Instant.parse("2026-08-12T00:00:00Z"),
                new DataGovernanceDtos.Summary(1, 1, assets.size(), 0,
                        assets.stream().mapToInt(asset -> asset.columns().size()).sum(),
                        0, assets.size(), 0, 0),
                List.of(), assets, List.of(), List.of(), List.of());
    }

    private DataGovernanceDtos.DataAsset asset(
            String key,
            String database,
            boolean tenantScoped,
            List<DataGovernanceDtos.Column> columns) {
        return new DataGovernanceDtos.DataAsset(
                key, database, "dwp_" + database, "public",
                key.substring(key.lastIndexOf('.') + 1), "TABLE", null,
                "Test", "dwp-test", "ACTIVE", "HIGH", "CONFIDENTIAL",
                "VERIFIED", "Test asset", null, 0, 0, tenantScoped,
                0, 0, 0, 0, List.of(), columns);
    }

    private DataGovernanceDtos.Column column(String name) {
        return new DataGovernanceDtos.Column(
                name, "bigint", false, null, null,
                false, false, false, "INTERNAL");
    }
}

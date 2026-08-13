package com.dwp.services.platform.auditcontrol;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditControlServiceTest {

    private final AuditControlRepository repository = mock(AuditControlRepository.class);
    private final AuditControlService service = new AuditControlService(
            repository,
            new AuditRiskEngine(),
            mock(AuditIntegrityService.class),
            mock(AuditOutboxRecorder.class),
            new ObjectMapper().findAndRegisterModules(),
            "dwp-platform-server");

    @Test
    void assemblesCaseWorkspaceWithRiskAndTaskPressure() {
        Long tenantId = 1L;
        UUID caseId = UUID.randomUUID();
        Instant now = Instant.now();
        AuditControlDtos.AuditCase auditCase = new AuditControlDtos.AuditCase(
                caseId, 42L, "Privileged access review", "Review unexpected role change",
                "HIGH", "INVESTIGATING", "analyst-1", null,
                now.minusSeconds(3_600), now.plusSeconds(7_200), "AT_RISK", null,
                "creator", "analyst-1", now, 0, 1);
        AuditControlDtos.Finding finding = new AuditControlDtos.Finding(
                UUID.randomUUID(), null, "RISK_RULE", "high-risk-admin-change",
                "HIGH", 86, "INVESTIGATING", "Unexpected role change",
                "A privileged role was modified", "dwp-auth-server", "actor-1",
                "ROLE", "tenant-admin", 1, now.minusSeconds(3_600), now,
                "analyst-1", caseId, null, now);
        AuditControlDtos.CaseEntity entity = new AuditControlDtos.CaseEntity(
                "USER", "actor-1", "Actor One", "ACTOR", 86,
                now.minusSeconds(3_600), now, Map.of());
        AuditControlDtos.CaseTask overdue = new AuditControlDtos.CaseTask(
                UUID.randomUUID(), "Confirm business owner", null, "OPEN", "HIGH",
                "analyst-1", now.minusSeconds(300), null,
                "creator", "creator", now, now);
        AuditControlDtos.CaseTask completed = new AuditControlDtos.CaseTask(
                UUID.randomUUID(), "Preserve evidence", null, "DONE", "MEDIUM",
                "analyst-1", now.plusSeconds(3_600), now,
                "creator", "analyst-1", now, now);

        when(repository.caseById(tenantId, caseId)).thenReturn(Optional.of(auditCase));
        when(repository.caseFindings(tenantId, caseId)).thenReturn(List.of(finding));
        when(repository.caseEvidence(tenantId, caseId)).thenReturn(List.of());
        when(repository.caseEntities(tenantId, caseId)).thenReturn(List.of(entity));
        when(repository.caseActivities(tenantId, caseId)).thenReturn(List.of());
        when(repository.caseTasks(tenantId, caseId)).thenReturn(List.of(overdue, completed));

        AuditControlDtos.CaseWorkspace workspace = service.caseWorkspace(tenantId, caseId);

        assertThat(workspace.summary().maxRiskScore()).isEqualTo(86);
        assertThat(workspace.summary().openTasks()).isEqualTo(1);
        assertThat(workspace.summary().overdueTasks()).isEqualTo(1);
        assertThat(workspace.summary().findingCount()).isEqualTo(1);
        assertThat(workspace.summary().entityCount()).isEqualTo(1);
    }

    @Test
    void rejectsCaseClosureWhileInvestigationTasksRemainOpen() {
        Long tenantId = 1L;
        UUID caseId = UUID.randomUUID();
        Instant now = Instant.now();
        AuditControlDtos.AuditCase auditCase = auditCase(caseId, "INVESTIGATING", null, now);
        AuditControlDtos.CaseTask openTask = new AuditControlDtos.CaseTask(
                UUID.randomUUID(), "Preserve evidence", null, "OPEN", "HIGH",
                "analyst-1", now.plusSeconds(3_600), null,
                "creator", "creator", now, now);

        when(repository.caseById(tenantId, caseId)).thenReturn(Optional.of(auditCase));
        when(repository.caseTasks(tenantId, caseId)).thenReturn(List.of(openTask));

        assertThatThrownBy(() -> service.updateCase(
                tenantId, "analyst-1", caseId,
                new AuditControlDtos.CaseUpdate(
                        null, null, null, "CLOSED", null, "Investigation completed.")))
                .isInstanceOf(RuntimeException.class);

        verify(repository, never()).updateCase(
                eq(tenantId), eq(caseId), anyString(), any(AuditControlDtos.CaseUpdate.class));
    }

    @Test
    void generatesOneHashedImmutableClosureSnapshotForAClosedCase() {
        Long tenantId = 1L;
        String actorId = "analyst-1";
        UUID caseId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        Instant now = Instant.now();
        AuditControlDtos.AuditCase auditCase =
                auditCase(caseId, "CLOSED", "Verified and contained.", now);
        AuditControlDtos.CaseClosureReport persisted = new AuditControlDtos.CaseClosureReport(
                reportId, caseId, 42L, 1, "a".repeat(64), actorId, now,
                Map.of("schemaVersion", "1.0"));

        when(repository.latestCaseClosureReport(tenantId, caseId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(persisted));
        when(repository.caseById(tenantId, caseId)).thenReturn(Optional.of(auditCase));
        when(repository.caseFindings(tenantId, caseId)).thenReturn(List.of());
        when(repository.caseEvidence(tenantId, caseId)).thenReturn(List.of());
        when(repository.caseEntities(tenantId, caseId)).thenReturn(List.of());
        when(repository.caseActivities(tenantId, caseId)).thenReturn(List.of());
        when(repository.caseTasks(tenantId, caseId)).thenReturn(List.of());
        when(repository.createCaseClosureReport(
                eq(tenantId), eq(caseId), eq(actorId), anyMap(), anyString()))
                .thenReturn(reportId);

        AuditControlDtos.CaseClosureReport result =
                service.ensureCaseClosureReport(tenantId, actorId, caseId);

        assertThat(result).isEqualTo(persisted);
        verify(repository).createCaseClosureReport(
                eq(tenantId), eq(caseId), eq(actorId),
                argThat(report -> "1.0".equals(report.get("schemaVersion"))
                        && report.keySet().containsAll(Set.of(
                                "generatedAt", "generatedBy", "investigation"))),
                argThat(hash -> hash.matches("[0-9a-f]{64}")));
    }

    @Test
    void rejectsWorkspaceChangesAfterCaseClosure() {
        Long tenantId = 1L;
        UUID caseId = UUID.randomUUID();
        Instant now = Instant.now();
        when(repository.caseById(tenantId, caseId))
                .thenReturn(Optional.of(auditCase(caseId, "CLOSED", "Verified and contained.", now)));

        assertThatThrownBy(() -> service.addCaseNote(
                tenantId, "analyst-1", caseId,
                new AuditControlDtos.CaseNoteCreate("Late ungoverned note")))
                .isInstanceOf(RuntimeException.class);

        verify(repository, never()).recordCaseActivity(
                eq(tenantId), eq(caseId), eq("NOTE_ADDED"), anyString(), anyString(), anyMap());
    }

    @Test
    void createsVersionedAuditPolicyDiffWithContentEvidence() {
        Long tenantId = 1L;
        String actorId = "policy-author";
        UUID activeRevisionId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        AuditControlDtos.RetentionPolicy active = new AuditControlDtos.RetentionPolicy(
                365, 2_555, 50_000, true, true, 70,
                "system", Instant.now(), activeRevisionId, 1L);
        AuditControlDtos.PolicyRevisionCreate request = new AuditControlDtos.PolicyRevisionCreate(
                730, 2_555, 25_000, true, true, 80,
                "Increase regulated evidence retention.", null);
        AuditControlDtos.PolicyRevision created = policyRevision(
                revisionId, 2L, "DRAFT", actorId, null);

        when(repository.policy(tenantId)).thenReturn(active);
        when(repository.createPolicyRevision(
                eq(tenantId), eq(actorId), eq(request), eq(activeRevisionId), eq(null),
                anyMap(), anyString())).thenReturn(revisionId);
        when(repository.policyRevision(tenantId, revisionId)).thenReturn(Optional.of(created));

        AuditControlDtos.PolicyRevision result = service.createPolicyRevision(
                tenantId, actorId, request);

        assertThat(result).isEqualTo(created);
        verify(repository).createPolicyRevision(
                eq(tenantId), eq(actorId), eq(request), eq(activeRevisionId), eq(null),
                argThat(diff -> diff.keySet().containsAll(Set.of(
                        "standardRetentionDays", "exportLimitRows", "highRiskThreshold"))),
                argThat(hash -> hash.matches("[0-9a-f]{64}")));
    }

    @Test
    void policyRequesterCannotApproveTheirOwnRevision() {
        Long tenantId = 1L;
        String actorId = "policy-author";
        UUID revisionId = UUID.randomUUID();
        AuditControlDtos.PolicyApproval approval = new AuditControlDtos.PolicyApproval(
                UUID.randomUUID(), "PENDING", actorId, Instant.now(),
                Instant.now().plusSeconds(3_600), null, null, null, 0L);
        when(repository.policyRevision(tenantId, revisionId)).thenReturn(Optional.of(
                policyRevision(revisionId, 2L, "IN_REVIEW", actorId, approval)));

        assertThatThrownBy(() -> service.decidePolicyRevision(
                tenantId, actorId, revisionId,
                new AuditControlDtos.PolicyRevisionDecision(
                        "APPROVED", "Reviewed.", 0L)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Separation of duties");

        verify(repository, never()).decidePolicyRevision(
                eq(tenantId), eq(revisionId), any(), anyString(), anyString(), anyString(),
                eq(0L));
    }

    private AuditControlDtos.AuditCase auditCase(
            UUID caseId, String status, String resolution, Instant now) {
        return new AuditControlDtos.AuditCase(
                caseId, 42L, "Privileged access review", "Review unexpected role change",
                "HIGH", status, "analyst-1", resolution,
                now.minusSeconds(3_600), now.plusSeconds(7_200), "ON_TRACK",
                "CLOSED".equals(status) ? now : null,
                "creator", "analyst-1", now, 0, 1);
    }

    private AuditControlDtos.PolicyRevision policyRevision(
            UUID revisionId,
            long revisionNumber,
            String state,
            String actorId,
            AuditControlDtos.PolicyApproval approval) {
        return new AuditControlDtos.PolicyRevision(
                revisionId, revisionNumber, state, 730, 2_555, 25_000,
                true, true, 80, UUID.randomUUID(), null, null,
                "Increase regulated evidence retention.", Map.of(), "a".repeat(64),
                actorId, Instant.now(), null, null, null, null, 0L, approval);
    }
}

package com.dwp.services.platform.auditcontrol;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditControlServiceTest {

    private final AuditControlRepository repository = mock(AuditControlRepository.class);
    private final AuditControlService service = new AuditControlService(
            repository,
            new AuditRiskEngine(),
            mock(AuditIntegrityService.class),
            mock(AuditOutboxRecorder.class),
            new ObjectMapper(),
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
}

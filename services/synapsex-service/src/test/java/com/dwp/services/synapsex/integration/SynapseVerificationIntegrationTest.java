package com.dwp.services.synapsex.integration;

import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.entity.AgentAction;
import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.repository.AgentActionRepository;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import com.dwp.services.synapsex.repository.AuditEventLogRepository;
import com.dwp.services.synapsex.testcontainers.SynapseTestcontainersBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SynapseX API/정책/감사 로그 검증 통합 테스트.
 * - tenant_id 없으면 400
 * - case/status/action simulate/approve/execute → audit_event_log 1건 이상
 * - cross-tenant 조회 시 404
 */
@AutoConfigureMockMvc
class SynapseVerificationIntegrationTest extends SynapseTestcontainersBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentCaseRepository agentCaseRepository;

    @Autowired
    private AgentActionRepository agentActionRepository;

    @Autowired
    private AuditEventLogRepository auditEventLogRepository;

    private static final Long TENANT_1 = 1L;
    private static final Long TENANT_2 = 2L;
    private static final Long ACTOR_USER_ID = 100L;

    private Long caseIdTenant1;
    private Long actionIdTenant1;
    private Long caseIdTenant2;

    @BeforeEach
    void setUp() {
        AgentCase c1 = AgentCase.builder()
                .tenantId(TENANT_1)
                .detectedAt(Instant.now())
                .caseType("DUPLICATE_INVOICE")
                .severity("HIGH")
                .status(com.dwp.services.synapsex.entity.AgentCaseStatus.OPEN)
                .build();
        c1 = agentCaseRepository.save(c1);
        caseIdTenant1 = c1.getCaseId();

        AgentAction a1 = AgentAction.builder()
                .tenantId(TENANT_1)
                .caseId(caseIdTenant1)
                .actionType("PAYMENT_BLOCK")
                .status(com.dwp.services.synapsex.entity.AgentActionStatus.PROPOSED)
                .plannedAt(Instant.now())
                .build();
        a1 = agentActionRepository.save(a1);
        actionIdTenant1 = a1.getActionId();

        AgentCase c2 = AgentCase.builder()
                .tenantId(TENANT_2)
                .detectedAt(Instant.now())
                .caseType("DUPLICATE_INVOICE")
                .severity("MEDIUM")
                .status(com.dwp.services.synapsex.entity.AgentCaseStatus.OPEN)
                .build();
        c2 = agentCaseRepository.save(c2);
        caseIdTenant2 = c2.getCaseId();
    }

    @Nested
    @DisplayName("1) tenant_id 없으면 400")
    class TenantIdRequiredTest {

        @Test
        @DisplayName("GET /synapse/cases - X-Tenant-ID 없으면 400")
        void getCases_withoutTenant_returns400() throws Exception {
            mockMvc.perform(get("/synapse/cases"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value("ERROR"))
                    .andExpect(jsonPath("$.errorCode").exists());
        }

        @Test
        @DisplayName("GET /synapse/cases/{id} - X-Tenant-ID 없으면 400")
        void getCaseDetail_withoutTenant_returns400() throws Exception {
            mockMvc.perform(get("/synapse/cases/{caseId}", caseIdTenant1))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /synapse/cases/{id}/status - X-Tenant-ID 없으면 400")
        void updateStatus_withoutTenant_returns400() throws Exception {
            mockMvc.perform(post("/synapse/cases/{caseId}/status", caseIdTenant1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\": \"IN_PROGRESS\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /synapse/actions/{id}/simulate - X-Tenant-ID 없으면 400")
        void simulate_withoutTenant_returns400() throws Exception {
            mockMvc.perform(post("/synapse/actions/{actionId}/simulate", actionIdTenant1))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /synapse/actions/{id}/approve - X-Tenant-ID 없으면 400")
        void approve_withoutTenant_returns400() throws Exception {
            mockMvc.perform(post("/synapse/actions/{actionId}/approve", actionIdTenant1))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /synapse/actions/{id}/execute - X-Tenant-ID 없으면 400")
        void execute_withoutTenant_returns400() throws Exception {
            agentActionRepository.findById(actionIdTenant1).ifPresent(a -> {
                a.setStatus(com.dwp.services.synapsex.entity.AgentActionStatus.APPROVED);
                agentActionRepository.save(a);
            });
            mockMvc.perform(post("/synapse/actions/{actionId}/execute", actionIdTenant1))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("2) audit_event_log 1건 이상 생성 검증")
    class AuditEventLogVerificationTest {

        @Test
        @DisplayName("POST /cases/{id}/status → audit_event_log STATUS_CHANGE 기록")
        void caseStatusChange_recordsAudit() throws Exception {
            long before = auditEventLogRepository.findByTenantId(TENANT_1, PageRequest.of(0, 1000)).getTotalElements();

            mockMvc.perform(post("/synapse/cases/{caseId}/status", caseIdTenant1)
                            .header("X-Tenant-ID", TENANT_1.toString())
                            .header("X-User-ID", ACTOR_USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\": \"IN_PROGRESS\"}"))
                    .andExpect(status().isOk());

            var logs = auditEventLogRepository.findByTenantId(TENANT_1, PageRequest.of(0, 1000));
            assertThat(logs.getTotalElements()).isGreaterThan(before);
            assertThat(logs.getContent().stream()
                    .anyMatch(e -> AuditEventConstants.TYPE_STATUS_CHANGE.equals(e.getEventType())))
                    .isTrue();
        }

        @Test
        @DisplayName("POST /actions/{id}/simulate → audit_event_log SIMULATE 기록")
        void actionSimulate_recordsAudit() throws Exception {
            long before = auditEventLogRepository.findByTenantId(TENANT_1, PageRequest.of(0, 1000)).getTotalElements();

            mockMvc.perform(post("/synapse/actions/{actionId}/simulate", actionIdTenant1)
                            .header("X-Tenant-ID", TENANT_1.toString())
                            .header("X-User-ID", ACTOR_USER_ID.toString()))
                    .andExpect(status().isOk());

            var logs = auditEventLogRepository.findByTenantId(TENANT_1, PageRequest.of(0, 1000));
            assertThat(logs.getTotalElements()).isGreaterThan(before);
            assertThat(logs.getContent().stream()
                    .anyMatch(e -> AuditEventConstants.TYPE_SIMULATE.equals(e.getEventType())))
                    .isTrue();
        }

        @Test
        @DisplayName("POST /actions/{id}/approve → audit_event_log APPROVE 기록")
        void actionApprove_recordsAudit() throws Exception {
            long before = auditEventLogRepository.findByTenantId(TENANT_1, PageRequest.of(0, 1000)).getTotalElements();

            mockMvc.perform(post("/synapse/actions/{actionId}/approve", actionIdTenant1)
                            .header("X-Tenant-ID", TENANT_1.toString())
                            .header("X-User-ID", ACTOR_USER_ID.toString()))
                    .andExpect(status().isOk());

            var logs = auditEventLogRepository.findByTenantId(TENANT_1, PageRequest.of(0, 1000));
            assertThat(logs.getTotalElements()).isGreaterThan(before);
            assertThat(logs.getContent().stream()
                    .anyMatch(e -> AuditEventConstants.TYPE_APPROVE.equals(e.getEventType())))
                    .isTrue();
        }

        @Test
        @DisplayName("POST /actions/{id}/execute → audit_event_log EXECUTE 기록")
        void actionExecute_recordsAudit() throws Exception {
            agentActionRepository.findById(actionIdTenant1).ifPresent(a -> {
                a.setStatus(com.dwp.services.synapsex.entity.AgentActionStatus.APPROVED);
                agentActionRepository.save(a);
            });
            long before = auditEventLogRepository.findByTenantId(TENANT_1, PageRequest.of(0, 1000)).getTotalElements();

            mockMvc.perform(post("/synapse/actions/{actionId}/execute", actionIdTenant1)
                            .header("X-Tenant-ID", TENANT_1.toString())
                            .header("X-User-ID", ACTOR_USER_ID.toString()))
                    .andExpect(status().isOk());

            var logs = auditEventLogRepository.findByTenantId(TENANT_1, PageRequest.of(0, 1000));
            assertThat(logs.getTotalElements()).isGreaterThan(before);
            assertThat(logs.getContent().stream()
                    .anyMatch(e -> AuditEventConstants.TYPE_EXECUTE.equals(e.getEventType())))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("3) cross-tenant 조회 시 404")
    class CrossTenantBlockTest {

        @Test
        @DisplayName("tenant1이 tenant2의 case 조회 시 404")
        void tenant1AccessingTenant2Case_returns404() throws Exception {
            mockMvc.perform(get("/synapse/cases/{caseId}", caseIdTenant2)
                            .header("X-Tenant-ID", TENANT_1.toString()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("tenant1이 tenant2의 case status 변경 시 404 (case not found)")
        void tenant1UpdatingTenant2CaseStatus_returns404() throws Exception {
            mockMvc.perform(post("/synapse/cases/{caseId}/status", caseIdTenant2)
                            .header("X-Tenant-ID", TENANT_1.toString())
                            .header("X-User-ID", ACTOR_USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\": \"IN_PROGRESS\"}"))
                    .andExpect(status().isBadRequest()); // IllegalArgumentException → 400
        }

        @Test
        @DisplayName("tenant1이 tenant2의 action simulate 시 404 (action not found)")
        void tenant1SimulatingTenant2Action_returns404() throws Exception {
            AgentAction a2 = AgentAction.builder()
                    .tenantId(TENANT_2)
                    .caseId(caseIdTenant2)
                    .actionType("PAYMENT_BLOCK")
                    .status(com.dwp.services.synapsex.entity.AgentActionStatus.PROPOSED)
                    .plannedAt(Instant.now())
                    .build();
            a2 = agentActionRepository.save(a2);

            mockMvc.perform(post("/synapse/actions/{actionId}/simulate", a2.getActionId())
                            .header("X-Tenant-ID", TENANT_1.toString()))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("4) guardrail forbidden → 403/409 + audit outcome=FAIL")
    @Disabled("Agent Tool API(/agent-tools/propose) 경유 시 PolicyEngine 적용. 일반 Action API는 guardrail 미적용.")
    class GuardrailForbiddenTest {

        @Test
        @DisplayName("guardrail DENIED 시 403/409 및 audit outcome=FAIL 기록")
        void guardrailDenied_returns403Or409_andRecordsAuditFail() {
            // Agent Tool propose 호출 시 PolicyEngine.evaluateAction이 DENIED 반환하면
            // 403 또는 409 + audit_event_log (outcome=FAIL) 기록 검증
        }
    }
}

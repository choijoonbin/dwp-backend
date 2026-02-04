package com.dwp.services.synapsex.integration;

import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.entity.AgentAction;
import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.entity.IntegrationOutbox;
import com.dwp.services.synapsex.repository.AgentActionRepository;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import com.dwp.services.synapsex.repository.AuditEventLogRepository;
import com.dwp.services.synapsex.repository.ConfigProfileRepository;
import com.dwp.services.synapsex.repository.IntegrationOutboxRepository;
import com.dwp.services.synapsex.testcontainers.SynapseTestcontainersBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit 의무 이벤트 검증.
 * 아래 액션 발생 시 audit_event_log에 기록되는지 통합테스트.
 * 누락 시 테스트 실패로 PR block.
 */
@AutoConfigureMockMvc
class AuditMandatoryEventIntegrationTest extends SynapseTestcontainersBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentCaseRepository agentCaseRepository;

    @Autowired
    private AgentActionRepository agentActionRepository;

    @Autowired
    private AuditEventLogRepository auditEventLogRepository;

    @Autowired
    private ConfigProfileRepository configProfileRepository;

    @Autowired
    private IntegrationOutboxRepository integrationOutboxRepository;

    private static final Long TENANT_ID = 1L;
    private static final Long ACTOR_USER_ID = 100L;

    private Long caseId;
    private Long actionId;

    @BeforeEach
    void setUp() {
        AgentCase c = AgentCase.builder()
                .tenantId(TENANT_ID)
                .detectedAt(Instant.now())
                .caseType("DUPLICATE_INVOICE")
                .severity("HIGH")
                .status(com.dwp.services.synapsex.entity.AgentCaseStatus.OPEN)
                .build();
        c = agentCaseRepository.save(c);
        caseId = c.getCaseId();

        AgentAction a = AgentAction.builder()
                .tenantId(TENANT_ID)
                .caseId(caseId)
                .actionType("PAYMENT_BLOCK")
                .status(com.dwp.services.synapsex.entity.AgentActionStatus.PROPOSED)
                .plannedAt(Instant.now())
                .build();
        a = agentActionRepository.save(a);
        actionId = a.getActionId();
    }

    @Nested
    @DisplayName("CASE_ASSIGN")
    class CaseAssignAuditTest {

        @Test
        @DisplayName("POST /cases/{id}/assign 시 audit_event_log에 CASE_ASSIGN 기록")
        void assignCase_recordsAudit() throws Exception {
            mockMvc.perform(post("/synapse/cases/{caseId}/assign", caseId)
                            .header("X-Tenant-ID", TENANT_ID.toString())
                            .header("X-User-ID", ACTOR_USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"assigneeUserId\": 200}"))
                    .andExpect(status().isOk());

            var logs = auditEventLogRepository.findByTenantId(TENANT_ID, org.springframework.data.domain.Pageable.unpaged());
            assertThat(logs.getContent().stream()
                    .anyMatch(e -> AuditEventConstants.TYPE_ASSIGN.equals(e.getEventType())))
                    .as("CASE_ASSIGN 이벤트가 audit_event_log에 기록되어야 함")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("CASE_STATUS_CHANGE")
    class CaseStatusChangeAuditTest {

        @Test
        @DisplayName("POST /cases/{id}/status 시 audit_event_log에 STATUS_CHANGE 기록")
        void updateStatus_recordsAudit() throws Exception {
            mockMvc.perform(post("/synapse/cases/{caseId}/status", caseId)
                            .header("X-Tenant-ID", TENANT_ID.toString())
                            .header("X-User-ID", ACTOR_USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\": \"IN_PROGRESS\"}"))
                    .andExpect(status().isOk());

            var logs = auditEventLogRepository.findByTenantId(TENANT_ID, org.springframework.data.domain.Pageable.unpaged());
            assertThat(logs.getContent().stream()
                    .anyMatch(e -> AuditEventConstants.TYPE_STATUS_CHANGE.equals(e.getEventType())))
                    .as("CASE_STATUS_CHANGE 이벤트가 audit_event_log에 기록되어야 함")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("CASE_COMMENT_CREATE")
    class CaseCommentCreateAuditTest {

        @Test
        @DisplayName("POST /cases/{id}/comment 시 audit_event_log에 CASE_COMMENT_CREATE 기록")
        void addComment_recordsAudit() throws Exception {
            mockMvc.perform(post("/synapse/cases/{caseId}/comment", caseId)
                            .header("X-Tenant-ID", TENANT_ID.toString())
                            .header("X-User-ID", ACTOR_USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commentText\": \"검토 완료\"}"))
                    .andExpect(status().isOk());

            var logs = auditEventLogRepository.findByTenantId(TENANT_ID, org.springframework.data.domain.Pageable.unpaged());
            assertThat(logs.getContent().stream()
                    .anyMatch(e -> AuditEventConstants.TYPE_COMMENT_CREATE.equals(e.getEventType())))
                    .as("CASE_COMMENT_CREATE 이벤트가 audit_event_log에 기록되어야 함")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("ACTION_SIMULATE")
    class ActionSimulateAuditTest {

        @Test
        @DisplayName("POST /actions/{id}/simulate 시 audit_event_log에 SIMULATE 기록")
        void simulateAction_recordsAudit() throws Exception {
            mockMvc.perform(post("/synapse/actions/{actionId}/simulate", actionId)
                            .header("X-Tenant-ID", TENANT_ID.toString())
                            .header("X-User-ID", ACTOR_USER_ID.toString()))
                    .andExpect(status().isOk());

            var logs = auditEventLogRepository.findByTenantId(TENANT_ID, org.springframework.data.domain.Pageable.unpaged());
            assertThat(logs.getContent().stream()
                    .anyMatch(e -> AuditEventConstants.TYPE_SIMULATE.equals(e.getEventType())))
                    .as("ACTION_SIMULATE 이벤트가 audit_event_log에 기록되어야 함")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("ACTION_APPROVE")
    class ActionApproveAuditTest {

        @Test
        @DisplayName("POST /actions/{id}/approve 시 audit_event_log에 APPROVE 기록")
        void approveAction_recordsAudit() throws Exception {
            mockMvc.perform(post("/synapse/actions/{actionId}/approve", actionId)
                            .header("X-Tenant-ID", TENANT_ID.toString())
                            .header("X-User-ID", ACTOR_USER_ID.toString()))
                    .andExpect(status().isOk());

            var logs = auditEventLogRepository.findByTenantId(TENANT_ID, org.springframework.data.domain.Pageable.unpaged());
            assertThat(logs.getContent().stream()
                    .anyMatch(e -> AuditEventConstants.TYPE_APPROVE.equals(e.getEventType())))
                    .as("ACTION_APPROVE 이벤트가 audit_event_log에 기록되어야 함")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("ACTION_EXECUTE")
    class ActionExecuteAuditTest {

        @BeforeEach
        void setActionApproved() {
            agentActionRepository.findById(actionId).ifPresent(a -> {
                a.setStatus(com.dwp.services.synapsex.entity.AgentActionStatus.APPROVED);
                agentActionRepository.save(a);
            });
        }

        @Test
        @DisplayName("POST /actions/{id}/execute 시 audit_event_log에 EXECUTE 기록")
        void executeAction_recordsAudit() throws Exception {
            mockMvc.perform(post("/synapse/actions/{actionId}/execute", actionId)
                            .header("X-Tenant-ID", TENANT_ID.toString())
                            .header("X-User-ID", ACTOR_USER_ID.toString()))
                    .andExpect(status().isOk());

            var logs = auditEventLogRepository.findByTenantId(TENANT_ID, org.springframework.data.domain.Pageable.unpaged());
            assertThat(logs.getContent().stream()
                    .anyMatch(e -> AuditEventConstants.TYPE_EXECUTE.equals(e.getEventType())))
                    .as("ACTION_EXECUTE 이벤트가 audit_event_log에 기록되어야 함")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("POLICY_CHANGE")
    class PolicyChangeAuditTest {

        @Test
        @DisplayName("POST /admin/thresholds 시 audit_event_log에 POLICY_CHANGE 기록")
        void upsertThreshold_recordsAudit() throws Exception {
            Long profileId = configProfileRepository.findByTenantIdAndIsDefaultTrue(TENANT_ID)
                    .map(p -> p.getProfileId())
                    .orElse(1L);

            mockMvc.perform(post("/synapse/admin/thresholds")
                            .header("X-Tenant-ID", TENANT_ID.toString())
                            .header("X-User-ID", ACTOR_USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "profileId": %d,
                                      "dimension": "HKONT",
                                      "dimensionKey": "600000",
                                      "waers": "KRW",
                                      "thresholdAmount": 500000
                                    }
                                    """.formatted(profileId)))
                    .andExpect(status().isOk());

            var logs = auditEventLogRepository.findByTenantId(TENANT_ID, org.springframework.data.domain.Pageable.unpaged());
            assertThat(logs.getContent().stream()
                    .anyMatch(e -> AuditEventConstants.TYPE_POLICY_CHANGE.equals(e.getEventType())))
                    .as("POLICY_CHANGE 이벤트가 audit_event_log에 기록되어야 함")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("GUARDRAIL_CHANGE")
    class GuardrailChangeAuditTest {

        @Test
        @DisplayName("POST /guardrails 시 audit_event_log에 GUARDRAIL_CHANGE 기록")
        void createGuardrail_recordsAudit() throws Exception {
            mockMvc.perform(post("/synapse/guardrails")
                            .header("X-Tenant-ID", TENANT_ID.toString())
                            .header("X-User-ID", ACTOR_USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "name": "audit-test-guardrail",
                                      "scope": "case_type",
                                      "ruleJson": {"maxAmount": 1000000},
                                      "isEnabled": true
                                    }
                                    """))
                    .andExpect(status().isOk());

            var logs = auditEventLogRepository.findByTenantId(TENANT_ID, org.springframework.data.domain.Pageable.unpaged());
            assertThat(logs.getContent().stream()
                    .anyMatch(e -> AuditEventConstants.TYPE_GUARDRAIL_CHANGE.equals(e.getEventType())))
                    .as("GUARDRAIL_CHANGE 이벤트가 audit_event_log에 기록되어야 함")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("DICTIONARY_CHANGE")
    class DictionaryChangeAuditTest {

        @Test
        @DisplayName("POST /dictionary 시 audit_event_log에 DICTIONARY_CHANGE 기록")
        void createTerm_recordsAudit() throws Exception {
            mockMvc.perform(post("/synapse/dictionary")
                            .header("X-Tenant-ID", TENANT_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "termKey": "audit-test-term",
                                      "labelKo": "테스트 용어",
                                      "category": "TEST"
                                    }
                                    """))
                    .andExpect(status().isOk());

            var logs = auditEventLogRepository.findByTenantId(TENANT_ID, org.springframework.data.domain.Pageable.unpaged());
            assertThat(logs.getContent().stream()
                    .anyMatch(e -> AuditEventConstants.TYPE_DICTIONARY_CHANGE.equals(e.getEventType())))
                    .as("DICTIONARY_CHANGE 이벤트가 audit_event_log에 기록되어야 함")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("INTEGRATION_OUTBOX_ENQUEUE")
    class IntegrationOutboxEnqueueAuditTest {

        @Test
        @DisplayName("POST /integration/outbox 시 audit_event_log에 INTEGRATION_OUTBOX_ENQUEUE 기록")
        void enqueue_recordsAudit() throws Exception {
            mockMvc.perform(post("/synapse/integration/outbox")
                            .header("X-Tenant-ID", TENANT_ID.toString())
                            .header("X-User-ID", ACTOR_USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "targetSystem": "SAP",
                                      "eventType": "PAYMENT_BLOCK",
                                      "eventKey": "test-key-1",
                                      "payload": {"actionId": 1}
                                    }
                                    """))
                    .andExpect(status().isOk());

            var logs = auditEventLogRepository.findByTenantId(TENANT_ID, org.springframework.data.domain.Pageable.unpaged());
            assertThat(logs.getContent().stream()
                    .anyMatch(e -> AuditEventConstants.TYPE_INTEGRATION_OUTBOX_ENQUEUE.equals(e.getEventType())))
                    .as("INTEGRATION_OUTBOX_ENQUEUE 이벤트가 audit_event_log에 기록되어야 함")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("INTEGRATION_RESULT_UPDATE")
    class IntegrationResultUpdateAuditTest {

        private Long outboxId;

        @BeforeEach
        void createOutbox() {
            IntegrationOutbox outbox = IntegrationOutbox.builder()
                    .tenantId(TENANT_ID)
                    .targetSystem("SAP")
                    .eventType("PAYMENT_BLOCK")
                    .eventKey("test-result-key")
                    .payload(java.util.Map.of("actionId", 1))
                    .status("PENDING")
                    .retryCount(0)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            outbox = integrationOutboxRepository.save(outbox);
            outboxId = outbox.getOutboxId();
        }

        @Test
        @DisplayName("PATCH /integration/outbox/{id}/result 시 audit_event_log에 INTEGRATION_RESULT_UPDATE 기록")
        void updateResult_recordsAudit() throws Exception {
            mockMvc.perform(patch("/synapse/integration/outbox/{outboxId}/result", outboxId)
                            .header("X-Tenant-ID", TENANT_ID.toString())
                            .header("X-User-ID", ACTOR_USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "status": "PROCESSED",
                                      "resultMessage": "SAP 반영 완료"
                                    }
                                    """))
                    .andExpect(status().isOk());

            var logs = auditEventLogRepository.findByTenantId(TENANT_ID, org.springframework.data.domain.Pageable.unpaged());
            assertThat(logs.getContent().stream()
                    .anyMatch(e -> AuditEventConstants.TYPE_INTEGRATION_RESULT_UPDATE.equals(e.getEventType())))
                    .as("INTEGRATION_RESULT_UPDATE 이벤트가 audit_event_log에 기록되어야 함")
                    .isTrue();
        }
    }
}

package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase3: case_action_execution — 액션 제안 실행(시뮬) 결과
 */
@Entity
@Table(schema = "dwp_aura", name = "case_action_execution")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseActionExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "execution_id")
    private UUID executionId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "proposal_id")
    private UUID proposalId;

    @Column(name = "action_type", length = 64)
    private String actionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_json", columnDefinition = "jsonb")
    private JsonNode requestJson;

    @Column(name = "mode", nullable = false, length = 20)
    @Builder.Default
    private String mode = "SIMULATION";

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "COMPLETED";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "jsonb")
    private JsonNode resultJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "executed_by")
    private Long executedBy;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "gateway_request_id", length = 255)
    private String gatewayRequestId;

    public static final String MODE_SIMULATION = "SIMULATION";
    public static final String MODE_LIVE = "LIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
}

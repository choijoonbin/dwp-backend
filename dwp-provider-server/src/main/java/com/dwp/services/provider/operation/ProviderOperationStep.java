package com.dwp.services.provider.operation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "prv_operation_steps")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderOperationStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "operation_step_id")
    private Long operationStepId;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @Column(name = "step_key", nullable = false, length = 80)
    private String stepKey;

    @Column(name = "lifecycle_state", nullable = false, length = 24)
    private String lifecycleState;

    @Column(name = "target_service", nullable = false, length = 80)
    private String targetService;

    @Column(name = "external_reference", length = 255)
    private String externalReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redacted_result", nullable = false, columnDefinition = "jsonb")
    private String redactedResult;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_error_code", length = 80)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = 1000)
    private String lastErrorMessage;
}

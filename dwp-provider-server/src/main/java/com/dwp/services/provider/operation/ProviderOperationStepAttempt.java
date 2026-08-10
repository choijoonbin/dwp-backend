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
@Table(name = "prv_operation_step_attempts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderOperationStepAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "operation_step_attempt_id")
    private UUID operationStepAttemptId;

    @Column(name = "operation_step_id", nullable = false)
    private Long operationStepId;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redacted_result", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private String redactedResult = "{}";

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    @Builder.Default
    private Instant startedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;
}

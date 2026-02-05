package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Phase B: detect_run — 탐지 배치 실행 단위
 */
@Entity
@Table(schema = "dwp_aura", name = "detect_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DetectRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "run_id")
    private Long runId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "window_from", nullable = false)
    private Instant windowFrom;

    @Column(name = "window_to", nullable = false)
    private Instant windowTo;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "STARTED";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "counts_json", columnDefinition = "jsonb")
    private JsonNode countsJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;
}

package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Phase B: ingest_run — 원천데이터 적재 실행 단위
 */
@Entity
@Table(schema = "dwp_aura", name = "ingest_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "run_id")
    private Long runId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "batch_id")
    private String batchId;

    @Column(name = "window_from")
    private Instant windowFrom;

    @Column(name = "window_to")
    private Instant windowTo;

    @Column(name = "record_count")
    private Integer recordCount;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "STARTED";

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

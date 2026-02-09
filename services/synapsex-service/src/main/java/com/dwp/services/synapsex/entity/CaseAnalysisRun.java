package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase2: case_analysis_run — 케이스 분석 실행 단위
 * Aura 연동 trigger → stream → callback
 */
@Entity
@Table(schema = "dwp_aura", name = "case_analysis_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseAnalysisRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "STARTED";

    @Column(name = "mode", nullable = false, length = 20)
    @Builder.Default
    private String mode = "LIVE";

    @Column(name = "requested_by", nullable = false, length = 20)
    @Builder.Default
    private String requestedBy = "HUMAN";

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "aura_trace_id", length = 100)
    private String auraTraceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static final String STATUS_STARTED = "STARTED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
}

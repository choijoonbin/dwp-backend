package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * AI 분석 시 사고 과정(Thought Chain) 로그 — run별 시간순 저장.
 * 시연 후 상세 페이지에서 근거를 재조회할 수 있도록 thought/step 이벤트를 보관.
 */
@Entity
@Table(schema = "dwp_aura", name = "thought_chain_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ThoughtChainLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "data", columnDefinition = "TEXT")
    private String data;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

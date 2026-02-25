package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * fi_doc_header — FI 전표 헤더 (Canonical)
 */
@Entity
@Table(schema = "dwp_aura", name = "fi_doc_header")
@IdClass(FiDocHeaderId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FiDocHeader {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Id
    @Column(name = "bukrs", nullable = false, length = 4)
    private String bukrs;

    @Id
    @Column(name = "belnr", nullable = false, length = 10)
    private String belnr;

    @Id
    @Column(name = "gjahr", nullable = false, length = 4)
    private String gjahr;

    @Column(name = "doc_source", nullable = false, length = 10)
    private String docSource;

    @Column(name = "budat", nullable = false)
    private LocalDate budat;

    @Column(name = "bldat")
    private LocalDate bldat;

    @Column(name = "cpudt")
    private LocalDate cpudt;

    @Column(name = "cputm")
    private LocalTime cputm;

    @Column(name = "usnam", length = 12)
    private String usnam;

    @Column(name = "tcode", length = 20)
    private String tcode;

    @Column(name = "blart", length = 2)
    private String blart;

    @Column(name = "waers", length = 5)
    private String waers;

    @Column(name = "kursf", precision = 18, scale = 6)
    private BigDecimal kursf;

    @Column(name = "xblnr", length = 30)
    private String xblnr;

    @Column(name = "bktxt", length = 200)
    private String bktxt;

    /** 데모 생성 시 시나리오 유형(예: SPLIT_PAYMENT). Aura evidence_json.intended_risk_type 전달용. */
    @Column(name = "intended_risk_type", length = 50)
    private String intendedRiskType;

    /** 규정 v2.0: 근무/휴가 (WORK, LEAVE). Aura evidence/metadata 전달용. */
    @Column(name = "hr_status", length = 20)
    private String hrStatus;
    /** 규정 v2.0: 업종 코드 또는 라벨 (예: RESTAURANT, GOLF). */
    @Column(name = "mcc_code", length = 20)
    private String mccCode;
    /** 규정 v2.0: 한도초과 여부. */
    @Column(name = "budget_exceeded")
    private Boolean budgetExceeded;

    @Column(name = "status_code", length = 20)
    private String statusCode;

    @Column(name = "reversal_belnr", length = 10)
    private String reversalBelnr;

    @Column(name = "last_change_ts")
    private java.time.Instant lastChangeTs;

    @Column(name = "raw_event_id")
    private Long rawEventId;

    @Column(name = "created_at", nullable = false)
    private java.time.Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private java.time.Instant updatedAt;
}

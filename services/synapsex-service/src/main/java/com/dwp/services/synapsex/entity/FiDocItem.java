package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * fi_doc_item — FI 전표 라인
 */
@Entity
@Table(schema = "dwp_aura", name = "fi_doc_item")
@IdClass(FiDocItemId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FiDocItem {

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

    @Id
    @Column(name = "buzei", nullable = false, length = 3)
    private String buzei;

    @Column(name = "hkont", nullable = false, length = 10)
    private String hkont;

    @Column(name = "bschl", length = 2)
    private String bschl;

    @Column(name = "shkzg", length = 1)
    private String shkzg;

    @Column(name = "lifnr", length = 20)
    private String lifnr;

    @Column(name = "kunnr", length = 20)
    private String kunnr;

    @Column(name = "wrbtr", precision = 18, scale = 2)
    private BigDecimal wrbtr;

    @Column(name = "dmbtr", precision = 18, scale = 2)
    private BigDecimal dmbtr;

    @Column(name = "waers", length = 5)
    private String waers;

    @Column(name = "mwskz", length = 2)
    private String mwskz;

    @Column(name = "kostl", length = 10)
    private String kostl;

    @Column(name = "prctr", length = 10)
    private String prctr;

    @Column(name = "aufnr", length = 12)
    private String aufnr;

    @Column(name = "zterm", length = 4)
    private String zterm;

    @Column(name = "zfbdt")
    private LocalDate zfbdt;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "payment_block", nullable = false)
    @Builder.Default
    private Boolean paymentBlock = false;

    @Column(name = "dispute_flag", nullable = false)
    @Builder.Default
    private Boolean disputeFlag = false;

    @Column(name = "zuonr", length = 18)
    private String zuonr;

    @Column(name = "sgtxt", length = 200)
    private String sgtxt;

    @Column(name = "last_change_ts")
    private Instant lastChangeTs;

    @Column(name = "raw_event_id")
    private Long rawEventId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

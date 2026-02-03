package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * fi_open_item — 미결제/오픈아이템 (AP/AR)
 */
@Entity
@Table(schema = "dwp_aura", name = "fi_open_item")
@IdClass(FiOpenItemId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FiOpenItem {

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

    @Column(name = "item_type", nullable = false, length = 10)
    private String itemType;  // AP, AR

    @Column(name = "lifnr", length = 20)
    private String lifnr;

    @Column(name = "kunnr", length = 20)
    private String kunnr;

    @Column(name = "baseline_date")
    private LocalDate baselineDate;

    @Column(name = "zterm", length = 4)
    private String zterm;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "open_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal openAmount;

    @Column(name = "currency", nullable = false, length = 5)
    private String currency;

    @Column(name = "cleared", nullable = false)
    @Builder.Default
    private Boolean cleared = false;

    @Column(name = "clearing_date")
    private LocalDate clearingDate;

    @Column(name = "payment_block", nullable = false)
    @Builder.Default
    private Boolean paymentBlock = false;

    @Column(name = "dispute_flag", nullable = false)
    @Builder.Default
    private Boolean disputeFlag = false;

    @Column(name = "last_change_ts")
    private Instant lastChangeTs;

    @Column(name = "raw_event_id")
    private Long rawEventId;

    @Column(name = "last_update_ts", nullable = false)
    private Instant lastUpdateTs;
}

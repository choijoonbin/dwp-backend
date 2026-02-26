package com.dwp.services.synapsex.entity;

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

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(schema = "dwp_aura", name = "mcc_master")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MccMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mcc_id")
    private Long mccId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "mcc_code", nullable = false, length = 4)
    private String mccCode;

    @Column(name = "mcc_name", nullable = false, length = 100)
    private String mccName;

    @Column(name = "risk_category", nullable = false, length = 20)
    private String riskCategory;

    @Column(name = "related_article", length = 100)
    private String relatedArticle;

    @Column(name = "is_weekend_allowed")
    private Character isWeekendAllowed;

    @Column(name = "limit_amount_per_use", precision = 18, scale = 2)
    private BigDecimal limitAmountPerUse;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;
}

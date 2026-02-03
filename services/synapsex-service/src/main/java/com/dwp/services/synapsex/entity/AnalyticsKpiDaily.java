package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * analytics_kpi_daily — 일별 KPI 메트릭
 */
@Entity
@Table(schema = "dwp_aura", name = "analytics_kpi_daily")
@IdClass(AnalyticsKpiDailyId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalyticsKpiDaily {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Id
    @Column(name = "ymd", nullable = false)
    private LocalDate ymd;

    @Id
    @Column(name = "metric_key", nullable = false, length = 80)
    private String metricKey;

    @Column(name = "metric_value", nullable = false, precision = 18, scale = 4)
    private BigDecimal metricValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dims_json", columnDefinition = "jsonb")
    @Builder.Default
    private JsonNode dimsJson = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();

    @Id
    @Column(name = "dims_hash", nullable = false, length = 64)
    @Builder.Default
    private String dimsHash = "";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}

package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * recon_result — Reconciliation 결과
 */
@Entity
@Table(schema = "dwp_aura", name = "recon_result")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_id")
    private Long resultId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "resource_type", nullable = false, length = 50)
    private String resourceType;

    @Column(name = "resource_key", nullable = false, columnDefinition = "TEXT")
    private String resourceKey;

    @Column(name = "status", nullable = false, length = 10)
    private String status;  // PASS, FAIL

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "jsonb")
    private JsonNode detailJson;
}

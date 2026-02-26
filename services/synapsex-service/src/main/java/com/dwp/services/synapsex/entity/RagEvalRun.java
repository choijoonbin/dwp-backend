package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(schema = "dwp_aura", name = "rag_eval_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagEvalRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "run_key", nullable = false, length = 128)
    private String runKey;

    @Column(name = "zero_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal zeroRate;

    @Column(name = "hit_at_k", nullable = false, precision = 5, scale = 4)
    private BigDecimal hitAtK;

    @Column(name = "strict_hit_top1", nullable = false, precision = 5, scale = 4)
    private BigDecimal strictHitTop1;

    @Column(name = "total_cases", nullable = false)
    private Integer totalCases;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode resultJson;

    @Column(name = "gate_passed", nullable = false)
    private Boolean gatePassed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}

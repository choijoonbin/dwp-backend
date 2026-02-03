package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * ingestion_errors — ingestion 오류
 */
@Entity
@Table(schema = "dwp_aura", name = "ingestion_errors")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "raw_event_id")
    private Long rawEventId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "dataset_id", nullable = false, columnDefinition = "TEXT")
    private String datasetId;

    @Column(name = "record_key", columnDefinition = "TEXT")
    private String recordKey;

    @Column(name = "error_code", nullable = false, columnDefinition = "TEXT")
    private String errorCode;

    @Column(name = "error_detail", nullable = false, columnDefinition = "TEXT")
    private String errorDetail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "record_json", columnDefinition = "jsonb")
    private JsonNode recordJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

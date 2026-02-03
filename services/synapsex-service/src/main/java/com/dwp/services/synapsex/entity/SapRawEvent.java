package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/**
 * sap_raw_events — RAW 이벤트 (ingestion provenance)
 */
@Entity
@Table(schema = "dwp_aura", name = "sap_raw_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SapRawEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "source_system", nullable = false, columnDefinition = "TEXT")
    private String sourceSystem;

    @Column(name = "interface_name", nullable = false, columnDefinition = "TEXT")
    private String interfaceName;

    @Column(name = "extract_date", nullable = false)
    private LocalDate extractDate;

    @Column(name = "payload_format", nullable = false, columnDefinition = "TEXT")
    private String payloadFormat;

    @Column(name = "s3_object_key", columnDefinition = "TEXT")
    private String s3ObjectKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "jsonb")
    private JsonNode payloadJson;

    @Column(name = "checksum", columnDefinition = "TEXT")
    private String checksum;

    @Column(name = "status", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String status = "RECEIVED";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

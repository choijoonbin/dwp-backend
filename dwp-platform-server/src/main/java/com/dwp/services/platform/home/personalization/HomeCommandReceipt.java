package com.dwp.services.platform.home.personalization;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "usr_home_command_receipts")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeCommandReceipt {
    @Id
    @Column(name = "receipt_id", nullable = false)
    private UUID receiptId;
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    @Column(name = "actor_id", nullable = false)
    private Long actorId;
    @Column(name = "command_id", nullable = false)
    private UUID commandId;
    @Column(name = "operation", nullable = false, length = 48)
    private String operation;
    @Column(name = "target_key", nullable = false, length = 160)
    private String targetKey;
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;
    @Column(name = "response_type", nullable = false, length = 160)
    private String responseType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode responsePayload;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
}

package com.dwp.services.platform.widgetregistry;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "plt_widget_command_receipts")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WidgetCommandReceipt {
    @Id
    @Column(name = "receipt_id", nullable = false)
    private UUID receiptId;
    @Column(name = "actor_id", nullable = false)
    private Long actorId;
    @Column(name = "command_id", nullable = false)
    private UUID commandId;
    @Column(name = "operation", nullable = false, length = 80)
    private String operation;
    @Column(name = "target_key", nullable = false, length = 200)
    private String targetKey;
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;
    @Column(name = "response_type", nullable = false, length = 200)
    private String responseType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode responsePayload;
    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
}

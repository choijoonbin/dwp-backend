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
@Table(name = "plt_widget_registry_events")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WidgetRegistryEvent {
    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;
    @Column(name = "registry_revision", nullable = false)
    private Long registryRevision;
    @Column(name = "tenant_id")
    private Long tenantId;
    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;
    @Column(name = "aggregate_id", nullable = false, length = 160)
    private String aggregateId;
    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;
    @Column(name = "command_id")
    private UUID commandId;
    @Column(name = "actor_id", nullable = false)
    private Long actorId;
    @Column(name = "correlation_id", length = 128)
    private String correlationId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_snapshot", columnDefinition = "jsonb")
    private JsonNode beforeSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_snapshot", columnDefinition = "jsonb")
    private JsonNode afterSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_refs", nullable = false, columnDefinition = "jsonb")
    private JsonNode evidenceRefs;
    @Builder.Default
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
}

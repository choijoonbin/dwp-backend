package com.dwp.services.platform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sys_platform_audit_events")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformAuditEvent {

    @Id
    @Column(name = "audit_event_id", nullable = false, updatable = false)
    private UUID auditEventId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "actor_type", nullable = false, length = 20, updatable = false)
    private String actorType;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "action", nullable = false, length = 120, updatable = false)
    private String action;

    @Column(name = "target_type", nullable = false, length = 80, updatable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 160, updatable = false)
    private String targetId;

    @Column(name = "outcome", nullable = false, length = 20, updatable = false)
    private String outcome;

    @Column(name = "correlation_id", length = 128, updatable = false)
    private String correlationId;

    @Column(name = "before_snapshot", columnDefinition = "TEXT", updatable = false)
    private String beforeSnapshot;

    @Column(name = "after_snapshot", columnDefinition = "TEXT", updatable = false)
    private String afterSnapshot;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
}

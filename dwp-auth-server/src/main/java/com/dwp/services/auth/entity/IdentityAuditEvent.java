package com.dwp.services.auth.entity;

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
@Table(name = "sys_identity_audit_events")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdentityAuditEvent {

    @Id
    @Column(name = "audit_event_id", nullable = false, updatable = false)
    private UUID auditEventId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private Long actorId;

    @Column(name = "action", nullable = false, updatable = false, length = 120)
    private String action;

    @Column(name = "target_type", nullable = false, updatable = false, length = 80)
    private String targetType;

    @Column(name = "target_id", nullable = false, updatable = false, length = 160)
    private String targetId;

    @Column(name = "correlation_id", updatable = false, length = 128)
    private String correlationId;

    @Column(name = "before_snapshot", updatable = false, columnDefinition = "TEXT")
    private String beforeSnapshot;

    @Column(name = "after_snapshot", updatable = false, columnDefinition = "TEXT")
    private String afterSnapshot;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
}


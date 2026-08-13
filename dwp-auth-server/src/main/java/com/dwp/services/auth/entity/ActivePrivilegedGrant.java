package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "com_active_privileged_grants")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivePrivilegedGrant extends BaseEntity {

    @Id
    @Column(name = "active_privileged_grant_id", nullable = false, updatable = false)
    private UUID activePrivilegedGrantId;

    @Column(name = "privileged_access_request_id", nullable = false)
    private UUID privilegedAccessRequestId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "scope_ref", length = 160)
    private String scopeRef;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "revoke_reason", length = 1000)
    private String revokeReason;
}

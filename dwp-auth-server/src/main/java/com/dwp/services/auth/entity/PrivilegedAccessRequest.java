package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "com_privileged_access_requests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrivilegedAccessRequest extends BaseEntity {

    @Id
    @Column(name = "privileged_access_request_id", nullable = false, updatable = false)
    private UUID privilegedAccessRequestId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "requester_user_id", nullable = false)
    private Long requesterUserId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "eligibility_id")
    private UUID eligibilityId;

    @Column(name = "request_type", nullable = false, length = 20)
    private String requestType;

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "scope_ref", length = 160)
    private String scopeRef;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    @Column(nullable = false, length = 1000)
    private String justification;

    @Column(name = "ticket_reference", length = 160)
    private String ticketReference;

    @Column(name = "assurance_level", nullable = false, length = 20)
    private String assuranceLevel;

    @Column(name = "approval_quorum", nullable = false)
    private Short approvalQuorum;

    @Column(name = "lifecycle_state", nullable = false, length = 24)
    private String lifecycleState;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    @Column(nullable = false)
    private Long version;
}

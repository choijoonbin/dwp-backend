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
@Table(name = "com_privileged_access_approvals")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrivilegedAccessApproval extends BaseEntity {

    @Id
    @Column(name = "privileged_access_approval_id", nullable = false, updatable = false)
    private UUID privilegedAccessApprovalId;

    @Column(name = "privileged_access_request_id", nullable = false)
    private UUID privilegedAccessRequestId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "approver_user_id", nullable = false)
    private Long approverUserId;

    @Column(nullable = false, length = 20)
    private String decision;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;
}

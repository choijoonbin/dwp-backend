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
@Table(name = "com_delegated_admin_scopes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DelegatedAdminScope extends BaseEntity {

    @Id
    @Column(name = "delegated_admin_scope_id", nullable = false, updatable = false)
    private UUID delegatedAdminScopeId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "administrator_user_id", nullable = false)
    private Long administratorUserId;

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "scope_ref", length = 160)
    private String scopeRef;

    @Column(name = "action_code", nullable = false, length = 80)
    private String actionCode;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState;

    @Column(nullable = false, length = 1000)
    private String justification;

    @Version
    @Column(nullable = false)
    private Long version;
}

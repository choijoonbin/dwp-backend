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
@Table(name = "com_privileged_role_eligibilities")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrivilegedRoleEligibility extends BaseEntity {

    @Id
    @Column(name = "privileged_role_eligibility_id", nullable = false, updatable = false)
    private UUID privilegedRoleEligibilityId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "principal_type", nullable = false, length = 20)
    private String principalType;

    @Column(name = "principal_id", nullable = false)
    private Long principalId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "scope_ref", length = 160)
    private String scopeRef;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(nullable = false, length = 1000)
    private String justification;

    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState;

    @Version
    @Column(nullable = false)
    private Long version;
}

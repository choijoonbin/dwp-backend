package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "com_privileged_access_policies")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrivilegedAccessPolicy extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "privileged_access_policy_id")
    private Long privilegedAccessPolicyId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "activation_mode", nullable = false, length = 24)
    private String activationMode;

    @Column(name = "maximum_duration_minutes", nullable = false)
    private Integer maximumDurationMinutes;

    @Column(name = "assurance_level", nullable = false, length = 20)
    private String assuranceLevel;

    @Column(name = "approval_quorum", nullable = false)
    private Short approvalQuorum;

    @Column(name = "emergency_mode", nullable = false, length = 24)
    private String emergencyMode;

    @Column(name = "ticket_required", nullable = false)
    private Boolean ticketRequired;

    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState;

    @Version
    @Column(nullable = false)
    private Long version;
}

package com.dwp.services.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sys_role_assignment_policies")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleAssignmentPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "assignment_policy_id")
    private Long assignmentPolicyId;

    @Column(name = "grantor_role_code", nullable = false, length = 50)
    private String grantorRoleCode;

    @Column(name = "target_role_code", nullable = false, length = 50)
    private String targetRoleCode;

    @Column(name = "assignment_mode", nullable = false, length = 20)
    private String assignmentMode;

    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState;
}

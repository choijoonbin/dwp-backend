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
@Table(name = "sys_role_conflict_policies")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleConflictPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "role_conflict_policy_id")
    private Long roleConflictPolicyId;

    @Column(name = "left_role_code", nullable = false, length = 50)
    private String leftRoleCode;

    @Column(name = "right_role_code", nullable = false, length = 50)
    private String rightRoleCode;

    @Column(name = "reason_code", nullable = false, length = 80)
    private String reasonCode;

    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState;
}

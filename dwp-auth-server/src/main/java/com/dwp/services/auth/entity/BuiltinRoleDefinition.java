package com.dwp.services.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sys_builtin_role_catalog")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuiltinRoleDefinition {

    @Id
    @Column(name = "role_code", length = 50)
    private String roleCode;

    @Column(name = "role_family", nullable = false, length = 24)
    private String roleFamily;

    @Column(name = "assignment_class", nullable = false, length = 24)
    private String assignmentClass;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState;
}

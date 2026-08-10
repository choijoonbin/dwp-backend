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

import java.time.Instant;

@Entity
@Table(name = "com_group_role_assignments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupRoleAssignment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "group_role_assignment_id")
    private Long groupRoleAssignmentId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Builder.Default
    @Column(name = "assignment_type", nullable = false, length = 20)
    private String assignmentType = "ACTIVE";

    @Builder.Default
    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType = "TENANT";

    @Column(name = "scope_ref", length = 160)
    private String scopeRef;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Builder.Default
    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState = "ACTIVE";

    @Column(length = 1000)
    private String justification;

    @Version
    @Column(nullable = false)
    private Long version;
}

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
@Table(name = "com_emergency_access_principals")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmergencyAccessPrincipal extends BaseEntity {

    @Id
    @Column(name = "emergency_access_principal_id", nullable = false, updatable = false)
    private UUID emergencyAccessPrincipalId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 1000)
    private String justification;

    @Column(name = "review_due_at", nullable = false)
    private Instant reviewDueAt;

    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState;

    @Version
    @Column(nullable = false)
    private Long version;
}

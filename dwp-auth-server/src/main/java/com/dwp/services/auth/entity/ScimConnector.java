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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sys_scim_connectors")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScimConnector extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "scim_connector_id")
    private UUID scimConnectorId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "connector_key", nullable = false, length = 100)
    private String connectorKey;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "token_prefix", nullable = false, length = 24)
    private String tokenPrefix;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "allowed_operations", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String allowedOperations;

    @Builder.Default
    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState = "ACTIVE";

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Version
    @Column(nullable = false)
    private Long version;
}

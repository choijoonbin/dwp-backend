package com.dwp.services.provider.tenant;

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

import java.util.UUID;

@Entity
@Table(name = "prv_tenants")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderTenant extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "provider_tenant_id")
    private UUID providerTenantId;

    @Column(name = "tenant_key", nullable = false, unique = true, length = 80)
    private String tenantKey;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "display_name", nullable = false, length = 240)
    private String displayName;

    @Column(name = "service_tier", nullable = false, length = 30)
    private String serviceTier;

    @Column(name = "data_region", nullable = false, length = 40)
    private String dataRegion;

    @Column(name = "isolation_model", nullable = false, length = 20)
    private String isolationModel;

    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState;

    @Column(name = "onboarding_state", nullable = false, length = 30)
    private String onboardingState;

    @Column(name = "auth_tenant_id")
    private Long authTenantId;

    @Column(name = "environment_key", nullable = false, length = 32)
    @Builder.Default
    private String environmentKey = "production";

    @Column(name = "default_locale", nullable = false, length = 35)
    @Builder.Default
    private String defaultLocale = "ko";

    @Column(name = "time_zone", nullable = false, length = 80)
    @Builder.Default
    private String timeZone = "Asia/Seoul";

    @Column(name = "schema_version", nullable = false)
    @Builder.Default
    private Integer schemaVersion = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "configuration", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private String configuration = "{}";

    @Column(name = "entitlement_revision", nullable = false)
    @Builder.Default
    private Long entitlementRevision = 0L;

    @Version
    @Column(nullable = false)
    private Long version;
}

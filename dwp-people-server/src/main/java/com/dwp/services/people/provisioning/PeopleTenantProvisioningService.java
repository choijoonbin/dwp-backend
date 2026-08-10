package com.dwp.services.people.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PeopleTenantProvisioningService {

    private final JdbcTemplate jdbc;

    public PeopleTenantProvisioningService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public PeopleTenantProvisioningDtos.ProvisionTenantResponse provision(
            PeopleTenantProvisioningDtos.ProvisionTenantRequest request) {
        validateIdentity(request.providerTenantId(), request.tenantId(), request.tenantKey());
        jdbc.update("""
                INSERT INTO sys_service_tenants (
                    provider_tenant_id, tenant_id, tenant_key, display_name,
                    lifecycle_state, data_region, isolation_model)
                VALUES (?, ?, ?, ?, 'PROVISIONING', ?, ?)
                ON CONFLICT (provider_tenant_id) DO UPDATE
                SET display_name = EXCLUDED.display_name,
                    data_region = EXCLUDED.data_region,
                    isolation_model = EXCLUDED.isolation_model,
                    updated_at = CURRENT_TIMESTAMP,
                    version = sys_service_tenants.version + 1
                """, request.providerTenantId(), request.tenantId(), request.tenantKey(),
                request.displayName(), request.dataRegion(), request.isolationModel());
        jdbc.update("""
                INSERT INTO ppl_organizations (
                    tenant_id, organization_key, organization_type, name, lifecycle_state)
                VALUES (?, 'ROOT', 'COMPANY', ?, 'INACTIVE')
                ON CONFLICT (tenant_id, organization_key) DO UPDATE
                SET name = EXCLUDED.name, updated_at = CURRENT_TIMESTAMP
                """, request.tenantId(), request.displayName());
        return new PeopleTenantProvisioningDtos.ProvisionTenantResponse(
                request.providerTenantId(), request.tenantId(), "PROVISIONING", 1,
                "people-tenant:" + request.tenantId());
    }

    @Transactional
    public PeopleTenantProvisioningDtos.ProvisionTenantResponse lifecycle(
            UUID providerTenantId,
            PeopleTenantProvisioningDtos.UpdateLifecycleRequest request) {
        ServiceTenant tenant = requireTenant(providerTenantId);
        jdbc.update("""
                UPDATE sys_service_tenants
                   SET lifecycle_state = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE provider_tenant_id = ?
                """, request.lifecycleState(), providerTenantId);
        jdbc.update("""
                UPDATE ppl_organizations
                   SET lifecycle_state = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE tenant_id = ? AND organization_key = 'ROOT'
                """, "ACTIVE".equals(request.lifecycleState()) ? "ACTIVE" : "INACTIVE", tenant.tenantId());
        return new PeopleTenantProvisioningDtos.ProvisionTenantResponse(
                providerTenantId, tenant.tenantId(), request.lifecycleState(), 1,
                "people-tenant:" + tenant.tenantId());
    }

    private void validateIdentity(UUID providerTenantId, Long tenantId, String tenantKey) {
        List<ServiceTenant> existing = jdbc.query("""
                SELECT provider_tenant_id, tenant_id, tenant_key, lifecycle_state
                  FROM sys_service_tenants WHERE provider_tenant_id = ?
                """, (result, ignored) -> new ServiceTenant(
                        result.getObject("provider_tenant_id", UUID.class),
                        result.getLong("tenant_id"),
                        result.getString("tenant_key"),
                        result.getString("lifecycle_state")), providerTenantId);
        if (!existing.isEmpty()
                && (!existing.get(0).tenantId().equals(tenantId)
                || !existing.get(0).tenantKey().equals(tenantKey))) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The provider tenant is already mapped to another people tenant.");
        }
    }

    private ServiceTenant requireTenant(UUID providerTenantId) {
        return jdbc.query("""
                SELECT provider_tenant_id, tenant_id, tenant_key, lifecycle_state
                  FROM sys_service_tenants WHERE provider_tenant_id = ?
                """, (result, ignored) -> new ServiceTenant(
                        result.getObject("provider_tenant_id", UUID.class),
                        result.getLong("tenant_id"),
                        result.getString("tenant_key"),
                        result.getString("lifecycle_state")), providerTenantId)
                .stream().findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private record ServiceTenant(
            UUID providerTenantId,
            Long tenantId,
            String tenantKey,
            String lifecycleState) {
    }
}

package com.dwp.services.platform.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
public class PlatformTenantProvisioningService {

    private final JdbcTemplate jdbc;
    private final Path assetRoot;

    public PlatformTenantProvisioningService(
            JdbcTemplate jdbc,
            @Value("${dwp.platform.assets.root:${user.home}/.dwp/platform-assets}") String assetRoot) {
        this.jdbc = jdbc;
        this.assetRoot = Path.of(assetRoot).toAbsolutePath().normalize();
    }

    @Transactional
    public PlatformTenantProvisioningDtos.ProvisionTenantResponse provision(
            PlatformTenantProvisioningDtos.ProvisionTenantRequest request) {
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
                INSERT INTO adm_tenant_branding (tenant_id, organization_name)
                VALUES (?, ?)
                ON CONFLICT (tenant_id) DO NOTHING
                """, request.tenantId(), request.displayName());
        jdbc.update("""
                INSERT INTO adm_home_experiences (
                    tenant_id, headline, subheadline, background_position, overlay_opacity)
                VALUES (?, ?, ?, 'CENTER', 18)
                ON CONFLICT (tenant_id) DO NOTHING
                """, request.tenantId(), request.displayName(), "Digital Workplace");
        seedLocales(request.tenantId(), request.defaultLocale());
        seedRegistry(request.tenantId(), request.entitlementKeys());
        seedNavigation(request.tenantId(), request.defaultLocale(), request.entitlementKeys());
        return new PlatformTenantProvisioningDtos.ProvisionTenantResponse(
                request.providerTenantId(), request.tenantId(), "PROVISIONING", 1,
                "platform-tenant:" + request.tenantId());
    }

    @Transactional
    public PlatformTenantProvisioningDtos.ProvisionTenantResponse lifecycle(
            UUID providerTenantId,
            PlatformTenantProvisioningDtos.UpdateLifecycleRequest request) {
        ServiceTenant tenant = requireTenant(providerTenantId);
        jdbc.update("""
                UPDATE sys_service_tenants
                   SET lifecycle_state = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE provider_tenant_id = ?
                """, request.lifecycleState(), providerTenantId);
        return new PlatformTenantProvisioningDtos.ProvisionTenantResponse(
                providerTenantId, tenant.tenantId(), request.lifecycleState(), 1,
                "platform-tenant:" + tenant.tenantId());
    }

    @Transactional
    public PlatformTenantProvisioningDtos.ProvisionTenantResponse replaceEntitlements(
            UUID providerTenantId,
            PlatformTenantProvisioningDtos.ReplaceEntitlementsRequest request) {
        ServiceTenant tenant = requireTenant(providerTenantId);
        seedRegistry(tenant.tenantId(), request.entitlementKeys());
        seedNavigation(tenant.tenantId(), "en", request.entitlementKeys());
        Set<String> desired = applications(request.entitlementKeys()).stream()
                .map(AppSeed::navigationKey)
                .collect(Collectors.toSet());
        for (AppSeed app : allApplications()) {
            if (desired.contains(app.navigationKey())) continue;
            jdbc.update("""
                    UPDATE adm_navigation_items
                       SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP,
                           version = version + 1
                     WHERE tenant_id = ? AND navigation_key = ?
                    """, tenant.tenantId(), app.navigationKey());
            jdbc.update("""
                    UPDATE adm_registry_entries
                       SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = ? AND registry_type = 'APP' AND entry_key = ?
                    """, tenant.tenantId(), app.registryKey());
        }
        return new PlatformTenantProvisioningDtos.ProvisionTenantResponse(
                providerTenantId, tenant.tenantId(), tenant.lifecycleState(), 1,
                "platform-tenant:" + tenant.tenantId());
    }

    public PlatformTenantProvisioningDtos.ProvisionTenantResponse provisionStorage(UUID providerTenantId) {
        ServiceTenant tenant = requireTenant(providerTenantId);
        Path tenantRoot = assetRoot.resolve(String.valueOf(tenant.tenantId())).normalize();
        if (!tenantRoot.startsWith(assetRoot)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid tenant storage root.");
        }
        try {
            Files.createDirectories(tenantRoot);
        } catch (IOException exception) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Tenant asset storage could not be provisioned.",
                    exception);
        }
        return new PlatformTenantProvisioningDtos.ProvisionTenantResponse(
                providerTenantId, tenant.tenantId(), tenant.lifecycleState(), 1,
                tenantRoot.toString());
    }

    private void seedLocales(Long tenantId, String defaultLocale) {
        Map<String, String> locales = new LinkedHashMap<>();
        locales.put(defaultLocale, defaultLocale.startsWith("ko") ? "한국어" : "English");
        locales.putIfAbsent("ko", "한국어");
        locales.putIfAbsent("en", "English");
        int order = 0;
        for (Map.Entry<String, String> locale : locales.entrySet()) {
            jdbc.update("""
                    INSERT INTO adm_tenant_locales (
                        tenant_id, locale, display_name, default_locale,
                        lifecycle_state, sort_order)
                    VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                    ON CONFLICT (tenant_id, locale) DO UPDATE
                    SET display_name = EXCLUDED.display_name,
                        default_locale = EXCLUDED.default_locale,
                        lifecycle_state = 'ACTIVE',
                        sort_order = EXCLUDED.sort_order,
                        updated_at = CURRENT_TIMESTAMP
                    """, tenantId, locale.getKey(), locale.getValue(),
                    locale.getKey().equals(defaultLocale), order++);
        }
    }

    private void seedRegistry(Long tenantId, List<String> entitlements) {
        for (AppSeed app : applications(entitlements)) {
            jdbc.update("""
                    INSERT INTO adm_registry_entries (
                        tenant_id, registry_type, entry_key, revision, name,
                        description, owner_ref, risk_tier, artifact_version, lifecycle_state)
                    VALUES (?, 'APP', ?, 1, ?, ?, 'platform:workspace', ?, '1.0.0', 'ACTIVE')
                    ON CONFLICT (tenant_id, registry_type, entry_key, revision) DO UPDATE
                    SET name = EXCLUDED.name,
                        description = EXCLUDED.description,
                        lifecycle_state = 'ACTIVE',
                        updated_at = CURRENT_TIMESTAMP
                    """, tenantId, app.registryKey(), app.englishLabel(),
                    app.description(), app.riskTier());
        }
    }

    private void seedNavigation(Long tenantId, String defaultLocale, List<String> entitlements) {
        jdbc.update("""
                INSERT INTO adm_navigation_items (
                    tenant_id, navigation_key, item_type, required_permission_code,
                    sort_order, lifecycle_state)
                VALUES (?, 'workspace', 'GROUP', 'VIEW', 10, 'ACTIVE')
                ON CONFLICT (tenant_id, navigation_key) DO UPDATE
                SET lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
                """, tenantId);
        Long parentId = jdbc.queryForObject("""
                SELECT navigation_item_id FROM adm_navigation_items
                 WHERE tenant_id = ? AND navigation_key = 'workspace'
                """, Long.class, tenantId);
        int order = 10;
        for (AppSeed app : applications(entitlements)) {
            jdbc.update("""
                    INSERT INTO adm_navigation_items (
                        tenant_id, navigation_key, item_type, parent_navigation_item_id,
                        registry_entry_key, route, icon_key, required_resource_key,
                        required_permission_code, sort_order, lifecycle_state)
                    VALUES (?, ?, 'APP', ?, ?, ?, ?, ?, 'VIEW', ?, 'ACTIVE')
                    ON CONFLICT (tenant_id, navigation_key) DO UPDATE
                    SET parent_navigation_item_id = EXCLUDED.parent_navigation_item_id,
                        registry_entry_key = EXCLUDED.registry_entry_key,
                        route = EXCLUDED.route,
                        icon_key = EXCLUDED.icon_key,
                        required_resource_key = EXCLUDED.required_resource_key,
                        sort_order = EXCLUDED.sort_order,
                        lifecycle_state = 'ACTIVE',
                        updated_at = CURRENT_TIMESTAMP
                    """, tenantId, app.navigationKey(), parentId, app.registryKey(),
                    app.route(), app.iconKey(), app.resourceKey(), order);
            order += 10;
        }
        List<NavigationLabel> labels = List.of(
                new NavigationLabel("workspace", "en", "Workspace", "Daily work applications"),
                new NavigationLabel("workspace", "ko", "워크스페이스", "일상 업무 애플리케이션"));
        for (AppSeed app : applications(entitlements)) {
            labels = new java.util.ArrayList<>(labels);
            labels.add(new NavigationLabel(app.navigationKey(), "en", app.englishLabel(), app.description()));
            labels.add(new NavigationLabel(app.navigationKey(), "ko", app.koreanLabel(), app.description()));
        }
        for (NavigationLabel label : labels) {
            jdbc.update("""
                    INSERT INTO adm_navigation_labels (
                        tenant_id, navigation_item_id, locale, label, description)
                    SELECT ?, navigation_item_id, ?, ?, ?
                      FROM adm_navigation_items
                     WHERE tenant_id = ? AND navigation_key = ?
                    ON CONFLICT (tenant_id, navigation_item_id, locale) DO UPDATE
                    SET label = EXCLUDED.label,
                        description = EXCLUDED.description,
                        updated_at = CURRENT_TIMESTAMP
                    """, tenantId, label.locale(), label.label(), label.description(),
                    tenantId, label.navigationKey());
        }
    }

    private List<AppSeed> applications(List<String> entitlements) {
        java.util.ArrayList<AppSeed> apps = new java.util.ArrayList<>();
        if (entitlements.contains("core.workspace")) {
            apps.add(new AppSeed("work", "DWP_WORK", "/work", "work", "APP.WORK",
                    "Work", "업무", "Priorities, approvals, and tasks", "LOW"));
            apps.add(new AppSeed("activity", "DWP_ACTIVITY", "/activity", "activity", "APP.ACTIVITY",
                    "Activity", "활동", "Human, system, and agent events", "LOW"));
            apps.add(new AppSeed("apps", "DWP_APPS", "/apps", "apps", "APP.APPS",
                    "Apps", "앱", "Available workplace applications", "LOW"));
        }
        if (entitlements.contains("ai.agent-runtime")) {
            apps.add(new AppSeed("ask", "DWP_ASK", "/ask", "ask", "APP.ASK",
                    "Ask DWP", "Ask DWP", "Grounded answers and governed actions", "MEDIUM"));
        }
        if (entitlements.contains("core.people")) {
            apps.add(new AppSeed("people", "DWP_PEOPLE", "/people", "people",
                    "APP.PEOPLE_DIRECTORY", "People", "구성원",
                    "Find colleagues and explore reporting relationships", "LOW"));
            apps.add(new AppSeed("workforce", "DWP_WORKFORCE", "/workforce", "workforce",
                    "APP.WORKFORCE_MANAGEMENT", "Workforce management", "인력 운영",
                    "Govern workforce data, positions, organization design, and HRIS operations",
                    "HIGH"));
        }
        return List.copyOf(apps);
    }

    private List<AppSeed> allApplications() {
        return List.of(
                new AppSeed("work", "DWP_WORK", "/work", "work", "APP.WORK",
                        "Work", "업무", "Priorities, approvals, and tasks", "LOW"),
                new AppSeed("activity", "DWP_ACTIVITY", "/activity", "activity", "APP.ACTIVITY",
                        "Activity", "활동", "Human, system, and agent events", "LOW"),
                new AppSeed("apps", "DWP_APPS", "/apps", "apps", "APP.APPS",
                        "Apps", "앱", "Available workplace applications", "LOW"),
                new AppSeed("ask", "DWP_ASK", "/ask", "ask", "APP.ASK",
                        "Ask DWP", "Ask DWP", "Grounded answers and governed actions", "MEDIUM"),
                new AppSeed("people", "DWP_PEOPLE", "/people", "people",
                        "APP.PEOPLE_DIRECTORY", "People", "구성원",
                        "Find colleagues and explore reporting relationships", "LOW"),
                new AppSeed("workforce", "DWP_WORKFORCE", "/workforce", "workforce",
                        "APP.WORKFORCE_MANAGEMENT", "Workforce management", "인력 운영",
                        "Govern workforce data, positions, organization design, and HRIS operations",
                        "HIGH"));
    }

    private void validateIdentity(UUID providerTenantId, Long tenantId, String tenantKey) {
        List<ServiceTenant> byProvider = jdbc.query("""
                SELECT provider_tenant_id, tenant_id, tenant_key, lifecycle_state
                  FROM sys_service_tenants WHERE provider_tenant_id = ?
                """, (result, ignored) -> new ServiceTenant(
                        result.getObject("provider_tenant_id", UUID.class),
                        result.getLong("tenant_id"),
                        result.getString("tenant_key"),
                        result.getString("lifecycle_state")), providerTenantId);
        if (!byProvider.isEmpty()
                && (!byProvider.get(0).tenantId().equals(tenantId)
                || !byProvider.get(0).tenantKey().equals(tenantKey))) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The provider tenant is already mapped to another platform tenant.");
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

    private record AppSeed(
            String navigationKey,
            String registryKey,
            String route,
            String iconKey,
            String resourceKey,
            String englishLabel,
            String koreanLabel,
            String description,
            String riskTier) {
    }

    private record NavigationLabel(
            String navigationKey,
            String locale,
            String label,
            String description) {
    }
}

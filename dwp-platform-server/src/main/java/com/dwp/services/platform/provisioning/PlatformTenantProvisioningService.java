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
        seedManagedPreferencePolicy(request.tenantId());
        seedLocales(request.tenantId(), request.defaultLocale());
        seedRegistry(request.tenantId(), request.entitlementKeys());
        seedNavigation(request.tenantId(), request.defaultLocale(), request.entitlementKeys());
        seedWorkspaceApps(request.tenantId(), request.entitlementKeys());
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
        seedWorkspaceApps(tenant.tenantId(), request.entitlementKeys());
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
                "asset-storage:tenant:" + tenant.tenantId());
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

    private void seedManagedPreferencePolicy(Long tenantId) {
        jdbc.update("""
                INSERT INTO adm_managed_preference_policies (tenant_id)
                VALUES (?)
                ON CONFLICT (tenant_id) DO NOTHING
                """, tenantId);
        jdbc.update("""
                INSERT INTO adm_managed_preference_rules (
                    managed_preference_policy_id, tenant_id, preference_path,
                    display_key, managed_value, exception_allowed)
                SELECT policy.managed_preference_policy_id, policy.tenant_id,
                       rule.preference_path, rule.display_key, rule.managed_value, TRUE
                  FROM adm_managed_preference_policies policy
                  CROSS JOIN (VALUES
                    ('appearance.fontFamily', 'settings.productFont.title', 'null'::jsonb),
                    ('appearance.accentColor', 'settings.brandAccent.title', 'null'::jsonb),
                    ('navigation.pattern', 'settings.navigationPattern.title', '"sidebar"'::jsonb)
                  ) AS rule(preference_path, display_key, managed_value)
                 WHERE policy.tenant_id = ?
                ON CONFLICT (tenant_id, preference_path) DO NOTHING
                """, tenantId);
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
            apps.add(new AppSeed(
                    "communications", "DWP_COMMUNICATIONS", "/communications", "communications",
                    "APP.COMMUNICATIONS", "Newsroom", "소식",
                    "Targeted company news, events, and required updates", "LOW"));
            apps.add(new AppSeed("apps", "DWP_APPS", "/apps", "apps", "APP.APPS",
                    "Apps", "앱", "Available workplace applications", "LOW"));
        }
        if (entitlements.contains("ai.agent-runtime")) {
            apps.add(new AppSeed("ask", "DWP_ASK", "/ask", "ask", "APP.ASK",
                    "Ask DWP", "Ask DWP", "Read-only request plans with an audit trace", "MEDIUM"));
        }
        if (entitlements.contains("core.people")) {
            apps.add(new AppSeed("hris", "DWP_HRIS", "/hr", "hris",
                    "APP.HRIS", "HRIS", "HRIS",
                    "Personal HR, people, organization, and governed workforce operations",
                    "MEDIUM"));
        }
        return List.copyOf(apps);
    }

    private void seedWorkspaceApps(Long tenantId, List<String> entitlements) {
        jdbc.update("""
                UPDATE adm_workspace_apps
                   SET lifecycle_state = 'RETIRED',
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ?
                """, tenantId);
        for (WorkspaceAppSeed app : workspaceApplications(entitlements)) {
            jdbc.update("""
                    INSERT INTO adm_workspace_apps (
                        tenant_id, app_key, name_ko, name_en,
                        description_ko, description_en, owner_name, category,
                        launch_mode, launch_target, icon_key, resource_key,
                        health_state, sort_order, lifecycle_state)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                    ON CONFLICT (tenant_id, app_key) DO UPDATE SET
                        name_ko = EXCLUDED.name_ko,
                        name_en = EXCLUDED.name_en,
                        description_ko = EXCLUDED.description_ko,
                        description_en = EXCLUDED.description_en,
                        owner_name = EXCLUDED.owner_name,
                        category = EXCLUDED.category,
                        launch_mode = EXCLUDED.launch_mode,
                        launch_target = EXCLUDED.launch_target,
                        icon_key = EXCLUDED.icon_key,
                        resource_key = EXCLUDED.resource_key,
                        health_state = EXCLUDED.health_state,
                        sort_order = EXCLUDED.sort_order,
                        lifecycle_state = 'ACTIVE',
                        version = adm_workspace_apps.version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    """, tenantId, app.appKey(), app.nameKo(), app.nameEn(),
                    app.descriptionKo(), app.descriptionEn(), app.owner(), app.category(),
                    app.launchMode(), app.launchTarget(), app.iconKey(), app.resourceKey(),
                    app.health(), app.sortOrder());
        }
    }

    private List<WorkspaceAppSeed> workspaceApplications(List<String> entitlements) {
        java.util.ArrayList<WorkspaceAppSeed> apps = new java.util.ArrayList<>();
        if (entitlements.contains("core.workspace")) {
            apps.add(new WorkspaceAppSeed(
                    "dwp-work", "업무", "Work",
                    "우선순위, 승인 및 할 일을 한곳에서 처리합니다.",
                    "Manage priorities, approvals, and tasks in one place.",
                    "DWP Platform", "PRODUCTIVITY", "NATIVE", "/work",
                    "work", "APP.WORK", "HEALTHY", 10));
            apps.add(new WorkspaceAppSeed(
                    "dwp-activity", "활동", "Activity",
                    "사용자, 시스템 및 에이전트 활동을 추적합니다.",
                    "Track human, system, and agent activity.",
                    "DWP Platform", "PRODUCTIVITY", "NATIVE", "/activity",
                    "activity", "APP.ACTIVITY", "HEALTHY", 30));
            apps.add(new WorkspaceAppSeed(
                    "dwp-communications", "소식", "Newsroom",
                    "나에게 필요한 회사 소식, 행사 및 필수 확인 콘텐츠를 한곳에서 읽습니다.",
                    "Read targeted company news, events, and required updates in one place.",
                    "DWP Communications", "PRODUCTIVITY", "NATIVE", "/communications",
                    "communications", "APP.COMMUNICATIONS", "HEALTHY", 35));
            apps.add(new WorkspaceAppSeed(
                    "ref-app-mail", "메일 및 일정", "Mail & calendar",
                    "메시지, 일정 및 회의 후속 조치를 연결합니다.",
                    "Connect messages, calendars, and meeting follow-ups.",
                    "Workplace Platform", "PRODUCTIVITY", "SSO", null,
                    "mail", "APP.MAIL_CALENDAR", "CONFIGURATION_REQUIRED", 40));
            apps.add(new WorkspaceAppSeed(
                    "ref-app-collaboration", "협업", "Collaboration",
                    "채팅, 채널 및 회의를 연결합니다.",
                    "Connect chat, channels, and meetings.",
                    "Workplace Platform", "PRODUCTIVITY", "SSO", null,
                    "collaboration", "APP.COLLABORATION", "CONFIGURATION_REQUIRED", 50));
            apps.add(new WorkspaceAppSeed(
                    "ref-app-service", "서비스 센터", "Services",
                    "IT, 구성원, 업무 환경, 재무 및 구매 요청을 한곳에서 처리합니다.",
                    "Discover and track IT, people, workplace, finance, and procurement services.",
                    "Shared Services", "SERVICE", "NATIVE", "/services",
                    "services", "APP.EMPLOYEE_SERVICES", "HEALTHY", 60));
            apps.add(new WorkspaceAppSeed(
                    "ref-app-knowledge", "지식", "Knowledge",
                    "정책 및 업무 가이드 연결을 구성합니다.",
                    "Configure connections to policies and workplace guides.",
                    "Knowledge Office", "KNOWLEDGE", "SSO", null,
                    "knowledge", "APP.KNOWLEDGE", "CONFIGURATION_REQUIRED", 80));
            apps.add(new WorkspaceAppSeed(
                    "ref-app-erp", "비즈니스 ERP", "Business ERP",
                    "재무 및 구매 업무를 연결합니다.",
                    "Connect finance and purchasing work.",
                    "Finance Platform", "BUSINESS", "SSO", null,
                    "erp", "APP.BUSINESS_ERP", "CONFIGURATION_REQUIRED", 90));
            apps.add(new WorkspaceAppSeed(
                    "ref-app-legacy", "레거시 운영", "Legacy operations",
                    "기존 운영 시스템으로 안전하게 연결합니다.",
                    "Provide governed access to existing operational systems.",
                    "Enterprise Systems", "LEGACY", "DEEP_LINK", null,
                    "legacy", "APP.LEGACY_OPERATIONS", "CONFIGURATION_REQUIRED", 100));
        }
        if (entitlements.contains("ai.agent-runtime")) {
            apps.add(new WorkspaceAppSeed(
                    "dwp-ask", "Ask DWP", "Ask DWP",
                    "감사 추적이 포함된 읽기 전용 요청 계획을 준비합니다.",
                    "Prepare read-only request plans with an audit trace.",
                    "DWP Platform", "KNOWLEDGE", "NATIVE", "/ask",
                    "ask", "APP.ASK", "MANAGED", 20));
        }
        if (entitlements.contains("core.people")) {
            apps.add(new WorkspaceAppSeed(
                    "ref-app-people", "HRIS", "HRIS",
                    "나의 인사, 구성원, 조직 및 권한에 따른 인력 운영을 제공합니다.",
                    "Personal HR, people, organization, and role-aware workforce operations.",
                    "People Platform", "PEOPLE", "NATIVE", "/hr",
                    "hris", "APP.HRIS", "HEALTHY", 70));
        }
        return List.copyOf(apps);
    }

    private List<AppSeed> allApplications() {
        return List.of(
                new AppSeed("work", "DWP_WORK", "/work", "work", "APP.WORK",
                        "Work", "업무", "Priorities, approvals, and tasks", "LOW"),
                new AppSeed("activity", "DWP_ACTIVITY", "/activity", "activity", "APP.ACTIVITY",
                        "Activity", "활동", "Human, system, and agent events", "LOW"),
                new AppSeed(
                        "communications", "DWP_COMMUNICATIONS", "/communications", "communications",
                        "APP.COMMUNICATIONS", "Newsroom", "소식",
                        "Targeted company news, events, and required updates", "LOW"),
                new AppSeed("apps", "DWP_APPS", "/apps", "apps", "APP.APPS",
                        "Apps", "앱", "Available workplace applications", "LOW"),
                new AppSeed("ask", "DWP_ASK", "/ask", "ask", "APP.ASK",
                        "Ask DWP", "Ask DWP", "Read-only request plans with an audit trace", "MEDIUM"),
                new AppSeed("hris", "DWP_HRIS", "/hr", "hris",
                        "APP.HRIS", "HRIS", "HRIS",
                        "Personal HR, people, organization, and governed workforce operations",
                        "MEDIUM"));
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

    private record WorkspaceAppSeed(
            String appKey,
            String nameKo,
            String nameEn,
            String descriptionKo,
            String descriptionEn,
            String owner,
            String category,
            String launchMode,
            String launchTarget,
            String iconKey,
            String resourceKey,
            String health,
            int sortOrder) {
    }
}

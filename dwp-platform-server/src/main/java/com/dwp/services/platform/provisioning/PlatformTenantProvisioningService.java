package com.dwp.services.platform.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.provisioning.ProviderTenantCommand;
import com.dwp.core.provisioning.ProviderTenantCommandReceiptStore;
import com.dwp.services.platform.home.ApprovedHomeApplicationCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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

import static com.dwp.services.platform.registry.GovernedAgentCatalogProfiles.jsonFor;

@Service
public class PlatformTenantProvisioningService {

    private final JdbcTemplate jdbc;
    private final Path assetRoot;
    private final ObjectMapper objectMapper;
    private final ProviderTenantCommandReceiptStore commandReceipts;

    public PlatformTenantProvisioningService(
            JdbcTemplate jdbc,
            @Value("${dwp.platform.assets.root:${user.home}/.dwp/platform-assets}") String assetRoot) {
        this(jdbc, assetRoot, new ObjectMapper());
    }

    @Autowired
    public PlatformTenantProvisioningService(
            JdbcTemplate jdbc,
            @Value("${dwp.platform.assets.root:${user.home}/.dwp/platform-assets}") String assetRoot,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.assetRoot = Path.of(assetRoot).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.commandReceipts = new ProviderTenantCommandReceiptStore(jdbc, objectMapper, "platform");
    }

    @Transactional
    public ProviderTenantCommand.Receipt command(
            UUID providerTenantId,
            ProviderTenantCommand.Request command) {
        return commandReceipts.execute(providerTenantId, command, () -> {
            if ("LIFECYCLE".equals(command.commandType())) {
                String state = command.payload().path("lifecycleState").asText("");
                if (!Set.of("ACTIVE", "SUSPENDED", "RETIRED").contains(state)) {
                    throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid lifecycle command payload.");
                }
                return objectMapper.valueToTree(lifecycle(
                        providerTenantId,
                        new PlatformTenantProvisioningDtos.UpdateLifecycleRequest(state)));
            }
            if ("ENTITLEMENTS".equals(command.commandType())) {
                List<String> keys = new java.util.ArrayList<>();
                command.payload().path("entitlementKeys").forEach(value -> keys.add(value.asText()));
                if (!command.payload().path("entitlementKeys").isArray()
                        || keys.stream().anyMatch(String::isBlank)) {
                    throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid entitlement command payload.");
                }
                return objectMapper.valueToTree(replaceEntitlements(
                        providerTenantId,
                        new PlatformTenantProvisioningDtos.ReplaceEntitlementsRequest(List.copyOf(keys))));
            }
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unsupported provider command type.");
        });
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
        seedWorkplacePolicy(request.tenantId());
        seedLocales(request.tenantId(), request.defaultLocale());
        seedRegistry(request.tenantId(), request.entitlementKeys());
        seedGovernedAgents(request.tenantId(), request.entitlementKeys());
        seedNavigation(request.tenantId(), request.defaultLocale(), request.entitlementKeys());
        seedWorkspaceApps(request.tenantId());
        PlatformCalendarTenantSeeder.seed(jdbc, request.tenantId(), request.displayName());
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
        seedGovernedAgents(tenant.tenantId(), request.entitlementKeys());
        seedNavigation(tenant.tenantId(), "en", request.entitlementKeys());
        seedWorkspaceApps(tenant.tenantId());
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

    private void seedWorkplacePolicy(Long tenantId) {
        jdbc.update("""
                INSERT INTO wp_tenant_policies (
                    tenant_id, booking_window_days, maximum_active_bookings,
                    minimum_booking_minutes, maximum_booking_minutes,
                    maximum_consecutive_days, working_day_start, working_day_end,
                    allow_recurring, require_check_in, check_in_lead_minutes,
                    auto_release_minutes, allow_assigned_desk_lending,
                    show_colleague_names, created_by, updated_by)
                VALUES (?, 28, 12, 30, 720, 5, '08:00', '20:00',
                        TRUE, TRUE, 30, 20, FALSE, FALSE, 1, 1)
                ON CONFLICT (tenant_id) DO NOTHING
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

    private void seedGovernedAgents(Long tenantId, List<String> entitlements) {
        List<AgentSeed> desired = governedAgents(entitlements);
        Set<String> desiredKeys = desired.stream().map(AgentSeed::entryKey).collect(Collectors.toSet());
        for (AgentSeed agent : desired) {
            jdbc.update("""
                    INSERT INTO adm_registry_entries (
                        tenant_id, registry_type, entry_key, revision, name,
                        description, owner_ref, risk_tier, artifact_version, lifecycle_state,
                        agent_catalog_profile)
                    VALUES (?, 'AGENT', ?, 1, ?, ?, ?, ?, ?, 'ACTIVE', CAST(? AS jsonb))
                    ON CONFLICT (tenant_id, registry_type, entry_key, revision) DO UPDATE
                    SET name = EXCLUDED.name,
                        description = EXCLUDED.description,
                        owner_ref = EXCLUDED.owner_ref,
                        risk_tier = EXCLUDED.risk_tier,
                        artifact_version = EXCLUDED.artifact_version,
                        agent_catalog_profile = EXCLUDED.agent_catalog_profile,
                        lifecycle_state = 'ACTIVE',
                        updated_at = CURRENT_TIMESTAMP
                    """, tenantId, agent.entryKey(), agent.name(), agent.description(),
                    agent.ownerRef(), agent.riskTier(), agent.artifactVersion(),
                    jsonFor(agent.entryKey()));
        }
        for (String managedKey : List.of(
                "REFERENCE_PLANNER", "DWP_ASSISTANT", "DWP_APPROVAL_EXPERT")) {
            if (desiredKeys.contains(managedKey)) continue;
            jdbc.update("""
                    UPDATE adm_registry_entries
                       SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = ?
                       AND registry_type = 'AGENT'
                       AND entry_key = ?
                       AND lifecycle_state <> 'RETIRED'
                    """, tenantId, managedKey);
        }
    }

    static List<AgentSeed> governedAgents(List<String> entitlements) {
        java.util.ArrayList<AgentSeed> desired = new java.util.ArrayList<>();
        if (entitlements.contains("ai.agent-runtime")) {
            desired.add(new AgentSeed(
                    "REFERENCE_PLANNER",
                    "DWAI-ON Reference Planner",
                    "Governed read-only action and administration plan previews",
                    "agent:planner",
                    "MEDIUM",
                    "reference-planner-v1"));
            desired.add(new AgentSeed(
                    "DWP_ASSISTANT",
                    "DWAI-ON Workplace Assistant",
                    "Read-only grounded workplace answers with permission-scoped evidence",
                    "agent:runtime",
                    "MEDIUM",
                    "ask-runtime-v2"));
            if (entitlements.contains("core.approvals")) {
                desired.add(new AgentSeed(
                        "DWP_APPROVAL_EXPERT",
                        "DWAI-ON Approval Expert",
                        "Read-only approval intelligence for tasks, requests, forms, SLA, and evidence",
                        "agent:approval",
                        "MEDIUM",
                        "approval-expert-v1"));
            }
        }
        return List.copyOf(desired);
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
            apps.add(new AppSeed("ask", "DWP_ASK", "/dwaion", "ask", "APP.ASK",
                    "DWAI·ON Workspace", "DWAI·ON 워크스페이스",
                    "AI workspace with evidence, sources, and an audit trace", "MEDIUM"));
        }
        if (entitlements.contains("core.people")) {
            apps.add(new AppSeed("hcm", "DWP_HCM", "/hr", "hcm",
                    "APP.HCM", "HR", "인사",
                    "DWP HCM personal HR, organization, and governed workforce operations",
                    "MEDIUM"));
        }
        return List.copyOf(apps);
    }

    private void seedWorkspaceApps(Long tenantId) {
        jdbc.update("""
                UPDATE adm_workspace_apps
                   SET lifecycle_state = 'RETIRED',
                       health_state = 'CONFIGURATION_REQUIRED',
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ?
                   AND (app_key = 'ref-app-collaboration'
                        OR upper(trim(resource_key)) IN (
                            'APP.MAIL_CALENDAR', 'APP.COLLABORATION',
                            'APP.ROOMS', 'APP.HRIS'))
                   AND (lifecycle_state <> 'RETIRED'
                        OR health_state <> 'CONFIGURATION_REQUIRED')
                """, tenantId);
        for (WorkspaceAppSeed app : workspaceApplications()) {
            jdbc.update("""
                    UPDATE adm_workspace_apps
                       SET lifecycle_state = 'RETIRED',
                           health_state = 'CONFIGURATION_REQUIRED',
                           version = version + 1,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = ?
                       AND upper(trim(resource_key)) = ?
                       AND app_key <> ?
                       AND (lifecycle_state <> 'RETIRED'
                            OR health_state <> 'CONFIGURATION_REQUIRED')
                    """, tenantId, app.resourceKey(), app.appKey());
            jdbc.update("""
                    INSERT INTO adm_workspace_apps (
                        tenant_id, app_key, name_ko, name_en,
                        description_ko, description_en, owner_name, category,
                        launch_mode, launch_target, icon_key, resource_key,
                        required_permission_code, badge_source_key,
                        health_state, sort_order, lifecycle_state)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
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
                        required_permission_code = EXCLUDED.required_permission_code,
                        badge_source_key = EXCLUDED.badge_source_key,
                        health_state = EXCLUDED.health_state,
                        sort_order = EXCLUDED.sort_order,
                        lifecycle_state = 'ACTIVE',
                        version = adm_workspace_apps.version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    """, tenantId, app.appKey(), app.nameKo(), app.nameEn(),
                    app.descriptionKo(), app.descriptionEn(), app.owner(), app.category(),
                    app.launchMode(), app.launchTarget(), app.iconKey(), app.resourceKey(),
                    app.requiredPermissionCode(), app.badgeSourceKey(),
                    app.health(), app.sortOrder());
        }
    }

    private List<WorkspaceAppSeed> workspaceApplications() {
        return ApprovedHomeApplicationCatalog.applications().stream()
                .map(application -> new WorkspaceAppSeed(
                        application.appKey(), application.nameKo(), application.nameEn(),
                        application.nameKo() + " 업무를 DWP 홈에서 안전하게 시작합니다.",
                        "Launch " + application.nameEn() + " safely from DWP Home.",
                        application.owner(), application.category(), application.launchMode(),
                        application.launchTarget(), application.iconKey(), application.resourceKey(),
                        application.requiredPermissionCode(), application.badgeSourceKey(),
                        application.healthState(), launchpadSortOrder(application)))
                .toList();
    }

    private int launchpadSortOrder(ApprovedHomeApplicationCatalog.Application application) {
        int groupOffset = switch (application.groupKey()) {
            case "work" -> 0;
            case "connect" -> 100;
            case "services" -> 200;
            case "systems" -> 300;
            default -> 400;
        };
        return groupOffset + application.sortOrder();
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
                new AppSeed(
                        "calendar", "DWP_CALENDAR", "/calendar/home", "calendar",
                        "APP.CALENDAR", "Calendar", "캘린더",
                        "Schedules, focus time, responses, and workplace bookings", "LOW"),
                new AppSeed(
                        "rooms", "DWP_ROOMS", "/rooms/find", "rooms",
                        "APP.ROOMS", "Rooms", "회의실",
                        "Live room availability, booking, invitations, and operations", "LOW"),
                new AppSeed(
                        "approvals", "DWP_APPROVALS", "/approvals/home", "approvals",
                        "APP.APPROVALS", "Approvals", "전자결재",
                        "Requests, governed decisions, delegation, and approval operations", "MEDIUM"),
                new AppSeed(
                        "spaces", "DWP_SPACES", "/spaces/home", "spaces",
                        "APP.SPACES", "Spaces", "Space",
                        "Purpose-built collaboration with governed content and membership", "MEDIUM"),
                new AppSeed("apps", "DWP_APPS", "/apps", "apps", "APP.APPS",
                        "Apps", "앱", "Available workplace applications", "LOW"),
                new AppSeed("ask", "DWP_ASK", "/dwaion", "ask", "APP.ASK",
                        "DWAI·ON Workspace", "DWAI·ON 워크스페이스",
                        "AI workspace with evidence, sources, and an audit trace", "MEDIUM"),
                new AppSeed("hcm", "DWP_HCM", "/hr", "hcm",
                        "APP.HCM", "HR", "인사",
                        "DWP HCM personal HR, organization, and governed workforce operations",
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

    record AgentSeed(
            String entryKey,
            String name,
            String description,
            String ownerRef,
            String riskTier,
            String artifactVersion) {
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
            String requiredPermissionCode,
            String badgeSourceKey,
            String health,
            int sortOrder) {
    }
}

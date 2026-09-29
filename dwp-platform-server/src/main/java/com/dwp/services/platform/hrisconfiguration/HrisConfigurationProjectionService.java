package com.dwp.services.platform.hrisconfiguration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.RolePlaneBoundary;
import com.dwp.services.platform.home.personalization.HomeTemplateDtos;
import com.dwp.services.platform.home.personalization.HomeTemplateService;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.dwp.services.platform.navigation.NavigationDtos;
import com.dwp.services.platform.navigation.NavigationService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class HrisConfigurationProjectionService {

    private final NavigationService navigation;
    private final HomeTemplateService homeTemplates;

    public HrisConfigurationProjectionService(
            NavigationService navigation,
            HomeTemplateService homeTemplates) {
        this.navigation = navigation;
        this.homeTemplates = homeTemplates;
    }

    public HrisConfigurationProjectionDtos.Projection project(
            Long tenantId,
            Long subjectId,
            String permissionsHeader,
            String rolesHeader,
            String locale) {
        requirePositive(tenantId);
        requirePositive(subjectId);
        Set<String> permissions = tokens(permissionsHeader, 500, true);
        Set<String> roles = tokens(rolesHeader, 100, true);
        if (RolePlaneBoundary.hasConflict(roles) || RolePlaneBoundary.isProviderIdentity(roles)) {
            throw forbidden("Tenant HRIS projection is unavailable to provider identities.");
        }
        if (!permissions.contains("APP.HCM:VIEW") && !permissions.contains("APP.HRIS:VIEW")) {
            throw forbidden("The HRIS application entitlement is required.");
        }
        boolean configurationUpdate = hasConfigurationPermission(permissions, "UPDATE");
        boolean configurationPublish = hasConfigurationPermission(permissions, "PUBLISH");
        boolean configurationAdmin = configurationUpdate || configurationPublish;
        boolean auditor = hasConfigurationPermission(permissions, "AUDIT")
                || permissions.contains("DATA.WORKFORCE:AUDIT");
        if (configurationAdmin && auditor) {
            throw forbidden("Configuration and enterprise-audit authority cannot be combined.");
        }

        List<HrisConfigurationProjectionDtos.MenuItem> menus = List.of();
        List<HrisConfigurationProjectionDtos.WidgetItem> widgets = List.of();
        List<HrisConfigurationProjectionDtos.SourceStatus> sources = new ArrayList<>();

        try {
            menus = navigation.runtimeTree(tenantId, locale == null ? "en" : locale).stream()
                    .map(node -> filterMenu(node, permissions))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            sources.add(menus.isEmpty()
                    ? source("NAVIGATION",
                            HrisConfigurationProjectionDtos.SourceState.CONFIGURATION_REQUIRED,
                            "HRIS_NAVIGATION_CONFIGURATION_REQUIRED")
                    : available("NAVIGATION"));
        } catch (RuntimeException exception) {
            sources.add(unavailable("NAVIGATION", "NAVIGATION_SOURCE_UNAVAILABLE"));
        }

        try {
            List<HomeTemplateDtos.HomeTemplateResponse> templates =
                    homeTemplates.list(tenantId, permissionsHeader, rolesHeader);
            widgets = templates.stream()
                    .filter(template -> "PUBLISHED".equals(template.lifecycle()))
                    .filter(template -> Integer.valueOf(HomePreferenceDtos.SCHEMA_VERSION)
                            .equals(template.schemaVersion()))
                    .flatMap(template -> widgets(template).stream())
                    .toList();
            sources.add(widgetStatus(templates, widgets));
        } catch (RuntimeException exception) {
            // Navigation remains useful when personalization is disabled or temporarily unavailable.
            sources.add(unavailable("HOME_TEMPLATE", "HOME_TEMPLATE_SOURCE_UNAVAILABLE"));
        }

        long available = sources.stream()
                .filter(source -> source.state()
                        == HrisConfigurationProjectionDtos.SourceState.AVAILABLE)
                .count();
        HrisConfigurationProjectionDtos.ProjectionState state = available == sources.size()
                ? HrisConfigurationProjectionDtos.ProjectionState.COMPLETE
                : available == 0
                        ? HrisConfigurationProjectionDtos.ProjectionState.UNAVAILABLE
                        : HrisConfigurationProjectionDtos.ProjectionState.PARTIAL;
        List<String> actions = actions(
                hasConfigurationPermission(permissions, "VIEW") || configurationAdmin,
                configurationUpdate, configurationPublish, auditor);
        OffsetDateTime evaluatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        return new HrisConfigurationProjectionDtos.Projection(
                tenantId, subjectId, evaluatedAt, version(tenantId, menus, widgets, sources, actions),
                state, auditor && !configurationAdmin, false,
                actions, menus, widgets, List.copyOf(sources));
    }

    private HrisConfigurationProjectionDtos.MenuItem filterMenu(
            NavigationDtos.RuntimeNode node,
            Set<String> permissions) {
        List<HrisConfigurationProjectionDtos.MenuItem> children = safe(node.children()).stream()
                .map(child -> filterMenu(child, permissions))
                .filter(java.util.Objects::nonNull)
                .toList();
        boolean hrisNode = isHrisNode(node);
        if (!hrisNode && children.isEmpty()) return null;
        if (hrisNode && !permissionAllows(node, permissions)) return null;
        return new HrisConfigurationProjectionDtos.MenuItem(
                node.navigationKey(), node.itemType(), node.label(), node.description(),
                node.route(), node.iconKey(), canonicalResource(node.requiredResourceKey()),
                normalizedPermission(node.requiredPermissionCode()), children);
    }

    private boolean permissionAllows(
            NavigationDtos.RuntimeNode node,
            Set<String> permissions) {
        String resource = canonicalResource(node.requiredResourceKey());
        if (resource == null) return true;
        String permission = normalizedPermission(node.requiredPermissionCode());
        return permissions.contains(resource + ":" + permission)
                || ("APP.HCM".equals(resource)
                        && permissions.contains("APP.HRIS:" + permission));
    }

    private boolean isHrisNode(NavigationDtos.RuntimeNode node) {
        if ("APP.HCM".equals(canonicalResource(node.requiredResourceKey()))) return true;
        String route = lower(node.route());
        if (route != null && (route.equals("/hr") || route.startsWith("/hr/")
                || route.equals("/hris") || route.startsWith("/hris/")
                || route.equals("/hcm") || route.startsWith("/hcm/"))) return true;
        String key = lower(node.navigationKey());
        return key != null && (key.equals("hr") || key.startsWith("hr.")
                || key.startsWith("hris") || key.startsWith("hcm"));
    }

    private List<HrisConfigurationProjectionDtos.WidgetItem> widgets(
            HomeTemplateDtos.HomeTemplateResponse template) {
        if (template.layout() == null || template.layout().widgets() == null) return List.of();
        return template.layout().widgets().stream()
                .filter(widget -> Boolean.TRUE.equals(widget.visible()))
                .filter(widget -> isHrisWidget(widget.widgetKey()))
                .map(widget -> new HrisConfigurationProjectionDtos.WidgetItem(
                        template.templateId(), template.templateKey(), template.name(),
                        widget.widgetKey(), widget.size(), widget.height(), template.version()))
                .toList();
    }

    private boolean isHrisWidget(String value) {
        String key = lower(value);
        return key != null && (key.startsWith("hris-") || key.startsWith("hcm-")
                || key.startsWith("hr-"));
    }

    private HrisConfigurationProjectionDtos.SourceStatus widgetStatus(
            List<HomeTemplateDtos.HomeTemplateResponse> templates,
            List<HrisConfigurationProjectionDtos.WidgetItem> widgets) {
        if (!widgets.isEmpty()) return available("HOME_TEMPLATE");
        boolean stale = templates.stream()
                .filter(template -> "PUBLISHED".equals(template.lifecycle()))
                .filter(template -> !Integer.valueOf(HomePreferenceDtos.SCHEMA_VERSION)
                        .equals(template.schemaVersion()))
                .anyMatch(this::containsHrisWidget);
        if (stale) {
            return source("HOME_TEMPLATE", HrisConfigurationProjectionDtos.SourceState.STALE,
                    "HOME_TEMPLATE_SCHEMA_STALE");
        }
        boolean revoked = templates.stream()
                .filter(template -> "REVOKED".equals(template.lifecycle()))
                .anyMatch(this::containsHrisWidget);
        if (revoked) {
            return source("HOME_TEMPLATE", HrisConfigurationProjectionDtos.SourceState.REVOKED,
                    "HOME_TEMPLATE_REVOKED");
        }
        return source("HOME_TEMPLATE",
                HrisConfigurationProjectionDtos.SourceState.CONFIGURATION_REQUIRED,
                "HRIS_WIDGET_CONFIGURATION_REQUIRED");
    }

    private boolean containsHrisWidget(HomeTemplateDtos.HomeTemplateResponse template) {
        return template.layout() != null && template.layout().widgets() != null
                && template.layout().widgets().stream()
                        .anyMatch(widget -> isHrisWidget(widget.widgetKey()));
    }

    private List<String> actions(
            boolean configurationView,
            boolean configurationUpdate,
            boolean configurationPublish,
            boolean auditor) {
        List<String> result = new ArrayList<>(List.of("VIEW_PROJECTION"));
        if (configurationView) result.add("VIEW_CONFIGURATION");
        if (configurationUpdate) result.add("UPDATE_CONFIGURATION");
        if (configurationPublish) result.add("PUBLISH_CONFIGURATION");
        if (auditor) result.add("AUDIT_READ");
        return List.copyOf(result);
    }

    private boolean hasConfigurationPermission(Set<String> permissions, String action) {
        return permissions.contains("HCM.CONFIGURATION_WORKBENCH:" + action)
                || permissions.contains("HRIS.CONFIGURATION_WORKBENCH:" + action);
    }

    private Set<String> tokens(String value, int maximum, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) throw forbidden("Verified authority headers are required.");
            return Set.of();
        }
        if (value.length() > 32_000 || !value.equals(value.trim())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw forbidden("Verified authority headers are invalid.");
        }
        String[] parts = value.split(",", -1);
        if (parts.length > maximum) throw forbidden("Verified authority headers are invalid.");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Arrays.stream(parts).forEach(part -> {
            if (part.isBlank() || !part.equals(part.trim())) {
                throw forbidden("Verified authority headers are invalid.");
            }
            result.add(part.toUpperCase(Locale.ROOT));
        });
        return Set.copyOf(result);
    }

    private String canonicalResource(String resource) {
        if (resource == null || resource.isBlank()) return null;
        String normalized = resource.trim().toUpperCase(Locale.ROOT);
        return "APP.HRIS".equals(normalized) ? "APP.HCM" : normalized;
    }

    private String normalizedPermission(String permission) {
        return permission == null || permission.isBlank()
                ? "VIEW" : permission.trim().toUpperCase(Locale.ROOT);
    }

    private String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private HrisConfigurationProjectionDtos.SourceStatus available(String source) {
        return source(source, HrisConfigurationProjectionDtos.SourceState.AVAILABLE, null);
    }

    private HrisConfigurationProjectionDtos.SourceStatus unavailable(
            String source,
            String reason) {
        return source(source, HrisConfigurationProjectionDtos.SourceState.UNAVAILABLE, reason);
    }

    private HrisConfigurationProjectionDtos.SourceStatus source(
            String source,
            HrisConfigurationProjectionDtos.SourceState state,
            String reason) {
        return new HrisConfigurationProjectionDtos.SourceStatus(source, state, reason);
    }

    private String version(
            Long tenantId,
            List<HrisConfigurationProjectionDtos.MenuItem> menus,
            List<HrisConfigurationProjectionDtos.WidgetItem> widgets,
            List<HrisConfigurationProjectionDtos.SourceStatus> sources,
            List<String> actions) {
        String material = tenantId + "|" + menus + "|" + widgets + "|" + sources + "|" + actions;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "hris-projection-v1-" + java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void requirePositive(Long value) {
        if (value == null || value <= 0) {
            throw forbidden("Verified tenant and subject identity are required.");
        }
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }
}

package com.dwp.services.auth.productaccess;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Projects effective authority evidence for UI composition. This decision is deliberately not a
 * command PEP: AppGovernanceService and each configuration owner API must re-authorize mutations.
 */
final class HrisProductAccessPolicy {

    static final String POLICY_VERSION = "PEP-SYS-W1-4";
    private static final String APP_HCM = "APP.HCM";
    private static final Set<String> APP_ENTRY_ACTIONS = Set.of("VIEW", "MANAGE");
    private static final Set<String> HOME_DATA_ACTIONS = Set.of(
            "VIEW", "VIEW_SELF", "MANAGE");
    private static final Set<String> SELF_DATA_RESOURCES = Set.of(
            "DATA.WORKFORCE", "DATA.HR_TIME", "DATA.HR_ABSENCE",
            "DATA.HR_PAY", "DATA.HR_TALENT");
    private static final Set<String> CONFIGURATION_RESOURCES = Set.of(
            "HCM.CONFIGURATION_WORKBENCH", "HRIS.CONFIGURATION_WORKBENCH");
    private static final Set<String> GOVERNANCE_RESPONSIBILITIES = Set.of(
            "APP_OWNER", "APP_ACCESS_MANAGER");

    record ScopedResponsibility(
            String responsibilityCode,
            String resourceType,
            String resourceKey,
            String resourceSetKey,
            OffsetDateTime validTo) {
    }

    record Evidence(
            Collection<String> permissionKeys,
            Collection<String> deniedPermissionKeys,
            Collection<ScopedResponsibility> scopedResponsibilities) {
    }

    HrisProductAccessDtos.AccessSnapshot evaluate(
            Long tenantId,
            Long subjectId,
            Evidence evidence,
            OffsetDateTime evaluatedAt) {
        requirePositive(tenantId, "tenantId");
        requirePositive(subjectId, "subjectId");
        if (evidence == null || evaluatedAt == null) {
            throw new IllegalArgumentException("Evidence and evaluatedAt are required.");
        }

        Set<String> permissionEvidence = normalized(evidence.permissionKeys());
        Set<String> deniedPermissions = normalized(evidence.deniedPermissionKeys());
        Set<String> permissions = permissionEvidence.stream()
                .filter(permission -> !denied(permission, deniedPermissions))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<ScopedResponsibility> scopes = effectiveScopes(
                evidence.scopedResponsibilities(), evaluatedAt);
        boolean appEntitled = requirementAllowed(
                APP_HCM, APP_ENTRY_ACTIONS, permissionEvidence, deniedPermissions);
        List<HrisProductAccessDtos.Grant> evidenceGrants = permissions.stream()
                .map(permission -> evidenceGrant(permission, tenantId, subjectId))
                .filter(java.util.Objects::nonNull)
                .toList();
        boolean manager = evidenceGrants.stream()
                .anyMatch(grant -> "TEAM".equals(grant.scopeType()));
        boolean operations = evidenceGrants.stream()
                .anyMatch(grant -> "DATA".equals(grant.resourceType())
                        && "TENANT".equals(grant.scopeType()));
        boolean configurationView = hasConfigurationPermission(permissions, "VIEW")
                || hasConfigurationPermission(permissions, "UPDATE")
                || hasConfigurationPermission(permissions, "PUBLISH");
        boolean configurationUpdate = hasConfigurationPermission(permissions, "UPDATE");
        boolean configurationPublish = hasConfigurationPermission(permissions, "PUBLISH");
        boolean configurationAuthority = configurationUpdate || configurationPublish;
        boolean governanceAuthority = scopes.stream().anyMatch(scope ->
                GOVERNANCE_RESPONSIBILITIES.contains(scope.responsibilityCode()));
        boolean auditAuthority = evidenceGrants.stream()
                .anyMatch(grant -> "AUDIT".equals(grant.resourceType()))
                || hasConfigurationPermission(permissions, "AUDIT")
                || scopes.stream().anyMatch(scope ->
                        "APP_ACCESS_REVIEWER".equals(scope.responsibilityCode()));
        boolean sodConflict = (configurationAuthority || governanceAuthority
                || evidenceGrants.stream().anyMatch(HrisProductAccessDtos.Grant::mutable))
                && auditAuthority;

        if (!appEntitled || sodConflict) {
            String reason = appEntitled ? "SEPARATION_OF_DUTIES_CONFLICT"
                    : "APP_ENTITLEMENT_MISSING";
            return snapshot(
                    tenantId, subjectId, evaluatedAt, evidence, HrisProductAccessDtos.AccessState.DENIED,
                    reason, List.of(), List.of(), appEntitled, configurationAuthority,
                    governanceAuthority, auditAuthority, true, sodConflict);
        }

        LinkedHashSet<HrisProductAccessDtos.RoleGroup> groups = new LinkedHashSet<>();
        groups.add(HrisProductAccessDtos.RoleGroup.EMPLOYEE);
        if (manager) groups.add(HrisProductAccessDtos.RoleGroup.MANAGER);
        if (operations) groups.add(HrisProductAccessDtos.RoleGroup.OPERATIONS);
        if (configurationAuthority) groups.add(HrisProductAccessDtos.RoleGroup.CONFIGURATION_ADMIN);
        if (auditAuthority) groups.add(HrisProductAccessDtos.RoleGroup.ENTERPRISE_AUDITOR);

        Map<String, HrisProductAccessDtos.Grant> grants = new LinkedHashMap<>();
        add(grants, grant("VIEW", "PRODUCT", "UI.MENU.HRIS", "SELF", subjectId.toString(), false));
        add(grants, grant("VIEW", "PRODUCT", "UI.WIDGET.HRIS", "SELF", subjectId.toString(), false));
        // The APP.HCM/APP.HRIS entry requirement is shell-only. DATA grants are projected
        // exclusively from explicit effective permission evidence below.
        evidenceGrants.forEach(value -> add(grants, value));
        if (configurationView) {
            add(grants, grant("VIEW", "CONFIGURATION",
                    "HCM.CONFIGURATION_WORKBENCH", "TENANT", tenantId.toString(), false));
        }
        if (configurationUpdate) {
            add(grants, grant("UPDATE", "CONFIGURATION",
                    "HCM.CONFIGURATION_WORKBENCH", "TENANT", tenantId.toString(), true));
        }
        if (configurationPublish) {
            add(grants, grant("PUBLISH", "CONFIGURATION",
                    "HCM.CONFIGURATION_WORKBENCH", "TENANT", tenantId.toString(), true));
        }
        scopes.stream()
                .filter(scope -> GOVERNANCE_RESPONSIBILITIES.contains(scope.responsibilityCode()))
                .forEach(scope -> add(grants, grant(
                        "VIEW", "APP_RESOURCE_SET", APP_HCM,
                        "RESOURCE_SET", scope.resourceSetKey(), false)));
        if (auditAuthority) {
            add(grants, grant("VIEW", "AUDIT",
                    "HCM.CONFIGURATION_WORKBENCH", "TENANT", tenantId.toString(), false));
        }

        List<HrisProductAccessDtos.RoleGroup> orderedGroups = groups.stream()
                .sorted(Comparator.comparingInt(Enum::ordinal)).toList();
        List<HrisProductAccessDtos.Grant> orderedGrants = List.copyOf(grants.values());
        return snapshot(
                tenantId, subjectId, evaluatedAt, evidence, HrisProductAccessDtos.AccessState.ALLOWED,
                "AUTHORIZED", orderedGroups, orderedGrants, true,
                configurationAuthority, governanceAuthority, auditAuthority,
                auditAuthority && !configurationAuthority && !governanceAuthority, false);
    }

    private HrisProductAccessDtos.Grant evidenceGrant(
            String permission,
            Long tenantId,
            Long subjectId) {
        int separator = permission.lastIndexOf(':');
        if (separator <= 0) return null;
        String resource = permission.substring(0, separator);
        if (!SELF_DATA_RESOURCES.contains(resource)) return null;
        String action = permission.substring(separator + 1);
        return switch (action) {
            case "VIEW", "VIEW_SELF" -> grant("VIEW", "DATA", resource,
                    "SELF", subjectId.toString(), false);
            case "VIEW_TEAM" -> grant("VIEW", "DATA", resource,
                    "TEAM", "ASSIGNED", false);
            case "VIEW_TENANT" -> grant("VIEW", "DATA", resource,
                    "TENANT", tenantId.toString(), false);
            case "MANAGE" -> grant("MANAGE", "DATA", resource,
                    "TENANT", tenantId.toString(), true);
            case "AUDIT" -> grant("VIEW", "AUDIT", resource,
                    "TENANT", tenantId.toString(), false);
            default -> null;
        };
    }

    private boolean denied(String permission, Set<String> deniedPermissions) {
        if (deniedPermissions.contains(permission)) return true;
        int separator = permission.lastIndexOf(':');
        if (separator <= 0) return false;
        String resource = permission.substring(0, separator);
        String action = permission.substring(separator + 1);
        if (APP_HCM.equals(resource) && APP_ENTRY_ACTIONS.contains(action)) {
            return requirementDenied(resource, APP_ENTRY_ACTIONS, deniedPermissions);
        }
        if (!SELF_DATA_RESOURCES.contains(resource)) return false;
        if (HOME_DATA_ACTIONS.contains(action)
                && requirementDenied(resource, HOME_DATA_ACTIONS, deniedPermissions)) {
            return true;
        }
        return action.startsWith("VIEW_")
                && deniedPermissions.contains(resource + ":VIEW");
    }

    private boolean requirementAllowed(
            String resource,
            Set<String> actions,
            Set<String> permissions,
            Set<String> deniedPermissions) {
        return !requirementDenied(resource, actions, deniedPermissions)
                && actions.stream().anyMatch(action ->
                        permissions.contains(resource + ':' + action));
    }

    private boolean requirementDenied(
            String resource,
            Set<String> actions,
            Set<String> deniedPermissions) {
        return actions.stream().anyMatch(action ->
                deniedPermissions.contains(resource + ':' + action));
    }

    private boolean hasConfigurationPermission(Set<String> permissions, String action) {
        return CONFIGURATION_RESOURCES.stream()
                .anyMatch(resource -> permissions.contains(resource + ":" + action));
    }

    private HrisProductAccessDtos.AccessSnapshot snapshot(
            Long tenantId,
            Long subjectId,
            OffsetDateTime evaluatedAt,
            Evidence evidence,
            HrisProductAccessDtos.AccessState state,
            String reasonCode,
            List<HrisProductAccessDtos.RoleGroup> groups,
            List<HrisProductAccessDtos.Grant> grants,
            boolean appEntitled,
            boolean configurationAuthority,
            boolean governanceAuthority,
            boolean auditAuthority,
            boolean readOnly,
            boolean sodConflict) {
        return new HrisProductAccessDtos.AccessSnapshot(
                tenantId, subjectId, evaluatedAt, POLICY_VERSION,
                evidenceVersion(evidence, state, reasonCode, groups, grants),
                state, reasonCode, groups, grants,
                new HrisProductAccessDtos.AuthorityFlags(
                        appEntitled, configurationAuthority, governanceAuthority,
                        auditAuthority, readOnly, sodConflict, false));
    }

    private List<ScopedResponsibility> effectiveScopes(
            Collection<ScopedResponsibility> values,
            OffsetDateTime evaluatedAt) {
        if (values == null) return List.of();
        return values.stream()
                .filter(value -> value != null
                        && value.responsibilityCode() != null
                        && APP_HCM.equals(canonicalResource(value.resourceKey()))
                        && value.resourceSetKey() != null && !value.resourceSetKey().isBlank()
                        && (value.validTo() == null || value.validTo().isAfter(evaluatedAt)))
                .map(value -> new ScopedResponsibility(
                        upper(value.responsibilityCode()), upper(value.resourceType()),
                        canonicalResource(value.resourceKey()), value.resourceSetKey(), value.validTo()))
                .toList();
    }

    private Set<String> normalized(Collection<String> values) {
        if (values == null) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.stream().filter(value -> value != null && !value.isBlank())
                .map(this::canonicalPermission).forEach(result::add);
        return Set.copyOf(result);
    }

    private String canonicalPermission(String value) {
        String normalized = upper(value);
        return normalized.startsWith("APP.HRIS:")
                ? "APP.HCM:" + normalized.substring("APP.HRIS:".length()) : normalized;
    }

    private String canonicalResource(String value) {
        String normalized = upper(value);
        return "APP.HRIS".equals(normalized) ? APP_HCM : normalized;
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private HrisProductAccessDtos.Grant grant(
            String action,
            String resourceType,
            String resourceKey,
            String scopeType,
            String scopeKey,
            boolean mutable) {
        return new HrisProductAccessDtos.Grant(
                action, resourceType, resourceKey, scopeType, scopeKey, mutable);
    }

    private void add(
            Map<String, HrisProductAccessDtos.Grant> grants,
            HrisProductAccessDtos.Grant grant) {
        String key = grant.action() + '|' + grant.resourceKey() + '|' + grant.scopeType()
                + '|' + grant.scopeKey();
        grants.putIfAbsent(key, grant);
    }

    private String evidenceVersion(
            Evidence evidence,
            HrisProductAccessDtos.AccessState state,
            String reason,
            List<HrisProductAccessDtos.RoleGroup> groups,
            List<HrisProductAccessDtos.Grant> grants) {
        List<String> material = new ArrayList<>();
        normalized(evidence.permissionKeys()).stream().sorted().forEach(value -> material.add("p:" + value));
        normalized(evidence.deniedPermissionKeys()).stream().sorted()
                .forEach(value -> material.add("d:" + value));
        if (evidence.scopedResponsibilities() != null) {
            evidence.scopedResponsibilities().stream().filter(java.util.Objects::nonNull)
                    .map(value -> "s:" + value.responsibilityCode() + ":" + value.resourceKey()
                            + ":" + value.resourceSetKey() + ":" + value.validTo())
                    .sorted().forEach(material::add);
        }
        material.add("state:" + state + ":" + reason);
        groups.forEach(group -> material.add("g:" + group));
        grants.forEach(grant -> material.add("a:" + grant.action() + ":" + grant.resourceKey()
                + ":" + grant.scopeType() + ":" + grant.scopeKey() + ":" + grant.mutable()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", material).getBytes(StandardCharsets.UTF_8));
            return "hris-access-v1-" + java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive.");
        }
    }
}

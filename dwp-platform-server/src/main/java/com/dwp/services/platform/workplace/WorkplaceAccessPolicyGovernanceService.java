package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceRepository.*;

final class WorkplaceAccessPolicyGovernanceService
        extends WorkplaceSpatialGovernanceSupport {

    private static final Set<String> POLICY_FIELDS = Set.of(
            "bookingWindowDays", "maximumActiveBookings", "minimumBookingMinutes",
            "maximumBookingMinutes", "maximumConsecutiveDays", "workingDayStart",
            "workingDayEnd", "allowRecurring", "requireCheckIn", "checkInLeadMinutes",
            "autoReleaseMinutes", "allowAssignedDeskLending", "showColleagueNames",
            "bookingRetentionDays");
    private static final Set<String> BOOLEAN_POLICY_FIELDS = Set.of(
            "allowRecurring", "requireCheckIn", "allowAssignedDeskLending",
            "showColleagueNames");
    private static final int MAX_POLICY_BYTES = 16_384;

    WorkplaceAccessPolicyGovernanceService(
            WorkplaceSpatialGovernanceRepository repository,
            ObjectMapper objectMapper) {
        super(repository, objectMapper);
    }

    List<SiteAccessRule> accessRules(Long tenantId, UUID siteId) {
        requireSite(tenantId, siteId);
        return repository.accessRules(tenantId, siteId).stream()
                .map(this::accessRule).toList();
    }

    SiteAccessRule saveAccessRule(
            Long tenantId,
            Long actorId,
            UUID siteId,
            UUID ruleId,
            String correlationId,
            SiteAccessRuleRequest request) {
        requireSite(tenantId, siteId);
        validateAccessRule(request);
        requireCreateOrUpdateVersion(ruleId, request.version(), "access rule");
        AccessRuleRow before = null;
        if (ruleId != null) {
            before = requireAccessRule(tenantId, ruleId);
            if (!before.siteId().equals(siteId)) throw notFound();
        }
        UUID targetId = ruleId == null ? UUID.randomUUID() : ruleId;
        try {
            if (ruleId == null) {
                repository.createAccessRule(tenantId, actorId, siteId, targetId, request);
            } else if (!repository.updateAccessRule(
                    tenantId, actorId, siteId, targetId, request)) {
                throw conflict("The site access rule changed. Refresh and retry.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "An access rule already exists for this subject and permission.", exception);
        }
        AccessRuleRow after = requireAccessRule(tenantId, targetId);
        audit(tenantId, actorId,
                ruleId == null ? "workplace.governance.access.rule.created"
                        : "workplace.governance.access.rule.updated",
                "WP_ACCESS_RULE", targetId, correlationId, before, after, null);
        return accessRule(after);
    }

    SiteAccessDecision evaluateSiteAccess(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            UUID siteId,
            AccessPermission permission) {
        requireSite(tenantId, siteId);
        Set<UUID> groupRefs = verifiedGroupRefs(verifiedGroupRefs);
        OffsetDateTime now = OffsetDateTime.now();
        List<AccessRuleRow> active = repository.activeAccessRules(tenantId, siteId, now);
        if (active.isEmpty()) {
            return new SiteAccessDecision(siteId, userId, permission, false,
                    "DENY_NOT_CONFIGURED", List.of(), now);
        }
        List<AccessRuleRow> matched = active.stream()
                .filter(rule -> grants(rule.permission(), permission))
                .filter(rule -> matches(rule, userId, groupRefs))
                .toList();
        List<UUID> matchedIds = matched.stream().map(AccessRuleRow::accessRuleId).toList();
        if (matched.stream().anyMatch(rule -> rule.effect() == AccessEffect.DENY)) {
            return new SiteAccessDecision(
                    siteId, userId, permission, false, "DENY_EXPLICIT", matchedIds, now);
        }
        boolean allowed = matched.stream().anyMatch(rule -> rule.effect() == AccessEffect.ALLOW);
        return new SiteAccessDecision(siteId, userId, permission, allowed,
                allowed ? "ALLOW_EXPLICIT" : "DENY_NO_MATCH", matchedIds, now);
    }

    List<PolicyOverride> policyOverrides(
            Long tenantId,
            PolicyScopeType scopeType,
            UUID scopeId) {
        if (scopeType == null && scopeId == null) {
            return repository.policyOverrides(tenantId).stream()
                    .map(this::policyOverride).toList();
        }
        requireScope(tenantId, scopeType, scopeId);
        return repository.policyOverrides(tenantId, scopeType, scopeId).stream()
                .map(this::policyOverride).toList();
    }

    PolicyOverride savePolicyOverride(
            Long tenantId,
            Long actorId,
            UUID overrideId,
            String correlationId,
            PolicyScopeType queryScopeType,
            UUID queryScopeId,
            PolicyOverrideRequest request) {
        requireMatchingPolicyScopeQuery(
                queryScopeType, queryScopeId, request.scopeType(), request.scopeId());
        validatePolicyPatch(request.policyPatch());
        requireCreateOrUpdateVersion(overrideId, request.version(), "policy override");
        PolicyOverrideRow before = overrideId == null
                ? null : requirePolicyOverride(tenantId, overrideId);
        if (before != null && (before.scopeType() != request.scopeType()
                || !Objects.equals(before.scopeId(), request.scopeId()))) {
            throw conflict("A policy override scope is immutable. Create a new override instead.");
        }
        ScopeColumns columns = scopeColumns(request.scopeType(), request.scopeId());
        requireScope(tenantId, request.scopeType(), request.scopeId());
        UUID targetId = overrideId == null ? UUID.randomUUID() : overrideId;
        try {
            if (overrideId == null) {
                repository.createPolicyOverride(
                        tenantId, actorId, targetId, request, columns);
            } else if (!repository.updatePolicyOverride(
                    tenantId, actorId, targetId, request)) {
                throw conflict("The policy override changed. Refresh and retry.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("A policy override already exists at this scope.", exception);
        }
        PolicyOverrideRow after = requirePolicyOverride(tenantId, targetId);
        EffectivePolicyPreview preview = previewPolicy(
                tenantId, request.scopeType(), request.scopeId());
        audit(tenantId, actorId,
                overrideId == null ? "workplace.governance.policy.override.created"
                        : "workplace.governance.policy.override.updated",
                "WP_POLICY_OVERRIDE", targetId, correlationId, before,
                Map.of("override", after, "effectivePolicy", preview.effectivePolicy()), null);
        return policyOverride(after);
    }

    EffectivePolicyPreview previewPolicy(
            Long tenantId,
            PolicyScopeType targetScopeType,
            UUID targetScopeId) {
        ScopePath path = requireScope(tenantId, targetScopeType, targetScopeId);
        JsonNode base = repository.tenantBasePolicy(tenantId)
                .orElseThrow(this::notFound);
        ObjectNode effective = requireObject(base, "Tenant Workplace policy").deepCopy();
        Map<String, PolicyFieldSource> sources = new LinkedHashMap<>();
        effective.fieldNames().forEachRemaining(field -> sources.put(field,
                new PolicyFieldSource(PolicyScopeType.TENANT, null, null, 0)));

        List<PolicyOverrideRow> applied = repository.policyOverrides(tenantId).stream()
                .filter(row -> row.state() == RuleState.ACTIVE)
                .filter(row -> row.scopeType() == PolicyScopeType.TENANT
                        || Objects.equals(row.scopeId(), path.id(row.scopeType())))
                .sorted(Comparator.comparingInt(row -> row.scopeType().ordinal()))
                .toList();
        for (PolicyOverrideRow row : applied) {
            row.policyPatch().properties().forEach(entry -> {
                effective.set(entry.getKey(), entry.getValue().deepCopy());
                sources.put(entry.getKey(), new PolicyFieldSource(
                        row.scopeType(), row.scopeId(), row.policyOverrideId(), row.version()));
            });
        }
        validateEffectivePolicy(effective);
        return new EffectivePolicyPreview(targetScopeType, targetScopeId, effective,
                Map.copyOf(sources), applied.stream()
                .map(PolicyOverrideRow::policyOverrideId).toList(), OffsetDateTime.now());
    }

    List<DelegatedAdminScope> delegatedScopes(Long tenantId) {
        return repository.delegatedScopes(tenantId).stream()
                .map(this::delegatedScope).toList();
    }

    DelegatedAdminScope saveDelegatedScope(
            Long tenantId,
            Long actorId,
            UUID delegationId,
            String correlationId,
            DelegatedAdminScopeRequest request) {
        validateDelegatedScope(tenantId, request);
        requireCreateOrUpdateVersion(delegationId, request.version(), "delegated scope");
        DelegatedScopeRow before = delegationId == null
                ? null : requireDelegatedScope(tenantId, delegationId);
        UUID targetId = delegationId == null ? UUID.randomUUID() : delegationId;
        try {
            if (delegationId == null) {
                repository.createDelegatedScope(tenantId, actorId, targetId, request);
            } else if (!repository.updateDelegatedScope(
                    tenantId, actorId, targetId, request)) {
                throw conflict("The delegated scope changed. Refresh and retry.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "An active delegated scope already exists for this subject.", exception);
        }
        DelegatedScopeRow after = requireDelegatedScope(tenantId, targetId);
        audit(tenantId, actorId,
                delegationId == null ? "workplace.governance.delegation.created"
                        : "workplace.governance.delegation.updated",
                "WP_DELEGATION", targetId, correlationId, before, after, null);
        return delegatedScope(after);
    }

    List<EffectiveDelegatedScope> effectiveDelegatedScopes(
            Long tenantId,
            Long actorId,
            String verifiedGroupRefs) {
        Set<UUID> groupRefs = verifiedGroupRefs(verifiedGroupRefs);
        return repository.activeDelegatedScopes(tenantId, OffsetDateTime.now()).stream()
                .filter(scope -> scope.scopeType() == DelegatedScopeType.SITE)
                .filter(scope -> scope.delegateType() == DelegateType.USER
                        ? Objects.equals(scope.delegateUserId(), actorId)
                        : groupRefs.contains(scope.delegateGroupRef()))
                .map(scope -> new EffectiveDelegatedScope(
                        scope.delegationId(), scope.scopeType(), scope.scopeId(),
                        scope.permissions(), scope.validUntil()))
                .toList();
    }

    private void requireMatchingPolicyScopeQuery(
            PolicyScopeType queryScopeType,
            UUID queryScopeId,
            PolicyScopeType bodyScopeType,
            UUID bodyScopeId) {
        if (queryScopeType == null && queryScopeId == null) return;
        if (queryScopeType != bodyScopeType || !Objects.equals(queryScopeId, bodyScopeId)) {
            throw invalid("The policy scope query must match the request body.");
        }
    }

    private void validateAccessRule(SiteAccessRuleRequest request) {
        boolean user = request.subjectType() == AccessSubjectType.USER
                && request.subjectUserId() != null && request.subjectGroupRef() == null;
        boolean group = request.subjectType() == AccessSubjectType.GROUP_REF
                && request.subjectUserId() == null && request.subjectGroupRef() != null;
        if (!user && !group) {
            throw invalid("An access rule requires exactly one identifier-based subject.");
        }
        validatePeriod(request.validFrom(), request.validUntil());
    }

    private void validateDelegatedScope(
            Long tenantId, DelegatedAdminScopeRequest request) {
        boolean user = request.delegateType() == DelegateType.USER
                && request.delegateUserId() != null && request.delegateGroupRef() == null;
        boolean group = request.delegateType() == DelegateType.GROUP_REF
                && request.delegateUserId() == null && request.delegateGroupRef() != null;
        if (!user && !group) {
            throw invalid("A delegated scope requires exactly one identifier-based delegate.");
        }
        if (request.scopeType() != DelegatedScopeType.SITE) {
            throw invalid("Only SITE delegated administration scopes are supported.");
        }
        boolean site = request.scopeType() == DelegatedScopeType.SITE
                && request.siteId() != null && request.managedGroupRef() == null;
        if (!site) {
            throw invalid("A delegated SITE scope requires one site and no group scope.");
        }
        requireSite(tenantId, request.siteId());
        if (request.permissions().size() != Set.copyOf(request.permissions()).size()) {
            throw invalid("Delegated permissions must be unique.");
        }
        validatePeriod(request.validFrom(), request.validUntil());
    }

    private void validatePeriod(OffsetDateTime from, OffsetDateTime until) {
        if (from != null && until != null && !until.isAfter(from)) {
            throw invalid("The validity end must be later than its start.");
        }
    }

    private void validatePolicyPatch(JsonNode value) {
        ObjectNode patch = requireObject(value, "Policy override");
        if (serializedSize(patch) > MAX_POLICY_BYTES) {
            throw invalid("Policy override exceeds the 16 KiB limit.");
        }
        patch.properties().forEach(entry -> {
            String field = entry.getKey();
            JsonNode candidate = entry.getValue();
            if (!POLICY_FIELDS.contains(field) || candidate == null || candidate.isNull()) {
                throw invalid("Policy override contains an unsupported or null field.");
            }
            if (BOOLEAN_POLICY_FIELDS.contains(field) && !candidate.isBoolean()) {
                throw invalid(field + " must be a boolean.");
            }
            if (Set.of("workingDayStart", "workingDayEnd").contains(field)) {
                if (!candidate.isTextual()) throw invalid(field + " must be a local time.");
                parseTime(candidate.asText(), field);
            }
            if (!BOOLEAN_POLICY_FIELDS.contains(field)
                    && !Set.of("workingDayStart", "workingDayEnd").contains(field)) {
                if (!candidate.isIntegralNumber()) throw invalid(field + " must be an integer.");
                validatePolicyInteger(field, candidate.asInt());
            }
        });
    }

    private void validateEffectivePolicy(ObjectNode policy) {
        validatePolicyPatch(policy);
        if (policy.path("maximumBookingMinutes").asInt()
                < policy.path("minimumBookingMinutes").asInt()) {
            throw invalid("Maximum booking duration must not be shorter than the minimum.");
        }
        if (policy.path("maximumConsecutiveDays").asInt()
                > policy.path("bookingWindowDays").asInt()) {
            throw invalid("Maximum consecutive days must fit within the booking window.");
        }
        LocalTime start = parseTime(policy.path("workingDayStart").asText(), "workingDayStart");
        LocalTime end = parseTime(policy.path("workingDayEnd").asText(), "workingDayEnd");
        if (!end.isAfter(start)) throw invalid("Working-day end must be later than its start.");
    }

    private void validatePolicyInteger(String field, int value) {
        int minimum;
        int maximum;
        switch (field) {
            case "bookingWindowDays" -> { minimum = 1; maximum = 365; }
            case "maximumActiveBookings" -> { minimum = 1; maximum = 100; }
            case "minimumBookingMinutes" -> { minimum = 15; maximum = 1440; }
            case "maximumBookingMinutes" -> { minimum = 15; maximum = 10080; }
            case "maximumConsecutiveDays" -> { minimum = 1; maximum = 31; }
            case "checkInLeadMinutes", "autoReleaseMinutes" -> {
                minimum = 0;
                maximum = 240;
            }
            case "bookingRetentionDays" -> { minimum = 30; maximum = 3650; }
            default -> throw invalid("Unsupported integer policy field: " + field);
        }
        if (value < minimum || value > maximum) {
            throw invalid(field + " is outside its supported range.");
        }
    }

    private ScopePath requireScope(
            Long tenantId, PolicyScopeType scopeType, UUID scopeId) {
        if (scopeType == null) {
            throw invalid("A policy scope type is required when a scope identifier is provided.");
        }
        if ((scopeType == PolicyScopeType.TENANT) != (scopeId == null)) {
            throw invalid("Tenant scope has no identifier; every narrower scope requires one.");
        }
        return repository.scopePath(tenantId, scopeType, scopeId)
                .orElseThrow(this::notFound);
    }

    private ScopeColumns scopeColumns(PolicyScopeType scopeType, UUID scopeId) {
        return new ScopeColumns(
                scopeType == PolicyScopeType.CAMPUS ? scopeId : null,
                scopeType == PolicyScopeType.SITE ? scopeId : null,
                scopeType == PolicyScopeType.FLOOR ? scopeId : null,
                scopeType == PolicyScopeType.ZONE ? scopeId : null,
                scopeType == PolicyScopeType.RESOURCE ? scopeId : null);
    }

    private boolean grants(AccessPermission granted, AccessPermission requested) {
        return granted.ordinal() >= requested.ordinal();
    }

    private boolean matches(AccessRuleRow rule, Long userId, Set<UUID> groupRefs) {
        return rule.subjectType() == AccessSubjectType.USER
                ? Objects.equals(rule.subjectUserId(), userId)
                : groupRefs.contains(rule.subjectGroupRef());
    }

    private Set<UUID> verifiedGroupRefs(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .limit(400)
                .map(this::uuidOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private UUID uuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private LocalTime parseTime(String value, String field) {
        try {
            return LocalTime.parse(value);
        } catch (RuntimeException exception) {
            throw invalid(field + " must use ISO local-time format.");
        }
    }
}

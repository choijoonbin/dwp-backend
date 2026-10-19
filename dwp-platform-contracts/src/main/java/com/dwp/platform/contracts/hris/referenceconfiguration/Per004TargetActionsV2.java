package com.dwp.platform.contracts.hris.referenceconfiguration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;

/** Exact state selection for IAQ-056. IAQ-020 callers must always use formsScales(). */
public final class Per004TargetActionsV2 {
    private static final Set<String> FIELDS = Set.of("POLICY_VERSION", "PERFORMANCE_POLICY", "EFFECTIVE_STATUS");
    private static final String PREFIX = "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions";
    private Per004TargetActionsV2() { }

    public static List<TargetActionV2> formsScales() { return List.of(); }

    public static List<TargetActionV2> settings(OwnerProjection item, OffsetDateTime asOf, OffsetDateTime now,
            Set<String> viewFields, Set<String> allowedActionRoutes, FeatureDecision feature,
            UUID subject, long tenantId, long authorizationRevision, boolean makerCheckerAndStepUpSatisfied) {
        if (item == null || asOf == null || now == null || asOf.isAfter(now) || asOf.isBefore(now.minusSeconds(30))
                || !FIELDS.equals(viewFields) || allowedActionRoutes == null || item.configurationId() == null
                || item.lifecycleState() == null || item.schemaKey() == null || !SCHEMAS.contains(item.schemaKey())
                || item.scopeDigest() == null || !item.scopeDigest().matches("^[a-f0-9]{64}$")) return List.of();
        String verb = switch (item.lifecycleState()) {
            case "NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE" -> item.createEligible() ? "create" : null;
            case "DRAFT" -> item.ownerFinalized() ? "validate" : null;
            case "VALIDATED" -> "simulate";
            case "SIMULATED" -> "submit";
            case "PENDING_APPROVAL" -> "approve";
            case "APPROVED" -> due(item.effectiveFrom(), now) ? "publish" : null;
            case "SCHEDULED" -> due(item.effectiveFrom(), now) && due(item.scheduledAt(), now) ? "publish" : null;
            default -> null;
        };
        if (verb == null || (verb.equals("approve") || verb.equals("publish")) && !makerCheckerAndStepUpSatisfied) return List.of();
        String route = "route.hcm.configuration.configuration-version-" + verb + ".action";
        if (!allowedActionRoutes.contains(route) || !featureAllows(feature, item, route, subject, tenantId, authorizationRevision, now)) return List.of();
        boolean create = verb.equals("create");
        if (!create && (item.version() == null || item.version() < 1 || item.aggregateVersion() == null || item.aggregateVersion() < 0)) return List.of();
        String href = "/api/platform/v1/admin/hris/configurations/" + item.configurationId() + "/versions"
                + (create ? "" : "/" + item.version() + "/" + verb);
        return List.of(new TargetActionV2(PREFIX + (create ? "" : ".resource." + verb), "POST",
                create ? "REFERENCE_CONFIGURATION" : "REFERENCE_CONFIGURATION_VERSION", item.configurationId(),
                create ? null : item.version(), create ? null : item.aggregateVersion(), href,
                "REQUIRED_IDEMPOTENCY_KEY", "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED"));
    }

    private static boolean featureAllows(FeatureDecision f, OwnerProjection item, String route,
            UUID subject, long tenantId, long revision, OffsetDateTime now) {
        return f != null && f.binding() != null && tenantId > 0 && f.binding().tenantId() == tenantId
                && subject != null && subject.equals(f.binding().subjectPrincipalPublicId())
                && item.configurationId().equals(f.binding().configurationId()) && "PER".equals(f.binding().resolvedDomain())
                && item.scopeDigest().equals(f.binding().scopeDigest()) && revision == f.binding().commandAuthorizationRevision()
                && route.equals(f.binding().exactSharedActionRouteContractKey())
                && FEATURE.equals(f.featureKey()) && FEATURE_ROUTE.equals(f.decisionRoute())
                && "ACTIVE".equals(f.featureState()) && "ALLOWED".equals(f.decision())
                && f.featureDecisionRevision() != null && !f.featureDecisionRevision().isBlank()
                && f.evaluatedAt() != null && !f.evaluatedAt().isAfter(now)
                && f.revalidateAt() != null && f.revalidateAt().isAfter(now);
    }
    private static boolean due(OffsetDateTime instant, OffsetDateTime now) { return instant != null && !now.isBefore(instant); }
}

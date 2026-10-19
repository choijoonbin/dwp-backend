package com.dwp.services.people.hris.performance.per004;

import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;

/** Contract-closed IAQ-020/056 adapter. Lifecycle always comes from Platform, not local artifacts. */
final class Per004WorkbenchProjection {
    enum Variant { FORMS_SCALES, PERFORMANCE }
    enum FieldDecision { VIEW, MASK, OMIT }

    record TrustedContext(long tenantId, UUID subject, String scopeDigest, String scopeRevision,
            String authorizationDecisionRevision, long authorizationRevision,
            UUID correlationId, Map<String, FieldDecision> fields, Set<String> allowedActionRoutes,
            FeatureDecision featureDecision, boolean makerCheckerAndStepUpSatisfied) { }
    record LocalSnapshot(UUID configurationId, long version, UUID artifactId, String schemaKey,
            String payloadReference, String payloadDigest, String scopeDigest, long artifactRevision,
            String title, UUID templateVersionId, String templateDigest) { }

    private final ObjectMapper mapper;
    Per004WorkbenchProjection(ObjectMapper mapper) { this.mapper = mapper; }

    JsonNode assemble(Variant variant, TrustedContext context, OffsetDateTime asOf,
            OwnerProjectionResponse platform, List<LocalSnapshot> local) {
        requireContext(variant, context);
        if (asOf == null || local == null || local.size() > 200) throw invalid();
        if (platform == null || platform.asOf() == null || !platform.asOf().isEqual(asOf)
                || !"CURRENT".equals(platform.freshness()) || platform.items() == null
                || platform.items().size() > 100) return unavailable(variant, context, asOf);
        Map<UUID, List<LocalSnapshot>> localByConfiguration = new HashMap<>();
        for (LocalSnapshot row : local) {
            if (row == null || row.configurationId() == null || row.artifactId() == null
                    || row.version() < 1 || row.artifactRevision() < 1
                    || !SCHEMAS.contains(row.schemaKey()) || !ownerReference(row.payloadReference())
                    || !row.payloadReference().equals(ARTIFACT_REFERENCE_PREFIX + row.artifactId())
                    || !digest(row.payloadDigest()) || !row.scopeDigest().equals(context.scopeDigest())) throw invalid();
            localByConfiguration.computeIfAbsent(row.configurationId(), ignored -> new ArrayList<>()).add(row);
        }
        ObjectNode response = shell(variant, context, asOf);
        ArrayNode items = (ArrayNode) response.get("items");
        ArrayNode failures = (ArrayNode) response.path("queryState").path("partialFailures");
        Set<UUID> seen = new HashSet<>();
        int views = 0, masks = 0, omits = 0;
        for (OwnerProjection owner : platform.items()) {
            boolean unboundCreate = variant == Variant.PERFORMANCE && owner != null
                    && "NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE".equals(owner.lifecycleState())
                    && owner.createEligible() && !owner.ownerFinalized()
                    && owner.version() == null && owner.aggregateVersion() == null
                    && owner.payloadReference() == null && owner.payloadDigest() == null
                    && "dwp.hris.performance.policy.v1".equals(owner.schemaKey());
            if (owner == null || owner.configurationId() == null || !seen.add(owner.configurationId())
                    || !context.scopeDigest().equals(owner.scopeDigest())
                    || !SCHEMAS.contains(owner.schemaKey()) || owner.schemaVersion() == null
                    || owner.schemaVersion() != 1 || !unboundCreate && (owner.version() == null || owner.version() < 1
                    || owner.aggregateVersion() == null || owner.aggregateVersion() < 0
                    || !ownerReference(owner.payloadReference()) || !digest(owner.payloadDigest()))
                    || owner.lifecycleState() == null) {
                fail(failures, context, "platform_projection", "PLATFORM_PROJECTION_MISMATCH");
                continue;
            }
            if (unboundCreate) {
                if (localByConfiguration.containsKey(owner.configurationId())) {
                    fail(failures, context, "owner_artifact", "OWNER_BINDING_INCOMPLETE");
                    continue;
                }
                ObjectNode item = items.addObject();
                item.put("itemPublicId", owner.configurationId().toString());
                item.put("title", owner.key() == null || owner.key().isBlank()
                        ? "Performance configuration" : owner.key());
                item.put("statusCode", owner.lifecycleState());
                item.putObject("fieldGroups"); // No typed payload exists yet; all fields are omitted.
                omits += 3;
                ObjectNode link = item.putObject("deepLink");
                link.put("route", "/hr/settings/performance");
                link.put("resourcePublicId", owner.configurationId().toString());
                ArrayNode actions = item.putArray("availableActions");
                if (context.fields().values().stream().allMatch(decision -> decision == FieldDecision.VIEW)
                        && failures.isEmpty()) {
                    for (TargetActionV2 descriptor : Per004ActionSelector.select(owner, asOf, asOf,
                            context.fields().keySet(), context.allowedActionRoutes(), context.featureDecision(),
                            context.subject(), context.tenantId(), context.authorizationRevision(),
                            context.makerCheckerAndStepUpSatisfied()))
                        actions.add(mapper.valueToTree(descriptor));
                }
                continue;
            }
            List<LocalSnapshot> matches = localByConfiguration.getOrDefault(owner.configurationId(), List.of())
                    .stream().filter(row -> row.schemaKey().equals(owner.schemaKey())
                            && row.version() == owner.version()
                            && row.payloadReference().equals(owner.payloadReference())
                            && row.payloadDigest().equals(owner.payloadDigest()))
                    .toList();
            if (matches.size() != 1 || !owner.ownerFinalized()) {
                fail(failures, context, "owner_artifact", "OWNER_BINDING_INCOMPLETE");
                continue;
            }
            LocalSnapshot snapshot = matches.getFirst();
            LocalSnapshot form = null, scale = null;
            if (variant == Variant.FORMS_SCALES) {
                List<LocalSnapshot> forms = localByConfiguration.get(owner.configurationId()).stream()
                        .filter(row -> row.schemaKey().equals("dwp.hris.performance.review-form.v1"))
                        .toList();
                List<LocalSnapshot> scales = localByConfiguration.get(owner.configurationId()).stream()
                        .filter(row -> row.schemaKey().equals("dwp.hris.performance.rating-scale.v1"))
                        .toList();
                if (forms.size() != 1 || scales.size() != 1
                        || forms.getFirst().templateVersionId() == null
                        || scales.getFirst().templateVersionId() == null
                        || !digest(forms.getFirst().templateDigest())
                        || !digest(scales.getFirst().templateDigest())) {
                    fail(failures, context, "per014_template", "TEMPLATE_PROVENANCE_UNAVAILABLE");
                    continue;
                }
                form = forms.getFirst();
                scale = scales.getFirst();
            }
            ObjectNode item = items.addObject();
            item.put("itemPublicId", owner.configurationId().toString());
            item.put("title", snapshot.title() == null || snapshot.title().isBlank()
                    ? "Performance configuration" : snapshot.title());
            item.put("statusCode", owner.lifecycleState());
            ObjectNode groups = item.putObject("fieldGroups");
            for (String group : groups(variant)) {
                FieldDecision decision = context.fields().get(group);
                if (decision == FieldDecision.OMIT) { omits++; continue; }
                ObjectNode field = groups.putObject(group);
                field.put("decision", decision.name());
                if (decision == FieldDecision.MASK) {
                    masks++;
                    field.putNull("value");
                    field.put("maskReason", "FIELD_POLICY");
                    field.put("maskedDisplayKey", "common.restricted");
                } else {
                    views++;
                    field.set("value", value(variant, group, owner, snapshot, form, scale));
                }
            }
            ObjectNode link = item.putObject("deepLink");
            link.put("route", variant == Variant.FORMS_SCALES
                    ? "/hr/operations/performance/forms-scales" : "/hr/settings/performance");
            link.put("resourcePublicId", owner.configurationId().toString());
            ArrayNode actions = item.putArray("availableActions");
            if (variant == Variant.PERFORMANCE && context.fields().values().stream()
                    .allMatch(decision -> decision == FieldDecision.VIEW) && failures.isEmpty()) {
                for (TargetActionV2 descriptor : Per004ActionSelector.select(owner, asOf, asOf,
                        context.fields().keySet(), context.allowedActionRoutes(), context.featureDecision(),
                        context.subject(), context.tenantId(), context.authorizationRevision(),
                        context.makerCheckerAndStepUpSatisfied())) actions.add(mapper.valueToTree(descriptor));
            }
        }
        // Any partial response is display-only, including items assembled before a later failure.
        if (!failures.isEmpty()) {
            for (JsonNode item : items) ((ObjectNode) item).putArray("availableActions");
        }
        ObjectNode state = (ObjectNode) response.get("queryState");
        state.put("state", failures.isEmpty() ? (items.isEmpty() ? "EMPTY" : "COMPLETE") : "PARTIAL");
        state.put("freshness", failures.isEmpty() ? "CURRENT" : "STALE");
        if (failures.isEmpty() && platform.nextCursor() != null) state.put("nextCursor", platform.nextCursor());
        ObjectNode summary = (ObjectNode) state.get("fieldDecisionSummary");
        summary.put("viewCount", views);
        summary.put("maskedCount", masks);
        summary.put("omittedCount", omits);
        return response;
    }

    JsonNode unavailable(Variant variant, TrustedContext context, OffsetDateTime asOf) {
        requireContext(variant, context);
        ObjectNode response = shell(variant, context, asOf);
        ObjectNode state = (ObjectNode) response.get("queryState");
        state.put("state", "UNAVAILABLE");
        state.put("freshness", "UNAVAILABLE");
        return response;
    }

    private JsonNode value(Variant variant, String group, OwnerProjection owner, LocalSnapshot snapshot,
            LocalSnapshot form, LocalSnapshot scale) {
        ObjectNode value = mapper.createObjectNode();
        if (variant == Variant.FORMS_SCALES) {
            if (group.equals("FORM_VERSION")) {
                value.put("key", "performance.review.form");
                value.put("version", form.templateVersionId().toString());
                value.put("digest", form.templateDigest());
            } else if (group.equals("RATING_SCALE")) {
                value.put("publicId", scale.artifactId().toString());
                value.put("code", "RATING_SCALE");
                value.put("label", scale.title());
                value.put("revision", scale.artifactRevision());
            } else {
                value.put("code", owner.lifecycleState());
                value.put("revision", snapshot.artifactRevision());
            }
        } else if (group.equals("EFFECTIVE_STATUS")) {
            value.put("code", owner.lifecycleState());
            value.put("revision", snapshot.artifactRevision());
        } else {
            if (owner.effectiveFrom() == null) throw invalid();
            value.put("policyKey", "performance.policy");
            value.put("revision", snapshot.artifactRevision());
            value.put("effectiveFrom", owner.effectiveFrom().toLocalDate().toString());
            if (owner.effectiveTo() == null) value.putNull("effectiveTo");
            else value.put("effectiveTo", owner.effectiveTo().toLocalDate().toString());
            value.put("status", owner.lifecycleState());
        }
        return value;
    }

    private ObjectNode shell(Variant variant, TrustedContext context, OffsetDateTime asOf) {
        ObjectNode response = mapper.createObjectNode();
        response.put("projectionVersion", "2");
        response.put("asOf", asOf.toString());
        response.put("variantKey", variant.name());
        ObjectNode state = response.putObject("queryState");
        state.put("state", "EMPTY");
        state.put("scopeDecisionRevision", context.scopeRevision());
        state.put("freshness", "CURRENT");
        state.putNull("nextCursor");
        ObjectNode summary = state.putObject("fieldDecisionSummary");
        summary.put("viewCount", 0);
        summary.put("maskedCount", 0);
        summary.put("omittedCount", 0);
        state.putArray("partialFailures");
        state.put("correlationId", context.correlationId().toString());
        ObjectNode cache = state.putObject("cachePolicy");
        cache.put("authorizationDecisionRevision", context.authorizationDecisionRevision());
        cache.put("invalidationEvent", "dwp.hris.access.assignment.changed.v1");
        cache.put("maxAgeSeconds", 0);
        response.putArray("items");
        return response;
    }
    private static void fail(ArrayNode failures, TrustedContext context, String component, String code) {
        if (failures.size() >= 32) return;
        ObjectNode failure = failures.addObject();
        failure.put("componentKey", component);
        failure.put("errorCode", code);
        failure.put("correlationId", context.correlationId().toString());
        failure.put("retryable", true);
    }
    private static List<String> groups(Variant variant) {
        return variant == Variant.FORMS_SCALES
                ? List.of("FORM_VERSION", "RATING_SCALE", "POLICY_STATUS")
                : List.of("POLICY_VERSION", "PERFORMANCE_POLICY", "EFFECTIVE_STATUS");
    }
    private static void requireContext(Variant variant, TrustedContext context) {
        if (variant == null || context == null || context.tenantId() < 1 || context.subject() == null
                || context.correlationId() == null || !digest(context.scopeDigest())
                || context.scopeRevision() == null || context.scopeRevision().isBlank()
                || context.authorizationDecisionRevision() == null
                || context.authorizationDecisionRevision().isBlank()
                || context.fields() == null || !context.fields().keySet().equals(Set.copyOf(groups(variant)))
                || context.fields().values().stream().anyMatch(value -> value == null)
                || context.allowedActionRoutes() == null) throw invalid();
    }
    private static boolean digest(String value) { return value != null && value.matches("^[a-f0-9]{64}$"); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("PER004_PROJECTION_INVALID"); }
}

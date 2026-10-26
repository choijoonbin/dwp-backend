package com.dwp.services.platform.hrisconfiguration.integration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side IAQ-057/062 seam: one owner truth projected through two closed
 * policy views. It neither mounts a route nor creates a second page owner.
 */
public final class Sys008ProjectionContract {

    public static final String OWNER = "SYS008_SINGLE_OWNER_QUERY";
    public static final String RELATIONSHIP =
            "ONE_OWNER_TRUTH_TWO_POLICY_PROJECTIONS_NO_DUPLICATE_PERSISTENCE_"
                    + "CONTROLLER_DOMAIN_OR_COMPLETION_CREDIT";
    public static final String CAPABILITY = "hcm.configuration.workbench.view";
    public static final String DEFAULT_SORT = "itemPublicId:ASC";
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 100;
    public static final boolean SECOND_SIDEBAR_NODE = false;
    public static final boolean DUPLICATE_PAGE_OWNER = false;

    public static final Set<String> CURSOR_BINDINGS = Set.of(
            "tenant", "actor", "queryVariant", "filters", "sort", "asOf",
            "locale", "timeZone", "scopeRevision", "fieldPolicyRevision",
            "authorizationRevision");

    public static final Set<String> ACTION_KEYS = Set.of(
            "hris.action.sys.post.platform.v1.admin.hris.connectors",
            "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.test",
            "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.dry-runs",
            "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.executions",
            "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.validate",
            "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.submit",
            "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.approve",
            "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.activate",
            "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings",
            "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.validate",
            "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.submit",
            "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.approve",
            "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.activate",
            "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.reconcile",
            "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.retry",
            "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.cancel");

    public static final Map<Projection, ProjectionSpec> PROJECTIONS = Map.of(
            Projection.PRIMARY_HRIS_SETTINGS,
            new ProjectionSpec(
                    "IAQ-057", "BASE:settings-integrations", "GET",
                    "/api/platform/v1/hris/workbench/settings-integrations",
                    "/v1/hris/workbench/settings-integrations",
                    "/hr/settings/integrations",
                    "route.hcm.configuration.settings-integrations.page", "PAGE",
                    Set.of(FieldGroup.CONNECTOR_METADATA, FieldGroup.POLICY_VERSION,
                            FieldGroup.EFFECTIVE_STATUS),
                    Set.of("connectorMetadata", "policyVersion", "effectiveStatus",
                            "effectiveOn"),
                    Set.of("connectorMetadata:ASC", "policyVersion:ASC",
                            "effectiveStatus:ASC", "connectorMetadata:DESC",
                            "policyVersion:DESC", "effectiveStatus:DESC",
                            "itemPublicId:ASC", "itemPublicId:DESC"),
                    true),
            Projection.PRIMARY_DWP_CONTROL,
            new ProjectionSpec(
                    "IAQ-062", "BASE:dwp-integrations", "GET",
                    "/api/platform/v1/hris/workbench/dwp-integrations",
                    "/v1/hris/workbench/dwp-integrations",
                    "/admin/integrations/productivity?product=HCM",
                    "route.hcm.configuration.dwp-integrations.data", "DATA",
                    Set.of(FieldGroup.CONNECTOR_METADATA, FieldGroup.EXECUTION_POLICY,
                            FieldGroup.EFFECTIVE_STATUS),
                    Set.of("connectorMetadata", "executionPolicy", "effectiveStatus",
                            "effectiveOn"),
                    Set.of("connectorMetadata:ASC", "executionPolicy:ASC",
                            "effectiveStatus:ASC", "connectorMetadata:DESC",
                            "executionPolicy:DESC", "effectiveStatus:DESC",
                            "itemPublicId:ASC", "itemPublicId:DESC"),
                    false));

    private Sys008ProjectionContract() {}

    public static ProjectionResponse project(
            Projection projection,
            OwnerResult ownerResult,
            Map<FieldGroup, FieldDecision> fieldDecisions) {
        ProjectionSpec spec = Objects.requireNonNull(
                PROJECTIONS.get(projection), "projection must be sealed");
        Objects.requireNonNull(ownerResult, "ownerResult must not be null");
        Map<FieldGroup, FieldDecision> decisions = Map.copyOf(
                Objects.requireNonNull(fieldDecisions,
                        "fieldDecisions must not be null"));
        if (!decisions.keySet().equals(spec.fieldGroups())) {
            throw new IllegalArgumentException(
                    "field decisions must exactly cover the projection fields");
        }
        if (ownerResult.state() == QueryState.UNAVAILABLE) {
            return new ProjectionResponse(projection, QueryState.UNAVAILABLE, List.of());
        }
        boolean actionsAllowed = ownerResult.state() == QueryState.COMPLETE
                && ownerResult.evidenceCurrent()
                && decisions.values().stream().allMatch(
                        decision -> decision == FieldDecision.VIEW);
        List<ProjectionItem> items = ownerResult.items().stream().map(ownerItem -> {
            var fields = new java.util.EnumMap<FieldGroup, ProjectedField>(
                    FieldGroup.class);
            spec.fieldGroups().forEach(group -> {
                FieldDecision decision = decisions.get(group);
                if (decision != FieldDecision.OMIT) {
                    fields.put(group, new ProjectedField(
                            decision,
                            decision == FieldDecision.VIEW
                                    ? ownerItem.fields().get(group) : null));
                }
            });
            return new ProjectionItem(
                    ownerItem.itemPublicId(), ownerItem.effectiveStatusRevision(),
                    Map.copyOf(fields),
                    actionsAllowed ? ownerItem.availableActions() : Set.of());
        }).toList();
        return new ProjectionResponse(projection, ownerResult.state(), items);
    }

    public static ScopeChangeReset scopeChangeReset() {
        return new ScopeChangeReset(true, true, true);
    }

    public enum Projection {
        PRIMARY_HRIS_SETTINGS,
        PRIMARY_DWP_CONTROL
    }

    public enum FieldGroup {
        CONNECTOR_METADATA,
        POLICY_VERSION,
        EXECUTION_POLICY,
        EFFECTIVE_STATUS
    }

    public enum FieldDecision {
        VIEW,
        MASK,
        OMIT
    }

    public enum QueryState {
        COMPLETE,
        EMPTY,
        PARTIAL,
        UNAVAILABLE
    }

    public record ProjectionSpec(
            String entryQuery,
            String iaNodeId,
            String method,
            String browserApi,
            String serviceApi,
            String canonicalRoute,
            String routeContractKey,
            String routeKind,
            Set<FieldGroup> fieldGroups,
            Set<String> filters,
            Set<String> sorts,
            boolean ownsPage) {

        public ProjectionSpec {
            fieldGroups = Set.copyOf(fieldGroups);
            filters = Set.copyOf(filters);
            sorts = Set.copyOf(sorts);
            if (fieldGroups.size() != 3 || filters.size() != 4 || sorts.size() != 8) {
                throw new IllegalArgumentException("projection cardinality is not sealed");
            }
        }
    }

    public record TrustedOwnerQuery(
            long tenantId,
            long actorId,
            Projection projection,
            Map<String, String> filters,
            String sort,
            Instant asOf,
            String locale,
            String timeZone,
            long scopeRevision,
            long fieldPolicyRevision,
            long authorizationRevision,
            int limit,
            AuthenticatedCursor cursor) {

        public TrustedOwnerQuery {
            if (tenantId < 1 || actorId < 1 || scopeRevision < 1
                    || fieldPolicyRevision < 1 || authorizationRevision < 1) {
                throw new IllegalArgumentException(
                        "trusted query authority revisions must be positive");
            }
            ProjectionSpec spec = Objects.requireNonNull(
                    PROJECTIONS.get(projection), "projection must be sealed");
            filters = Map.copyOf(Objects.requireNonNull(filters));
            if (!spec.filters().containsAll(filters.keySet())) {
                throw new IllegalArgumentException("query contains an unknown filter");
            }
            if (!spec.sorts().contains(sort)) {
                throw new IllegalArgumentException("query contains an unknown sort");
            }
            Objects.requireNonNull(asOf, "asOf must not be null");
            if (locale == null || locale.isBlank()
                    || timeZone == null || timeZone.isBlank()) {
                throw new IllegalArgumentException("locale and timeZone are required");
            }
            if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
                throw new IllegalArgumentException("limit is outside the sealed range");
            }
            if (cursor != null && !cursor.matches(
                    tenantId, actorId, projection, filters, sort, asOf, locale,
                    timeZone, scopeRevision, fieldPolicyRevision,
                    authorizationRevision)) {
                throw new IllegalArgumentException(
                        "cursor does not match all eleven authenticated bindings");
            }
        }
    }

    public record AuthenticatedCursor(
            long tenantId,
            long actorId,
            Projection queryVariant,
            Map<String, String> filters,
            String sort,
            Instant asOf,
            String locale,
            String timeZone,
            long scopeRevision,
            long fieldPolicyRevision,
            long authorizationRevision,
            String tokenDigest) {

        public AuthenticatedCursor {
            filters = Map.copyOf(filters);
            if (tokenDigest == null || !tokenDigest.matches("^[0-9a-f]{64}$")) {
                throw new IllegalArgumentException(
                        "cursor token digest must be a lowercase SHA-256 digest");
            }
        }

        private boolean matches(
                long expectedTenantId,
                long expectedActorId,
                Projection expectedVariant,
                Map<String, String> expectedFilters,
                String expectedSort,
                Instant expectedAsOf,
                String expectedLocale,
                String expectedTimeZone,
                long expectedScopeRevision,
                long expectedFieldPolicyRevision,
                long expectedAuthorizationRevision) {
            return tenantId == expectedTenantId
                    && actorId == expectedActorId
                    && queryVariant == expectedVariant
                    && filters.equals(expectedFilters)
                    && Objects.equals(sort, expectedSort)
                    && Objects.equals(asOf, expectedAsOf)
                    && Objects.equals(locale, expectedLocale)
                    && Objects.equals(timeZone, expectedTimeZone)
                    && scopeRevision == expectedScopeRevision
                    && fieldPolicyRevision == expectedFieldPolicyRevision
                    && authorizationRevision == expectedAuthorizationRevision;
        }
    }

    public record OwnerItem(
            UUID itemPublicId,
            long effectiveStatusRevision,
            Map<FieldGroup, Object> fields,
            Set<String> availableActions) {

        public OwnerItem {
            Objects.requireNonNull(itemPublicId, "itemPublicId must not be null");
            if (effectiveStatusRevision < 1) {
                throw new IllegalArgumentException(
                        "effectiveStatusRevision must be positive");
            }
            fields = Map.copyOf(Objects.requireNonNull(fields));
            if (!fields.keySet().equals(Set.of(FieldGroup.values()))) {
                throw new IllegalArgumentException(
                        "owner truth must contain every sealed field group");
            }
            availableActions = Set.copyOf(Objects.requireNonNull(availableActions));
            if (!ACTION_KEYS.containsAll(availableActions)) {
                throw new IllegalArgumentException(
                        "actions must be an item-level PEP intersection");
            }
        }
    }

    public record OwnerResult(
            QueryState state,
            boolean evidenceCurrent,
            List<OwnerItem> items,
            String ownerReceipt) {

        public OwnerResult {
            Objects.requireNonNull(state, "state must not be null");
            items = List.copyOf(Objects.requireNonNull(items));
            if ((state == QueryState.EMPTY || state == QueryState.UNAVAILABLE)
                    && !items.isEmpty()) {
                throw new IllegalArgumentException(
                        "empty or unavailable owner result cannot retain rows");
            }
            if (ownerReceipt == null || ownerReceipt.isBlank()) {
                throw new IllegalArgumentException(
                        "server-side owner lineage receipt is required");
            }
        }
    }

    public record ProjectedField(FieldDecision decision, Object value) {
        public ProjectedField {
            Objects.requireNonNull(decision, "decision must not be null");
            if (decision == FieldDecision.OMIT
                    || (decision == FieldDecision.MASK && value != null)) {
                throw new IllegalArgumentException(
                        "masked fields carry no value and omitted fields are not serialized");
            }
        }
    }

    public record ProjectionItem(
            UUID itemPublicId,
            long effectiveStatusRevision,
            Map<FieldGroup, ProjectedField> fields,
            Set<String> availableActions) {
        public ProjectionItem {
            Objects.requireNonNull(itemPublicId, "itemPublicId must not be null");
            fields = Map.copyOf(fields);
            availableActions = Set.copyOf(availableActions);
        }
    }

    /** Deliberately excludes tenant and the server-only owner receipt. */
    public record ProjectionResponse(
            Projection projection,
            QueryState state,
            List<ProjectionItem> items) {
        public ProjectionResponse {
            Objects.requireNonNull(projection, "projection must not be null");
            Objects.requireNonNull(state, "state must not be null");
            items = List.copyOf(items);
        }
    }

    public record ScopeChangeReset(
            boolean clearSelection,
            boolean clearCursor,
            boolean clearCachedRows) {}
}

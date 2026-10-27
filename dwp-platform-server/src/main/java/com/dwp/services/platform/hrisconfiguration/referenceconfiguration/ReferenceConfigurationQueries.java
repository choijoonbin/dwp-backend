package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.exception.BaseException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.typedOwner;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationGenericActions.*;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationRepository.*;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationContext.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationSupport.*;

/** Catalog and workbench projections, field policies and cursor validation. */
final class ReferenceConfigurationQueries {
    private final ReferenceConfigurationRepository repository;
    private final ReferenceConfigurationSupport support;
    private final CursorFactory cursors;

    ReferenceConfigurationQueries(ReferenceConfigurationRepository repository,
            ReferenceConfigurationSupport support, CursorFactory cursors) {
        this.repository = repository;
        this.support = support;
        this.cursors = cursors;
    }

    CatalogResponse configurations(Access access, CatalogQuery query) {
        support.requireManager(access);
        validateCatalogQuery(query);
        CatalogFilter filter = parseCatalogFilter(query.filter());
        authorizeCatalogQuery(access, query, filter);
        Cursor cursor = decodeCursor(query.cursor());
        OffsetDateTime asOf = effectiveAsOf(query.asOf(), cursor);
        String scopeRevision = support.scopeRevision(access);
        String queryDigest = support.digest(Map.of(
                "asOf", asOf,
                "filter", filter == null ? "" : filter,
                "fields", query.fields(),
                "freshness", query.freshness()));
        UUID seek = validateCursor(
                cursor, "CATALOG", access.tenantId(), scopeRevision, queryDigest, asOf);
        CatalogPage page = repository.queryCatalog(
                access.tenantId(), access.scope(), asOf, filter, seek, query.limit());
        if (!page.seekFound()) throw support.conflict("REFERENCE_CONFIGURATION_CURSOR_STALE");
        String nextCursor = page.hasNext() && !page.items().isEmpty()
                ? encodeCursor(cursors.create(
                        "CATALOG", access.tenantId(), scopeRevision, queryDigest, asOf,
                        page.items().getLast().publicId()))
                : null;
        boolean stale = asOf.isBefore(support.now().minusMinutes(5));
        if (stale && query.freshness() == FreshnessPreference.FRESH_ONLY) {
            throw support.conflict("REFERENCE_CONFIGURATION_FRESH_SNAPSHOT_REQUIRED");
        }
        List<CatalogItem> projectedItems = page.items().stream()
                .map(item -> projectCatalogItem(query.fields(), item))
                .toList();
        return new CatalogResponse(
                asOf,
                nextCursor,
                projectedItems,
                stale ? asOf : support.now().plusSeconds(30),
                asOf.plusMinutes(5));
    }
    WorkbenchResponse workbench(Access access, WorkbenchQuery query) {
        support.requireView(access);
        validateQuery(query);
        authorizeWorkbenchQuery(access, query);
        Cursor cursor = decodeCursor(query.cursor());
        OffsetDateTime asOf = effectiveAsOf(query.asOf(), cursor);
        String scopeRevision = support.scopeRevision(access);
        String queryDigest = workbenchQueryDigest(query, asOf);
        UUID seek = validateCursor(
                cursor, "WORKBENCH", access.tenantId(), scopeRevision, queryDigest, asOf);
        Freshness freshness = asOf.isBefore(support.now().minusSeconds(30))
                ? Freshness.STALE
                : Freshness.CURRENT;
        if (freshness == Freshness.STALE && query.freshness() == FreshnessPreference.FRESH_ONLY) {
            throw support.conflict("REFERENCE_CONFIGURATION_FRESH_SNAPSHOT_REQUIRED");
        }

        WorkbenchPage page;
        try {
            page = repository.queryWorkbench(
                    access.tenantId(), access.scope(), query, asOf, seek, query.limit());
        } catch (RuntimeException exception) {
            PartialFailure failure = new PartialFailure(
                    "reference-configuration",
                    "REFERENCE_CONFIGURATION_SOURCE_UNAVAILABLE",
                    query.correlationId(),
                    true);
            WorkbenchQueryState unavailable = new WorkbenchQueryState(
                    QueryState.UNAVAILABLE, scopeRevision, Freshness.UNAVAILABLE, null,
                    new FieldDecisionSummary(0, 0, 0), List.of(failure), query.correlationId(),
                    new CachePolicy(scopeRevision, INVALIDATION_EVENT, 0));
            return new WorkbenchResponse("1", asOf, "REFERENCE", unavailable, List.of());
        }
        if (!page.seekFound()) throw support.conflict("REFERENCE_CONFIGURATION_CURSOR_STALE");

        List<PartialFailure> failures = new ArrayList<>();
        List<WorkbenchItem> items = new ArrayList<>();
        for (WorkbenchRow row : page.rows()) {
            try {
                items.add(workbenchItem(access, query.fields(), row.item(), row.detail()));
            } catch (RuntimeException exception) {
                failures.add(new PartialFailure(
                        "reference-configuration",
                        "REFERENCE_CONFIGURATION_ITEM_UNAVAILABLE",
                        query.correlationId(),
                        true));
            }
        }
        String nextCursor = page.hasNext() && !page.rows().isEmpty()
                ? encodeCursor(cursors.create(
                        "WORKBENCH", access.tenantId(), scopeRevision, queryDigest, asOf,
                        page.rows().getLast().item().publicId()))
                : null;
        long viewCount = items.stream().flatMap(item -> item.fieldGroups().values().stream())
                .filter(value -> value.decision() == FieldDecision.VIEW).count();
        long maskedCount = items.stream().flatMap(item -> item.fieldGroups().values().stream())
                .filter(value -> value.decision() == FieldDecision.MASK).count();
        long omittedCount = (long) items.size() * FIELD_GROUPS.size() - viewCount - maskedCount;
        QueryState state = !failures.isEmpty()
                ? QueryState.PARTIAL
                : items.isEmpty() ? QueryState.EMPTY : QueryState.COMPLETE;
        WorkbenchQueryState queryState = new WorkbenchQueryState(
                state, scopeRevision, freshness, nextCursor,
                new FieldDecisionSummary(viewCount, maskedCount, omittedCount),
                List.copyOf(failures), query.correlationId(),
                new CachePolicy(scopeRevision, INVALIDATION_EVENT,
                        freshness == Freshness.CURRENT ? 30 : 0));
        return new WorkbenchResponse("1", asOf, "REFERENCE", queryState, List.copyOf(items));
    }

    private WorkbenchItem workbenchItem(
            Access access,
            List<String> requestedFields,
            CatalogItem item,
            VersionDetail detail) {
        Map<String, FieldValue<?>> groups = new LinkedHashMap<>();
        boolean identityVisible = fieldVisible(access, requestedFields, "POLICY_VERSION")
                && fieldVisible(access, requestedFields, "REFERENCE_DATA");
        boolean statusVisible = fieldVisible(access, requestedFields, "EFFECTIVE_STATUS");
        addField(access, requestedFields, groups, "POLICY_VERSION", new PolicyValue(
                identityVisible ? detail.schemaKey() : "RESTRICTED",
                detail.version(), detail.effectiveFrom().toLocalDate(),
                detail.effectiveTo() == null ? null : detail.effectiveTo().toLocalDate(),
                statusVisible ? detail.state().name() : "RESTRICTED"));
        addField(access, requestedFields, groups, "REFERENCE_DATA", new ReferenceValue(
                item.publicId().toString(), item.domain().name(),
                identityVisible ? item.key() : "RESTRICTED", item.version()));
        addField(access, requestedFields, groups, "EFFECTIVE_STATUS", new StatusValue(
                item.state().name(), item.version()));
        if (groups.isEmpty()) {
            throw support.forbidden("REFERENCE_CONFIGURATION_ALL_FIELDS_OMITTED");
        }
        return new WorkbenchItem(
                item.publicId().toString(),
                identityVisible ? item.key() : "Restricted reference configuration",
                statusVisible ? item.state().name() : "RESTRICTED",
                Map.copyOf(groups),
                new DeepLink(WORKBENCH_ROUTE, item.publicId().toString()),
                actions(access, groups));
    }

    private void addField(
            Access access,
            List<String> requestedFields,
            Map<String, FieldValue<?>> groups,
            String key,
            Object value) {
        if (!requestedFields.contains(key) || access.omittedFieldGroups().contains(key)) return;
        if (access.maskedFieldGroups().contains(key)) {
            groups.put(key, FieldValue.masked("FIELD_POLICY", "hris.field.masked"));
        } else {
            groups.put(key, FieldValue.view(value));
        }
    }

    private List<AvailableAction> actions(
            Access access,
            Map<String, FieldValue<?>> groups) {
        boolean configurationVisible = decision(groups, "POLICY_VERSION") == FieldDecision.VIEW
                && decision(groups, "REFERENCE_DATA") == FieldDecision.VIEW;
        boolean impactVisible = decision(groups, "EFFECTIVE_STATUS") == FieldDecision.VIEW;
        if (!configurationVisible) return List.of();
        List<AvailableAction> result = new ArrayList<>();
        Set<String> permissions = support.permissions(access.permissions());
        if (permissions.contains(UPDATE_PERMISSION)) result.addAll(AUTHOR_ACTIONS);
        if (impactVisible && permissions.contains(PUBLISH_PERMISSION)) result.addAll(PUBLISHER_ACTIONS);
        return List.copyOf(result);
    }

    private FieldDecision decision(Map<String, FieldValue<?>> groups, String key) {
        FieldValue<?> value = groups.get(key);
        return value == null ? FieldDecision.OMIT : value.decision();
    }

    private boolean fieldVisible(
            Access access,
            List<String> requestedFields,
            String fieldGroup) {
        return requestedFields.contains(fieldGroup)
                && !access.maskedFieldGroups().contains(fieldGroup)
                && !access.omittedFieldGroups().contains(fieldGroup);
    }

    private CatalogItem projectCatalogItem(
            List<String> fields,
            CatalogItem item) {
        return new CatalogItem(
                item.publicId(),
                fields.contains("key") ? item.key() : null,
                fields.contains("type") ? item.domain() : null,
                fields.contains("state") ? item.state() : null,
                fields.contains("version") ? item.version() : null,
                fields.contains("updatedAt") ? item.updatedAt() : null);
    }

    private void authorizeCatalogQuery(
            Access access,
            CatalogQuery query,
            CatalogFilter filter) {
        if (query.fields().contains("key")) {
            requireQueryFieldView(access, query.fields(), "key", "POLICY_VERSION",
                    "REFERENCE_CONFIGURATION_CATALOG_FIELD_POLICY_DENIED");
            requireQueryFieldView(access, query.fields(), "key", "REFERENCE_DATA",
                    "REFERENCE_CONFIGURATION_CATALOG_FIELD_POLICY_DENIED");
        }
        if (query.fields().contains("type")) {
            requireQueryFieldView(access, query.fields(), "type", "REFERENCE_DATA",
                    "REFERENCE_CONFIGURATION_CATALOG_FIELD_POLICY_DENIED");
        }
        if (query.fields().contains("state") || query.fields().contains("updatedAt")) {
            requirePolicyView(access, "EFFECTIVE_STATUS",
                    "REFERENCE_CONFIGURATION_CATALOG_FIELD_POLICY_DENIED");
        }
        if (query.fields().contains("version")) {
            requirePolicyView(access, "POLICY_VERSION",
                    "REFERENCE_CONFIGURATION_CATALOG_FIELD_POLICY_DENIED");
        }
        if (filter == null) return;
        if (filter.key() != null) {
            requireCatalogFilterView(access, query.fields(), "key", "POLICY_VERSION");
            requireCatalogFilterView(access, query.fields(), "key", "REFERENCE_DATA");
        }
        if (filter.domain() != null) {
            requireCatalogFilterView(access, query.fields(), "type", "REFERENCE_DATA");
        }
        if (filter.state() != null) {
            requireCatalogFilterView(access, query.fields(), "state", "EFFECTIVE_STATUS");
        }
        if (filter.minimumVersion() != null) {
            requireCatalogFilterView(access, query.fields(), "version", "POLICY_VERSION");
        }
    }

    private void requireCatalogFilterView(
            Access access,
            List<String> fields,
            String wireField,
            String fieldGroup) {
        requireQueryFieldView(access, fields, wireField, fieldGroup,
                "REFERENCE_CONFIGURATION_CATALOG_FILTER_POLICY_DENIED");
    }

    private void authorizeWorkbenchQuery(Access access, WorkbenchQuery query) {
        WorkbenchFilter filter = query.filter();
        if (support.notEmpty(filter.policyKeys())) {
            requireQueryFieldView(access, query.fields(), "POLICY_VERSION", "POLICY_VERSION",
                    "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
            requireQueryFieldView(access, query.fields(), "REFERENCE_DATA", "REFERENCE_DATA",
                    "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
        }
        if (filter.minimumPolicyRevision() != null) {
            requireQueryFieldView(access, query.fields(), "POLICY_VERSION", "POLICY_VERSION",
                    "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
        }
        if (support.notEmpty(filter.referencePublicIds()) || support.notEmpty(filter.referenceCodes())) {
            requireQueryFieldView(access, query.fields(), "REFERENCE_DATA", "REFERENCE_DATA",
                    "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
        }
        if (support.notEmpty(filter.effectiveStatuses()) || filter.minimumStatusRevision() != null
                || filter.effectiveOn() != null) {
            requireQueryFieldView(access, query.fields(), "EFFECTIVE_STATUS", "EFFECTIVE_STATUS",
                    "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
        }
        for (String sort : query.sort()) {
            if (sort.startsWith("policyVersion:")) {
                requireQueryFieldView(access, query.fields(), "POLICY_VERSION", "POLICY_VERSION",
                        "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
            } else if (sort.startsWith("referenceData:")) {
                requireQueryFieldView(access, query.fields(), "POLICY_VERSION", "POLICY_VERSION",
                        "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
                requireQueryFieldView(access, query.fields(), "REFERENCE_DATA", "REFERENCE_DATA",
                        "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
            } else if (sort.startsWith("effectiveStatus:")) {
                requireQueryFieldView(access, query.fields(), "EFFECTIVE_STATUS", "EFFECTIVE_STATUS",
                        "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
            }
        }
    }

    private void requireQueryFieldView(
            Access access,
            List<String> requestedFields,
            String requestedField,
            String fieldGroup,
            String error) {
        if (!requestedFields.contains(requestedField)) throw support.forbidden(error);
        requirePolicyView(access, fieldGroup, error);
    }

    private void requirePolicyView(Access access, String fieldGroup, String error) {
        if (access.maskedFieldGroups().contains(fieldGroup)
                || access.omittedFieldGroups().contains(fieldGroup)) {
            throw support.forbidden(error);
        }
    }

    private void validateCatalogQuery(CatalogQuery query) {
        if (query == null || query.limit() < 1 || query.limit() > 100
                || query.fields() == null || query.fields().isEmpty()
                || !query.fields().contains("publicId")
                || query.freshness() == null
                || !CATALOG_FIELDS.containsAll(query.fields())
                || new LinkedHashSet<>(query.fields()).size() != query.fields().size()) {
            throw support.invalid("REFERENCE_CONFIGURATION_CATALOG_QUERY_INVALID");
        }
        if (query.filter() != null && query.filter().length() > 1000) {
            throw support.invalid("REFERENCE_CONFIGURATION_CATALOG_FILTER_INVALID");
        }
    }

    private CatalogFilter parseCatalogFilter(String value) {
        if (value == null || value.isBlank()) return null;
        String key = null;
        Domain domain = null;
        LifecycleState state = null;
        Long minimumVersion = null;
        Set<String> seen = new LinkedHashSet<>();
        for (String expression : value.split(";", -1)) {
            String[] pair = expression.split("==", 2);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()
                    || !seen.add(pair[0])) {
                throw support.invalid("REFERENCE_CONFIGURATION_CATALOG_FILTER_INVALID");
            }
            try {
                switch (pair[0]) {
                    case "key" -> key = pair[1];
                    case "type" -> domain = Domain.valueOf(pair[1]);
                    case "state" -> state = LifecycleState.valueOf(pair[1]);
                    case "minimumVersion" -> minimumVersion = Long.parseLong(pair[1]);
                    default -> throw support.invalid("REFERENCE_CONFIGURATION_CATALOG_FILTER_INVALID");
                }
            } catch (IllegalArgumentException exception) {
                throw support.invalid("REFERENCE_CONFIGURATION_CATALOG_FILTER_INVALID");
            }
        }
        if (minimumVersion != null && minimumVersion < 1) {
            throw support.invalid("REFERENCE_CONFIGURATION_CATALOG_FILTER_INVALID");
        }
        return new CatalogFilter(key, domain, state, minimumVersion);
    }

    private void validateQuery(WorkbenchQuery query) {
        if (query == null || query.limit() == null || query.limit() < 1 || query.limit() > 100) {
            throw support.invalid("REFERENCE_CONFIGURATION_QUERY_INVALID");
        }
        if (query.filter() == null) throw support.invalid("REFERENCE_CONFIGURATION_FILTER_REQUIRED");
        validateFilterValues(query.filter());
        if (query.sort() == null || query.sort().isEmpty() || query.sort().size() > 3
                || new LinkedHashSet<>(query.sort()).size() != query.sort().size()
                || !ALLOWED_SORTS.containsAll(query.sort())) {
            throw support.invalid("REFERENCE_CONFIGURATION_SORT_INVALID");
        }
        if (query.fields() == null || query.fields().isEmpty()
                || !FIELD_GROUPS.containsAll(query.fields())
                || new LinkedHashSet<>(query.fields()).size() != query.fields().size()
                || query.freshness() == null) {
            throw support.invalid("REFERENCE_CONFIGURATION_FIELD_SELECTION_INVALID");
        }
        Locale locale = Locale.forLanguageTag(query.locale());
        if (locale.getLanguage().isBlank()) throw support.invalid("REFERENCE_CONFIGURATION_LOCALE_INVALID");
        try {
            ZoneId.of(query.timeZone());
        } catch (RuntimeException exception) {
            throw support.invalid("REFERENCE_CONFIGURATION_TIME_ZONE_INVALID");
        }
        if (query.correlationId() == null) {
            throw support.invalid("REFERENCE_CONFIGURATION_CORRELATION_REQUIRED");
        }
    }

    private void validateFilterValues(WorkbenchFilter filter) {
        validateList(filter.policyKeys(), 50, "^[A-Za-z0-9._:-]{1,128}$");
        validateList(filter.referencePublicIds(), 50, "^[A-Za-z0-9._:-]{1,128}$");
        validateList(filter.referenceCodes(), 50, "^[A-Z][A-Z0-9_]{1,63}$");
        validateList(filter.effectiveStatuses(), 50, "^[A-Z][A-Z0-9_]{1,63}$");
        if ((filter.minimumPolicyRevision() != null && filter.minimumPolicyRevision() < 1)
                || (filter.minimumStatusRevision() != null && filter.minimumStatusRevision() < 1)) {
            throw support.invalid("REFERENCE_CONFIGURATION_FILTER_INVALID");
        }
    }

    private void validateList(List<String> values, int max, String pattern) {
        if (values == null) return;
        if (values.size() > max || new LinkedHashSet<>(values).size() != values.size()
                || values.stream().anyMatch(value -> value == null || !value.matches(pattern))) {
            throw support.invalid("REFERENCE_CONFIGURATION_FILTER_INVALID");
        }
    }

    private String workbenchQueryDigest(WorkbenchQuery query, OffsetDateTime asOf) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("asOf", asOf);
        identity.put("filter", query.filter());
        identity.put("fields", query.fields());
        identity.put("freshness", query.freshness());
        identity.put("sort", query.sort());
        identity.put("locale", query.locale());
        identity.put("timeZone", query.timeZone());
        return support.digest(identity);
    }

    private OffsetDateTime effectiveAsOf(OffsetDateTime requested, Cursor cursor) {
        OffsetDateTime value = requested != null ? requested : cursor != null ? cursor.asOf() : support.now();
        if (value.isAfter(support.now().plusSeconds(1))) {
            throw support.invalid("REFERENCE_CONFIGURATION_AS_OF_INVALID");
        }
        return value;
    }

    private UUID validateCursor(
            Cursor cursor,
            String kind,
            long tenantId,
            String scopeRevision,
            String queryDigest,
            OffsetDateTime asOf) {
        if (cursor == null) return null;
        if (!kind.equals(cursor.kind())
                || cursor.tenantId() != tenantId
                || !scopeRevision.equals(cursor.scopeRevision())
                || !queryDigest.equals(cursor.queryDigest())
                || !asOf.isEqual(cursor.asOf())) {
            throw support.conflict("REFERENCE_CONFIGURATION_CURSOR_STALE");
        }
        return cursor.seekAfter();
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null) return null;
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 7);
            if (parts.length != 7) throw support.invalid("REFERENCE_CONFIGURATION_CURSOR_INVALID");
            String unsigned = String.join("|", parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]);
            if (!support.digest(unsigned).equals(parts[6])) {
                throw support.invalid("REFERENCE_CONFIGURATION_CURSOR_INVALID");
            }
            return cursors.create(
                    parts[0], Long.parseLong(parts[1]), parts[2], parts[3],
                    OffsetDateTime.parse(parts[4]), UUID.fromString(parts[5]));
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw support.invalid("REFERENCE_CONFIGURATION_CURSOR_INVALID");
        }
    }

    private String encodeCursor(Cursor state) {
        String unsigned = String.join("|",
                state.kind(), Long.toString(state.tenantId()), state.scopeRevision(),
                state.queryDigest(), state.asOf().toString(), state.seekAfter().toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (unsigned + "|" + support.digest(unsigned)).getBytes(StandardCharsets.UTF_8));
    }

    String encodeCursor(
            long tenantId,
            String scopeRevision,
            String queryDigest,
            OffsetDateTime asOf,
            UUID itemPublicId) {
        return encodeCursor(cursors.create(
                "WORKBENCH", tenantId, scopeRevision, queryDigest, asOf, itemPublicId));
    }

}

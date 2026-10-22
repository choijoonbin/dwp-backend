package com.dwp.services.payroll.statutory.pay004;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAY_RETIREMENT_WORKBENCH.enabled",
        havingValue = "true", matchIfMissing = false)
class Pay004Service {

    private final Pay004Store store;
    private final Pay004Ports.ProjectionDecisionPort projections;
    private final Pay004Canonical canonical;
    private final Clock clock;

    Pay004Service(
            Pay004Store store,
            Pay004Ports.ProjectionDecisionPort projections,
            Pay004Canonical canonical) {
        this(store, projections, canonical, Clock.systemUTC());
    }

    Pay004Service(
            Pay004Store store,
            Pay004Ports.ProjectionDecisionPort projections,
            Pay004Canonical canonical,
            Clock clock) {
        this.store = store;
        this.projections = projections;
        this.canonical = canonical;
        this.clock = clock;
    }

    Pay004Models.EntryQueryResponse retirement(
            Pay004Authority.Actor actor, Pay004Models.QueryRequest query) {
        actor.require(Pay004Route.RETIREMENT);
        requireCurrent(actor);
        if (!Pay004Models.VARIANT.equals(query.variantKey())) {
            throw invalid("Query variant differs from IAQ-049.");
        }
        Map<String, Pay004Models.Decision> decisions = decisions(actor);
        if (decisions.values().stream().allMatch(
                decision -> decision == Pay004Models.Decision.OMIT)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "PAY-004 has no permitted field group.");
        }
        requireQueryableFields(decisions, query);
        Pay004Canonical.CursorState cursor = canonical.cursorState(query.cursor());
        Instant asOf = cursor == null
                ? query.asOf() == null ? clock.instant() : query.asOf()
                : cursor.asOf();
        if (cursor != null && query.asOf() != null
                && !query.asOf().equals(cursor.asOf())) {
            throw invalid("Cursor asOf differs from the governed query.");
        }

        String binding = canonical.queryBinding(
                actor, query, asOf, decisions);
        if (cursor != null && !cursor.bindingDigest().equals(binding)) {
            throw invalid("Cursor belongs to another governed PAY-004 query.");
        }
        int offset = cursor == null ? 0 : cursor.offset();
        Pay004Models.SourcePage source = store.retirement(
                actor, query, asOf, offset);
        if (source.rows().size() > query.limit()) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "PAY-004 source page exceeds the governed limit.");
        }
        String freshness = switch (source.availability()) {
            case AVAILABLE -> "CURRENT";
            case PARTIAL -> "CURRENT";
            case UNAVAILABLE -> "UNAVAILABLE";
        };
        if (source.availability() == Pay004Models.Availability.UNAVAILABLE) {
            return response(actor, query, asOf,
                    Pay004Models.QueryState.UNAVAILABLE, freshness, List.of(),
                    null, new Pay004Models.FieldDecisionSummary(0, 0, 0),
                    List.of(failure("pay004.owner-source", query)));
        }

        List<Pay004Models.RetirementProjection> selected = source.rows();
        LinkedHashSet<String> failedGroups = new LinkedHashSet<>();
        source.failedGroups().stream()
                .filter(group -> decisions.get(group)
                        == Pay004Models.Decision.VIEW)
                .forEach(failedGroups::add);
        List<Pay004Models.EntryItem> items = selected.stream()
                .map(row -> item(row, decisions, failedGroups)).toList();
        String next = source.hasNext()
                ? canonical.cursor(offset + selected.size(), binding, asOf)
                : null;
        Pay004Models.FieldDecisionSummary summary = summary(items);
        if (items.isEmpty()) {
            if (!failedGroups.isEmpty()) {
                return response(actor, query, asOf,
                        Pay004Models.QueryState.UNAVAILABLE, "UNAVAILABLE",
                        List.of(), null,
                        new Pay004Models.FieldDecisionSummary(0, 0, 0),
                        failedGroups.stream()
                                .map(group -> failure(group, query)).toList());
            }
            return response(actor, query, asOf, Pay004Models.QueryState.EMPTY,
                    "CURRENT", List.of(), null,
                    new Pay004Models.FieldDecisionSummary(0, 0, 0), List.of());
        }
        if (!failedGroups.isEmpty()) {
            List<Pay004Models.PartialFailure> failures = failedGroups.stream()
                    .map(group -> failure(group, query)).toList();
            return response(actor, query, asOf,
                    Pay004Models.QueryState.PARTIAL, "CURRENT", items, next,
                    summary, failures);
        }
        return response(actor, query, asOf, Pay004Models.QueryState.COMPLETE,
                "CURRENT", items, next, summary, List.of());
    }

    private Pay004Models.EntryItem item(
            Pay004Models.RetirementProjection row,
            Map<String, Pay004Models.Decision> decisions,
            Set<String> failedGroups) {
        LinkedHashMap<String, Pay004Models.FieldDecision> fields =
                new LinkedHashMap<>();
        value(fields, decisions, failedGroups, "RETIREMENT_RESULT",
                row.retirementResult() == null ? null
                        : Pay004Models.MoneyValue.from(row.retirementResult()));
        value(fields, decisions, failedGroups, "POLICY_VERSION",
                row.policyKey() == null ? null : new Pay004Models.PolicyValue(
                        row.policyKey(), row.policyRevision(),
                        row.policyEffectiveFrom(), row.policyEffectiveTo(),
                        row.countryPackState().name()));
        value(fields, decisions, failedGroups, "CASE_STATUS",
                new Pay004Models.StatusValue(
                        row.caseState().name(), row.caseRevision()));
        if (fields.isEmpty()) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "PAY-004 item has no safe field projection.");
        }
        String caseId = row.caseId().toString();
        boolean statusDisclosed = decisions.get("CASE_STATUS")
                == Pay004Models.Decision.VIEW
                && !failedGroups.contains("CASE_STATUS");
        return new Pay004Models.EntryItem(
                caseId, "Retirement case",
                statusDisclosed ? row.caseState().name() : "RESTRICTED",
                fields,
                new Pay004Models.DeepLink(
                        Pay004Models.CANONICAL_ROUTE, caseId), List.of());
    }

    private void value(
            Map<String, Pay004Models.FieldDecision> target,
            Map<String, Pay004Models.Decision> decisions,
            Set<String> failedGroups,
            String group,
            Object value) {
        Pay004Models.Decision decision = decisions.get(group);
        if (decision == Pay004Models.Decision.OMIT) return;
        if (decision == Pay004Models.Decision.MASK) {
            target.put(group, Pay004Models.FieldDecision.mask());
            return;
        }
        if (failedGroups.contains(group)) return;
        if (value != null) {
            target.put(group, Pay004Models.FieldDecision.view(value));
        }
    }

    private Map<String, Pay004Models.Decision> decisions(
            Pay004Authority.Actor actor) {
        Map<String, Pay004Models.Decision> result = projections.decisions(
                actor, Pay004Models.VARIANT);
        if (result == null
                || !result.keySet().equals(Set.copyOf(Pay004Models.FIELD_GROUPS))
                || result.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Exact PAY-004 field decisions are unavailable.");
        }
        return Map.copyOf(result);
    }

    private void requireQueryableFields(
            Map<String, Pay004Models.Decision> decisions,
            Pay004Models.QueryRequest query) {
        LinkedHashSet<String> required = new LinkedHashSet<>();
        query.filter().keySet().forEach(filter -> {
            if (filter.startsWith("filter[retirementResult]")) {
                required.add("RETIREMENT_RESULT");
            } else if (filter.startsWith("filter[policyVersion]")) {
                required.add("POLICY_VERSION");
            } else if (filter.startsWith("filter[caseStatus]")) {
                required.add("CASE_STATUS");
            } else if (filter.equals("filter[effectiveOn]")) {
                required.add("CASE_STATUS");
            }
        });
        query.sort().forEach(sort -> {
            if (sort.startsWith("retirementResult:")) {
                required.add("RETIREMENT_RESULT");
            } else if (sort.startsWith("policyVersion:")) {
                required.add("POLICY_VERSION");
            } else if (sort.startsWith("caseStatus:")) {
                required.add("CASE_STATUS");
            }
        });
        if (required.stream().anyMatch(group ->
                decisions.get(group) != Pay004Models.Decision.VIEW)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "PAY-004 query criteria require disclosed fields.");
        }
    }

    private Pay004Models.FieldDecisionSummary summary(
            List<Pay004Models.EntryItem> items) {
        int view = items.stream().mapToInt(item -> (int) item.fieldGroups()
                .values().stream().filter(field ->
                        "VIEW".equals(field.decision())).count()).sum();
        int mask = items.stream().mapToInt(item -> (int) item.fieldGroups()
                .values().stream().filter(field ->
                        "MASK".equals(field.decision())).count()).sum();
        return new Pay004Models.FieldDecisionSummary(
                view, mask, items.size() * Pay004Models.FIELD_GROUPS.size()
                - view - mask);
    }

    private Pay004Models.EntryQueryResponse response(
            Pay004Authority.Actor actor,
            Pay004Models.QueryRequest query,
            Instant asOf,
            Pay004Models.QueryState state,
            String freshness,
            List<Pay004Models.EntryItem> items,
            String next,
            Pay004Models.FieldDecisionSummary summary,
            List<Pay004Models.PartialFailure> failures) {
        return new Pay004Models.EntryQueryResponse(
                "1", asOf, Pay004Models.VARIANT,
                new Pay004Models.QueryStateValue(
                        state, actor.scope().projectionRevision(), freshness,
                        next, summary, failures, query.correlationId(),
                        new Pay004Models.CachePolicy(
                                actor.subject().decisionRevision(),
                                "dwp.hris.access.assignment.changed.v1", 0)),
                items);
    }

    private Pay004Models.PartialFailure failure(
            String group, Pay004Models.QueryRequest query) {
        String componentKey = group.matches("[A-Z][A-Z0-9_]*")
                ? "pay004." + group.toLowerCase(java.util.Locale.ROOT)
                .replace('_', '-')
                : group;
        return new Pay004Models.PartialFailure(
                componentKey, "PRODUCER_UNAVAILABLE",
                query.correlationId(), true);
    }

    private void requireCurrent(Pay004Authority.Actor actor) {
        if (!actor.subject().revalidateAt().isAfter(clock.instant())) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                    "PAY-004 authorization evidence is stale.");
        }
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}

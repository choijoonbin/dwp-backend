package com.dwp.services.payroll.preparation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC operations for payroll queries. */
final class PayrollPreparationQueryJdbc extends PayrollPreparationJdbcSupport {

    private final PayrollPreparationSourceJdbc sources;

    PayrollPreparationQueryJdbc(
            NamedParameterJdbcTemplate jdbc,
            PayrollPreparationCanonical canonical,
            PayrollPreparationSemanticSnapshotPort semantics,
            PayrollPreparationPopulationSnapshotPort populations,
            Clock clock,
            PayrollPreparationSourceJdbc sources) {
        super(jdbc, canonical, semantics, populations, clock);
        this.sources = sources;
    }

    public PayrollPreparationModels.EntryQueryResponse population(
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationModels.EntryQuery query) {
        canonical.requireCursorAuthority();
        bindTenant(actor.subject().tenantId());
        PayrollPreparationCanonical.CursorState cursorState =
                canonical.cursorState(query.cursor());
        Instant asOf = cursorState == null
                ? (query.asOf() == null ? clock.instant() : query.asOf())
                : cursorState.asOf();
        if (cursorState != null && query.asOf() != null
                && !query.asOf().equals(cursorState.asOf())) {
            throw invalid("Cursor asOf differs from the governed query.");
        }
        List<PayrollPreparationModels.EntryItem> items = new ArrayList<>();
        for (PayrollPreparationAuthority.PopulationTuple tuple
                : actor.scope().populations()) {
            PayrollPreparationPopulationSnapshotPort.Review review =
                    populations.review(actor, tuple, asOf);
            if (review == null || review.availability()
                    != PayrollPreparationPopulationSnapshotPort.Availability.EXACT) {
                return unavailable(query, actor, asOf,
                        "Authoritative payroll population membership is unavailable.");
            }
            if (!sources.populationAuthority(
                    actor.subject().tenantId(), tuple,
                    actor.subject().populationKind(), null, review.revision(),
                    review.projectionDigest(), review.itemCount(), asOf, null)) {
                return unavailable(query, actor, asOf,
                        "Published payroll population identity is unavailable.");
            }
            Foundation foundation = sources.foundation(
                    actor.subject().tenantId(), tuple.legalEntityId(),
                    tuple.payrollGroupId(), query.filter().effectiveOn(), asOf)
                    .orElse(null);
            if (foundation == null) continue;
            PayrollPreparationModels.EntryItem item = entryItem(
                    actor, tuple, review.itemCount(),
                    Math.max(foundation.version(), review.revision()), asOf,
                    review.projectionDigest(), review.itemCount() > 0 && sources.formulaCount(
                            actor.subject().tenantId(), tuple, asOf) > 0);
            if (matches(query, item)) items.add(item);
        }
        items.sort(comparator(query.sort()));
        Map<String, Object> cursorBinding = new LinkedHashMap<>();
        cursorBinding.put("schema", "PayrollPopulationCursorBinding.v2");
        cursorBinding.put("tenantId", actor.subject().tenantId());
        cursorBinding.put("subjectPrincipalPublicId",
                actor.subject().subjectPrincipalPublicId());
        cursorBinding.put("scopeRevision", actor.scope().projectionRevision());
        cursorBinding.put("scopeDigest", actor.scope().scopeDigest());
        cursorBinding.put("decisionRevision", actor.subject().decisionRevision());
        cursorBinding.put("authorizationRevision",
                actor.subject().authorizationRevision());
        cursorBinding.put("filter", query.filter());
        cursorBinding.put("limit", query.limit());
        cursorBinding.put("locale", query.locale());
        cursorBinding.put("sort", query.sort());
        cursorBinding.put("timeZone", query.timeZone());
        cursorBinding.put("asOf", asOf);
        cursorBinding.put("freshness", "CURRENT");
        String bindingDigest = canonical.digest(cursorBinding);
        if (cursorState != null
                && !bindingDigest.equals(cursorState.bindingDigest())) {
            throw invalid("Cursor belongs to another governed query.");
        }
        int offset = cursorState == null ? 0 : cursorState.offset();
        if (offset > items.size()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Cursor is beyond the current Payroll population projection.");
        }
        int end = Math.min(offset + query.limit(), items.size());
        List<PayrollPreparationModels.EntryItem> page =
                List.copyOf(items.subList(offset, end));
        String nextCursor = end < items.size()
                ? canonical.cursor(end, bindingDigest, asOf) : null;
        int viewed = page.stream().mapToInt(item -> (int) item.fieldGroups()
                .values().stream().filter(value ->
                        "VIEW".equals(value.decision())).count()).sum();
        int masked = page.stream().mapToInt(item -> (int) item.fieldGroups()
                .values().stream().filter(value ->
                        "MASK".equals(value.decision())).count()).sum();
        PayrollPreparationModels.QueryState state =
                new PayrollPreparationModels.QueryState(
                        items.isEmpty() ? "EMPTY" : "COMPLETE",
                        actor.scope().projectionRevision(), "CURRENT", nextCursor,
                        new PayrollPreparationModels.FieldDecisionSummary(
                                viewed, masked, page.size() * 2 - viewed - masked),
                        List.of(), query.correlationId(),
                        new PayrollPreparationModels.CachePolicy(
                                actor.subject().decisionRevision(),
                                "dwp.hris.access.assignment.changed.v1", 0));
        return new PayrollPreparationModels.EntryQueryResponse(
                "1", asOf, "POPULATION", state, page);
    }

    PayrollPreparationModels.EntryItem entryItem(
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationAuthority.PopulationTuple tuple,
            long workerCount,
            long revision,
            Instant asOf,
            String projectionDigest,
            boolean formulaReady) {
        String publicId = "population:" + tuple.legalEntityId()
                + ":" + tuple.payrollGroupId();
        Map<String, PayrollPreparationModels.FieldDecision> fields =
                new LinkedHashMap<>();
        fields.put("PAYROLL_POPULATION", PayrollPreparationModels.FieldDecision.view(
                new PayrollPreparationModels.AggregateValue(
                        workerCount, revision, asOf)));
        fields.put("PROJECTION_VERSION", PayrollPreparationModels.FieldDecision.view(
                new PayrollPreparationModels.MetadataValue(
                        "IAQ-014", "1", projectionDigest)));
        List<PayrollPreparationModels.AvailableAction> actions =
                workerCount > 0 && formulaReady
                        && actor.subject().permissions().contains(
                        PayrollPreparationRoute.PREPARE.capabilityCode())
                        ? List.of(new PayrollPreparationModels.AvailableAction(
                        "hris.action.pay.run.prepare", "POST",
                        "/api/payroll/v1/runs", "REQUIRED_IDEMPOTENCY_KEY",
                        "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED"))
                        : List.of();
        return new PayrollPreparationModels.EntryItem(
                publicId,
                "Payroll population " + tuple.legalEntityId() + " / "
                        + tuple.payrollGroupId(),
                workerCount > 0 && formulaReady
                        ? "READY" : "PREFLIGHT_BLOCKED",
                fields,
                new PayrollPreparationModels.DeepLink(
                        "/hr/operations/payroll/population", publicId), actions);
    }

    PayrollPreparationModels.EntryQueryResponse unavailable(
            PayrollPreparationModels.EntryQuery query,
            PayrollPreparationAuthority.Actor actor,
            Instant asOf,
            String ignoredMessage) {
        PayrollPreparationModels.QueryState state =
                new PayrollPreparationModels.QueryState(
                        "UNAVAILABLE", actor.scope().projectionRevision(),
                        "UNAVAILABLE", null,
                        new PayrollPreparationModels.FieldDecisionSummary(0, 0, 0),
                        List.of(new PayrollPreparationModels.PartialFailure(
                                "payroll.population.membership",
                                "AUTHORITY_RESOLUTION_UNAVAILABLE",
                                query.correlationId(), false)),
                        query.correlationId(),
                        new PayrollPreparationModels.CachePolicy(
                                actor.subject().decisionRevision(),
                                "dwp.hris.access.assignment.changed.v1", 0));
        return new PayrollPreparationModels.EntryQueryResponse(
                "1", asOf, "POPULATION", state, List.of());
    }

    boolean matches(
            PayrollPreparationModels.EntryQuery query,
            PayrollPreparationModels.EntryItem item) {
        PayrollPreparationModels.AggregateValue aggregate =
                (PayrollPreparationModels.AggregateValue) item.fieldGroups()
                        .get("PAYROLL_POPULATION").value();
        PayrollPreparationModels.MetadataValue metadata =
                (PayrollPreparationModels.MetadataValue) item.fieldGroups()
                        .get("PROJECTION_VERSION").value();
        PayrollPreparationModels.AggregateFilter population =
                query.filter().payrollPopulation();
        if (population != null
                && ((population.minimumItemCount() != null
                && aggregate.itemCount() < population.minimumItemCount())
                || (population.maximumItemCount() != null
                && aggregate.itemCount() > population.maximumItemCount()))) {
            return false;
        }
        PayrollPreparationModels.MetadataFilter projection =
                query.filter().projectionVersion();
        return projection == null
                || ((projection.keys().isEmpty()
                || projection.keys().contains(metadata.key()))
                && (projection.versions().isEmpty()
                || projection.versions().contains(metadata.version())));
    }

    Comparator<PayrollPreparationModels.EntryItem> comparator(
            List<String> sorts) {
        Comparator<PayrollPreparationModels.EntryItem> result = null;
        for (String sort : sorts) {
            String[] parts = sort.split(":", 2);
            Comparator<PayrollPreparationModels.EntryItem> next = switch (parts[0]) {
                case "payrollPopulation" -> Comparator.comparingLong(item ->
                        ((PayrollPreparationModels.AggregateValue) item.fieldGroups()
                                .get("PAYROLL_POPULATION").value()).itemCount());
                case "projectionVersion" -> Comparator.comparing(item ->
                        ((PayrollPreparationModels.MetadataValue) item.fieldGroups()
                                .get("PROJECTION_VERSION").value()).version());
                default -> Comparator.comparing(
                        PayrollPreparationModels.EntryItem::itemPublicId);
            };
            if ("DESC".equals(parts[1])) next = next.reversed();
            result = result == null ? next : result.thenComparing(next);
        }
        return result.thenComparing(
                PayrollPreparationModels.EntryItem::itemPublicId);
    }
}

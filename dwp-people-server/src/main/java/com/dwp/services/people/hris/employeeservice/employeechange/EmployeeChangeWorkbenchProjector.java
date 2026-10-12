package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure IAQ-009 validation, cursor binding, filtering, ordering, and projection. */
@Component
@ConditionalOnProperty(prefix = "dwp.hris.employee-change", name = "enabled",
        havingValue = "true")
final class EmployeeChangeWorkbenchProjector {

    private static final Set<String> SORTS = Set.of(
            "changeRequestSummary:ASC", "approvalStatus:ASC", "employment:ASC",
            "changeRequestSummary:DESC", "approvalStatus:DESC", "employment:DESC",
            "itemPublicId:ASC", "itemPublicId:DESC");
    private static final String CREATE_HREF =
            "/api/people/v1/hris/employee-change-requests";
    private static final String SUBMIT_HREF =
            "/api/people/v1/hris/employee-change-requests/{requestPublicId}/submit";
    private static final String APPLY_HREF =
            "/api/people/v1/hris/employee-change-requests/{requestPublicId}/apply";

    private final ObjectMapper mapper;
    private final EmployeeChangeCanonicalizer canonicalizer;

    EmployeeChangeWorkbenchProjector(
            ObjectMapper mapper,
            EmployeeChangeCanonicalizer canonicalizer) {
        this.mapper = mapper;
        this.canonicalizer = canonicalizer;
    }

    PreparedQuery prepare(
            EmployeeChangeDtos.WorkbenchQuery source,
            String rawScopeRevision,
            Instant now) {
        if (source == null || source.filter() == null || source.limit() < 1
                || source.limit() > 100 || source.correlationId() == null) {
            throw invalid("The IAQ-009 query is incomplete.");
        }
        List<String> sorts = source.sort() == null ? List.of() : List.copyOf(source.sort());
        if (sorts.isEmpty() || sorts.size() > 3 || new HashSet<>(sorts).size() != sorts.size()
                || !SORTS.containsAll(sorts)) {
            throw invalid("The IAQ-009 sort is not allowlisted and unique.");
        }
        String locale = canonicalLocale(source.locale());
        String timeZone = canonicalZone(source.timeZone());
        EmployeeChangeDtos.WorkbenchFilter filter = canonicalFilter(source.filter());
        String scopeRevision = closedRevision(rawScopeRevision);
        String queryDigest = canonicalizer.requestDigest(new QueryBinding(
                source.limit(), filter, sorts, locale, timeZone));

        Instant asOf = source.asOf() == null ? now : source.asOf();
        int offset = 0;
        if (source.cursor() != null) {
            CursorEnvelope cursor = decode(source.cursor());
            String checksum = checksum(cursor.offset(), cursor.scopeDecisionRevision(),
                    cursor.queryDigest(), cursor.asOf());
            if (!checksum.equals(cursor.checksum())
                    || cursor.offset() < 0
                    || !scopeRevision.equals(cursor.scopeDecisionRevision())
                    || !queryDigest.equals(cursor.queryDigest())
                    || (source.asOf() != null && !source.asOf().equals(cursor.asOf()))) {
                throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                        "The IAQ-009 cursor is stale or belongs to another query.");
            }
            offset = cursor.offset();
            asOf = cursor.asOf();
        }
        return new PreparedQuery(
                source.limit(), filter, sorts, locale, timeZone,
                source.correlationId(), asOf, offset, scopeRevision, queryDigest);
    }

    EmployeeChangeDtos.WorkbenchProjection project(
            PreparedQuery query,
            List<EmployeeChangeRepository.WorkbenchRow> source,
            Set<String> readableFieldGroups,
            UUID actorPersonPublicId,
            EmployeeChangeAuthority.ActionTruth actionTruth) {
        List<EmployeeChangeRepository.WorkbenchRow> matching = source.stream()
                .filter(row -> matches(row, query.filter()))
                .toList();
        boolean invalidRevision = matching.stream().anyMatch(row -> row.version() < 1);
        List<EmployeeChangeRepository.WorkbenchRow> filtered = matching.stream()
                .filter(row -> row.version() >= 1)
                .sorted(comparator(query.sorts()))
                .toList();
        int from = Math.min(query.offset(), filtered.size());
        int to = Math.min(from + query.limit(), filtered.size());
        List<EmployeeChangeDtos.WorkbenchItem> items = filtered.subList(from, to).stream()
                .map(row -> item(
                        row, readableFieldGroups, actorPersonPublicId,
                        query.asOf(), actionTruth))
                .toList();
        String next = to < filtered.size()
                ? encode(new CursorEnvelope(to, query.scopeRevision(), query.queryDigest(),
                        query.asOf(), checksum(to, query.scopeRevision(),
                        query.queryDigest(), query.asOf())))
                : null;
        EmployeeChangeDtos.FieldDecisionSummary summary = summary(items);
        List<EmployeeChangeDtos.PartialFailure> failures = invalidRevision
                ? List.of(new EmployeeChangeDtos.PartialFailure(
                        "employee-change.request", "INVALID_REQUEST_REVISION",
                        query.correlationId(), false))
                : List.of();
        String state = !failures.isEmpty()
                ? "PARTIAL" : items.isEmpty() ? "EMPTY" : "COMPLETE";
        String freshness = "PARTIAL".equals(state) ? "STALE" : "CURRENT";
        return validate(new EmployeeChangeDtos.WorkbenchProjection(
                "1", query.asOf(), "EMPLOYEE_CHANGE_REQUESTS",
                new EmployeeChangeDtos.QueryState(
                        state, query.scopeRevision(), freshness, next, summary, failures,
                        query.correlationId(), new EmployeeChangeDtos.CachePolicy(
                                0, "dwp.hris.access.assignment.changed.v1",
                                query.scopeRevision())),
                items));
    }

    EmployeeChangeDtos.WorkbenchProjection unavailable(
            PreparedQuery query,
            String errorCode,
            boolean retryable) {
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("IAQ-009 unavailable error code is invalid.");
        }
        return validate(new EmployeeChangeDtos.WorkbenchProjection(
                "1", query.asOf(), "EMPLOYEE_CHANGE_REQUESTS",
                new EmployeeChangeDtos.QueryState(
                        "UNAVAILABLE", query.scopeRevision(), "UNAVAILABLE", null,
                        new EmployeeChangeDtos.FieldDecisionSummary(0, 0, 0),
                        List.of(new EmployeeChangeDtos.PartialFailure(
                                "employee-change.repository", errorCode,
                                query.correlationId(), retryable)),
                        query.correlationId(), new EmployeeChangeDtos.CachePolicy(
                                0, "dwp.hris.access.assignment.changed.v1",
                                query.scopeRevision())),
                List.of()));
    }

    EmployeeChangeDtos.FieldDecisionSummary summary(
            List<EmployeeChangeDtos.WorkbenchItem> items) {
        int viewed = 0;
        int masked = 0;
        for (EmployeeChangeDtos.WorkbenchItem item : items) {
            for (EmployeeChangeDtos.FieldDecision<?> decision : item.fieldGroups().values()) {
                if ("VIEW".equals(decision.decision())) viewed++;
                if ("MASK".equals(decision.decision())) masked++;
            }
        }
        return new EmployeeChangeDtos.FieldDecisionSummary(viewed, masked, 0);
    }

    private EmployeeChangeDtos.WorkbenchItem item(
            EmployeeChangeRepository.WorkbenchRow row,
            Set<String> readable,
            UUID actorPersonPublicId,
            Instant asOf,
            EmployeeChangeAuthority.ActionTruth actionTruth) {
        Map<String, EmployeeChangeDtos.FieldDecision<?>> fields = new LinkedHashMap<>();
        fields.put("CHANGE_REQUEST_SUMMARY", decision(
                readable.contains("DIRECTORY"),
                new EmployeeChangeDtos.AggregateValue(
                        row.fieldGroups().size(), row.version(), asOf),
                "employee.change.summary.masked"));
        fields.put("APPROVAL_STATUS", decision(
                readable.contains("EMPLOYMENT"),
                new EmployeeChangeDtos.StatusValue(row.lifecycleState(), row.version()),
                "employee.change.approval-status.masked"));
        fields.put("EMPLOYMENT", decision(
                readable.contains("EMPLOYMENT"),
                new EmployeeChangeDtos.EffectiveRangeValue(
                        row.effectiveDate(), null, row.version()),
                "employee.change.employment.masked"));

        List<EmployeeChangeDtos.AvailableAction> actions = new ArrayList<>();
        if (actionTruth.create()) {
            actions.add(action(EmployeeChangeDtos.CREATE_ACTION, CREATE_HREF));
        }
        if (actionTruth.submit() && "DRAFT".equals(row.lifecycleState())
                && row.requesterPublicId().equals(actorPersonPublicId)) {
            actions.add(action(EmployeeChangeDtos.SUBMIT_ACTION, SUBMIT_HREF));
        }
        if (actionTruth.apply() && "APPROVED".equals(row.lifecycleState())
                && !row.requesterPublicId().equals(actorPersonPublicId)) {
            actions.add(action(EmployeeChangeDtos.APPLY_ACTION, APPLY_HREF));
        }
        String title = readable.contains("DIRECTORY")
                ? row.targetDisplayName() + " · " + row.changeType()
                : "Restricted employee change";
        return new EmployeeChangeDtos.WorkbenchItem(
                row.publicId().toString(), title, row.lifecycleState(), fields,
                new EmployeeChangeDtos.DeepLink(
                        EmployeeChangeDtos.DEEP_LINK_ROUTE, row.publicId().toString()),
                actions);
    }

    private EmployeeChangeDtos.WorkbenchProjection validate(
            EmployeeChangeDtos.WorkbenchProjection projection) {
        EmployeeChangeDtos.QueryState query = projection.queryState();
        boolean emptyItems = projection.items().isEmpty();
        boolean hasFailures = !query.partialFailures().isEmpty();
        boolean valid = switch (query.state()) {
            case "COMPLETE" -> !emptyItems && !hasFailures
                    && "CURRENT".equals(query.freshness());
            case "EMPTY" -> emptyItems && !hasFailures
                    && "CURRENT".equals(query.freshness())
                    && query.nextCursor() == null;
            case "PARTIAL" -> hasFailures && "STALE".equals(query.freshness());
            case "UNAVAILABLE" -> emptyItems && "UNAVAILABLE".equals(query.freshness())
                    && query.nextCursor() == null;
            default -> false;
        };
        if (!valid || projection.items().stream().anyMatch(this::hasInvalidRevision)) {
            throw new IllegalStateException("The IAQ-009 projection state is inconsistent.");
        }
        return projection;
    }

    private boolean hasInvalidRevision(EmployeeChangeDtos.WorkbenchItem item) {
        for (EmployeeChangeDtos.FieldDecision<?> decision : item.fieldGroups().values()) {
            if (!(decision instanceof EmployeeChangeDtos.ViewFieldDecision<?> viewed)) continue;
            Object value = viewed.value();
            long revision = switch (value) {
                case EmployeeChangeDtos.AggregateValue aggregate -> aggregate.revision();
                case EmployeeChangeDtos.StatusValue status -> status.revision();
                case EmployeeChangeDtos.EffectiveRangeValue range -> range.revision();
                default -> 0;
            };
            if (revision < 1) return true;
        }
        return false;
    }

    private EmployeeChangeDtos.FieldDecision<?> decision(
            boolean allowed,
            Object value,
            String displayKey) {
        return allowed
                ? EmployeeChangeDtos.FieldDecision.view(value)
                : EmployeeChangeDtos.FieldDecision.mask("FIELD_POLICY_DENIED", displayKey);
    }

    private EmployeeChangeDtos.AvailableAction action(String key, String href) {
        return new EmployeeChangeDtos.AvailableAction(
                key, "POST", href, "REQUIRED_IDEMPOTENCY_KEY",
                "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
    }

    private boolean matches(
            EmployeeChangeRepository.WorkbenchRow row,
            EmployeeChangeDtos.WorkbenchFilter filter) {
        int itemCount = row.fieldGroups().size();
        EmployeeChangeDtos.AggregateFilter summary = filter.changeRequestSummary();
        if (summary != null
                && ((summary.minimumItemCount() != null
                        && itemCount < summary.minimumItemCount())
                    || (summary.maximumItemCount() != null
                        && itemCount > summary.maximumItemCount()))) return false;
        EmployeeChangeDtos.StatusFilter status = filter.approvalStatus();
        if (status != null
                && ((status.codes() != null && !status.codes().isEmpty()
                        && !status.codes().contains(row.lifecycleState()))
                    || (status.minimumRevision() != null
                        && row.version() < status.minimumRevision()))) return false;
        EmployeeChangeDtos.EffectiveRangeFilter employment = filter.employment();
        if (employment != null
                && ((employment.activeOn() != null
                        && !row.effectiveDate().equals(employment.activeOn()))
                    || (employment.from() != null
                        && row.effectiveDate().isBefore(employment.from()))
                    || (employment.to() != null
                        && row.effectiveDate().isAfter(employment.to())))) return false;
        return filter.effectiveOn() == null || row.effectiveDate().equals(filter.effectiveOn());
    }

    private Comparator<EmployeeChangeRepository.WorkbenchRow> comparator(List<String> sorts) {
        Comparator<EmployeeChangeRepository.WorkbenchRow> result = null;
        for (String sort : sorts) {
            String[] parts = sort.split(":", 2);
            Comparator<EmployeeChangeRepository.WorkbenchRow> next = switch (parts[0]) {
                case "changeRequestSummary" -> Comparator.comparingInt(
                        row -> row.fieldGroups().size());
                case "approvalStatus" -> Comparator.comparing(
                        EmployeeChangeRepository.WorkbenchRow::lifecycleState);
                case "employment" -> Comparator.comparing(
                        EmployeeChangeRepository.WorkbenchRow::effectiveDate);
                case "itemPublicId" -> Comparator.comparing(
                        row -> row.publicId().toString());
                default -> throw invalid("Unsupported IAQ-009 sort.");
            };
            if ("DESC".equals(parts[1])) next = next.reversed();
            result = result == null ? next : result.thenComparing(next);
        }
        Comparator<EmployeeChangeRepository.WorkbenchRow> stable = Comparator.comparing(
                row -> row.publicId().toString());
        return result == null ? stable : result.thenComparing(stable);
    }

    private EmployeeChangeDtos.WorkbenchFilter canonicalFilter(
            EmployeeChangeDtos.WorkbenchFilter filter) {
        EmployeeChangeDtos.AggregateFilter aggregate = filter.changeRequestSummary();
        if (aggregate != null) {
            if ((aggregate.minimumItemCount() != null
                        && aggregate.minimumItemCount() < 0)
                    || (aggregate.maximumItemCount() != null
                        && aggregate.maximumItemCount() < 0)) {
                throw invalid("The aggregate filter counts cannot be negative.");
            }
            if (aggregate.minimumItemCount() != null
                    && aggregate.maximumItemCount() != null
                    && aggregate.minimumItemCount() > aggregate.maximumItemCount()) {
                throw invalid("The aggregate filter range is inverted.");
            }
        }
        EmployeeChangeDtos.StatusFilter status = filter.approvalStatus();
        if (status != null) {
            if (status.minimumRevision() != null && status.minimumRevision() < 1) {
                throw invalid("The status filter revision must be positive.");
            }
            if (status.codes() != null) {
                List<String> codes = status.codes().stream()
                        .map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT))
                        .toList();
                if (codes.size() > 50 || new HashSet<>(codes).size() != codes.size()
                        || codes.stream().anyMatch(value ->
                            !value.matches("[A-Z][A-Z0-9_]{1,63}"))) {
                    throw invalid("The status filter is invalid.");
                }
                status = new EmployeeChangeDtos.StatusFilter(
                        codes, status.minimumRevision());
            }
        }
        EmployeeChangeDtos.EffectiveRangeFilter range = filter.employment();
        if (range != null && range.from() != null && range.to() != null
                && range.from().isAfter(range.to())) {
            throw invalid("The employment filter range is inverted.");
        }
        return new EmployeeChangeDtos.WorkbenchFilter(
                aggregate, status, range, filter.effectiveOn());
    }

    private String canonicalLocale(String value) {
        if (value == null || value.length() < 2 || value.length() > 35
                || !value.equals(value.trim())) throw invalid("The locale is invalid.");
        Locale locale = Locale.forLanguageTag(value.replace('_', '-'));
        String result = locale.toLanguageTag();
        if (locale.getLanguage().isBlank() || "und".equals(result)) {
            throw invalid("The locale is invalid.");
        }
        return result;
    }

    private String canonicalZone(String value) {
        if (value == null || value.isBlank() || value.length() > 255
                || !value.equals(value.trim())) throw invalid("The time zone is invalid.");
        try {
            return ZoneId.of(value).getId();
        } catch (RuntimeException exception) {
            throw invalid("The time zone is invalid.");
        }
    }

    private String closedRevision(String value) {
        if (value != null && value.matches("[A-Za-z0-9._:-]{1,128}")) return value;
        if (value == null || value.isBlank()) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The IAQ-009 authorization revision is unavailable.");
        }
        return "rev:" + canonicalizer.digestText(value).substring(0, 32);
    }

    private String checksum(int offset, String scope, String queryDigest, Instant asOf) {
        return canonicalizer.requestDigest(new CursorBinding(offset, scope, queryDigest, asOf));
    }

    private String encode(CursorEnvelope value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mapper.writeValueAsBytes(value));
        } catch (Exception exception) {
            throw new IllegalStateException("IAQ-009 cursor encoding failed.", exception);
        }
    }

    private CursorEnvelope decode(String value) {
        if (value.length() < 16 || value.length() > 1024
                || !value.matches("[A-Za-z0-9_-]+")) {
            throw invalid("The IAQ-009 cursor is invalid.");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value.getBytes(StandardCharsets.US_ASCII));
            return mapper.readValue(bytes, CursorEnvelope.class);
        } catch (Exception exception) {
            throw invalid("The IAQ-009 cursor is invalid.");
        }
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    record PreparedQuery(
            int limit,
            EmployeeChangeDtos.WorkbenchFilter filter,
            List<String> sorts,
            String locale,
            String timeZone,
            UUID correlationId,
            Instant asOf,
            int offset,
            String scopeRevision,
            String queryDigest) {
        PreparedQuery {
            sorts = List.copyOf(sorts);
        }
    }

    private record QueryBinding(
            int limit,
            EmployeeChangeDtos.WorkbenchFilter filter,
            List<String> sorts,
            String locale,
            String timeZone) {
    }

    private record CursorBinding(
            int offset,
            String scopeDecisionRevision,
            String queryDigest,
            Instant asOf) {
    }

    private record CursorEnvelope(
            int offset,
            String scopeDecisionRevision,
            String queryDigest,
            Instant asOf,
            String checksum) {
    }
}

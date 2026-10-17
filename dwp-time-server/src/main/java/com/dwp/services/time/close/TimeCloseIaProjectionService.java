package com.dwp.services.time.close;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.close.TimeCloseIaQueryModels.AggregateFilter;
import com.dwp.services.time.close.TimeCloseIaQueryModels.AvailableAction;
import com.dwp.services.time.close.TimeCloseIaQueryModels.CachePolicy;
import com.dwp.services.time.close.TimeCloseIaQueryModels.CloseProjectionRow;
import com.dwp.services.time.close.TimeCloseIaQueryModels.DeepLink;
import com.dwp.services.time.close.TimeCloseIaQueryModels.EffectiveRangeFilter;
import com.dwp.services.time.close.TimeCloseIaQueryModels.FieldDecisionSummary;
import com.dwp.services.time.close.TimeCloseIaQueryModels.PartialFailure;
import com.dwp.services.time.close.TimeCloseIaQueryModels.QueryState;
import com.dwp.services.time.close.TimeCloseIaQueryModels.ReceiptFilter;
import com.dwp.services.time.close.TimeCloseIaQueryModels.StatusFilter;
import com.dwp.services.time.close.TimeCloseIaQueryModels.TimeCloseItem;
import com.dwp.services.time.close.TimeCloseIaQueryModels.TimeCloseProjection;
import com.dwp.services.time.close.TimeCloseIaQueryModels.TimeCloseQuery;
import com.dwp.services.time.close.TimeCloseModels.VerifiedCloseRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Exact IAQ-039/043/044 parsing, field decisions, stable sorting and paging. */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.time.close.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class TimeCloseIaProjectionService {

    private static final String INVALIDATION_EVENT =
            "dwp.hris.access.assignment.changed.v1";
    private static final String MASK_REASON = "FIELD_POLICY_DENIED";
    private static final String ACTION_POLICY =
            "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED";
    private static final Set<String> BASE_PARAMETERS = Set.of(
            "limit", "sort", "locale", "timeZone", "correlationId", "asOf", "cursor",
            "filter[effectiveOn]", "filter[timePeriod][activeOn]",
            "filter[timePeriod][from]", "filter[timePeriod][to]");
    private static final Set<String> COMMAND_FILTERS = Set.of(
            "filter[exceptionSummary][minimumItemCount]",
            "filter[exceptionSummary][maximumItemCount]",
            "filter[commandReceipt][receiptIds]",
            "filter[commandReceipt][states]");
    private static final Set<String> DAILY_FILTERS = Set.of(
            "filter[impactSummary][minimumItemCount]",
            "filter[impactSummary][maximumItemCount]",
            "filter[closeStatus][codes]",
            "filter[closeStatus][minimumRevision]");
    private static final Set<String> MONTHLY_FILTERS = Set.of(
            "filter[payrollHandoff][receiptIds]",
            "filter[payrollHandoff][states]",
            "filter[closeStatus][codes]",
            "filter[closeStatus][minimumRevision]");

    private final TimeCloseRepository repository;
    private final Clock clock;
    private final byte[] cursorKey;

    @Autowired
    public TimeCloseIaProjectionService(
            TimeCloseRepository repository,
            @Value("${dwp.hris.time.close.cursor-secret:}") String cursorSecret) {
        this(repository, Clock.systemUTC(), cursorSecret);
    }

    TimeCloseIaProjectionService(
            TimeCloseRepository repository, Clock clock, String cursorSecret) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (cursorSecret == null || cursorSecret.length() < 24) {
            throw new IllegalStateException("TIM close cursor secret must be at least 24 chars");
        }
        cursorKey = cursorSecret.getBytes(StandardCharsets.UTF_8).clone();
    }

    TimeCloseQuery parse(
            TimeCloseOwnerRoute route,
            VerifiedCloseRequest authority,
            HttpServletRequest request,
            UUID correlationHeader) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(request, "request");
        if (correlationHeader == null || !correlationHeader.equals(authority.correlationId())) {
            throw unavailable("The TIM close correlation contract is mismatched.");
        }
        Map<String, String[]> parameters = request.getParameterMap();
        if (parameters.size() > 32) throw invalid("Too many IAQ filter parameters.");
        Set<String> allowed = new LinkedHashSet<>(BASE_PARAMETERS);
        allowed.addAll(filters(route));
        if (!allowed.containsAll(parameters.keySet())) {
            throw invalid("The IAQ query contains an unknown parameter.");
        }
        int limit = integer(single(parameters, "limit", true), "limit", 1, 100);
        List<String> sort = many(parameters, "sort", true, 3);
        Set<String> sortAllowlist = sorts(route);
        if (new HashSet<>(sort).size() != sort.size() || !sortAllowlist.containsAll(sort)) {
            throw invalid("IAQ sort values must be unique and allowlisted.");
        }
        String locale = locale(single(parameters, "locale", true));
        String timeZone = zone(single(parameters, "timeZone", true));
        UUID correlationId = uuid(single(parameters, "correlationId", true));
        if (!correlationHeader.equals(correlationId)) {
            throw unavailable("The TIM close correlation contract is mismatched.");
        }
        Instant asOf = instant(single(parameters, "asOf", false));
        String cursor = single(parameters, "cursor", false);
        if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{16,1024}")) {
            throw invalid("The IAQ cursor is malformed.");
        }
        LocalDate effectiveOn = date(single(parameters, "filter[effectiveOn]", false));
        EffectiveRangeFilter range = new EffectiveRangeFilter(
                date(single(parameters, "filter[timePeriod][activeOn]", false)),
                date(single(parameters, "filter[timePeriod][from]", false)),
                date(single(parameters, "filter[timePeriod][to]", false)));
        if (range.from() != null && range.to() != null && range.to().isBefore(range.from())) {
            throw invalid("The IAQ effective range is inverted.");
        }
        AggregateFilter aggregate = switch (route) {
            case COMMAND_CENTER -> aggregate(parameters, "exceptionSummary");
            case DAILY_CLOSE -> aggregate(parameters, "impactSummary");
            default -> null;
        };
        StatusFilter status = route == TimeCloseOwnerRoute.DAILY_CLOSE
                        || route == TimeCloseOwnerRoute.MONTHLY_CLOSE
                ? status(parameters) : null;
        ReceiptFilter receipt = switch (route) {
            case COMMAND_CENTER -> receipt(parameters, "commandReceipt");
            case MONTHLY_CLOSE -> receipt(parameters, "payrollHandoff");
            default -> null;
        };
        return new TimeCloseQuery(
                limit, sort, locale, timeZone, correlationId, asOf, cursor,
                effectiveOn, range, aggregate, status, receipt);
    }

    TimeCloseProjection query(
            VerifiedCloseRequest authority,
            TimeCloseOwnerRoute route,
            TimeCloseQuery query) {
        requireQueryAuthority(authority, route);
        Prepared prepared = prepare(authority, route, query);
        try {
            List<CloseProjectionRow> source = repository.queryProjectionRows(
                    authority.tenantId(), authority.legalEntityPublicId(),
                    authority.timeGroupPublicId(), scope(route), prepared.asOf());
            if (source.stream().filter(Objects::nonNull)
                    .anyMatch(row -> changedAfterSnapshot(row, prepared.asOf()))) {
                throw new BaseException(
                        ErrorCode.OBJECT_VERSION_CONFLICT,
                        "The IAQ snapshot changed after the cursor high-water.");
            }
            return project(authority, route, query, prepared, source);
        } catch (BaseException contract) {
            throw contract;
        } catch (RuntimeException unavailable) {
            return unavailable(authority, route, prepared, "TIME_CLOSE_QUERY_UNAVAILABLE", true);
        }
    }

    private TimeCloseProjection project(
            VerifiedCloseRequest authority,
            TimeCloseOwnerRoute route,
            TimeCloseQuery query,
            Prepared prepared,
            List<CloseProjectionRow> source) {
        boolean partial = source.stream().anyMatch(row -> row == null || invalid(row));
        List<CloseProjectionRow> matching = source.stream()
                .filter(Objects::nonNull)
                .filter(row -> !invalid(row))
                .filter(row -> matches(row, query, route, prepared.asOf()))
                .sorted(comparator(route, query.sort()))
                .toList();
        int from = Math.min(prepared.offset(), matching.size());
        int to = Math.min(from + query.limit(), matching.size());
        List<TimeCloseItem> items = matching.subList(from, to).stream()
                .map(row -> item(authority, route, row, prepared.asOf()))
                .toList();
        String next = to < matching.size()
                ? cursor(to, prepared.scopeRevision(), prepared.queryDigest(), prepared.asOf())
                : null;
        String state = partial ? "PARTIAL" : items.isEmpty() ? "EMPTY" : "COMPLETE";
        List<PartialFailure> failures = partial
                ? List.of(new PartialFailure(
                        "time.close.projection", "INVALID_CLOSE_SUMMARY",
                        authority.correlationId(), false))
                : List.of();
        return new TimeCloseProjection(
                "1", prepared.asOf(), variant(route),
                queryState(authority, query.correlationId(), prepared.scopeRevision(),
                        state, partial ? "STALE" : "CURRENT", next,
                        decisionSummary(route, items), failures),
                items);
    }

    private TimeCloseProjection unavailable(
            VerifiedCloseRequest authority,
            TimeCloseOwnerRoute route,
            Prepared prepared,
            String code,
            boolean retryable) {
        return new TimeCloseProjection(
                "1", prepared.asOf(), variant(route),
                queryState(authority, authority.correlationId(), prepared.scopeRevision(),
                        "UNAVAILABLE", "UNAVAILABLE", null,
                        new FieldDecisionSummary(0, 0, 0),
                        List.of(new PartialFailure(
                                "time.close.repository", code,
                                authority.correlationId(), retryable))),
                List.of());
    }

    private Prepared prepare(
            VerifiedCloseRequest authority, TimeCloseOwnerRoute route, TimeCloseQuery query) {
        Instant asOf = query.asOf() == null ? clock.instant() : query.asOf();
        String scopeRevision = TimeCloseDigests.sha256(
                "TIM-CLOSE-IAQ-SCOPE.v1", authority.tenantId(),
                authority.populationPublicId(), authority.populationRevision(),
                authority.grantRevision(), authority.populationScopeDigest(),
                authority.fieldPolicyRevision(), authority.authorizationRevision(),
                authority.legalEntityPublicId(), authority.timeGroupPublicId(), route.bindingId());
        String queryDigest = TimeCloseDigests.sha256(
                "TIM-CLOSE-IAQ-QUERY.v1", route.bindingId(), query.limit(), query.sort(),
                query.locale(), query.timeZone(), query.effectiveOn(), query.timePeriod(),
                query.aggregate(), query.status(), query.receipt());
        requireFieldVisibility(authority, route, query);
        int offset = 0;
        if (query.cursor() != null) {
            Cursor decoded = decode(query.cursor());
            if (!decoded.scopeRevision().equals(scopeRevision)
                    || !decoded.queryDigest().equals(queryDigest)
                    || (query.asOf() != null && !decoded.asOf().equals(query.asOf()))) {
                throw new BaseException(
                        ErrorCode.OBJECT_VERSION_CONFLICT,
                        "The IAQ cursor is stale or belongs to another query.");
            }
            asOf = decoded.asOf();
            offset = decoded.offset();
        }
        return new Prepared(asOf, offset, scopeRevision, queryDigest);
    }

    private TimeCloseItem item(
            VerifiedCloseRequest authority,
            TimeCloseOwnerRoute route,
            CloseProjectionRow row,
            Instant asOf) {
        LinkedHashMap<String, Map<String, Object>> groups = new LinkedHashMap<>();
        add(groups, authority, "TIME_PERIOD", value(
                "effectiveFrom", row.periodStart(),
                "effectiveTo", row.periodEnd(),
                "revision", row.version()));
        if (route == TimeCloseOwnerRoute.COMMAND_CENTER) {
            add(groups, authority, "EXCEPTION_SUMMARY", value(
                    "itemCount", row.blockerCount(), "revision", row.version(),
                    "asOf", asOf));
            if (row.latestReceiptId() != null) {
                add(groups, authority, "COMMAND_RECEIPT", value(
                        "receiptId", row.latestReceiptId(),
                        "state", row.latestReceiptState(),
                        "occurredAt", row.latestReceiptAt(),
                        "revision", positive(row.latestReceiptRevision())));
            }
        } else {
            add(groups, authority, "CLOSE_STATUS", value(
                    "code", row.status(), "revision", row.version()));
            if (route == TimeCloseOwnerRoute.DAILY_CLOSE) {
                add(groups, authority, "IMPACT_SUMMARY", value(
                        "itemCount", row.impactCount(), "revision", row.version(),
                        "asOf", asOf));
            } else if (row.latestHandoffId() != null) {
                add(groups, authority, "PAYROLL_HANDOFF", value(
                        "receiptId", row.latestHandoffId(),
                        "state", row.latestHandoffState(),
                        "occurredAt", row.latestHandoffAt(),
                        "revision", positive(row.latestHandoffRevision())));
            }
        }
        if (groups.isEmpty()) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "At least one IAQ field group must remain visible.");
        }
        return new TimeCloseItem(
                row.periodId().toString(), title(route), row.status(), Map.copyOf(groups),
                new DeepLink(deepLink(route), row.periodId().toString()),
                actions(authority, row));
    }

    private static void add(
            Map<String, Map<String, Object>> target,
            VerifiedCloseRequest authority,
            String group,
            Map<String, Object> viewed) {
        if (authority.omittedFieldGroups().contains(group)) return;
        LinkedHashMap<String, Object> decision = new LinkedHashMap<>();
        if (authority.maskedFieldGroups().contains(group)) {
            decision.put("decision", "MASK");
            decision.put("value", null);
            decision.put("maskReason", MASK_REASON);
            decision.put("maskedDisplayKey",
                    "hris.time." + group.toLowerCase(Locale.ROOT).replace('_', '-') + ".masked");
        } else {
            decision.put("decision", "VIEW");
            decision.put("value", viewed);
        }
        target.put(group, decision);
    }

    private static List<AvailableAction> actions(
            VerifiedCloseRequest authority, CloseProjectionRow row) {
        List<AvailableAction> actions = new ArrayList<>();
        boolean closeOperator = authority.permissions().contains("ACTION.HRIS_TIME_CLOSE:EXECUTE")
                && authority.responsibilities().contains("HRIS_DUTY_TIME_CLOSE_OPERATOR");
        boolean reopenOperator = authority.permissions().contains("ACTION.HRIS_TIME_REOPEN:MANAGE")
                && authority.responsibilities().contains("HRIS_DUTY_TIME_REOPEN_OPERATOR");
        if (closeOperator && Set.of("OPEN", "REOPENED", "FAILED").contains(row.status())) {
            actions.add(action("hris.action.tim.close.validate",
                    "/api/time/v1/close-periods/{periodId}/validations"));
        }
        if (closeOperator && row.blockerCount() == 0
                && Set.of("OPEN", "REOPENED").contains(row.status())) {
            actions.add(action("hris.action.tim.close.close",
                    "/api/time/v1/close-periods/{periodId}/close"));
        }
        if (reopenOperator && "CLOSED".equals(row.status())) {
            actions.add(action("hris.action.tim.close.reopen",
                    "/api/time/v1/close-periods/{periodId}/reopen"));
        }
        if (closeOperator && "CLOSED".equals(row.status())) {
            actions.add(action("hris.action.tim.handoff.create",
                    "/api/time/v1/close-periods/{periodId}/payroll-handoffs"));
        }
        return List.copyOf(actions);
    }

    private static AvailableAction action(String key, String href) {
        return new AvailableAction(
                key, "POST", href, "REQUIRED_IDEMPOTENCY_KEY", ACTION_POLICY);
    }

    private static boolean matches(
            CloseProjectionRow row,
            TimeCloseQuery query,
            TimeCloseOwnerRoute route,
            Instant asOf) {
        if (row.latestReceiptAt() != null && row.latestReceiptAt().isAfter(asOf)) return false;
        if (query.effectiveOn() != null
                && (query.effectiveOn().isBefore(row.periodStart())
                    || query.effectiveOn().isAfter(row.periodEnd()))) return false;
        EffectiveRangeFilter range = query.timePeriod();
        if (range.activeOn() != null
                && (range.activeOn().isBefore(row.periodStart())
                    || range.activeOn().isAfter(row.periodEnd()))) return false;
        if (range.from() != null && row.periodEnd().isBefore(range.from())) return false;
        if (range.to() != null && row.periodStart().isAfter(range.to())) return false;
        int count = route == TimeCloseOwnerRoute.COMMAND_CENTER
                ? row.blockerCount() : row.impactCount();
        if (query.aggregate() != null
                && (query.aggregate().minimumItemCount() != null
                    && count < query.aggregate().minimumItemCount()
                    || query.aggregate().maximumItemCount() != null
                    && count > query.aggregate().maximumItemCount())) return false;
        if (query.status() != null
                && ((!query.status().codes().isEmpty()
                        && !query.status().codes().contains(row.status()))
                    || query.status().minimumRevision() != null
                        && row.version() < query.status().minimumRevision())) return false;
        if (query.receipt() != null) {
            UUID receiptId = route == TimeCloseOwnerRoute.MONTHLY_CLOSE
                    ? row.latestHandoffId() : row.latestReceiptId();
            String state = route == TimeCloseOwnerRoute.MONTHLY_CLOSE
                    ? row.latestHandoffState() : row.latestReceiptState();
            if (!query.receipt().receiptIds().isEmpty()
                    && (receiptId == null
                        || !query.receipt().receiptIds().contains(receiptId.toString()))) {
                return false;
            }
            if (!query.receipt().states().isEmpty()
                    && (state == null || !query.receipt().states().contains(state))) return false;
        }
        return true;
    }

    private static Comparator<CloseProjectionRow> comparator(
            TimeCloseOwnerRoute route, List<String> sorts) {
        Comparator<CloseProjectionRow> comparator = null;
        for (String sort : sorts) {
            String[] parts = sort.split(":", -1);
            Comparator<CloseProjectionRow> next = switch (parts[0]) {
                case "timePeriod" -> Comparator.comparing(CloseProjectionRow::periodStart)
                        .thenComparing(CloseProjectionRow::periodEnd);
                case "exceptionSummary" -> Comparator.comparingInt(
                        CloseProjectionRow::blockerCount);
                case "impactSummary" -> Comparator.comparingInt(
                        CloseProjectionRow::impactCount);
                case "closeStatus" -> Comparator.comparing(CloseProjectionRow::status);
                case "commandReceipt" -> Comparator.comparing(
                        CloseProjectionRow::latestReceiptState,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                case "payrollHandoff" -> Comparator.comparing(
                        CloseProjectionRow::latestHandoffState,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                case "itemPublicId" -> Comparator.comparing(
                        row -> row.periodId().toString());
                default -> throw invalid("IAQ sort is unsupported.");
            };
            if ("DESC".equals(parts[1])) next = next.reversed();
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        Comparator<CloseProjectionRow> tie = Comparator.comparing(
                row -> row.periodId().toString());
        return comparator == null ? tie : comparator.thenComparing(tie);
    }

    private static QueryState queryState(
            VerifiedCloseRequest authority,
            UUID correlationId,
            String scopeRevision,
            String state,
            String freshness,
            String next,
            FieldDecisionSummary summary,
            List<PartialFailure> failures) {
        return new QueryState(
                state, scopeRevision, freshness, next, summary, failures, correlationId,
                new CachePolicy(authority.decisionRevision(), INVALIDATION_EVENT, 0));
    }

    private static FieldDecisionSummary decisionSummary(
            TimeCloseOwnerRoute route, List<TimeCloseItem> items) {
        int views = 0;
        int masks = 0;
        int omissions = 0;
        for (TimeCloseItem item : items) {
            for (Map<String, Object> decision : item.fieldGroups().values()) {
                if ("VIEW".equals(decision.get("decision"))) views++;
                if ("MASK".equals(decision.get("decision"))) masks++;
            }
            omissions += route.fieldGroups().size() - item.fieldGroups().size();
        }
        return new FieldDecisionSummary(views, masks, omissions);
    }

    private static boolean invalid(CloseProjectionRow row) {
        return row.periodId() == null || row.closeScope() == null
                || row.periodStart() == null || row.periodEnd() == null
                || row.createdAt() == null || row.updatedAt() == null
                || row.periodEnd().isBefore(row.periodStart())
                || row.status() == null || row.version() < 1
                || row.closeRevision() < 0 || row.blockerCount() < 0
                || row.impactCount() < 0;
    }

    private static boolean changedAfterSnapshot(CloseProjectionRow row, Instant asOf) {
        return row.updatedAt().isAfter(asOf)
                || row.latestReceiptCompletedAt() != null
                    && row.latestReceiptCompletedAt().isAfter(asOf)
                || row.latestHandoffUpdatedAt() != null
                    && row.latestHandoffUpdatedAt().isAfter(asOf);
    }

    private static void requireQueryAuthority(
            VerifiedCloseRequest authority, TimeCloseOwnerRoute route) {
        if (authority == null || authority.action() != TimeCloseModels.CloseAction.QUERY
                || !authority.routeContractKey().equals(route.routeContractKey())
                || !authority.purposeCode().equals(route.purpose())) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Current IAQ authority is required.");
        }
    }

    private static void requireFieldVisibility(
            VerifiedCloseRequest authority, TimeCloseOwnerRoute route, TimeCloseQuery query) {
        Set<String> referenced = new HashSet<>();
        if (query.effectiveOn() != null || hasRange(query.timePeriod())) referenced.add("TIME_PERIOD");
        if (query.aggregate() != null) referenced.add(route == TimeCloseOwnerRoute.COMMAND_CENTER
                ? "EXCEPTION_SUMMARY" : "IMPACT_SUMMARY");
        if (query.status() != null) referenced.add("CLOSE_STATUS");
        if (query.receipt() != null) referenced.add(route == TimeCloseOwnerRoute.COMMAND_CENTER
                ? "COMMAND_RECEIPT" : "PAYROLL_HANDOFF");
        for (String sort : query.sort()) {
            String name = sort.substring(0, sort.indexOf(':'));
            switch (name) {
                case "timePeriod" -> referenced.add("TIME_PERIOD");
                case "exceptionSummary" -> referenced.add("EXCEPTION_SUMMARY");
                case "impactSummary" -> referenced.add("IMPACT_SUMMARY");
                case "closeStatus" -> referenced.add("CLOSE_STATUS");
                case "commandReceipt" -> referenced.add("COMMAND_RECEIPT");
                case "payrollHandoff" -> referenced.add("PAYROLL_HANDOFF");
                default -> { }
            }
        }
        if (referenced.stream().anyMatch(group -> authority.maskedFieldGroups().contains(group)
                || authority.omittedFieldGroups().contains(group))) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Filtering or sorting a concealed IAQ field group is forbidden.");
        }
    }

    private static boolean hasRange(EffectiveRangeFilter range) {
        return range.activeOn() != null || range.from() != null || range.to() != null;
    }

    private String cursor(int offset, String scope, String query, Instant asOf) {
        String payload = offset + "\n" + scope + "\n" + query + "\n" + asOf;
        String signed = payload + "\n" + hmac(payload);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signed.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decode(String token) {
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] values = decoded.split("\\n", -1);
            if (values.length != 5) throw invalid("The IAQ cursor is malformed.");
            String payload = String.join("\n", values[0], values[1], values[2], values[3]);
            if (!java.security.MessageDigest.isEqual(
                    hmac(payload).getBytes(StandardCharsets.UTF_8),
                    values[4].getBytes(StandardCharsets.UTF_8))) {
                throw invalid("The IAQ cursor signature is invalid.");
            }
            int offset = Integer.parseInt(values[0]);
            if (offset < 0) throw invalid("The IAQ cursor offset is invalid.");
            return new Cursor(offset, values[1], values[2], Instant.parse(values[3]));
        } catch (BaseException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw invalid("The IAQ cursor is malformed.");
        }
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorKey, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", impossible);
        }
    }

    private static Set<String> filters(TimeCloseOwnerRoute route) {
        return switch (route) {
            case COMMAND_CENTER -> COMMAND_FILTERS;
            case DAILY_CLOSE -> DAILY_FILTERS;
            case MONTHLY_CLOSE -> MONTHLY_FILTERS;
            default -> throw new IllegalArgumentException("not an IAQ route");
        };
    }

    private static Set<String> sorts(TimeCloseOwnerRoute route) {
        return switch (route) {
            case COMMAND_CENTER -> Set.of(
                    "timePeriod:ASC", "exceptionSummary:ASC", "commandReceipt:ASC",
                    "timePeriod:DESC", "exceptionSummary:DESC", "commandReceipt:DESC",
                    "itemPublicId:ASC", "itemPublicId:DESC");
            case DAILY_CLOSE -> Set.of(
                    "timePeriod:ASC", "closeStatus:ASC", "impactSummary:ASC",
                    "timePeriod:DESC", "closeStatus:DESC", "impactSummary:DESC",
                    "itemPublicId:ASC", "itemPublicId:DESC");
            case MONTHLY_CLOSE -> Set.of(
                    "timePeriod:ASC", "closeStatus:ASC", "payrollHandoff:ASC",
                    "timePeriod:DESC", "closeStatus:DESC", "payrollHandoff:DESC",
                    "itemPublicId:ASC", "itemPublicId:DESC");
            default -> throw new IllegalArgumentException("not an IAQ route");
        };
    }

    private static String scope(TimeCloseOwnerRoute route) {
        return switch (route) {
            case DAILY_CLOSE -> "DAY";
            case MONTHLY_CLOSE -> "MONTH";
            case COMMAND_CENTER -> null;
            default -> throw new IllegalArgumentException("not an IAQ route");
        };
    }

    private static String variant(TimeCloseOwnerRoute route) {
        return switch (route) {
            case COMMAND_CENTER -> "COMMAND_CENTER";
            case DAILY_CLOSE -> "DAILY_CLOSE";
            case MONTHLY_CLOSE -> "MONTHLY_CLOSE";
            default -> throw new IllegalArgumentException("not an IAQ route");
        };
    }

    private static String deepLink(TimeCloseOwnerRoute route) {
        return switch (route) {
            case COMMAND_CENTER -> "/hr/operations/time/command-center";
            case DAILY_CLOSE -> "/hr/operations/time/daily-close";
            case MONTHLY_CLOSE -> "/hr/operations/time/monthly-close";
            default -> throw new IllegalArgumentException("not an IAQ route");
        };
    }

    private static String title(TimeCloseOwnerRoute route) {
        return switch (route) {
            case COMMAND_CENTER -> "Time command center";
            case DAILY_CLOSE -> "Daily time close";
            case MONTHLY_CLOSE -> "Monthly time close";
            default -> throw new IllegalArgumentException("not an IAQ route");
        };
    }

    private static AggregateFilter aggregate(Map<String, String[]> values, String group) {
        Long minimum = nullableLong(single(
                values, "filter[" + group + "][minimumItemCount]", false), 0);
        Long maximum = nullableLong(single(
                values, "filter[" + group + "][maximumItemCount]", false), 0);
        if (minimum != null && maximum != null && maximum < minimum) {
            throw invalid("The IAQ aggregate range is inverted.");
        }
        return minimum == null && maximum == null ? null : new AggregateFilter(minimum, maximum);
    }

    private static StatusFilter status(Map<String, String[]> values) {
        List<String> codes = many(values, "filter[closeStatus][codes]", false, 50);
        Long revision = nullableLong(single(
                values, "filter[closeStatus][minimumRevision]", false), 1);
        for (String code : codes) {
            if (!code.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw invalid("The IAQ status code is invalid.");
            }
        }
        return codes.isEmpty() && revision == null ? null : new StatusFilter(codes, revision);
    }

    private static ReceiptFilter receipt(Map<String, String[]> values, String group) {
        List<String> ids = many(values, "filter[" + group + "][receiptIds]", false, 50);
        List<String> states = many(values, "filter[" + group + "][states]", false, 50);
        for (String value : ids) {
            if (!value.matches("[A-Za-z0-9._:-]{1,128}")) {
                throw invalid("The IAQ receipt id is invalid.");
            }
        }
        for (String state : states) {
            if (!state.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw invalid("The IAQ receipt state is invalid.");
            }
        }
        return ids.isEmpty() && states.isEmpty() ? null : new ReceiptFilter(ids, states);
    }

    private static String single(
            Map<String, String[]> values, String name, boolean required) {
        String[] candidates = values.get(name);
        if (candidates == null) {
            if (required) throw invalid("Missing IAQ parameter " + name + ".");
            return null;
        }
        if (candidates.length != 1 || candidates[0] == null
                || candidates[0].isBlank() || candidates[0].length() > 1_024
                || !candidates[0].equals(candidates[0].strip())) {
            throw invalid("IAQ parameter " + name + " is malformed.");
        }
        return candidates[0];
    }

    private static List<String> many(
            Map<String, String[]> values, String name, boolean required, int maximum) {
        String[] candidates = values.get(name);
        if (candidates == null) {
            if (required) throw invalid("Missing IAQ parameter " + name + ".");
            return List.of();
        }
        if (candidates.length < 1 || candidates.length > maximum) {
            throw invalid("IAQ parameter " + name + " has invalid cardinality.");
        }
        List<String> result = new ArrayList<>(candidates.length);
        Set<String> seen = new HashSet<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank() || candidate.length() > 256
                    || !candidate.equals(candidate.strip()) || !seen.add(candidate)) {
                throw invalid("IAQ parameter " + name + " is malformed.");
            }
            result.add(candidate);
        }
        return List.copyOf(result);
    }

    private static int integer(String value, String name, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException invalid) {
            throw invalid("IAQ parameter " + name + " is outside its bounds.");
        }
    }

    private static Long nullableLong(String value, long minimum) {
        if (value == null) return null;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < minimum) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException invalid) {
            throw invalid("IAQ numeric filter is outside its bounds.");
        }
    }

    private static LocalDate date(String value) {
        if (value == null) return null;
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (!parsed.toString().equals(value)) throw new DateTimeParseException("", value, 0);
            return parsed;
        } catch (DateTimeParseException invalid) {
            throw invalid("IAQ date is invalid.");
        }
    }

    private static Instant instant(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            throw invalid("IAQ asOf is invalid.");
        }
    }

    private static UUID uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value) || parsed.equals(new UUID(0, 0))) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (RuntimeException invalid) {
            throw invalid("IAQ correlation id is invalid.");
        }
    }

    private static String locale(String value) {
        if (value.length() < 2 || value.length() > 35) throw invalid("IAQ locale is invalid.");
        Locale parsed = Locale.forLanguageTag(value);
        if (parsed.getLanguage().isBlank() || "und".equals(parsed.toLanguageTag())) {
            throw invalid("IAQ locale is invalid.");
        }
        return parsed.toLanguageTag();
    }

    private static String zone(String value) {
        try {
            if (value.length() > 255) throw new DateTimeException("too long");
            return ZoneId.of(value).getId();
        } catch (DateTimeException invalid) {
            throw invalid("IAQ timeZone is invalid.");
        }
    }

    private static long positive(Long value) {
        return value == null || value < 1 ? 1 : value;
    }

    private static LinkedHashMap<String, Object> value(Object... pairs) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            value.put((String) pairs[index], pairs[index + 1]);
        }
        return value;
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    private record Prepared(
            Instant asOf, int offset, String scopeRevision, String queryDigest) {}

    private record Cursor(
            int offset, String scopeRevision, String queryDigest, Instant asOf) {}
}

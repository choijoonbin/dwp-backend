package com.dwp.services.time.interpretation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationAction;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationSummary;
import com.dwp.services.time.interpretation.InterpretationModels.PopulationKind;
import com.dwp.services.time.interpretation.InterpretationModels.VerifiedInterpretationRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Pure IAQ-041 normalization, scope-bound paging, and exact projection. */
public final class InterpretationIaProjectionService {

    public static final String VIEW_PURPOSE = "TIME_OPERATIONS";

    private static final String PROJECTION_VERSION = "1";
    private static final String VARIANT_KEY = "INTERPRETATION";
    private static final String DEEP_LINK_ROUTE = "/hr/operations/time/interpretation";
    private static final String RUN_ACTION = "hris.action.tim.interpretation.run";
    private static final String RUN_HREF = "/api/time/v1/interpretation-runs";
    private static final String INVALIDATION_EVENT = "dwp.hris.access.assignment.changed.v1";
    private static final String REQUIRED_VIEW_PERMISSION = "DATA.HR_TIME:VIEW";
    private static final String OPERATIONS_RUN_PERMISSION = "DATA.HR_TIME:UPDATE";
    private static final String SELF_RUN_PERMISSION = "DATA.HR_TIME:CREATE";
    private static final String FIELD_POLICY_REASON = "FIELD_POLICY_DENIED";
    private static final Set<String> FIELD_GROUPS = Set.of(
            "INTERPRETATION_RESULT", "RULE_VERSION", "TRACE_SUMMARY");
    private static final Set<String> SORTS = Set.of(
            "interpretationResult:ASC", "ruleVersion:ASC", "traceSummary:ASC",
            "interpretationResult:DESC", "ruleVersion:DESC", "traceSummary:DESC",
            "itemPublicId:ASC", "itemPublicId:DESC");

    private final Clock clock;
    private final byte[] cursorKey;

    public InterpretationIaProjectionService(Clock clock, String cursorSecret) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (cursorSecret == null || cursorSecret.length() < 24) {
            throw new IllegalArgumentException("IAQ-041 cursor secret is too short");
        }
        this.cursorKey = cursorSecret.getBytes(StandardCharsets.UTF_8).clone();
    }

    public InterpretationIaQueryModels.InterpretationProjection project(
            VerifiedInterpretationRequest authority,
            InterpretationIaQueryModels.InterpretationQueryRequest request,
            List<InterpretationSummary> summaries) {
        return projectInternal(authority, request, summaries, Map.of(), false);
    }

    public InterpretationIaQueryModels.InterpretationProjection project(
            VerifiedInterpretationRequest authority,
            InterpretationIaQueryModels.InterpretationQueryRequest request,
            List<InterpretationSummary> summaries,
            Map<UUID, Integer> traceItemCounts) {
        return projectInternal(authority, request, summaries, traceItemCounts, false);
    }

    InterpretationIaQueryModels.InterpretationProjection projectWindow(
            VerifiedInterpretationRequest authority,
            InterpretationIaQueryModels.InterpretationQueryRequest request,
            List<InterpretationSummary> summaries) {
        return projectInternal(authority, request, summaries, Map.of(), true);
    }

    private InterpretationIaQueryModels.InterpretationProjection projectInternal(
            VerifiedInterpretationRequest authority,
            InterpretationIaQueryModels.InterpretationQueryRequest request,
            List<InterpretationSummary> summaries,
            Map<UUID, Integer> traceItemCounts,
            boolean repositoryWindow) {
        requireAuthority(authority);
        PreparedQuery query = prepare(authority, request);
        List<InterpretationSummary> source = summaries == null
                ? List.of()
                : new ArrayList<>(summaries);
        Map<UUID, Integer> counts = traceItemCounts == null ? Map.of() : Map.copyOf(traceItemCounts);

        List<InterpretationSummary> scoped = source.stream()
                .filter(Objects::nonNull)
                .filter(summary -> summary.workerPublicId() != null
                        && authority.authorizedWorkerPublicIds().contains(summary.workerPublicId()))
                .toList();
        boolean invalidSource = source.stream().anyMatch(summary -> summary == null
                || summary.workerPublicId() == null
                || (authority.authorizedWorkerPublicIds().contains(summary.workerPublicId())
                    && invalid(summary, counts)))
                || scoped.stream()
                        .filter(summary -> summary.runPublicId() != null
                                && summary.workerPublicId() != null)
                        .map(InterpretationIaProjectionService::itemPublicId)
                        .distinct()
                        .count() != scoped.stream()
                                .filter(summary -> summary.runPublicId() != null
                                        && summary.workerPublicId() != null)
                                .count();
        List<Candidate> matching = scoped.stream()
                .filter(summary -> !invalid(summary, counts))
                .map(summary -> new Candidate(summary, traceItemCount(summary, counts)))
                .filter(candidate -> !candidate.summary().createdAt().isAfter(query.asOf()))
                .filter(candidate -> matches(candidate, query.filter()))
                .sorted(comparator(query.sort()))
                .toList();

        int from = repositoryWindow ? 0 : Math.min(query.offset(), matching.size());
        int to = Math.min(from + query.limit(), matching.size());
        List<InterpretationIaQueryModels.InterpretationItem> items = matching.subList(from, to)
                .stream()
                .map(candidate -> item(authority, candidate))
                .toList();
        int nextOffset = repositoryWindow ? query.offset() + to : to;
        String nextCursor = to < matching.size()
                ? issueCursor(nextOffset, query.scopeRevision(), query.queryDigest(), query.asOf())
                : null;
        List<InterpretationIaQueryModels.PartialFailure> failures = invalidSource
                ? List.of(failure(
                        "interpretation.projection", "INVALID_INTERPRETATION_SUMMARY",
                        query.correlationId(), false))
                : List.of();
        String state = !failures.isEmpty()
                ? "PARTIAL"
                : items.isEmpty() ? "EMPTY" : "COMPLETE";
        String freshness = "PARTIAL".equals(state) ? "STALE" : "CURRENT";

        return validate(new InterpretationIaQueryModels.InterpretationProjection(
                PROJECTION_VERSION,
                query.asOf(),
                VARIANT_KEY,
                queryState(
                        authority, query, state, freshness, nextCursor,
                        decisionSummary(items), failures),
                items));
    }

    public InterpretationIaQueryModels.InterpretationProjection unavailable(
            VerifiedInterpretationRequest authority,
            InterpretationIaQueryModels.InterpretationQueryRequest request,
            String errorCode,
            boolean retryable) {
        requireAuthority(authority);
        PreparedQuery query = prepare(authority, request);
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw invalid("IAQ-041 unavailable error code is invalid.");
        }
        return validate(new InterpretationIaQueryModels.InterpretationProjection(
                PROJECTION_VERSION,
                query.asOf(),
                VARIANT_KEY,
                queryState(
                        authority,
                        query,
                        "UNAVAILABLE",
                        "UNAVAILABLE",
                        null,
                        new InterpretationIaQueryModels.FieldDecisionSummary(0, 0, 0),
                        List.of(failure(
                                "interpretation.repository", errorCode,
                                query.correlationId(), retryable))),
                List.of()));
    }

    PreparedQuery prepare(
            VerifiedInterpretationRequest authority,
            InterpretationIaQueryModels.InterpretationQueryRequest source) {
        if (source == null || source.filter() == null
                || source.limit() < 1 || source.limit() > 100
                || source.correlationId() == null) {
            throw invalid("A complete IAQ-041 request is required.");
        }
        if (!authority.correlationId().equals(source.correlationId())) {
            throw authorityUnavailable();
        }
        List<String> sort = source.sort() == null ? List.of() : List.copyOf(source.sort());
        if (sort.isEmpty() || sort.size() > 3
                || new HashSet<>(sort).size() != sort.size()
                || !SORTS.containsAll(sort)) {
            throw invalid("IAQ-041 sort values must be unique and allowlisted.");
        }
        InterpretationIaQueryModels.InterpretationFilter filter = normalize(source.filter());
        String locale = canonicalLocale(source.locale());
        String timeZone = canonicalZone(source.timeZone());
        String scopeRevision = scopeRevision(authority);
        String queryDigest = InterpretationDigests.sha256(
                "IAQ-041",
                source.limit(),
                filter.interpretationResult(),
                filter.ruleVersion(),
                filter.traceSummary(),
                filter.effectiveOn(),
                sort,
                locale,
                timeZone);
        Instant asOf = source.asOf() == null ? clock.instant() : source.asOf();
        int offset = 0;
        if (source.cursor() != null) {
            if (!source.cursor().matches("[A-Za-z0-9_-]{16,1024}")) {
                throw invalid("IAQ-041 cursor format is invalid.");
            }
            Cursor cursor = decodeCursor(source.cursor());
            if (cursor.offset() < 0
                    || !scopeRevision.equals(cursor.scopeRevision())
                    || !queryDigest.equals(cursor.queryDigest())
                    || (source.asOf() != null && !source.asOf().equals(cursor.asOf()))) {
                throw new BaseException(
                        ErrorCode.OBJECT_VERSION_CONFLICT,
                        "The IAQ-041 cursor is stale or belongs to another query.");
            }
            offset = cursor.offset();
            asOf = cursor.asOf();
        }
        return new PreparedQuery(
                source.limit(), filter, sort, locale, timeZone, source.correlationId(),
                asOf, offset, scopeRevision, queryDigest);
    }

    private InterpretationIaQueryModels.QueryState queryState(
            VerifiedInterpretationRequest authority,
            PreparedQuery query,
            String state,
            String freshness,
            String nextCursor,
            InterpretationIaQueryModels.FieldDecisionSummary summary,
            List<InterpretationIaQueryModels.PartialFailure> failures) {
        return new InterpretationIaQueryModels.QueryState(
                state,
                query.scopeRevision(),
                freshness,
                nextCursor,
                summary,
                failures,
                query.correlationId(),
                new InterpretationIaQueryModels.CachePolicy(
                        authority.decisionRevision(), INVALIDATION_EVENT, 0));
    }

    private InterpretationIaQueryModels.InterpretationItem item(
            VerifiedInterpretationRequest authority,
            Candidate candidate) {
        InterpretationSummary summary = candidate.summary();
        InterpretationIaQueryModels.InterpretationFieldGroups groups =
                new InterpretationIaQueryModels.InterpretationFieldGroups(
                        field(
                                authority,
                                "INTERPRETATION_RESULT",
                                new InterpretationIaQueryModels.StatusValue(
                                        summary.status().name(), summary.attemptNo()),
                                "hris.time.interpretation-result.masked"),
                        field(
                                authority,
                                "RULE_VERSION",
                                new InterpretationIaQueryModels.PolicyValue(
                                        policyKey(summary),
                                        summary.ruleVersionRevision(),
                                        summary.periodStart(),
                                        summary.periodEnd(),
                                        "PUBLISHED"),
                                "hris.time.rule-version.masked"),
                        field(
                                authority,
                                "TRACE_SUMMARY",
                                new InterpretationIaQueryModels.AggregateValue(
                                        candidate.traceItemCount(),
                                        summary.attemptNo(),
                                        summary.createdAt()),
                                "hris.time.trace-summary.masked"));
        List<InterpretationIaQueryModels.AvailableAction> actions =
                authority.actions().contains(InterpretationAction.RUN)
                        && canRun(authority)
                        && authority.authorizedWorkerPublicIds().contains(summary.workerPublicId())
                        ? List.of(runAction())
                        : List.of();
        return new InterpretationIaQueryModels.InterpretationItem(
                itemPublicId(summary),
                "Time interpretation",
                summary.status().name(),
                groups,
                new InterpretationIaQueryModels.DeepLink(
                        DEEP_LINK_ROUTE, summary.runPublicId().toString()),
                actions);
    }

    @SuppressWarnings("unchecked")
    private static <T> InterpretationIaQueryModels.FieldDecision<T> field(
            VerifiedInterpretationRequest authority,
            String group,
            T viewed,
            String displayKey) {
        if (authority.omittedFieldGroups().contains(group)) return null;
        if (authority.maskedFieldGroups().contains(group)) {
            return (InterpretationIaQueryModels.FieldDecision<T>)
                    InterpretationIaQueryModels.FieldDecision.mask(
                            FIELD_POLICY_REASON, displayKey);
        }
        return InterpretationIaQueryModels.FieldDecision.view(viewed);
    }

    private static InterpretationIaQueryModels.AvailableAction runAction() {
        return new InterpretationIaQueryModels.AvailableAction(
                RUN_ACTION,
                "POST",
                RUN_HREF,
                "REQUIRED_IDEMPOTENCY_KEY",
                "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
    }

    private static boolean canRun(VerifiedInterpretationRequest authority) {
        return authority.permissions().contains(OPERATIONS_RUN_PERMISSION)
                || (authority.permissions().contains(SELF_RUN_PERMISSION)
                    && authority.populationKind() == PopulationKind.SELF);
    }

    private static InterpretationIaQueryModels.FieldDecisionSummary decisionSummary(
            List<InterpretationIaQueryModels.InterpretationItem> items) {
        int viewed = 0;
        int masked = 0;
        int omitted = 0;
        for (InterpretationIaQueryModels.InterpretationItem item : items) {
            InterpretationIaQueryModels.FieldDecision<?>[] fields = {
                item.fieldGroups().INTERPRETATION_RESULT(),
                item.fieldGroups().RULE_VERSION(),
                item.fieldGroups().TRACE_SUMMARY()
            };
            for (InterpretationIaQueryModels.FieldDecision<?> field : fields) {
                if (field == null) omitted++;
                else if (field instanceof InterpretationIaQueryModels.MaskedField<?>) masked++;
                else viewed++;
            }
        }
        return new InterpretationIaQueryModels.FieldDecisionSummary(viewed, masked, omitted);
    }

    private static InterpretationIaQueryModels.InterpretationFilter normalize(
            InterpretationIaQueryModels.InterpretationFilter source) {
        InterpretationIaQueryModels.StatusFilter status = source.interpretationResult();
        if (status != null) {
            List<String> codes = canonicalCodes(status.codes());
            requirePositive(status.minimumRevision(), "interpretation result revision");
            status = new InterpretationIaQueryModels.StatusFilter(codes, status.minimumRevision());
        }
        InterpretationIaQueryModels.PolicyFilter policy = source.ruleVersion();
        if (policy != null) {
            List<String> keys = canonicalPolicyKeys(policy.policyKeys());
            requirePositive(policy.minimumRevision(), "rule version revision");
            policy = new InterpretationIaQueryModels.PolicyFilter(keys, policy.minimumRevision());
        }
        InterpretationIaQueryModels.AggregateFilter aggregate = source.traceSummary();
        if (aggregate != null) {
            if ((aggregate.minimumItemCount() != null && aggregate.minimumItemCount() < 0)
                    || (aggregate.maximumItemCount() != null
                            && aggregate.maximumItemCount() < 0)
                    || (aggregate.minimumItemCount() != null
                            && aggregate.maximumItemCount() != null
                            && aggregate.minimumItemCount() > aggregate.maximumItemCount())) {
                throw invalid("IAQ-041 trace summary bounds are invalid.");
            }
        }
        return new InterpretationIaQueryModels.InterpretationFilter(
                status, policy, aggregate, source.effectiveOn());
    }

    private static List<String> canonicalCodes(List<String> source) {
        if (source == null) return null;
        List<String> result = source.stream()
                .map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT))
                .sorted()
                .toList();
        if (result.size() > 50 || new HashSet<>(result).size() != result.size()
                || result.stream().anyMatch(value ->
                        !value.matches("[A-Z][A-Z0-9_]{1,63}"))) {
            throw invalid("IAQ-041 interpretation result filter is invalid.");
        }
        return result;
    }

    private static List<String> canonicalPolicyKeys(List<String> source) {
        if (source == null) return null;
        List<String> result = source.stream()
                .map(value -> value == null ? "" : value.trim())
                .sorted()
                .toList();
        if (result.size() > 50 || new HashSet<>(result).size() != result.size()
                || result.stream().anyMatch(value ->
                        !value.matches("[A-Za-z0-9._:-]{1,128}"))) {
            throw invalid("IAQ-041 rule version filter is invalid.");
        }
        return result;
    }

    private static void requirePositive(Long value, String label) {
        if (value != null && value < 1) {
            throw invalid("IAQ-041 " + label + " must be positive.");
        }
    }

    private static boolean matches(
            Candidate candidate,
            InterpretationIaQueryModels.InterpretationFilter filter) {
        InterpretationSummary summary = candidate.summary();
        InterpretationIaQueryModels.StatusFilter status = filter.interpretationResult();
        if (status != null
                && ((status.codes() != null && !status.codes().isEmpty()
                        && !status.codes().contains(summary.status().name()))
                    || (status.minimumRevision() != null
                        && summary.attemptNo() < status.minimumRevision()))) {
            return false;
        }
        InterpretationIaQueryModels.PolicyFilter rule = filter.ruleVersion();
        if (rule != null
                && ((rule.policyKeys() != null && !rule.policyKeys().isEmpty()
                        && !rule.policyKeys().contains(policyKey(summary)))
                    || (rule.minimumRevision() != null
                        && summary.ruleVersionRevision() < rule.minimumRevision()))) {
            return false;
        }
        InterpretationIaQueryModels.AggregateFilter aggregate = filter.traceSummary();
        if (aggregate != null
                && ((aggregate.minimumItemCount() != null
                        && candidate.traceItemCount() < aggregate.minimumItemCount())
                    || (aggregate.maximumItemCount() != null
                        && candidate.traceItemCount() > aggregate.maximumItemCount()))) {
            return false;
        }
        return filter.effectiveOn() == null
                || (!filter.effectiveOn().isBefore(summary.periodStart())
                    && !filter.effectiveOn().isAfter(summary.periodEnd()));
    }

    private static Comparator<Candidate> comparator(List<String> sorts) {
        Comparator<Candidate> result = null;
        for (String sort : sorts) {
            String[] parts = sort.split(":", 2);
            Comparator<Candidate> next = switch (parts[0]) {
                case "interpretationResult" -> Comparator.comparing(
                        candidate -> candidate.summary().status().name());
                case "ruleVersion" -> Comparator
                        .comparing((Candidate candidate) -> policyKey(candidate.summary()))
                        .thenComparingLong(
                                candidate -> candidate.summary().ruleVersionRevision());
                case "traceSummary" -> Comparator.comparingInt(Candidate::traceItemCount);
                case "itemPublicId" -> Comparator.comparing(
                        candidate -> itemPublicId(candidate.summary()));
                default -> throw invalid("Unsupported IAQ-041 sort.");
            };
            if ("DESC".equals(parts[1])) next = next.reversed();
            result = result == null ? next : result.thenComparing(next);
        }
        Comparator<Candidate> stable = Comparator.comparing(
                candidate -> itemPublicId(candidate.summary()));
        return result == null ? stable : result.thenComparing(stable);
    }

    private static boolean invalid(
            InterpretationSummary summary,
            Map<UUID, Integer> traceItemCounts) {
        Integer traceCount = summary.runPublicId() == null
                ? null
                : traceItemCount(summary, traceItemCounts);
        return summary.runPublicId() == null
                || summary.workerPublicId() == null
                || summary.periodStart() == null
                || summary.periodEnd() == null
                || summary.periodEnd().isBefore(summary.periodStart())
                || summary.status() == null
                || summary.ruleVersionPublicId() == null
                || summary.ruleVersionRevision() < 1
                || summary.workedMinutes() < 0
                || summary.regularMinutes() < 0
                || summary.overtimeMinutes() < 0
                || summary.regularMinutes() + summary.overtimeMinutes()
                        != summary.workedMinutes()
                || summary.attemptNo() < 1
                || summary.createdAt() == null
                || traceCount == null
                || traceCount < 0;
    }

    private InterpretationIaQueryModels.InterpretationProjection validate(
            InterpretationIaQueryModels.InterpretationProjection projection) {
        InterpretationIaQueryModels.QueryState query = projection.queryState();
        boolean empty = projection.items().isEmpty();
        boolean failures = !query.partialFailures().isEmpty();
        boolean valid = switch (query.state()) {
            case "COMPLETE" -> !empty && !failures && "CURRENT".equals(query.freshness());
            case "EMPTY" -> empty && !failures && "CURRENT".equals(query.freshness())
                    && query.nextCursor() == null;
            case "PARTIAL" -> failures && "STALE".equals(query.freshness());
            case "UNAVAILABLE" -> empty && "UNAVAILABLE".equals(query.freshness())
                    && query.nextCursor() == null;
            default -> false;
        };
        if (!valid || !PROJECTION_VERSION.equals(projection.projectionVersion())
                || !VARIANT_KEY.equals(projection.variantKey())) {
            throw new IllegalStateException("The IAQ-041 projection state is inconsistent.");
        }
        return projection;
    }

    private void requireAuthority(VerifiedInterpretationRequest authority) {
        Instant now = clock.instant();
        if (authority == null
                || authority.tenantId() <= 0
                || authority.actorId() <= 0
                || authority.subjectPrincipalPublicId() == null
                || authority.populationPublicId() == null
                || authority.revalidateAt() == null
                || !authority.revalidateAt().isAfter(now)
                || !authority.permissions().contains(REQUIRED_VIEW_PERMISSION)
                || !authority.actions().contains(InterpretationAction.VIEW)
                || !VIEW_PURPOSE.equals(authority.purposeCode())
                || !FIELD_GROUPS.containsAll(authority.maskedFieldGroups())
                || !FIELD_GROUPS.containsAll(authority.omittedFieldGroups())
                || !java.util.Collections.disjoint(
                        authority.maskedFieldGroups(), authority.omittedFieldGroups())
                || authority.omittedFieldGroups().containsAll(FIELD_GROUPS)) {
            throw authorityUnavailable();
        }
    }

    private static String canonicalLocale(String value) {
        if (value == null || value.length() < 2 || value.length() > 35
                || !value.equals(value.trim())) {
            throw invalid("IAQ-041 locale is invalid.");
        }
        Locale locale = Locale.forLanguageTag(value.replace('_', '-'));
        String canonical = locale.toLanguageTag();
        if (locale.getLanguage().isBlank() || "und".equals(canonical)) {
            throw invalid("IAQ-041 locale is invalid.");
        }
        return canonical;
    }

    private static String canonicalZone(String value) {
        if (value == null || value.isBlank() || value.length() > 255
                || !value.equals(value.trim())) {
            throw invalid("IAQ-041 time zone is invalid.");
        }
        try {
            return ZoneId.of(value).getId();
        } catch (DateTimeException exception) {
            throw invalid("IAQ-041 time zone is invalid.");
        }
    }

    private static String scopeRevision(VerifiedInterpretationRequest authority) {
        List<String> workers = authority.authorizedWorkerPublicIds().stream()
                .map(UUID::toString)
                .sorted()
                .toList();
        List<String> masked = authority.maskedFieldGroups().stream().sorted().toList();
        List<String> omitted = authority.omittedFieldGroups().stream().sorted().toList();
        return "scope:tim:" + InterpretationDigests.sha256(
                authority.tenantId(),
                authority.actorId(),
                authority.subjectPrincipalPublicId(),
                authority.populationPublicId(),
                authority.populationRevision(),
                authority.grantRevision(),
                authority.populationScopeDigest(),
                authority.fieldPolicyRevision(),
                authority.authorizationRevision(),
                authority.decisionRevision(),
                workers,
                masked,
                omitted);
    }

    private static String policyKey(InterpretationSummary summary) {
        return "tim.rule." + summary.ruleVersionPublicId();
    }

    private static String itemPublicId(InterpretationSummary summary) {
        return InterpretationDigests.sha256(
                "IAQ-041-InterpretationItem.v1",
                summary.runPublicId(),
                summary.workerPublicId());
    }

    private static int traceItemCount(
            InterpretationSummary summary,
            Map<UUID, Integer> traceItemCounts) {
        return traceItemCounts.getOrDefault(summary.runPublicId(), summary.traceItemCount());
    }

    private String issueCursor(int offset, String scope, String queryDigest, Instant asOf) {
        String payload = offset + "\n" + scope + "\n" + queryDigest + "\n" + asOf;
        String signed = payload + "\n" + signature(payload);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signed.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String value) {
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\n", -1);
            if (parts.length != 5) throw new IllegalArgumentException("cursor parts");
            String payload = String.join("\n", parts[0], parts[1], parts[2], parts[3]);
            if (!constantTimeEquals(signature(payload), parts[4])) {
                throw new IllegalArgumentException("cursor signature");
            }
            return new Cursor(
                    Integer.parseInt(parts[0]),
                    parts[1],
                    parts[2],
                    Instant.parse(parts[3]));
        } catch (RuntimeException exception) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The IAQ-041 cursor is invalid.",
                    exception);
        }
    }

    private String signature(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorKey, "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null || left.length() != right.length()) return false;
        int difference = 0;
        for (int index = 0; index < left.length(); index++) {
            difference |= left.charAt(index) ^ right.charAt(index);
        }
        return difference == 0;
    }

    private static InterpretationIaQueryModels.PartialFailure failure(
            String component,
            String code,
            UUID correlationId,
            boolean retryable) {
        return new InterpretationIaQueryModels.PartialFailure(
                component, code, correlationId, retryable);
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException authorityUnavailable() {
        return new BaseException(
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "Current TIM interpretation authority is absent, stale, or mismatched.");
    }

    record PreparedQuery(
            int limit,
            InterpretationIaQueryModels.InterpretationFilter filter,
            List<String> sort,
            String locale,
            String timeZone,
            UUID correlationId,
            Instant asOf,
            int offset,
            String scopeRevision,
            String queryDigest) {

        PreparedQuery {
            sort = List.copyOf(sort);
        }
    }

    private record Candidate(InterpretationSummary summary, int traceItemCount) {}

    private record Cursor(
            int offset,
            String scopeRevision,
            String queryDigest,
            Instant asOf) {}
}

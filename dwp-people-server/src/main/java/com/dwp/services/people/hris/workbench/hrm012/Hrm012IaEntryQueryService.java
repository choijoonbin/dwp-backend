package com.dwp.services.people.hris.workbench.hrm012;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012CanonicalJson;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Deterministic, redacted, run-grain IAQ-012 owner projection. */
@Service
@ConditionalOnProperty(prefix = "dwp.hris.data-quality-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public class Hrm012IaEntryQueryService {
    private static final Set<String> SORTS = Set.of(
            "ingestionStatus:ASC", "qualityFinding:ASC", "commandReceipt:ASC",
            "ingestionStatus:DESC", "qualityFinding:DESC", "commandReceipt:DESC",
            "itemPublicId:ASC", "itemPublicId:DESC");
    private static final Set<String> RUN_STATES = Set.of(
            "RECEIVED", "RUNNING", "FAILED_RETRYABLE",
            "SUCCEEDED", "REJECTED", "DEAD");
    private static final Set<String> RECEIPT_STATES = Set.of(
            "RECEIVED", "VALIDATING", "RUNNING", "SUCCEEDED", "REJECTED",
            "FAILED_RETRYABLE", "DEAD", "CANCELLED");
    private static final String CODE = "[A-Z][A-Z0-9_]{1,63}";

    private final Hrm012QueryAuthority authority;
    private final Hrm012QuerySource source;
    private final Hrm012CursorCodec cursors;
    private final Hrm012CanonicalJson canonical;
    private final Clock clock;

    @Autowired
    public Hrm012IaEntryQueryService(
            Hrm012QueryAuthority authority,
            Hrm012QuerySource source,
            Hrm012CursorCodec cursors,
            ObjectMapper mapper) {
        this(authority, source, cursors, mapper, Clock.systemUTC());
    }

    Hrm012IaEntryQueryService(
            Hrm012QueryAuthority authority,
            Hrm012QuerySource source,
            Hrm012CursorCodec cursors,
            ObjectMapper mapper,
            Clock clock) {
        this.authority = Objects.requireNonNull(authority);
        this.source = Objects.requireNonNull(source);
        this.cursors = Objects.requireNonNull(cursors);
        this.canonical = new Hrm012CanonicalJson(Objects.requireNonNull(mapper));
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public QueryResult query(Hrm012QueryModels.QueryRequest raw) {
        Prepared request = prepare(raw);
        Hrm012QueryAuthority.Decision decision = authority.authorize();
        PeopleRequestContext.Actor actor = decision.actor();
        if (actor == null || actor.tenantId() == null || actor.tenantId() < 1) {
            throw Hrm012Problem.denied("A trusted IAQ-012 tenant is required.");
        }
        requireQueryable(request.filter(), request.sort(), decision.fields());

        String filterDigest = canonical.digest(request.filter());
        Hrm012CursorCodec.Binding expected = binding(
                actor.tenantId(), request, decision, filterDigest, null);
        UUID after = null;
        if (request.cursor() != null) {
            if (!request.explicitAsOf()) {
                expected = new Hrm012CursorCodec.Binding(
                        expected.tenantId(), expected.scopeRevision(),
                        expected.authorizationRevision(), expected.populationRevision(),
                        expected.fieldPolicyRevision(), null, expected.sort(),
                        expected.limit(), null, expected.filterDigest(),
                        expected.locale(), expected.timeZone());
            }
            Hrm012CursorCodec.Binding verified = cursors.verify(
                    request.cursor(), expected);
            request = request.withAsOf(verified.asOf());
            try {
                after = UUID.fromString(verified.lastRunPublicId());
            } catch (RuntimeException exception) {
                throw Hrm012Problem.denied("The IAQ-012 cursor position is invalid.");
            }
        }

        List<Hrm012QueryModels.PartialFailure> failures = new ArrayList<>();
        Hrm012QuerySource.Page page;
        try {
            page = source.read(actor.tenantId(), new Hrm012QuerySource.QuerySpec(
                    request.asOf(), request.effectiveOn(), request.filter(),
                    request.sort(), request.limit(), after), decision.population().scope());
        } catch (Hrm012Problem problem) {
            throw problem;
        } catch (RuntimeException exception) {
            failures.add(new Hrm012QueryModels.PartialFailure(
                    "projection-source", "HRM012_SOURCE_UNAVAILABLE",
                    request.correlationId(), true));
            page = new Hrm012QuerySource.Page(
                    List.of(), true, false, false, null, List.of());
        }
        for (Hrm012QuerySource.ComponentFailure failure : page.partialFailures()) {
            failures.add(new Hrm012QueryModels.PartialFailure(
                    failure.componentKey(), failure.errorCode(),
                    request.correlationId(), failure.retryable()));
        }
        if (page.snapshotDrift()) {
            throw Hrm012Problem.conflict("HRM012_QUERY_SNAPSHOT_STALE",
                    "The IAQ-012 snapshot changed after its asOf boundary.");
        }
        if (after != null && !page.cursorPositionFound()) {
            throw Hrm012Problem.conflict("HRM012_CURSOR_POSITION_STALE",
                    "The IAQ-012 cursor position is no longer authoritative.");
        }
        List<Projected> projected = page.rows().stream()
                .map(row -> project(row, decision.fields())).toList();
        String next = page.hasMore() ? cursors.issue(binding(
                actor.tenantId(), request, decision, filterDigest,
                page.lastRunPublicId().toString())) : null;
        List<Hrm012QueryModels.Item> items = projected.stream()
                .map(Projected::item).toList();
        String state = failures.isEmpty()
                ? (items.isEmpty() ? "EMPTY" : "COMPLETE")
                : (items.isEmpty() ? "UNAVAILABLE" : "PARTIAL");
        String freshness = "UNAVAILABLE".equals(state) ? "UNAVAILABLE" : "CURRENT";
        Hrm012QueryModels.Projection projection = new Hrm012QueryModels.Projection(
                Hrm012QueryModels.PROJECTION_VERSION, Hrm012QueryModels.VARIANT,
                request.asOf(), new Hrm012QueryModels.QueryState(
                state, decision.scopeRevision(), freshness, next,
                summary(projected), failures, request.correlationId(),
                new Hrm012QueryModels.CachePolicy(
                        decision.authorizationRevision(),
                        Hrm012QueryModels.INVALIDATION_EVENT, 0)), items);
        String etag = '"' + canonical.digest(projection).substring(0, 32) + '"';
        return new QueryResult(projection, decision.authorizationRevision(), etag);
    }

    private Projected project(
            Hrm012QueryModels.SourceRow row,
            Map<String, Hrm012QueryAuthority.FieldDecision> decisions) {
        Map<String, Object> fields = new LinkedHashMap<>();
        int view = 0;
        int mask = 0;
        int omit = 0;

        Counts status = put(fields, "INGESTION_STATUS",
                new Hrm012QueryModels.StatusValue(
                        row.ingestionState(), row.runRevision()), decisions);
        view += status.view(); mask += status.mask(); omit += status.omit();

        Hrm012QueryModels.AuditValue finding = row.findingDecisionCode() == null
                ? new Hrm012QueryModels.AuditValue(
                "NONE", noFindingDigest(row), row.occurredAt(), row.runRevision())
                : new Hrm012QueryModels.AuditValue(
                row.findingDecisionCode(), row.findingEvidenceDigest(),
                row.findingOccurredAt(), row.findingRevision());
        Counts quality = put(fields, "QUALITY_FINDING", finding, decisions);
        view += quality.view(); mask += quality.mask(); omit += quality.omit();

        Counts receipt = put(fields, "COMMAND_RECEIPT",
                new Hrm012QueryModels.ReceiptValue(
                        row.receiptPublicId(), row.receiptState(),
                        row.receiptOccurredAt(), row.receiptRevision()), decisions);
        view += receipt.view(); mask += receipt.mask(); omit += receipt.omit();

        String publicId = row.runPublicId().toString();
        Hrm012QueryModels.Item item = new Hrm012QueryModels.Item(
                publicId, "Workforce ingestion",
                row.ingestionState(), fields,
                new Hrm012QueryModels.DeepLink(
                        Hrm012QueryModels.DEEP_LINK, publicId), List.of());
        return new Projected(item, view, mask, omit);
    }

    private String noFindingDigest(Hrm012QueryModels.SourceRow row) {
        return canonical.digest(Map.of(
                "domain", "HRM012_NO_FINDING_V1",
                "runPublicId", row.runPublicId().toString(),
                "runRevision", row.runRevision(),
                "occurredAt", row.occurredAt().toString()));
    }

    private Counts put(
            Map<String, Object> target,
            String key,
            Object value,
            Map<String, Hrm012QueryAuthority.FieldDecision> decisions) {
        Hrm012QueryAuthority.FieldDecision decision = decisions.get(key);
        if (decision == Hrm012QueryAuthority.FieldDecision.VIEW) {
            target.put(key, new Hrm012QueryModels.ViewedField(value));
            return new Counts(1, 0, 0);
        }
        if (decision == Hrm012QueryAuthority.FieldDecision.MASK) {
            target.put(key, new Hrm012QueryModels.MaskedField(
                    "FIELD_POLICY", "hris.masked." + key.toLowerCase(Locale.ROOT)));
            return new Counts(0, 1, 0);
        }
        return new Counts(0, 0, 1);
    }

    private Hrm012QueryModels.FieldDecisionSummary summary(List<Projected> projected) {
        return new Hrm012QueryModels.FieldDecisionSummary(
                projected.stream().mapToInt(Projected::view).sum(),
                projected.stream().mapToInt(Projected::mask).sum(),
                projected.stream().mapToInt(Projected::omit).sum());
    }

    private Prepared prepare(Hrm012QueryModels.QueryRequest request) {
        if (request == null || request.limit() < 1 || request.limit() > 100
                || request.filter() == null || request.sort().isEmpty()
                || request.sort().size() > 3
                || request.sort().stream().distinct().count() != request.sort().size()
                || !SORTS.containsAll(request.sort()) || request.correlationId() == null
                || request.cursor() != null
                && !request.cursor().matches("[A-Za-z0-9_-]{16,1024}")) {
            throw Hrm012Problem.invalid("The IAQ-012 query is invalid.");
        }
        String locale = canonicalLocale(request.locale());
        ZoneId zone = canonicalZone(request.timeZone());
        validateFilter(request.filter());
        boolean explicit = request.asOf() != null;
        Instant now = clock.instant();
        Instant asOf = explicit ? request.asOf() : now;
        if (asOf.isAfter(now.plusSeconds(60))) {
            throw Hrm012Problem.invalid("asOf cannot be in the future.");
        }
        LocalDate effective = request.filter().effectiveOn() == null
                ? asOf.atZone(zone).toLocalDate() : request.filter().effectiveOn();
        return new Prepared(request.limit(), request.filter(), request.sort(), locale,
                request.timeZone(), request.correlationId(), asOf, effective,
                explicit, request.cursor());
    }

    private void validateFilter(Hrm012QueryModels.Filter filter) {
        if (filter.ingestionStatus() != null) {
            requireClosedValues(filter.ingestionStatus().codes(), RUN_STATES,
                    "ingestionStatus");
            if (filter.ingestionStatus().minimumRevision() != null
                    && filter.ingestionStatus().minimumRevision() < 1) {
                throw Hrm012Problem.invalid("ingestionStatus revision is invalid.");
            }
        }
        if (filter.qualityFinding() != null) {
            requireCodes(filter.qualityFinding().decisionCodes(), "qualityFinding");
            String digest = filter.qualityFinding().evidenceDigest();
            if (digest != null && !digest.matches("^[a-f0-9]{64}$")) {
                throw Hrm012Problem.invalid("qualityFinding digest is invalid.");
            }
        }
        if (filter.commandReceipt() != null) {
            if (filter.commandReceipt().receiptIds().size() > 50
                    || filter.commandReceipt().receiptIds().stream().distinct().count()
                    != filter.commandReceipt().receiptIds().size()) {
                throw Hrm012Problem.invalid("commandReceipt ids are invalid.");
            }
            requireClosedValues(filter.commandReceipt().states(), RECEIPT_STATES,
                    "commandReceipt");
        }
    }

    private void requireCodes(List<String> values, String name) {
        if (values.size() > 50 || values.stream().distinct().count() != values.size()
                || values.stream().anyMatch(value -> value == null
                || !value.matches(CODE))) {
            throw Hrm012Problem.invalid(name + " filter is invalid.");
        }
    }

    private void requireClosedValues(
            List<String> values, Set<String> closed, String name) {
        if (values.size() > 50 || values.stream().distinct().count() != values.size()
                || !closed.containsAll(values)) {
            throw Hrm012Problem.invalid(name + " filter is invalid.");
        }
    }

    private void requireQueryable(
            Hrm012QueryModels.Filter filter,
            List<String> sort,
            Map<String, Hrm012QueryAuthority.FieldDecision> decisions) {
        if ((filter.ingestionStatus() != null
                && decisions.get("INGESTION_STATUS")
                != Hrm012QueryAuthority.FieldDecision.VIEW)
                || (filter.qualityFinding() != null
                && decisions.get("QUALITY_FINDING")
                != Hrm012QueryAuthority.FieldDecision.VIEW)
                || (filter.commandReceipt() != null
                && decisions.get("COMMAND_RECEIPT")
                != Hrm012QueryAuthority.FieldDecision.VIEW)
                || sort.stream().anyMatch(value ->
                value.startsWith("ingestionStatus:")
                        && decisions.get("INGESTION_STATUS")
                        != Hrm012QueryAuthority.FieldDecision.VIEW
                        || value.startsWith("qualityFinding:")
                        && decisions.get("QUALITY_FINDING")
                        != Hrm012QueryAuthority.FieldDecision.VIEW
                        || value.startsWith("commandReceipt:")
                        && decisions.get("COMMAND_RECEIPT")
                        != Hrm012QueryAuthority.FieldDecision.VIEW)) {
            throw Hrm012Problem.denied(
                    "A masked or omitted IAQ-012 field cannot be filtered or sorted.");
        }
    }

    private String canonicalLocale(String source) {
        if (source == null || !source.equals(source.strip())
                || source.length() < 2 || source.length() > 35) {
            throw Hrm012Problem.invalid("locale is invalid.");
        }
        Locale locale = Locale.forLanguageTag(source);
        String canonical = locale.toLanguageTag();
        if (locale.getLanguage().isBlank() || "und".equals(canonical)
                || !canonical.equals(source)) {
            throw Hrm012Problem.invalid("locale is invalid.");
        }
        return canonical;
    }

    private ZoneId canonicalZone(String source) {
        if (source == null || !source.equals(source.strip()) || source.isBlank()
                || source.length() > 255 || ZoneId.SHORT_IDS.containsKey(source)) {
            throw Hrm012Problem.invalid("timeZone is invalid.");
        }
        try {
            return ZoneId.of(source);
        } catch (DateTimeException exception) {
            throw Hrm012Problem.invalid("timeZone is invalid.");
        }
    }

    private Hrm012CursorCodec.Binding binding(
            long tenantId,
            Prepared request,
            Hrm012QueryAuthority.Decision decision,
            String filterDigest,
            String last) {
        return new Hrm012CursorCodec.Binding(
                tenantId, decision.scopeRevision(), decision.authorizationRevision(),
                decision.population().targetPopulationRevision(),
                decision.fieldPolicyRevision(), request.asOf(), request.sort(),
                request.limit(), last, filterDigest, request.locale(), request.timeZone());
    }

    public record QueryResult(
            Hrm012QueryModels.Projection projection,
            String authorizationRevision,
            String etag) {
    }

    private record Projected(Hrm012QueryModels.Item item, int view, int mask, int omit) {
    }

    private record Counts(int view, int mask, int omit) {
    }

    private record Prepared(
            int limit,
            Hrm012QueryModels.Filter filter,
            List<String> sort,
            String locale,
            String timeZone,
            UUID correlationId,
            Instant asOf,
            LocalDate effectiveOn,
            boolean explicitAsOf,
            String cursor) {
        Prepared withAsOf(Instant value) {
            ZoneId zone = ZoneId.of(timeZone);
            return new Prepared(limit, filter, sort, locale, timeZone, correlationId,
                    value, filter.effectiveOn() == null
                    ? value.atZone(zone).toLocalDate() : filter.effectiveOn(),
                    true, cursor);
        }
    }
}

package com.dwp.services.people.hr.performance.populationfreeze;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(
        name = "dwp.hris.performance.population-freeze.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class PerformanceReviewOperationsService {

    static final String QUERY_OPERATION = "performance.review-operations.query";
    static final String QUERY_PURPOSE = "PERFORMANCE_OPERATIONS";
    static final String APPLICATION_ENTITLEMENT = "APP.HRIS";
    static final String RESOURCE = "DATA.HR_TALENT";
    static final String AUTHORITY_ACTION = "VIEW";

    private static final String PROJECTION_VERSION = "1";
    private static final String VARIANT_KEY = "REVIEW_OPERATIONS";
    private static final String DEEP_LINK =
            "/hr/operations/performance/review-operations";
    private static final String INVALIDATION_EVENT =
            "dwp.hris.access.assignment.changed.v1";
    private static final String PREVIEW_ACTION = "hris.action.per.preview-population";
    private static final String FREEZE_ACTION = "hris.action.per.freeze-population";
    private static final String PREVIEW_HREF =
            "/api/people/v1/hris/performance/cycles/{cycleId}/population-previews";
    private static final String FREEZE_HREF =
            "/api/people/v1/hris/performance/cycles/{cycleId}/population-freezes";
    private static final Set<String> SORTS = Set.of(
            "reviewAssignment:ASC", "reviewAssignment:DESC",
            "workQueue:ASC", "workQueue:DESC",
            "dueStatus:ASC", "dueStatus:DESC",
            "itemPublicId:ASC", "itemPublicId:DESC");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Pattern PUBLIC_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9_-]{16,1024}");

    private final PerformanceReviewOperationsRepository repository;
    private final PerformanceReviewOperationsCursorCodec cursors;
    private final AuditOutboxRecorder audit;
    private final Clock clock;
    private final Supplier<PopulationFreezeAuthorityPort> authoritySupplier;

    @org.springframework.beans.factory.annotation.Autowired
    public PerformanceReviewOperationsService(
            PerformanceReviewOperationsRepository repository,
            PerformanceReviewOperationsCursorCodec cursors,
            AuditOutboxRecorder audit,
            ObjectProvider<PopulationFreezeAuthorityPort> authorityProvider) {
        this(repository, cursors, audit, Clock.systemUTC(), authorityProvider::getIfAvailable);
    }

    PerformanceReviewOperationsService(
            PerformanceReviewOperationsRepository repository,
            PerformanceReviewOperationsCursorCodec cursors,
            AuditOutboxRecorder audit,
            Clock clock,
            Supplier<PopulationFreezeAuthorityPort> authoritySupplier) {
        this.repository = Objects.requireNonNull(repository);
        this.cursors = Objects.requireNonNull(cursors);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
        this.authoritySupplier = Objects.requireNonNull(authoritySupplier);
    }

    @Transactional
    public PerformanceReviewOperationsDtos.Projection query(
            PerformanceReviewOperationsDtos.QueryRequest request) {
        NormalizedQuery query = normalize(request);
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePermission(actor);
        UUID subjectId = requireSubject(actor);
        PopulationFreezeAuthorityPort.AuthorityEvidence authority = requireAuthority(
                actor, subjectId, query.correlationId());
        String scopeRevision = "scope:" + authority.populationScopeDigest();
        String fingerprint = PerformanceReviewOperationsCursorCodec.fingerprint(
                query.fingerprintSource() + "|" + scopeRevision
                        + "|auth:" + authority.authorizationRevision());
        long offset = query.cursor() == null ? 0 : cursors.verify(
                query.cursor(), actor.tenantId(), fingerprint, scopeRevision);

        Instant effectiveStart = query.effectiveOn() == null ? null
                : query.effectiveOn().atStartOfDay(query.zoneId()).toInstant();
        Instant effectiveEnd = query.effectiveOn() == null ? null
                : query.effectiveOn().plusDays(1).atStartOfDay(query.zoneId()).toInstant();
        PerformanceReviewOperationsRepository.Page page = repository.query(
                new PerformanceReviewOperationsRepository.QueryCriteria(
                        actor.tenantId(), query.limit(), offset, query.asOf(), query.sort(),
                        query.reviewAssignmentCodes(), query.reviewAssignmentPublicIds(),
                        query.minimumOpenCount(), query.maximumOpenCount(), query.slaStates(),
                        query.dueStatusCodes(), query.minimumRevision(),
                        effectiveStart, effectiveEnd));

        List<PerformanceReviewOperationsDtos.Item> items = page.rows().stream()
                .map(row -> item(row, actor))
                .toList();
        String nextCursor = page.hasMore()
                ? cursors.issue(actor.tenantId(), offset + items.size(),
                fingerprint, scopeRevision)
                : null;
        PerformanceReviewOperationsDtos.QueryState state =
                new PerformanceReviewOperationsDtos.QueryState(
                        items.isEmpty() ? "EMPTY" : "COMPLETE",
                        scopeRevision,
                        "CURRENT",
                        nextCursor,
                        new PerformanceReviewOperationsDtos.FieldDecisionSummary(
                                items.size() * 3, 0, 0),
                        List.of(),
                        query.correlationId(),
                        new PerformanceReviewOperationsDtos.CachePolicy(
                                "auth:" + authority.authorizationRevision(),
                                INVALIDATION_EVENT,
                                0));
        audit(actor, query, authority, items.size(), page.hasMore());
        return new PerformanceReviewOperationsDtos.Projection(
                PROJECTION_VERSION, query.asOf(), VARIANT_KEY, state, items);
    }

    private PerformanceReviewOperationsDtos.Item item(
            PerformanceReviewOperationsRepository.ReviewOperationRow row,
            PeopleRequestContext.Actor actor) {
        long revision = row.aggregateVersion() + 1;
        PerformanceReviewOperationsDtos.FieldGroups groups =
                new PerformanceReviewOperationsDtos.FieldGroups(
                        new PerformanceReviewOperationsDtos.ViewedReferenceField(
                                "VIEW",
                                new PerformanceReviewOperationsDtos.ReferenceValue(
                                        row.cycleId().toString(), "PERFORMANCE_REVIEW",
                                        row.displayName(), revision)),
                        new PerformanceReviewOperationsDtos.ViewedQueueField(
                                "VIEW",
                                new PerformanceReviewOperationsDtos.QueueValue(
                                        row.openCount(), row.oldestAt(), row.slaStatus(),
                                        revision)),
                        new PerformanceReviewOperationsDtos.ViewedStatusField(
                                "VIEW",
                                new PerformanceReviewOperationsDtos.StatusValue(
                                        row.statusCode(), revision)));
        return new PerformanceReviewOperationsDtos.Item(
                "performance-cycle:" + row.cycleId(),
                row.displayName(),
                row.statusCode(),
                groups,
                new PerformanceReviewOperationsDtos.DeepLink(
                        DEEP_LINK, row.cycleId().toString()),
                actions(row, actor));
    }

    private List<PerformanceReviewOperationsDtos.AvailableAction> actions(
            PerformanceReviewOperationsRepository.ReviewOperationRow row,
            PeopleRequestContext.Actor actor) {
        if (!actor.hasPermission(RESOURCE, "UPDATE", "MANAGE")) return List.of();
        List<PerformanceReviewOperationsDtos.AvailableAction> actions = new ArrayList<>();
        if (row.cycleVersionId() != null && row.populationRuleVersionId() != null
                && List.of("DRAFT", "VALIDATED").contains(row.lifecycleState())) {
            actions.add(action(PREVIEW_ACTION, PREVIEW_HREF));
        }
        if ("VALIDATED".equals(row.lifecycleState())
                && "READY_TO_FREEZE".equals(row.statusCode())
                && row.populationPreviewId() != null
                && row.populationFreezeId() == null) {
            actions.add(action(FREEZE_ACTION, FREEZE_HREF));
        }
        return List.copyOf(actions);
    }

    private PerformanceReviewOperationsDtos.AvailableAction action(
            String actionKey,
            String href) {
        return new PerformanceReviewOperationsDtos.AvailableAction(
                actionKey, "POST", href, "REQUIRED_IDEMPOTENCY_KEY",
                "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
    }

    private NormalizedQuery normalize(PerformanceReviewOperationsDtos.QueryRequest request) {
        if (request == null || request.limit() < 1 || request.limit() > 100
                || request.filter() == null || request.sort() == null
                || request.sort().isEmpty() || request.sort().size() > 3
                || request.correlationId() == null) {
            throw invalid("A complete IAQ-021 request is required.");
        }
        List<String> sort = List.copyOf(request.sort());
        if (new HashSet<>(sort).size() != sort.size()
                || sort.stream().anyMatch(value -> !SORTS.contains(value))) {
            throw invalid("IAQ-021 sort values must be unique and allowlisted.");
        }
        String locale = canonicalLocale(request.locale());
        ZoneId zoneId = canonicalZone(request.timeZone());
        String cursor = request.cursor();
        if (cursor != null && !CURSOR.matcher(cursor).matches()) {
            throw invalid("IAQ-021 cursor format is invalid.");
        }
        PerformanceReviewOperationsDtos.Filter filter = request.filter();
        List<String> reviewCodes = filter.reviewAssignment() == null
                ? List.of() : codes(filter.reviewAssignment().codes());
        List<String> reviewIds = filter.reviewAssignment() == null
                ? List.of() : publicIds(filter.reviewAssignment().publicIds());
        Integer minimumOpen = filter.workQueue() == null
                ? null : nonnegative(filter.workQueue().minimumOpenCount());
        Integer maximumOpen = filter.workQueue() == null
                ? null : nonnegative(filter.workQueue().maximumOpenCount());
        if (minimumOpen != null && maximumOpen != null && minimumOpen > maximumOpen) {
            throw invalid("IAQ-021 work-queue bounds are inverted.");
        }
        List<String> slaStates = filter.workQueue() == null
                ? List.of() : codes(filter.workQueue().slaStates());
        List<String> dueCodes = filter.dueStatus() == null
                ? List.of() : codes(filter.dueStatus().codes());
        Long minimumRevision = filter.dueStatus() == null
                ? null : filter.dueStatus().minimumRevision();
        if (minimumRevision != null && minimumRevision < 1) {
            throw invalid("IAQ-021 due-status revision must be positive.");
        }
        Instant asOf = request.asOf() == null ? clock.instant() : request.asOf();
        return new NormalizedQuery(
                request.limit(), sort, locale, zoneId, request.correlationId(), asOf,
                cursor, reviewCodes, reviewIds, minimumOpen, maximumOpen, slaStates,
                dueCodes, minimumRevision, filter.effectiveOn());
    }

    private List<String> codes(List<String> values) {
        return normalizedValues(values, CODE, "code");
    }

    private List<String> publicIds(List<String> values) {
        return normalizedValues(values, PUBLIC_ID, "public id");
    }

    private List<String> normalizedValues(
            List<String> values,
            Pattern pattern,
            String description) {
        if (values == null) return List.of();
        if (values.size() > 50 || new HashSet<>(values).size() != values.size()
                || values.stream().anyMatch(value -> value == null
                || !pattern.matcher(value).matches())) {
            throw invalid("IAQ-021 " + description + " filter is invalid.");
        }
        return values.stream().sorted().toList();
    }

    private Integer nonnegative(Integer value) {
        if (value != null && value < 0) {
            throw invalid("IAQ-021 queue count must be nonnegative.");
        }
        return value;
    }

    private String canonicalLocale(String value) {
        if (value == null || value.length() < 2 || value.length() > 35) {
            throw invalid("IAQ-021 locale is invalid.");
        }
        Locale locale = Locale.forLanguageTag(value.replace('_', '-'));
        if (locale.getLanguage().isBlank() || "und".equals(locale.toLanguageTag())) {
            throw invalid("IAQ-021 locale is invalid.");
        }
        return locale.toLanguageTag();
    }

    private ZoneId canonicalZone(String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw invalid("IAQ-021 time zone is invalid.");
        }
        try {
            return ZoneId.of(value);
        } catch (DateTimeException exception) {
            throw invalid("IAQ-021 time zone is invalid.");
        }
    }

    private void requirePermission(PeopleRequestContext.Actor actor) {
        if (actor.tenantId() == null || actor.userId() == null
                || !actor.hasPermission(RESOURCE, "VIEW", "MANAGE")) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The performance review-operations permission is not granted.");
        }
    }

    private UUID requireSubject(PeopleRequestContext.Actor actor) {
        if (actor.personPublicId() == null) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "A governed subject principal is required for IAQ-021.");
        }
        return actor.personPublicId();
    }

    private PopulationFreezeAuthorityPort.AuthorityEvidence requireAuthority(
            PeopleRequestContext.Actor actor,
            UUID subjectId,
            UUID correlationId) {
        PopulationFreezeAuthorityPort port = authoritySupplier.get();
        if (port == null) throw authorityUnavailable(null);
        PopulationFreezeAuthorityPort.AuthorityEvidence evidence;
        try {
            evidence = port.authorize(new PopulationFreezeAuthorityPort.AuthorityRequest(
                    actor.tenantId(), actor.userId(), subjectId,
                    APPLICATION_ENTITLEMENT, RESOURCE, AUTHORITY_ACTION,
                    QUERY_OPERATION, QUERY_PURPOSE, correlationId));
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw authorityUnavailable(exception);
        }
        if (evidence == null
                || evidence.tenantId() != actor.tenantId()
                || evidence.actorId() != actor.userId()
                || !subjectId.equals(evidence.subjectPrincipalPublicId())
                || !APPLICATION_ENTITLEMENT.equals(evidence.applicationEntitlement())
                || !RESOURCE.equals(evidence.resource())
                || !AUTHORITY_ACTION.equals(evidence.action())
                || !QUERY_OPERATION.equals(evidence.operation())
                || !QUERY_PURPOSE.equals(evidence.purposeCode())
                || evidence.populationScopeDigest() == null
                || !evidence.populationScopeDigest().matches("[0-9a-f]{64}")
                || evidence.fieldPolicyRevision() <= 0
                || evidence.authorizationRevision() <= 0) {
            throw authorityUnavailable(null);
        }
        return evidence;
    }

    private void audit(
            PeopleRequestContext.Actor actor,
            NormalizedQuery query,
            PopulationFreezeAuthorityPort.AuthorityEvidence authority,
            int itemCount,
            boolean hasMore) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("DATA_ACCESS")
                .action("performance.review-operations.queried")
                .outcome("SUCCESS")
                .severity("INFO")
                .riskScore(10)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-people-server")
                .sourceModule("hris-performance-population")
                .targetType("PERFORMANCE_REVIEW_OPERATIONS")
                .targetId(query.correlationId().toString())
                .correlationId(query.correlationId().toString())
                .metadata(Map.of(
                        "itemCount", itemCount,
                        "hasMore", hasMore,
                        "authorizationRevision", authority.authorizationRevision(),
                        "fieldPolicyRevision", authority.fieldPolicyRevision(),
                        "filterKinds", query.filterKinds()))
                .retentionClass("STANDARD")
                .build());
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException authorityUnavailable(Throwable cause) {
        String message = "Canonical IAQ-021 authority is unavailable; access is denied.";
        return cause == null
                ? new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message)
                : new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message, cause);
    }

    private record NormalizedQuery(
            int limit,
            List<String> sort,
            String locale,
            ZoneId zoneId,
            UUID correlationId,
            Instant asOf,
            String cursor,
            List<String> reviewAssignmentCodes,
            List<String> reviewAssignmentPublicIds,
            Integer minimumOpenCount,
            Integer maximumOpenCount,
            List<String> slaStates,
            List<String> dueStatusCodes,
            Long minimumRevision,
            java.time.LocalDate effectiveOn) {

        String fingerprintSource() {
            return String.join("|",
                    Integer.toString(limit), String.join(",", sort), locale, zoneId.getId(),
                    asOf.toString(), String.join(",", reviewAssignmentCodes),
                    String.join(",", reviewAssignmentPublicIds),
                    String.valueOf(minimumOpenCount), String.valueOf(maximumOpenCount),
                    String.join(",", slaStates), String.join(",", dueStatusCodes),
                    String.valueOf(minimumRevision), String.valueOf(effectiveOn));
        }

        List<String> filterKinds() {
            List<String> values = new ArrayList<>();
            if (!reviewAssignmentCodes.isEmpty() || !reviewAssignmentPublicIds.isEmpty()) {
                values.add("REVIEW_ASSIGNMENT");
            }
            if (minimumOpenCount != null || maximumOpenCount != null || !slaStates.isEmpty()) {
                values.add("WORK_QUEUE");
            }
            if (!dueStatusCodes.isEmpty() || minimumRevision != null) {
                values.add("DUE_STATUS");
            }
            if (effectiveOn != null) values.add("EFFECTIVE_ON");
            return List.copyOf(values);
        }
    }
}

package com.dwp.services.time.clockingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.services.time.clockingestion.ClockIngestionModels.CaptureMode;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockAction;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockAppend;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockCommandReceipt;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockEvent;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockEventType;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockExceptionCandidate;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockExceptionProjectionQuery;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockProjectionCandidate;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockProjectionPage;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockProjectionQuery;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockSourceType;
import com.dwp.services.time.clockingestion.ClockIngestionModels.DstResolution;
import com.dwp.services.time.clockingestion.ClockIngestionModels.InstantAuthority;
import com.dwp.services.time.clockingestion.ClockIngestionModels.TrustLevel;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VerifiedClockRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClockIngestionIaEntryQueryContractTest {

    private static final Instant NOW = Instant.parse("2026-10-12T02:00:00Z");
    private static final UUID SUBJECT = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID POPULATION = UUID.fromString("60000000-0000-4000-8000-000000000002");
    private static final UUID WORKER = UUID.fromString("60000000-0000-4000-8000-000000000003");
    private static final UUID OTHER_WORKER = UUID.fromString("60000000-0000-4000-8000-000000000004");
    private static final UUID ASSIGNMENT = UUID.fromString("60000000-0000-4000-8000-000000000005");
    private static final UUID SOURCE = UUID.fromString("60000000-0000-4000-8000-000000000006");
    private static final UUID CORRELATION = UUID.fromString("60000000-0000-4000-8000-000000000007");
    private static final String DIGEST = "a".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private CapturingRepository repository;
    private ClockIngestionService service;

    @BeforeEach
    void setUp() {
        repository = new CapturingRepository(List.of(
                event(1, WORKER, 1, TrustLevel.LOW, CaptureMode.OFFLINE),
                event(2, OTHER_WORKER, 2, TrustLevel.VERIFIED, CaptureMode.ONLINE),
                event(3, WORKER, 3, TrustLevel.VERIFIED, CaptureMode.ONLINE)));
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ClockIngestionService(
                repository,
                new ClockEvidenceValidator(),
                fixed,
                new ClockQueryCursorCodec(
                        "clock-iaq-contract-secret-value",
                        Duration.ofMinutes(15),
                        objectMapper,
                        fixed),
                new ClockStepUpTestVerifier());
    }

    @Test
    void routePurposesAreCanonicalAndMismatchesFailBeforeRepositoryAccess() {
        assertThat(ClockOwnerRoute.WORKBENCH.authorityContractKey())
                .isEqualTo("route.hcm.operations.time-clock-ingestion.page");
        assertThat(ClockOwnerRoute.TIME_EXCEPTIONS.authorityContractKey())
                .isEqualTo("route.hcm.operations.time-exceptions.page");
        assertThat(ClockOwnerRoute.RECORD.authorityContractKey())
                .isEqualTo("route.hcm.personal.time-clock-event-record.action");
        assertThat(ClockOwnerRoute.VOID.authorityContractKey())
                .isEqualTo("route.hcm.operations.time-clock-event-void.action");
        assertThat(ClockOwnerRoute.WORKBENCH.requiredPurpose())
                .isEqualTo(ClockIngestionService.VIEW_PURPOSE);
        assertThat(ClockOwnerRoute.TIME_EXCEPTIONS.requiredPurpose())
                .isEqualTo(ClockIngestionService.VIEW_PURPOSE);
        assertThat(ClockOwnerRoute.RECORD.requiredPurpose())
                .isEqualTo(ClockIngestionService.CAPTURE_PURPOSE);
        assertThat(ClockOwnerRoute.VOID.requiredPurpose())
                .isEqualTo(ClockIngestionService.CAPTURE_PURPOSE);

        assertCode(
                () -> service.workbench(authority(
                        ClockIngestionService.CAPTURE_PURPOSE,
                        Set.of(WORKER), Set.of(), Set.of(), "scope-revision-13"),
                        clockRequest(10, null, List.of("clockEvent:ASC"))),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        assertCode(
                () -> service.record(
                        authority(
                                ClockIngestionService.VIEW_PURPOSE,
                                Set.of(WORKER), Set.of(), Set.of(), "scope-revision-13"),
                        UUID.randomUUID(),
                        null),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        assertThat(repository.lastWorkerScope).isNull();
    }

    @Test
    void iaq034SerializesTheAcceptedProjectionAndNeverSerializesTenantContext() throws Exception {
        VerifiedClockRequest authority = authority(
                ClockIngestionService.VIEW_PURPOSE,
                Set.of(WORKER), Set.of("INGESTION_STATUS"),
                Set.of("QUALITY_FINDING"), "scope-revision-13");

        ClockIaQueryModels.ClockProjection projection =
                service.workbench(authority, clockRequest(10, null, List.of("clockEvent:ASC")));
        JsonNode json = objectMapper.valueToTree(projection);

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "projectionVersion", "asOf", "variantKey", "queryState", "items");
        assertThat(json.path("projectionVersion").asText()).isEqualTo("1");
        assertThat(json.path("variantKey").asText()).isEqualTo("CLOCK_INGESTION");
        assertThat(json.path("items")).hasSize(2);
        JsonNode item = json.path("items").get(0);
        assertThat(item.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "itemPublicId", "title", "statusCode", "fieldGroups",
                "deepLink", "availableActions");
        assertThat(item.path("fieldGroups").has("CLOCK_EVENT")).isTrue();
        assertThat(item.path("fieldGroups").path("INGESTION_STATUS").path("decision").asText())
                .isEqualTo("MASK");
        assertThat(item.path("fieldGroups").path("INGESTION_STATUS").has("value")).isTrue();
        assertThat(item.path("fieldGroups").path("INGESTION_STATUS").path("value").isNull())
                .isTrue();
        assertThat(item.path("fieldGroups").has("QUALITY_FINDING")).isFalse();
        assertThat(projection.queryState().fieldDecisionSummary())
                .isEqualTo(new ClockIaQueryModels.FieldDecisionSummary(2, 2, 2));
        String serialized = objectMapper.writeValueAsString(projection);
        assertThat(serialized)
                .doesNotContain("tenantId")
                .doesNotContain("workerPublicId")
                .doesNotContain("peopleAssignmentPublicId")
                .doesNotContain("interpretation.run")
                .doesNotContain("timecard.correct")
                .doesNotContain("timecard.decide");
        assertThat(repository.lastWorkerScope).containsExactly(WORKER);
    }

    @Test
    void iaq042UsesOnlyDerivedFactsAndOffersOnlyTheVerifiedSliceAction() {
        ClockIaQueryModels.ExceptionProjection projection = service.exceptions(
                authority(
                        ClockIngestionService.VIEW_PURPOSE,
                        Set.of(WORKER), Set.of(), Set.of(), "scope-revision-13"),
                exceptionRequest(100, null, List.of("exception:ASC")));

        assertThat(projection.projectionVersion()).isEqualTo("1");
        assertThat(projection.variantKey()).isEqualTo("EXCEPTIONS");
        assertThat(projection.items())
                .extracting(ClockIaQueryModels.ExceptionItem::title)
                .containsExactlyInAnyOrder(
                        "LOW_TRUST_EVIDENCE",
                        "OFFLINE_EVIDENCE_REVIEW",
                        "SOURCE_SEQUENCE_GAP");
        assertThat(projection.items())
                .flatExtracting(ClockIaQueryModels.ExceptionItem::availableActions)
                .extracting(ClockIaQueryModels.AvailableAction::actionKey)
                .containsOnly("hris.action.tim.clock.void");
        assertThat(projection.items())
                .flatExtracting(ClockIaQueryModels.ExceptionItem::availableActions)
                .allSatisfy(action -> {
                    assertThat(action.method()).isEqualTo("POST");
                    assertThat(action.hrefTemplate())
                            .isEqualTo("/api/time/v1/clock-events/{eventId}/voids");
                    assertThat(action.idempotencyPolicy())
                            .isEqualTo("REQUIRED_IDEMPOTENCY_KEY");
                    assertThat(action.stepUpPolicy())
                            .isEqualTo("OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
                });
    }

    @Test
    void requestsAreBoundedAndCursorsAreBoundToScopeRevisionAndNormalizedQuery() {
        VerifiedClockRequest authority = authority(
                ClockIngestionService.VIEW_PURPOSE,
                Set.of(WORKER), Set.of(), Set.of(), "scope-revision-13");
        ClockIaQueryModels.ClockProjection first = service.workbench(
                authority, clockRequest(1, null, List.of("clockEvent:ASC")));

        assertThat(first.items()).hasSize(1);
        assertThat(first.queryState().nextCursor()).matches("[A-Za-z0-9_-]{16,1024}");
        ClockIaQueryModels.ClockProjection second = service.workbench(
                authority,
                clockRequest(
                        1, first.queryState().nextCursor(), List.of("clockEvent:ASC")));
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().getFirst().itemPublicId())
                .isNotEqualTo(first.items().getFirst().itemPublicId());

        assertCode(
                () -> service.workbench(
                        authority(
                                ClockIngestionService.VIEW_PURPOSE,
                                Set.of(WORKER), Set.of(), Set.of(), "scope-revision-14"),
                        clockRequest(
                                1, first.queryState().nextCursor(),
                                List.of("clockEvent:ASC"))),
                ErrorCode.FORBIDDEN);
        assertCode(
                () -> service.workbench(
                        authority,
                        clockRequest(
                                1, first.queryState().nextCursor(),
                                List.of("itemPublicId:ASC"))),
                ErrorCode.FORBIDDEN);
        assertCode(
                () -> service.workbench(
                        authority, clockRequest(101, null, List.of("clockEvent:ASC"))),
                ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void repositoryKeysetSnapshotDoesNotPrecutOldAscRowsOrAdmitLateFacts() {
        List<ClockEvent> facts = new ArrayList<>();
        for (int index = 1; index <= 1_003; index++) {
            Instant occurred = NOW.minusSeconds(20_000L - index);
            facts.add(eventAt(index, occurred, occurred.plusSeconds(1), null));
        }
        CapturingRepository largeRepository = new CapturingRepository(facts);
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        ClockIngestionService largeService = new ClockIngestionService(
                largeRepository,
                new ClockEvidenceValidator(),
                fixed,
                new ClockQueryCursorCodec(
                        "clock-iaq-large-contract-secret",
                        Duration.ofMinutes(15), objectMapper, fixed),
                new ClockStepUpTestVerifier());
        VerifiedClockRequest authority = authority(
                ClockIngestionService.VIEW_PURPOSE,
                Set.of(WORKER), Set.of(), Set.of(), "scope-revision-large");

        ClockIaQueryModels.ClockProjection page = largeService.workbench(
                authority, clockRequest(100, null, List.of("clockEvent:ASC")));
        assertThat(page.items().getFirst().itemPublicId())
                .isEqualTo(new UUID(1, 1).toString());

        ClockEvent late = eventAt(
                1_004, NOW.minusSeconds(30_000), NOW.plusSeconds(1), null);
        ClockEvent postSnapshotVoid = eventAt(
                1_005, NOW.minusSeconds(29_000), NOW.plusSeconds(2), new UUID(1, 1));
        largeRepository.add(late);
        largeRepository.add(postSnapshotVoid);

        List<String> identifiers = new ArrayList<>();
        identifiers.addAll(page.items().stream()
                .map(ClockIaQueryModels.ClockItem::itemPublicId).toList());
        while (page.queryState().nextCursor() != null) {
            page = largeService.workbench(
                    authority,
                    clockRequest(
                            100, page.queryState().nextCursor(), List.of("clockEvent:ASC")));
            identifiers.addAll(page.items().stream()
                    .map(ClockIaQueryModels.ClockItem::itemPublicId).toList());
        }

        assertThat(identifiers).hasSize(1_003).doesNotHaveDuplicates();
        assertThat(identifiers)
                .doesNotContain(late.clockEventId().toString(), postSnapshotVoid.clockEventId().toString());
        assertThat(identifiers.getLast()).isEqualTo(new UUID(1, 1_003).toString());
    }

    private static ClockIaQueryModels.ClockQueryRequest clockRequest(
            int limit,
            String cursor,
            List<String> sort) {
        return new ClockIaQueryModels.ClockQueryRequest(
                limit,
                ClockIaQueryModels.ClockFilter.empty(),
                sort,
                "ko-KR",
                "Asia/Seoul",
                CORRELATION,
                NOW,
                cursor);
    }

    private static ClockIaQueryModels.ExceptionQueryRequest exceptionRequest(
            int limit,
            String cursor,
            List<String> sort) {
        return new ClockIaQueryModels.ExceptionQueryRequest(
                limit,
                ClockIaQueryModels.ExceptionFilter.empty(),
                sort,
                "ko-KR",
                "Asia/Seoul",
                CORRELATION,
                NOW,
                cursor);
    }

    private static VerifiedClockRequest authority(
            String purpose,
            Set<UUID> workers,
            Set<String> masked,
            Set<String> omitted,
            String scopeRevision) {
        return new VerifiedClockRequest(
                41L,
                72L,
                SUBJECT,
                Set.of(ClockAction.VIEW, ClockAction.RECORD, ClockAction.CORRECT),
                POPULATION,
                9,
                11,
                13,
                WORKER,
                ASSIGNMENT,
                7,
                ClockIngestionService.CAPTURE_PURPOSE.equals(purpose)
                        ? AuthSignedClockStepUpVerifier.ROUTE_CONTRACT_KEY
                        : ClockOwnerRoute.WORKBENCH.authorityContractKey(),
                "psc-" + "c".repeat(64),
                HcmEligibilityScopeKey.derived(
                        41L, 72L, "hcm.operations.clock-ingestion", "scope-source",
                        "relationship-9", "population-9"),
                DIGEST,
                DIGEST,
                DIGEST,
                DIGEST,
                5,
                purpose,
                17,
                "psr-" + "b".repeat(64),
                NOW.plusSeconds(300),
                CORRELATION,
                workers,
                scopeRevision,
                masked,
                omitted,
                Set.of(SOURCE),
                true);
    }

    private static ClockEvent event(
            long order,
            UUID worker,
            long sourceSequence,
            TrustLevel trust,
            CaptureMode captureMode) {
        Instant occurredAt = NOW.minusSeconds(600 - order);
        return new ClockEvent(
                new UUID(1, order),
                order,
                41L,
                SOURCE,
                "source-" + sourceSequence,
                sourceSequence,
                ClockSourceType.API,
                null,
                captureMode,
                captureMode == CaptureMode.OFFLINE ? new UUID(2, order) : null,
                worker,
                ASSIGNMENT,
                7,
                ClockEventType.IN,
                occurredAt,
                occurredAt.plusSeconds(1),
                LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC),
                "UTC",
                0,
                DstResolution.EXACT_OFFSET_SUPPLIED,
                InstantAuthority.SOURCE_INSTANT,
                ClockEvidenceValidator.expectedTzdbVersion("UTC"),
                ClockEvidenceValidator.expectedTimeZoneRuleVersion(
                        "UTC", ClockEvidenceValidator.expectedTzdbVersion("UTC")),
                "c".repeat(64),
                "d".repeat(64),
                trust,
                null,
                null,
                CORRELATION,
                72L);
    }

    private static ClockEvent eventAt(
            long order, Instant occurredAt, Instant receivedAt, UUID voidsEventId) {
        ClockEvent source = event(
                order, WORKER, order, TrustLevel.VERIFIED, CaptureMode.ONLINE);
        return new ClockEvent(
                source.clockEventId(), order, source.tenantId(), source.sourcePublicId(),
                source.sourceEventKey(), voidsEventId == null ? order : null,
                source.sourceType(), source.devicePublicId(), source.captureMode(),
                source.offlineBatchPublicId(), source.workerPublicId(),
                source.peopleAssignmentPublicId(), source.peopleAssignmentRevision(),
                voidsEventId == null ? ClockEventType.IN : ClockEventType.VOID,
                occurredAt, receivedAt,
                LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC), source.sourceTimeZone(),
                source.sourceUtcOffsetMinutes(), source.dstResolution(), source.instantAuthority(),
                source.tzdbVersion(), source.timeZoneRuleVersion(),
                source.sourceProvenanceDigest(), source.payloadDigest(), source.trustLevel(),
                voidsEventId, voidsEventId == null ? null : "POST_SNAPSHOT_VOID",
                source.correlationId(), source.recordedBy());
    }

    private static void assertCode(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(expected);
    }

    private static final class CapturingRepository implements ClockEventRepository {
        private final List<ClockEvent> events;
        private Set<UUID> lastWorkerScope;

        private CapturingRepository(List<ClockEvent> events) {
            this.events = new ArrayList<>(events);
        }

        private void add(ClockEvent event) {
            events.add(event);
        }

        @Override
        public ClockCommandReceipt append(ClockAppend append) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ClockEvent> find(long tenantId, UUID clockEventId) {
            return events.stream()
                    .filter(event -> event.tenantId() == tenantId)
                    .filter(event -> event.clockEventId().equals(clockEventId))
                    .findFirst();
        }

        @Override
        public ClockProjectionPage<ClockProjectionCandidate> queryClock(
                ClockProjectionQuery query) {
            lastWorkerScope = Set.copyOf(query.workerScope());
            long highWater = highWater(
                    query.tenantId(), query.workerScope(), query.asOf(), query.snapshotHighWater());
            List<ClockProjectionCandidate> candidates = scoped(
                            query.tenantId(), query.workerScope(), query.asOf(), highWater).stream()
                    .map(event -> new ClockProjectionCandidate(
                            event,
                            "ACCEPTED",
                            event.trustLevel() == TrustLevel.LOW ? "LOW_TRUST_EVIDENCE"
                                    : event.captureMode() == CaptureMode.OFFLINE
                                            ? "OFFLINE_EVIDENCE_REVIEW" : "EVIDENCE_VERIFIED",
                            null))
                    .sorted(clockComparator(query.sort()))
                    .filter(candidate -> after(candidate, query.sort(), query.afterKey()))
                    .toList();
            boolean hasMore = candidates.size() > query.limit();
            List<ClockProjectionCandidate> page = candidates.stream()
                    .limit(query.limit()).toList();
            List<String> next = hasMore ? clockKey(page.getLast(), query.sort()) : List.of();
            return new ClockProjectionPage<>(highWater, page, hasMore, next);
        }

        @Override
        public ClockProjectionPage<ClockExceptionCandidate> queryExceptions(
                ClockExceptionProjectionQuery query) {
            lastWorkerScope = Set.copyOf(query.workerScope());
            long highWater = highWater(
                    query.tenantId(), query.workerScope(), query.asOf(), query.snapshotHighWater());
            List<ClockEvent> scoped = scoped(
                    query.tenantId(), query.workerScope(), query.asOf(), highWater);
            List<ClockExceptionCandidate> candidates = new ArrayList<>();
            long previous = 0;
            for (ClockEvent event : scoped) {
                if (event.sourceSequence() != null && previous > 0
                        && event.sourceSequence() > previous + 1) {
                    candidates.add(new ClockExceptionCandidate(
                            event, "SOURCE_SEQUENCE_GAP", "ERROR", "OPEN", null));
                }
                if (event.sourceSequence() != null) previous = Math.max(previous, event.sourceSequence());
                if (event.trustLevel() == TrustLevel.LOW) {
                    candidates.add(new ClockExceptionCandidate(
                            event, "LOW_TRUST_EVIDENCE", "WARNING", "OPEN", null));
                }
                if (event.captureMode() == CaptureMode.OFFLINE) {
                    candidates.add(new ClockExceptionCandidate(
                            event, "OFFLINE_EVIDENCE_REVIEW", "WARNING", "OPEN", null));
                }
            }
            candidates.sort(Comparator.comparing(ClockExceptionCandidate::exceptionCode)
                    .thenComparing(value -> value.event().clockEventId()));
            boolean hasMore = candidates.size() > query.limit();
            List<ClockExceptionCandidate> page = candidates.stream()
                    .limit(query.limit()).toList();
            return new ClockProjectionPage<>(highWater, page, hasMore, List.of());
        }

        private long highWater(
                long tenantId, Set<UUID> workers, Instant asOf, Long requested) {
            if (requested != null) return requested;
            return events.stream()
                    .filter(event -> event.tenantId() == tenantId)
                    .filter(event -> workers.contains(event.workerPublicId()))
                    .filter(event -> !event.receivedAt().isAfter(asOf))
                    .mapToLong(ClockEvent::eventOrder).max().orElse(0);
        }

        private List<ClockEvent> scoped(
                long tenantId, Set<UUID> workerScope, Instant asOf, long highWater) {
            lastWorkerScope = Set.copyOf(workerScope);
            List<ClockEvent> result = new ArrayList<>();
            events.stream()
                    .filter(event -> event.tenantId() == tenantId)
                    .filter(event -> workerScope.contains(event.workerPublicId()))
                    .filter(event -> event.eventOrder() <= highWater)
                    .filter(event -> !event.receivedAt().isAfter(asOf))
                    .filter(event -> !event.occurredAt().isAfter(asOf))
                    .sorted(Comparator.comparingLong(ClockEvent::eventOrder))
                    .forEach(result::add);
            return List.copyOf(result);
        }

        private Comparator<ClockProjectionCandidate> clockComparator(List<String> sort) {
            Comparator<ClockProjectionCandidate> result = null;
            for (String token : sort) {
                Comparator<ClockProjectionCandidate> next = token.startsWith("clockEvent:")
                        ? Comparator.comparing(value -> value.event().occurredAt())
                        : token.startsWith("itemPublicId:")
                                ? Comparator.comparing(value -> value.event().clockEventId())
                                : Comparator.comparing(ClockProjectionCandidate::statusCode);
                if (token.endsWith(":DESC")) next = next.reversed();
                result = result == null ? next : result.thenComparing(next);
            }
            Comparator<ClockProjectionCandidate> identifier =
                    Comparator.comparing(value -> value.event().clockEventId());
            return result == null ? identifier : result.thenComparing(identifier);
        }

        private boolean after(
                ClockProjectionCandidate candidate, List<String> sort, List<String> afterKey) {
            if (afterKey == null || afterKey.isEmpty()) return true;
            List<String> key = clockKey(candidate, sort);
            for (int index = 0; index < key.size(); index++) {
                int compared = key.get(index).compareTo(afterKey.get(index));
                if (compared == 0) continue;
                boolean descending = index < sort.size() && sort.get(index).endsWith(":DESC");
                return descending ? compared < 0 : compared > 0;
            }
            return false;
        }

        private List<String> clockKey(
                ClockProjectionCandidate candidate, List<String> sort) {
            List<String> values = new ArrayList<>();
            boolean hasId = false;
            for (String token : sort) {
                if (token.startsWith("clockEvent:")) {
                    values.add(candidate.event().occurredAt().toString());
                } else if (token.startsWith("itemPublicId:")) {
                    values.add(candidate.event().clockEventId().toString());
                    hasId = true;
                } else {
                    values.add(candidate.statusCode());
                }
            }
            if (!hasId) values.add(candidate.event().clockEventId().toString());
            return List.copyOf(values);
        }
    }
}

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
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockStepUpHeaders;
import com.dwp.services.time.clockingestion.ClockIngestionModels.DstResolution;
import com.dwp.services.time.clockingestion.ClockIngestionModels.InstantAuthority;
import com.dwp.services.time.clockingestion.ClockIngestionModels.RecordClockCommand;
import com.dwp.services.time.clockingestion.ClockIngestionModels.TrustLevel;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VerifiedClockRequest;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VoidClockCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClockIngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T02:00:00Z");
    private static final UUID SUBJECT = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID POPULATION = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID WORKER = UUID.fromString("20000000-0000-4000-8000-000000000003");
    private static final UUID ASSIGNMENT = UUID.fromString("20000000-0000-4000-8000-000000000004");
    private static final UUID SOURCE = UUID.fromString("20000000-0000-4000-8000-000000000005");
    private static final UUID IDEMPOTENCY = UUID.fromString("20000000-0000-4000-8000-000000000006");
    private static final UUID VOID_IDEMPOTENCY = UUID.fromString("20000000-0000-4000-8000-000000000007");
    private static final String DIGEST = "a".repeat(64);

    private InMemoryClockRepository repository;
    private ClockIngestionService service;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        repository = new InMemoryClockRepository();
        clock = new MutableClock(NOW);
        service = new ClockIngestionService(
                repository,
                new ClockEvidenceValidator(),
                clock,
                new ClockQueryCursorCodec(
                        "clock-ingestion-service-test-secret",
                        Duration.ofMinutes(15),
                        new ObjectMapper().findAndRegisterModules(),
                        clock),
                new ClockStepUpTestVerifier());
    }

    @Test
    void recordsOnceAndReturnsTheOriginalReceiptForAnExactReplay() {
        ClockCommandReceipt first = service.record(
                captureAuthority(true), IDEMPOTENCY, command("source-1", 1));
        clock.advance(Duration.ofMinutes(2));
        ClockCommandReceipt replay = service.record(
                captureAuthority(true), IDEMPOTENCY, command("source-1", 1));

        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(first.replayed()).isFalse();
        assertThat(replay.receiptId()).isEqualTo(first.receiptId());
        assertThat(replay.clockEventId()).isEqualTo(first.clockEventId());
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(replay.resultDigest()).isEqualTo(first.resultDigest());
        assertThat(replay.replayed()).isTrue();
        assertThat(repository.events).hasSize(1);
        assertThat(service.workbench(viewAuthority(true), clockQuery()).items())
                .extracting(ClockIaQueryModels.ClockItem::itemPublicId)
                .containsExactly(first.clockEventId().toString());
    }

    @Test
    void failsClosedWhenAnIdempotencyKeyIsReusedForDifferentEvidence() {
        service.record(captureAuthority(true), IDEMPOTENCY, command("source-1", 1));

        assertThatThrownBy(() ->
                service.record(captureAuthority(true), IDEMPOTENCY, command("source-2", 2)))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(repository.events).hasSize(1);
    }

    @Test
    void serializesOnlyThePinnedTimeCommandReceiptFields() {
        ClockCommandReceipt receipt = service.record(
                captureAuthority(true), IDEMPOTENCY, command("source-1", 1));
        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(receipt);

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "receiptId", "commandType", "originatingAction",
                "subjectPrincipalPublicId", "populationScopeDigest",
                "fieldPolicyRevision", "purposeCode", "authorizationRevision",
                "status", "correlationId", "requestDigest", "createdAt",
                "aggregateVersion", "resultRef");
        assertThat(json.path("fieldPolicyRevision").isIntegralNumber()).isTrue();
        assertThat(json.path("authorizationRevision").isIntegralNumber()).isTrue();
        assertThat(json.toString())
                .doesNotContain("idempotencyKey")
                .doesNotContain("clockEventId")
                .doesNotContain("resultDigest")
                .doesNotContain("replayed");
    }

    @Test
    void rejectsCrossPopulationTargetsAndUnboundSourcesWithoutDisclosure() {
        RecordClockCommand differentWorker = replaceTarget(
                command("source-1", 1),
                UUID.fromString("29999999-0000-4000-8000-000000000001"));
        assertThatThrownBy(() -> service.record(
                captureAuthority(true), IDEMPOTENCY, differentWorker))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        RecordClockCommand differentSource = replaceSource(
                command("source-1", 1),
                UUID.fromString("29999999-0000-4000-8000-000000000002"));
        assertThatThrownBy(() -> service.record(
                captureAuthority(true), IDEMPOTENCY, differentSource))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(repository.events).isEmpty();
    }

    @Test
    void appendsOneStepUpProtectedVoidAndNeverMutatesTheOriginal() {
        ClockCommandReceipt recorded = service.record(
                captureAuthority(true), IDEMPOTENCY, command("source-1", 1));
        ClockEvent original = repository.find(41L, recorded.clockEventId()).orElseThrow();

        assertThatThrownBy(() -> service.voidEvent(
                captureAuthority(false), recorded.clockEventId(), VOID_IDEMPOTENCY,
                stepUp(recorded.aggregateVersion()),
                new VoidClockCommand("DEVICE_DUPLICATE")))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.STEP_UP_REQUIRED));

        ClockCommandReceipt voided = service.voidEvent(
                captureAuthority(true), recorded.clockEventId(), VOID_IDEMPOTENCY,
                stepUp(recorded.aggregateVersion()),
                new VoidClockCommand("DEVICE_DUPLICATE"));
        clock.advance(Duration.ofMinutes(2));
        ClockCommandReceipt replay = service.voidEvent(
                captureAuthority(true), recorded.clockEventId(), VOID_IDEMPOTENCY,
                stepUp(recorded.aggregateVersion()),
                new VoidClockCommand("DEVICE_DUPLICATE"));
        assertThat(voided.commandType()).isEqualTo("CLOCK_VOID");
        assertThat(replay.receiptId()).isEqualTo(voided.receiptId());
        assertThat(replay.createdAt()).isEqualTo(voided.createdAt());
        assertThat(replay.resultDigest()).isEqualTo(voided.resultDigest());
        assertThat(replay.replayed()).isTrue();
        assertThatThrownBy(() -> service.voidEvent(
                captureAuthority(true), recorded.clockEventId(), VOID_IDEMPOTENCY,
                stepUp(recorded.aggregateVersion()),
                new VoidClockCommand("OPERATOR_CORRECTION")))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(repository.find(41L, recorded.clockEventId())).contains(original);
        assertThat(repository.find(41L, voided.clockEventId()).orElseThrow())
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo(ClockEventType.VOID);
                    assertThat(event.voidsClockEventId()).isEqualTo(original.clockEventId());
                    assertThat(event.correctionReasonCode()).isEqualTo("DEVICE_DUPLICATE");
                });

        assertThatThrownBy(() -> service.voidEvent(
                captureAuthority(true), recorded.clockEventId(),
                UUID.fromString("20000000-0000-4000-8000-000000000008"),
                stepUp(recorded.aggregateVersion()),
                new VoidClockCommand("OPERATOR_CORRECTION")))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void derivesIaq042ExceptionsFromOrderedFactsAndResolvesThemOnlyByVoid() {
        ClockCommandReceipt first = service.record(
                captureAuthority(true), IDEMPOTENCY, lowTrustOfflineCommand("offline-1", 1));
        service.record(
                captureAuthority(true),
                UUID.fromString("20000000-0000-4000-8000-000000000010"),
                command("source-3", 3));

        assertThat(service.exceptions(viewAuthority(true), exceptionQuery()).items())
                .extracting(ClockIaQueryModels.ExceptionItem::title)
                .containsExactlyInAnyOrder(
                        "LOW_TRUST_EVIDENCE", "OFFLINE_EVIDENCE_REVIEW", "SOURCE_SEQUENCE_GAP");

        service.voidEvent(
                captureAuthority(true), first.clockEventId(), VOID_IDEMPOTENCY,
                stepUp(first.aggregateVersion()),
                new VoidClockCommand("UNTRUSTED_SOURCE"));
        assertThat(service.exceptions(viewAuthority(true), exceptionQuery()).items())
                .filteredOn(item -> List.of(
                        "LOW_TRUST_EVIDENCE", "OFFLINE_EVIDENCE_REVIEW").contains(item.title()))
                .allSatisfy(item -> assertThat(item.statusCode()).isEqualTo("RESOLVED"));
    }

    @Test
    void preservesDelayedUniqueOfflineFactAndExposesTypedGapRecovery() {
        service.record(captureAuthority(true), IDEMPOTENCY, command("ordered-1", 1));
        service.record(
                captureAuthority(true),
                UUID.fromString("20000000-0000-4000-8000-000000000020"),
                command("ordered-3", 3));
        UUID delayedKey = UUID.fromString("20000000-0000-4000-8000-000000000021");
        ClockCommandReceipt delayed = service.record(
                captureAuthority(true), delayedKey, lowTrustOfflineCommand("delayed-2", 2));
        ClockCommandReceipt replay = service.record(
                captureAuthority(true), delayedKey, lowTrustOfflineCommand("delayed-2", 2));

        assertThat(replay.receiptId()).isEqualTo(delayed.receiptId());
        assertThat(repository.events).hasSize(3);
        var projection = service.exceptions(viewAuthority(true), exceptionQuery());
        assertThat(projection.items())
                .filteredOn(item -> item.title().equals("SOURCE_SEQUENCE_GAP"))
                .allSatisfy(item -> assertThat(item.statusCode()).isEqualTo("RESOLVED"));
        assertThat(projection.items())
                .filteredOn(item -> item.title().equals("OUT_OF_ORDER_OFFLINE_SEQUENCE"))
                .singleElement()
                .satisfies(item -> assertThat(item.statusCode()).isEqualTo("QUARANTINED"));

        assertThatThrownBy(() -> service.record(
                captureAuthority(true),
                UUID.fromString("20000000-0000-4000-8000-000000000022"),
                lowTrustOfflineCommand("delayed-2", 2)))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        service.voidEvent(
                captureAuthority(true), delayed.clockEventId(), VOID_IDEMPOTENCY,
                stepUp(delayed.aggregateVersion()),
                new VoidClockCommand("DELAYED_SEQUENCE_REVIEWED"));
        assertThat(service.exceptions(viewAuthority(true), exceptionQuery()).items())
                .filteredOn(item -> item.title().equals("OUT_OF_ORDER_OFFLINE_SEQUENCE"))
                .singleElement()
                .satisfies(item -> assertThat(item.statusCode()).isEqualTo("RESOLVED"));
    }

    private static VerifiedClockRequest viewAuthority(boolean stepUp) {
        return authority(ClockIngestionService.VIEW_PURPOSE, stepUp);
    }

    private static VerifiedClockRequest captureAuthority(boolean stepUp) {
        return authority(ClockIngestionService.CAPTURE_PURPOSE, stepUp);
    }

    private static VerifiedClockRequest authority(String purpose, boolean stepUp) {
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
                UUID.fromString("20000000-0000-4000-8000-000000000009"),
                Set.of(WORKER),
                "scope-revision-13",
                Set.of(),
                Set.of(),
                Set.of(SOURCE),
                stepUp);
    }

    private static ClockStepUpHeaders stepUp(Long eventVersion) {
        return new ClockStepUpHeaders(
                "challenge-1", "psr-" + "b".repeat(64), eventVersion);
    }

    private static ClockIaQueryModels.ClockQueryRequest clockQuery() {
        return new ClockIaQueryModels.ClockQueryRequest(
                100,
                ClockIaQueryModels.ClockFilter.empty(),
                List.of("clockEvent:ASC"),
                "en-US",
                "UTC",
                UUID.fromString("20000000-0000-4000-8000-000000000009"),
                NOW,
                null);
    }

    private static ClockIaQueryModels.ExceptionQueryRequest exceptionQuery() {
        return new ClockIaQueryModels.ExceptionQueryRequest(
                100,
                ClockIaQueryModels.ExceptionFilter.empty(),
                List.of("exception:ASC"),
                "en-US",
                "UTC",
                UUID.fromString("20000000-0000-4000-8000-000000000009"),
                NOW,
                null);
    }

    private static RecordClockCommand command(String sourceKey, long sequence) {
        String tzdb = ClockEvidenceValidator.expectedTzdbVersion("UTC");
        RecordClockCommand unsealed = new RecordClockCommand(
                SOURCE,
                sourceKey,
                sequence,
                ClockSourceType.API,
                null,
                CaptureMode.ONLINE,
                null,
                WORKER,
                ASSIGNMENT,
                7,
                ClockEventType.IN,
                Instant.parse("2026-10-12T01:30:00Z"),
                LocalDateTime.parse("2026-10-12T01:30:00"),
                "UTC",
                0,
                DstResolution.EXACT_OFFSET_SUPPLIED,
                InstantAuthority.SOURCE_INSTANT,
                tzdb,
                ClockEvidenceValidator.expectedTimeZoneRuleVersion("UTC", tzdb),
                "0".repeat(64),
                TrustLevel.VERIFIED);
        return seal(unsealed);
    }

    private static RecordClockCommand lowTrustOfflineCommand(String sourceKey, long sequence) {
        RecordClockCommand source = command(sourceKey, sequence);
        return seal(new RecordClockCommand(
                source.sourcePublicId(), source.sourceEventKey(), source.sourceSequence(),
                source.sourceType(), source.devicePublicId(), CaptureMode.OFFLINE,
                UUID.fromString("20000000-0000-4000-8000-000000000011"),
                source.workerPublicId(), source.peopleAssignmentPublicId(),
                source.peopleAssignmentRevision(), source.eventType(), source.occurredAt(),
                source.sourceLocalDateTime(), source.sourceTimeZone(),
                source.sourceUtcOffsetMinutes(), source.dstResolution(), source.instantAuthority(),
                source.tzdbVersion(), source.timeZoneRuleVersion(), "0".repeat(64),
                TrustLevel.LOW));
    }

    private static RecordClockCommand replaceTarget(RecordClockCommand source, UUID worker) {
        return seal(new RecordClockCommand(
                source.sourcePublicId(), source.sourceEventKey(), source.sourceSequence(),
                source.sourceType(), source.devicePublicId(), source.captureMode(),
                source.offlineBatchPublicId(), worker, source.peopleAssignmentPublicId(),
                source.peopleAssignmentRevision(), source.eventType(), source.occurredAt(),
                source.sourceLocalDateTime(), source.sourceTimeZone(),
                source.sourceUtcOffsetMinutes(), source.dstResolution(), source.instantAuthority(),
                source.tzdbVersion(), source.timeZoneRuleVersion(), "0".repeat(64),
                source.trustLevel()));
    }

    private static RecordClockCommand replaceSource(RecordClockCommand source, UUID sourceId) {
        return seal(new RecordClockCommand(
                sourceId, source.sourceEventKey(), source.sourceSequence(), source.sourceType(),
                source.devicePublicId(), source.captureMode(), source.offlineBatchPublicId(),
                source.workerPublicId(), source.peopleAssignmentPublicId(),
                source.peopleAssignmentRevision(), source.eventType(), source.occurredAt(),
                source.sourceLocalDateTime(), source.sourceTimeZone(),
                source.sourceUtcOffsetMinutes(), source.dstResolution(), source.instantAuthority(),
                source.tzdbVersion(), source.timeZoneRuleVersion(), "0".repeat(64),
                source.trustLevel()));
    }

    private static RecordClockCommand seal(RecordClockCommand source) {
        return new RecordClockCommand(
                source.sourcePublicId(), source.sourceEventKey(), source.sourceSequence(),
                source.sourceType(), source.devicePublicId(), source.captureMode(),
                source.offlineBatchPublicId(), source.workerPublicId(),
                source.peopleAssignmentPublicId(), source.peopleAssignmentRevision(),
                source.eventType(), source.occurredAt(), source.sourceLocalDateTime(),
                source.sourceTimeZone(), source.sourceUtcOffsetMinutes(), source.dstResolution(),
                source.instantAuthority(), source.tzdbVersion(), source.timeZoneRuleVersion(),
                ClockEvidenceValidator.provenanceDigest(source), source.trustLevel());
    }

    private static final class InMemoryClockRepository implements ClockEventRepository {
        private final Map<String, Stored> receipts = new HashMap<>();
        private final Map<UUID, ClockEvent> events = new LinkedHashMap<>();
        private final Map<UUID, Long> sourceSequences = new HashMap<>();
        private final Set<String> sourceEventIdentities = new java.util.HashSet<>();
        private final Set<String> sourceSequenceIdentities = new java.util.HashSet<>();
        private final Set<UUID> voided = new java.util.HashSet<>();
        private long order;

        @Override
        public ClockCommandReceipt append(ClockAppend append) {
            String key = append.authority().tenantId() + "|"
                    + append.authority().subjectPrincipalPublicId() + "|"
                    + append.commandType() + "|" + append.idempotencyKey();
            Stored existing = receipts.get(key);
            if (existing != null) {
                if (!existing.requestDigest.equals(append.requestDigest())) {
                    throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
                }
                return existing.receipt(true);
            }
            ClockEvent event = append.event();
            if (event.sourceSequence() != null) {
                long last = sourceSequences.getOrDefault(event.sourcePublicId(), 0L);
                String eventIdentity = event.sourcePublicId() + "|" + event.sourceEventKey();
                String sequenceIdentity = event.sourcePublicId() + "|" + event.sourceSequence();
                if (!sourceEventIdentities.add(eventIdentity)
                        || !sourceSequenceIdentities.add(sequenceIdentity)
                        || (event.captureMode() != CaptureMode.OFFLINE
                                && event.sourceSequence() <= last)) {
                    throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
                }
                sourceSequences.merge(
                        event.sourcePublicId(), event.sourceSequence(), Math::max);
            }
            if (event.voidsClockEventId() != null && !voided.add(event.voidsClockEventId())) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
            }
            ClockEvent storedEvent = withOrder(event, ++order);
            events.put(storedEvent.clockEventId(), storedEvent);
            Stored stored = new Stored(
                    UUID.randomUUID(), append.idempotencyKey(), append.commandType(),
                    append.requestDigest(), storedEvent, append.authority().correlationId());
            receipts.put(key, stored);
            return stored.receipt(false);
        }

        @Override
        public Optional<ClockEvent> find(long tenantId, UUID clockEventId) {
            return Optional.ofNullable(events.get(clockEventId))
                    .filter(event -> event.tenantId() == tenantId);
        }

        @Override
        public ClockProjectionPage<ClockProjectionCandidate> queryClock(
                ClockProjectionQuery query) {
            List<ClockEvent> scoped = scoped(query.tenantId(), query.workerScope(), query.asOf(),
                    query.snapshotHighWater());
            List<ClockProjectionCandidate> candidates = scoped.stream()
                    .map(event -> candidate(event, scoped))
                    .sorted(java.util.Comparator.comparingLong(
                            candidate -> candidate.event().eventOrder()))
                    .toList();
            return new ClockProjectionPage<>(
                    highWater(scoped), candidates.stream().limit(query.limit()).toList(),
                    candidates.size() > query.limit(), List.of());
        }

        @Override
        public ClockProjectionPage<ClockExceptionCandidate> queryExceptions(
                ClockExceptionProjectionQuery query) {
            List<ClockEvent> scoped = scoped(query.tenantId(), query.workerScope(), query.asOf(),
                    query.snapshotHighWater());
            List<ClockExceptionCandidate> candidates = new ArrayList<>();
            for (ClockEvent event : scoped) {
                if (event.eventType() == ClockEventType.VOID) continue;
                ClockProjectionCandidate fact = candidate(event, scoped);
                String quality = fact.qualityCode();
                if (quality.equals("SOURCE_SEQUENCE_GAP")
                        || quality.equals("SOURCE_SEQUENCE_GAP_RECOVERED")) {
                    candidates.add(exception(event, "SOURCE_SEQUENCE_GAP", "ERROR",
                            quality.endsWith("RECOVERED") ? "RESOLVED" : "OPEN",
                            fact.resolutionVoidEventId()));
                }
                if (quality.equals("OUT_OF_ORDER_OFFLINE_SEQUENCE")) {
                    candidates.add(exception(event, quality, "ERROR",
                            fact.resolutionVoidEventId() == null ? "QUARANTINED" : "RESOLVED",
                            fact.resolutionVoidEventId()));
                }
                if (event.trustLevel() == TrustLevel.LOW) {
                    candidates.add(exception(event, "LOW_TRUST_EVIDENCE", "WARNING",
                            fact.resolutionVoidEventId() == null ? "OPEN" : "RESOLVED",
                            fact.resolutionVoidEventId()));
                }
                if (event.captureMode() == CaptureMode.OFFLINE) {
                    candidates.add(exception(event, "OFFLINE_EVIDENCE_REVIEW", "WARNING",
                            fact.resolutionVoidEventId() == null ? "OPEN" : "RESOLVED",
                            fact.resolutionVoidEventId()));
                }
            }
            return new ClockProjectionPage<>(
                    highWater(scoped), candidates.stream().limit(query.limit()).toList(),
                    candidates.size() > query.limit(), List.of());
        }

        private List<ClockEvent> scoped(
                long tenantId, Set<UUID> workers, Instant asOf, Long requestedHighWater) {
            long highWater = requestedHighWater == null
                    ? events.values().stream()
                            .filter(event -> event.tenantId() == tenantId)
                            .filter(event -> workers.contains(event.workerPublicId()))
                            .filter(event -> !event.receivedAt().isAfter(asOf))
                            .mapToLong(ClockEvent::eventOrder).max().orElse(0)
                    : requestedHighWater;
            return events.values().stream()
                    .filter(event -> event.tenantId() == tenantId)
                    .filter(event -> workers.contains(event.workerPublicId()))
                    .filter(event -> event.eventOrder() <= highWater)
                    .filter(event -> !event.receivedAt().isAfter(asOf))
                    .filter(event -> !event.occurredAt().isAfter(asOf))
                    .sorted(java.util.Comparator.comparingLong(ClockEvent::eventOrder))
                    .toList();
        }

        private ClockProjectionCandidate candidate(ClockEvent event, List<ClockEvent> scoped) {
            UUID resolution = scoped.stream()
                    .filter(value -> event.clockEventId().equals(value.voidsClockEventId()))
                    .map(ClockEvent::clockEventId).findFirst().orElse(null);
            long prior = scoped.stream()
                    .filter(value -> value.eventOrder() < event.eventOrder())
                    .filter(value -> value.sourcePublicId().equals(event.sourcePublicId()))
                    .filter(value -> value.sourceSequence() != null)
                    .mapToLong(ClockEvent::sourceSequence).max().orElse(0);
            boolean gap = event.sourceSequence() != null && prior > 0
                    && event.sourceSequence() > prior + 1;
            boolean gapOpen = gap && java.util.stream.LongStream
                    .range(prior + 1, event.sourceSequence())
                    .anyMatch(missing -> scoped.stream().noneMatch(value ->
                            value.sourcePublicId().equals(event.sourcePublicId())
                                    && value.sourceSequence() != null
                                    && value.sourceSequence() == missing));
            boolean outOfOrder = event.sourceSequence() != null && prior > 0
                    && event.sourceSequence() < prior;
            String status = event.eventType() == ClockEventType.VOID ? "CORRECTIVE_VOID"
                    : resolution != null ? "VOIDED"
                    : outOfOrder ? "QUARANTINED" : "ACCEPTED";
            String quality = gap && gapOpen ? "SOURCE_SEQUENCE_GAP"
                    : gap ? "SOURCE_SEQUENCE_GAP_RECOVERED"
                    : outOfOrder ? "OUT_OF_ORDER_OFFLINE_SEQUENCE"
                    : event.trustLevel() == TrustLevel.LOW ? "LOW_TRUST_EVIDENCE"
                    : event.captureMode() == CaptureMode.OFFLINE
                            ? "OFFLINE_EVIDENCE_REVIEW" : "EVIDENCE_VERIFIED";
            return new ClockProjectionCandidate(event, status, quality, resolution);
        }

        private ClockExceptionCandidate exception(
                ClockEvent event, String code, String severity, String state, UUID resolution) {
            return new ClockExceptionCandidate(event, code, severity, state, resolution);
        }

        private long highWater(List<ClockEvent> scoped) {
            return scoped.stream().mapToLong(ClockEvent::eventOrder).max().orElse(0);
        }

        private static ClockEvent withOrder(ClockEvent event, long order) {
            return new ClockEvent(
                    event.clockEventId(), order, event.tenantId(), event.sourcePublicId(),
                    event.sourceEventKey(), event.sourceSequence(), event.sourceType(),
                    event.devicePublicId(), event.captureMode(), event.offlineBatchPublicId(),
                    event.workerPublicId(), event.peopleAssignmentPublicId(),
                    event.peopleAssignmentRevision(), event.eventType(), event.occurredAt(),
                    event.receivedAt(), event.sourceLocalDateTime(), event.sourceTimeZone(),
                    event.sourceUtcOffsetMinutes(), event.dstResolution(), event.instantAuthority(),
                    event.tzdbVersion(), event.timeZoneRuleVersion(),
                    event.sourceProvenanceDigest(), event.payloadDigest(), event.trustLevel(),
                    event.voidsClockEventId(), event.correctionReasonCode(),
                    event.correlationId(), event.recordedBy());
        }

        private record Stored(
                UUID receiptId,
                UUID idempotencyKey,
                String commandType,
                String requestDigest,
                ClockEvent event,
                UUID correlationId) {

            private ClockCommandReceipt receipt(boolean replayed) {
                return new ClockCommandReceipt(
                        receiptId,
                        commandType,
                        commandType.equals("CLOCK_RECORD")
                                ? "TIME_CLOCK_RECORD" : "TIME_CLOCK_CORRECT",
                        SUBJECT,
                        DIGEST,
                        5,
                        ClockIngestionService.CAPTURE_PURPOSE,
                        17,
                        "SUCCEEDED",
                        correlationId,
                        requestDigest,
                        event.receivedAt(),
                        event.eventOrder(),
                        event.clockEventId().toString(),
                        idempotencyKey,
                        event.clockEventId(),
                        event.payloadDigest(),
                        replayed);
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant value;

        private MutableClock(Instant value) {
            this.value = value;
        }

        private void advance(Duration duration) {
            value = value.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return value;
        }
    }
}

package com.dwp.services.people.hris.employment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.hr.assignment.AssignmentProposalCanonicalizer;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmploymentEventServiceTest {

    private static final long TENANT = 77L;
    private static final long MAKER = 19L;
    private static final UUID ACTOR = uuid(91);
    private static final UUID EVENT = uuid(21);
    private static final UUID CORRELATION = uuid(51);
    private static final UUID PERSON = uuid(1);
    private static final UUID WORKER = uuid(11);
    private static final UUID EMPLOYER = uuid(2);
    private static final UUID ORGANIZATION = uuid(3);
    private static final UUID POSITION = uuid(4);
    private static final UUID JOB = uuid(5);
    private static final UUID LOCATION = uuid(6);
    private static final Instant NOW = Instant.parse("2026-10-13T04:00:00Z");
    private static final String KEY = "hire-tenant-100-0001";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final EmploymentEventRepository repository = mock(EmploymentEventRepository.class);
    private final EmploymentEventAuthority authority = mock(EmploymentEventAuthority.class);
    private final HcmPopulationScopeService populationScopes =
            mock(HcmPopulationScopeService.class);
    private final HcmPopulationRepository populations = mock(HcmPopulationRepository.class);
    private final HcmHighRiskCommandGuard highRisk = mock(HcmHighRiskCommandGuard.class);
    private final EmploymentEventCanonicalizer canonicalizer =
            new EmploymentEventCanonicalizer(
                    mapper, new AssignmentProposalCanonicalizer(mapper));
    private final HcmPopulationScopeService.ResolvedPopulation population = population(true);
    private EmploymentEventService service;

    @BeforeEach
    void setUp() {
        PeopleRequestContext.set(MAKER, TENANT, ACTOR, Set.of("HRIS_OPERATOR"), Set.of());
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "set", new HcmPepContext.Evidence(
                route(), "decision:hrm-008:1", OffsetDateTime.now().plusMinutes(5),
                "context:hrm-008", "scope:hrm-008", "111"));
        when(authority.require(anyString())).thenAnswer(invocation ->
                "PUBLISH".equals(invocation.getArgument(0))
                        ? EmploymentEventAuthority.PUBLISH_CAPABILITY
                        : EmploymentEventAuthority.MANAGE_CAPABILITY);
        when(populationScopes.requireOperationsForMutation("READ")).thenReturn(population);
        when(populationScopes.requireOperations("READ")).thenReturn(population);
        when(populations.lockWorkerInPopulation(eq(TENANT), any(), anyLong())).thenReturn(true);
        when(populations.containsWorker(eq(TENANT), any(), anyLong())).thenReturn(true);
        service = new EmploymentEventService(
                repository, canonicalizer, authority, populationScopes, populations,
                highRisk, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        PeopleRequestContext.clear();
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "clear");
    }

    @Test
    void rejectsHrmPHireWhenItsPrimaryAssignmentWouldOverlap() {
        EmploymentEventDtos.CreateCommand request = hire(false);
        EmploymentEventRepository.ReceiptRow running = receipt(
                "RUNNING", null, canonicalizer.requestDigest(
                        "POST", "/api/people/v1/hris/assignment-events", "\"v:0\"",
                        KEY, CORRELATION, EmploymentEventDtos.PURPOSE, request));
        when(repository.insertReceipt(
                eq(TENANT), any(), anyString(), eq(KEY), anyString(), eq(ACTOR),
                anyString(), eq(ACTOR), anyString(), anyLong(), anyString(), anyLong(),
                anyString(), eq(null), eq(0L), eq(CORRELATION), eq(NOW)))
                .thenReturn(Optional.of(running));
        when(repository.preflight(eq(TENANT), any(), eq(true)))
                .thenReturn(snapshot(null, 0, true));

        assertThatThrownBy(() -> service.create(
                request, KEY, "\"v:0\"", CORRELATION, EmploymentEventDtos.PURPOSE))
                .isInstanceOf(EmploymentEventProblem.class)
                .satisfies(error -> assertThat(((EmploymentEventProblem) error).code())
                        .isEqualTo("PRIMARY_ASSIGNMENT_OVERLAP"));

        verify(repository, never()).createEvent(
                anyLong(), any(), any(), anyString(), any(), anyLong());
    }

    @Test
    void rejectsHrmPConcurrentWhenTheWorkerOwnerVersionIsStale() {
        EmploymentEventDtos.CreateCommand request = concurrent();
        when(repository.insertReceipt(
                eq(TENANT), any(), anyString(), anyString(), anyString(), eq(ACTOR),
                anyString(), eq(ACTOR), anyString(), anyLong(), anyString(), anyLong(),
                anyString(), eq(null), eq(8L), eq(CORRELATION), eq(NOW)))
                .thenReturn(Optional.of(receipt("RUNNING", null, "a".repeat(64))));
        when(repository.preflight(eq(TENANT), any(), eq(true)))
                .thenReturn(snapshot(101L, 9, false));

        assertThatThrownBy(() -> service.create(
                request, "concurrent-tenant-100-01", "\"v:8\"", CORRELATION,
                EmploymentEventDtos.PURPOSE))
                .isInstanceOf(EmploymentEventProblem.class)
                .satisfies(error -> {
                    EmploymentEventProblem problem = (EmploymentEventProblem) error;
                    assertThat(problem.code()).isEqualTo("STALE_OWNER_VERSION");
                    assertThat(problem.currentVersion()).isEqualTo(9L);
                });
    }

    @Test
    void deniesMissingPermissionBeforeClaimAndHidesPopulationMisses() {
        EmploymentEventDtos.CreateCommand request = hire(false);
        when(authority.require("CREATE")).thenThrow(
                new BaseException(ErrorCode.FORBIDDEN, "not permitted"));

        assertThatThrownBy(() -> service.create(
                request, KEY, "\"v:0\"", CORRELATION, EmploymentEventDtos.PURPOSE))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
        verify(repository, never()).insertReceipt(
                anyLong(), any(), anyString(), anyString(), anyString(), any(), anyString(),
                any(), anyString(), anyLong(), anyString(), anyLong(), anyString(), any(),
                any(), any(), any());

        doReturn(EmploymentEventAuthority.MANAGE_CAPABILITY)
                .when(authority).require("CREATE");
        HcmPopulationScopeService.ResolvedPopulation restricted = population(false);
        when(populationScopes.requireOperationsForMutation("READ")).thenReturn(restricted);
        when(repository.insertReceipt(
                eq(TENANT), any(), anyString(), anyString(), anyString(), eq(ACTOR),
                anyString(), eq(ACTOR), anyString(), anyLong(), anyString(), anyLong(),
                anyString(), eq(null), eq(8L), eq(CORRELATION), eq(NOW)))
                .thenReturn(Optional.of(receipt("RUNNING", null, "b".repeat(64))));
        when(repository.preflight(eq(TENANT), any(), eq(true)))
                .thenReturn(snapshot(101L, 8, false));
        when(populations.lockWorkerInPopulation(
                eq(TENANT), eq(restricted.scope()), eq(101L))).thenReturn(false);

        assertThatThrownBy(() -> service.create(
                concurrent(), "concurrent-tenant-100-01", "\"v:8\"", CORRELATION,
                EmploymentEventDtos.PURPOSE))
                .isInstanceOf(EmploymentEventProblem.class)
                .satisfies(error -> {
                    EmploymentEventProblem problem = (EmploymentEventProblem) error;
                    assertThat(problem.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(problem.code()).isEqualTo("TARGET_NOT_DISCLOSED");
                });
    }

    @Test
    void sameIdempotencyBindingRecoversOriginalReceiptAndDigestDriftConflicts() {
        EmploymentEventDtos.CreateCommand originalRequest = hire(false);
        String digest = canonicalizer.requestDigest(
                "POST", "/api/people/v1/hris/assignment-events", "\"v:0\"", KEY,
                CORRELATION, EmploymentEventDtos.PURPOSE, originalRequest);
        EmploymentEventRepository.EventRow stored = event(
                "DRAFT", 0, MAKER, null, null, null, null);
        EmploymentEventRepository.ReceiptRow original = receipt(
                "SUCCEEDED", "{\"eventPublicId\":\"" + EVENT + "\"}", digest);
        when(repository.insertReceipt(
                eq(TENANT), any(), anyString(), eq(KEY), anyString(), eq(ACTOR),
                anyString(), eq(ACTOR), anyString(), anyLong(), anyString(), anyLong(),
                anyString(), eq(null), eq(0L), eq(CORRELATION), eq(NOW)))
                .thenReturn(Optional.empty());
        when(repository.receiptByIdentity(
                TENANT, ACTOR, "CREATE_ASSIGNMENT_EVENT", KEY))
                .thenReturn(Optional.of(original));
        when(repository.event(TENANT, EVENT)).thenReturn(Optional.of(stored));

        EmploymentEventDtos.CommandResult replay = service.create(
                originalRequest, KEY, "\"v:0\"", CORRELATION,
                EmploymentEventDtos.PURPOSE);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.receipt().receiptId()).isEqualTo(original.receiptId());
        assertThat(replay.receipt().status()).isEqualTo("SUCCEEDED");
        verify(repository, never()).preflight(anyLong(), any(), anyBoolean());

        assertThatThrownBy(() -> service.create(
                hire(true), KEY, "\"v:0\"", CORRELATION,
                EmploymentEventDtos.PURPOSE))
                .isInstanceOf(EmploymentEventProblem.class)
                .satisfies(error -> {
                    EmploymentEventProblem problem = (EmploymentEventProblem) error;
                    assertThat(problem.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                    assertThat(problem.receiptId()).isEqualTo(original.receiptId());
                });
    }

    @Test
    void validationReturnsTheStoredCanonicalAssignmentImpact() {
        EmploymentEventRepository.EventRow draft = event(
                "DRAFT", 0, MAKER, null, null, null, null);
        EmploymentEventRepository.EventRow validated = event(
                "VALIDATED", 1, MAKER, null, null, null, null);
        EmploymentEventRepository.ReceiptRow running = receipt(
                "RUNNING", null, "f".repeat(64));
        EmploymentEventRepository.ReceiptRow complete = receipt(
                "SUCCEEDED", "{\"eventPublicId\":\"" + EVENT + "\"}",
                "f".repeat(64));
        EmploymentEventRepository.PreflightSnapshot snapshot =
                snapshot(null, 0, false);
        when(repository.insertReceipt(
                eq(TENANT), any(), anyString(), eq(KEY), anyString(), eq(ACTOR),
                anyString(), eq(ACTOR), anyString(), anyLong(), anyString(), anyLong(),
                anyString(), eq(EVENT), eq(0L), eq(CORRELATION), eq(NOW)))
                .thenReturn(Optional.of(running));
        when(repository.lockEvent(TENANT, EVENT)).thenReturn(Optional.of(draft));
        when(repository.preflight(eq(TENANT), any(), eq(true))).thenReturn(snapshot);
        when(repository.validate(
                eq(TENANT), eq(EVENT), eq(0L), anyString(), anyString(), eq(MAKER)))
                .thenReturn(validated);
        when(repository.completeReceipt(
                TENANT, running.receiptId(), validated, NOW)).thenReturn(complete);

        EmploymentEventDtos.CommandResult result = service.validate(
                EVENT, KEY, "\"v:0\"", CORRELATION, EmploymentEventDtos.PURPOSE);

        assertThat(result.impact().eventPublicId()).isEqualTo(EVENT);
        assertThat(result.impact().effectiveDate())
                .isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(result.impact().blockingIssues()).isEmpty();
        assertThat(result.impact().warnings()).isEmpty();
        assertThat(result.impact().impactDigest())
                .isEqualTo(validated.impactDigest());
    }

    @Test
    void approvedEventPublishesOnlyThroughFreshStepUpAndCompletesReceipt() {
        PeopleRequestContext.set(29L, TENANT, ACTOR, Set.of("HRIS_APPROVER"), Set.of());
        EmploymentEventRepository.EventRow approved = event(
                "APPROVED", 3, MAKER, uuid(61), uuid(62), 7L, approvalDigestAtSubmit());
        EmploymentEventRepository.EventRow published = event(
                "PUBLISHED", 4, MAKER, uuid(61), uuid(62), 7L, approvalDigestAtSubmit());
        EmploymentEventRepository.ReceiptRow running = receipt(
                "RUNNING", null, "c".repeat(64));
        EmploymentEventRepository.ReceiptRow complete = receipt(
                "SUCCEEDED", "{\"eventPublicId\":\"" + EVENT + "\"}",
                "c".repeat(64));
        EmploymentEventRepository.PreflightSnapshot snapshot = snapshot(null, 0, false);
        when(repository.insertReceipt(
                eq(TENANT), any(), anyString(), anyString(), anyString(), eq(ACTOR),
                anyString(), eq(ACTOR), anyString(), anyLong(), anyString(), anyLong(),
                anyString(), eq(EVENT), eq(3L), eq(CORRELATION), eq(NOW)))
                .thenReturn(Optional.of(running));
        when(repository.lockEvent(TENANT, EVENT)).thenReturn(Optional.of(approved));
        when(repository.preflight(eq(TENANT), any(), eq(true))).thenReturn(snapshot);
        when(repository.publishLedger(
                eq(TENANT), eq(approved), any(), eq(snapshot), eq(29L), eq(CORRELATION)))
                .thenReturn(new EmploymentEventRepository.PublishedLedger(
                        published,
                        new EmploymentEventRepository.WorkerRow(1, uuid(71), 1),
                        new EmploymentEventRepository.RelationshipRow(2, uuid(72), 1),
                        new EmploymentEventRepository.AssignmentRow(3, uuid(73), 1)));
        when(repository.completeReceipt(
                TENANT, running.receiptId(), published, NOW)).thenReturn(complete);
        HcmStepUpHeaders stepUp = new HcmStepUpHeaders(
                "signed-challenge", KEY, "decision:hrm-008:1", 3L);

        EmploymentEventDtos.CommandResult result = service.publish(
                EVENT, KEY, "\"v:3\"", CORRELATION,
                EmploymentEventDtos.PURPOSE, stepUp);

        assertThat(result.event().lifecycleState()).isEqualTo("PUBLISHED");
        assertThat(result.receipt().status()).isEqualTo("SUCCEEDED");
        verify(highRisk).requireExact(
                eq(EmploymentEventAuthority.PUBLISH_CAPABILITY), eq("EMPLOYMENT_EVENT"),
                eq(EVENT.toString()), eq(3L),
                eq("/api/people/v1/hris/assignment-events/" + EVENT + "/publish"),
                eq(Map.of()), eq(stepUp));
        verify(repository).publishLedger(
                eq(TENANT), eq(approved), any(), eq(snapshot), eq(29L), eq(CORRELATION));
    }

    private EmploymentEventDtos.CreateCommand hire(boolean alternateDate) {
        LocalDate start = LocalDate.of(2026, 10, alternateDate ? 2 : 1);
        EmploymentEventDtos.HirePayload payload = new EmploymentEventDtos.HirePayload(
                PERSON, ref("HRM.LegalEmployer.v1", EMPLOYER, 7), "EMPLOYEE", start,
                "CREATE_NEW", assignment(start, true));
        ObjectNode body = mapper.valueToTree(payload);
        ((ObjectNode) body.path("assignment")).put("fte", primaryFte(true));
        return new EmploymentEventDtos.CreateCommand(
                new EmploymentEventDtos.CommandContext(
                        0, KEY, EmploymentEventDtos.PURPOSE, start.atStartOfDay().toInstant(ZoneOffset.UTC)),
                "HIRE", body);
    }

    private EmploymentEventDtos.CreateCommand concurrent() {
        LocalDate start = LocalDate.of(2026, 11, 1);
        EmploymentEventDtos.ConcurrentEmploymentPayload payload =
                new EmploymentEventDtos.ConcurrentEmploymentPayload(
                        WORKER, 8, ref("HRM.LegalEmployer.v1", EMPLOYER, 7),
                        start, assignment(start, false));
        ObjectNode body = mapper.valueToTree(payload);
        ((ObjectNode) body.path("assignment")).put("fte", primaryFte(false));
        return new EmploymentEventDtos.CreateCommand(
                new EmploymentEventDtos.CommandContext(
                        8, "concurrent-tenant-100-01", EmploymentEventDtos.PURPOSE,
                        start.atStartOfDay().toInstant(ZoneOffset.UTC)),
                "CONCURRENT_EMPLOYMENT", body);
    }

    private EmploymentEventDtos.AssignmentDraft assignment(LocalDate start, boolean primary) {
        return new EmploymentEventDtos.AssignmentDraft(
                ref("HRM.Organization.v1", ORGANIZATION, 4),
                ref("HRM.Position.v1", POSITION, 2),
                ref("HRM.Job.v1", JOB, 3),
                ref("HRM.WorkLocation.v1", LOCATION, 5),
                start, null, primary, new BigDecimal(primary ? "1.0" : "0.4"));
    }

    private String primaryFte(boolean primary) {
        return primary ? "1.0" : "0.4";
    }

    private EmploymentEventDtos.OwnerRef ref(String schema, UUID id, long revision) {
        return new EmploymentEventDtos.OwnerRef(
                "HRM", schema, id, revision,
                canonicalizer.ownerRefDigest(schema, id, revision), null, null);
    }

    private EmploymentEventRepository.PreflightSnapshot snapshot(
            Long workerId, long workerVersion, boolean overlap) {
        return new EmploymentEventRepository.PreflightSnapshot(
                31L, workerId, workerId == null ? null : WORKER, workerVersion,
                reference(EMPLOYER, 7), reference(ORGANIZATION, 4),
                reference(POSITION, 2), reference(JOB, 3),
                new EmploymentEventRepository.ReferenceRow(
                        15, LOCATION, 5, "SEOUL", "Asia/Seoul", uuid(81)),
                overlap);
    }

    private EmploymentEventRepository.ReferenceRow reference(UUID id, long version) {
        return new EmploymentEventRepository.ReferenceRow(
                version + 100, id, version, "REF-" + version, null, null);
    }

    private EmploymentEventRepository.EventRow event(
            String state,
            long version,
            long maker,
            UUID approvalRequest,
            UUID decision,
            Long decisionRevision,
            String approvalDigest) {
        EmploymentEventDtos.NormalizedCommand command = canonicalizer.normalize(hire(false));
        return new EmploymentEventRepository.EventRow(
                41L, EVENT, TENANT, 31L, null, "HIRE",
                command.relationshipStart(), mapper.convertValue(
                command.context(), new TypeReference<LinkedHashMap<String, Object>>() { }),
                command.payload(), state, impact(), canonicalizer.digest(command.payload()),
                canonicalizer.digest(impact()), 0, version, maker, approvalRequest, decision,
                decisionRevision, approvalDigest, null, null, null,
                NOW, NOW, NOW, "PUBLISHED".equals(state) ? NOW : null, NOW, NOW);
    }

    private String approvalDigestAtSubmit() {
        EmploymentEventRepository.EventRow submitted = event(
                "PENDING_APPROVAL", 2, MAKER, uuid(61), null, null, null);
        return canonicalizer.digest(Map.of(
                "eventPublicId", submitted.publicId(),
                "eventType", submitted.eventType(),
                "eventVersion", 2L,
                "contentDigest", submitted.contentDigest(),
                "impactDigest", submitted.impactDigest(),
                "approvalRequestId", submitted.approvalRequestId()));
    }

    private Map<String, Object> impact() {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("blockingIssues", List.of());
        impact.put("warnings", List.of());
        impact.put("eventType", "HIRE");
        impact.put("effectiveDate", LocalDate.of(2026, 10, 1));
        impact.put("workerVersion", 0L);
        impact.put("legalEmployerRevision", 7L);
        impact.put("organizationRevision", 4L);
        impact.put("positionRevision", 2L);
        impact.put("jobRevision", 3L);
        impact.put("locationRevision", 5L);
        impact.put("primaryOverlap", false);
        return Map.copyOf(impact);
    }

    private EmploymentEventRepository.ReceiptRow receipt(
            String state, String result, String requestDigest) {
        return new EmploymentEventRepository.ReceiptRow(
                uuid(41), TENANT, "HRM_ASSIGNMENT_EVENT_CREATE", KEY, requestDigest,
                ACTOR, "CREATE_ASSIGNMENT_EVENT", ACTOR, "d".repeat(64), 3,
                EmploymentEventDtos.PURPOSE, 5, "e".repeat(64), null, 0L,
                state, state, result, CORRELATION, NOW);
    }

    private HcmPopulationScopeService.ResolvedPopulation population(boolean tenantWide) {
        return new HcmPopulationScopeService.ResolvedPopulation(
                null, new HcmPopulationRepository.PopulationScope(
                0L, null, tenantWide, Set.of(),
                Set.of("DIRECTORY", "EMPLOYMENT"), "policy:hrm-008"),
                new HcmPopulationRepository.PopulationEvidence(2, "population:hrm-008"));
    }

    private HcmV3PepRegistry.RouteAuthority route() {
        return new HcmV3PepRegistry.RouteAuthority(
                EmploymentEventAuthority.CREATE_ROUTE, "ACTION", "full-operations", false,
                Set.of(), Set.of("TARGET_POPULATION"), "binding:hrm-008",
                EmploymentEventAuthority.MANAGE_CAPABILITY, "ACTIVE", "POST",
                "/api/people/v1/hris/assignment-events", null);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }
}

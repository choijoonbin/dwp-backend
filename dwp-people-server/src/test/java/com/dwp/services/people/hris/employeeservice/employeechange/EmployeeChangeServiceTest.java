package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.hris.employeeservice.employeechange.approvalbridge.EmployeeChangeApprovalRequestOutbox;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmployeeChangeServiceTest {

    private static final long TENANT = 77L;
    private static final long USER = 19L;
    private static final UUID ACTOR = uuid(91);
    private static final UUID REQUEST = uuid(21);
    private static final UUID TARGET_PERSON = uuid(31);
    private static final UUID TARGET_AGGREGATE = uuid(41);
    private static final UUID CORRELATION = uuid(51);
    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");
    private static final String KEY = "employee-change-key-0001";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final EmployeeChangeRepository repository = mock(EmployeeChangeRepository.class);
    private final EmployeeChangePatchProtector protector =
            mock(EmployeeChangePatchProtector.class);
    private final EmployeeChangeAuthority authority = mock(EmployeeChangeAuthority.class);
    private final HcmPopulationScopeService populationScopes =
            mock(HcmPopulationScopeService.class);
    private final HcmPopulationRepository populations = mock(HcmPopulationRepository.class);
    private final HcmHighRiskCommandGuard highRisk = mock(HcmHighRiskCommandGuard.class);
    private final EmployeeChangeApprovalRequestOutbox approvalOutbox =
            mock(EmployeeChangeApprovalRequestOutbox.class);
    private final EmployeeChangeCanonicalizer canonicalizer =
            new EmployeeChangeCanonicalizer(mapper);
    private final EmployeeChangeWorkbenchProjector projector =
            new EmployeeChangeWorkbenchProjector(mapper, canonicalizer);
    private final HcmPopulationScopeService.ResolvedPopulation population = population();
    private EmployeeChangeService service;

    @BeforeEach
    void setUp() throws Exception {
        PeopleRequestContext.set(
                USER, TENANT, ACTOR, Set.of("HRIS_OPERATOR"), Set.of());
        setPepContext(new HcmPepContext.Evidence(
                routeAuthority(), "decision:employee-change:31",
                OffsetDateTime.now().plusMinutes(5), "context:31", "scope:31", "111"));
        when(populationScopes.requireOperationsForMutation("READ"))
                .thenReturn(population);
        when(populations.lockWorkerInPopulation(
                eq(TENANT), eq(population.scope()), anyLong())).thenReturn(true);
        service = new EmployeeChangeService(
                repository, canonicalizer, protector, authority, projector,
                populationScopes, populations, highRisk, approvalOutbox, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), "employee-change.standard", 1L);
    }

    @AfterEach
    void tearDown() throws Exception {
        PeopleRequestContext.clear();
        clearPepContext();
    }

    @Test
    void submitEnqueuesApprovalInsideOwnedLifecycle() {
        EmployeeChangeRepository.RequestRow draft = request("DRAFT", ACTOR, 3L, null);
        EmployeeChangeRepository.RequestRow submitted = request(
                "SUBMITTED", ACTOR, 4L, null);
        when(authority.require("SUBMIT")).thenReturn("hcm.operations.people.update");
        when(repository.requestForMutation(TENANT, REQUEST)).thenReturn(Optional.of(draft));
        when(repository.targetForMutation(
                TENANT, TARGET_PERSON, "PERSON", TARGET_AGGREGATE))
                .thenReturn(Optional.of(target()));
        when(repository.insertReceipt(
                eq(TENANT), any(), eq("EMPLOYEE_CHANGE_SUBMIT"), eq(KEY), anyString(),
                eq(ACTOR), eq("SUBMIT_EMPLOYEE_CHANGE_REQUEST"), eq(TARGET_PERSON),
                anyString(), anyLong(), eq("HR_OPERATIONS"), anyLong(), anyString(),
                eq(REQUEST), eq(3L), eq(CORRELATION), eq(NOW)))
                .thenReturn(Optional.of(receipt("RUNNING")));
        when(repository.submit(
                eq(TENANT), eq(draft), any(), eq("employee-change.standard"),
                eq(1L), eq(NOW), eq(USER))).thenReturn(submitted);
        when(repository.completeReceipt(
                eq(TENANT), eq(receipt("RUNNING").receiptId()),
                eq("SUBMITTED"), any(), eq(NOW)))
                .thenReturn(receipt("SUCCEEDED"));

        EmployeeChangeService.CommandResult result = service.submit(
                REQUEST, KEY, "\"v:3\"", CORRELATION, null);

        assertThat(result.entityVersion()).isEqualTo(4L);
        assertThat(result.receipt().status()).isEqualTo("SUCCEEDED");
        verify(repository).submit(
                eq(TENANT), eq(draft), any(), eq("employee-change.standard"),
                eq(1L), eq(NOW), eq(USER));
        verify(approvalOutbox).enqueueSubmitted(TENANT, REQUEST);
        verify(repository).appendAudit(
                eq(TENANT), eq(USER), eq("employee.change.submitted"),
                eq(REQUEST), eq(CORRELATION), any(), eq(NOW));
    }

    @Test
    void workbenchRepositoryFailureProducesValidatedUnavailableProjection() {
        when(authority.require("READ")).thenReturn(EmployeeChangeAuthority.READ_CAPABILITY);
        when(populationScopes.requireOperations("READ")).thenReturn(population);
        when(repository.workbench(TENANT, NOW, population.scope()))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        EmployeeChangeDtos.WorkbenchQuery query = new EmployeeChangeDtos.WorkbenchQuery(
                25, EmployeeChangeDtos.WorkbenchFilter.empty(),
                List.of("itemPublicId:ASC"), "ko-KR", "Asia/Seoul",
                CORRELATION, NOW, null);

        EmployeeChangeService.WorkbenchResult result = service.workbench(query);

        assertThat(result.projection().queryState().state()).isEqualTo("UNAVAILABLE");
        assertThat(result.projection().queryState().freshness()).isEqualTo("UNAVAILABLE");
        assertThat(result.projection().items()).isEmpty();
        assertThat(result.projection().queryState().partialFailures()).singleElement()
                .extracting(EmployeeChangeDtos.PartialFailure::errorCode)
                .isEqualTo("EMPLOYEE_CHANGE_QUERY_UNAVAILABLE");
    }

    @Test
    void submitReplayReturnsOriginalReceiptBeforeLifecycleChecksAndRejectsDrift()
            throws Exception {
        EmployeeChangeRepository.RequestRow submitted = request(
                "SUBMITTED", ACTOR, 4L, null);
        AtomicReference<EmployeeChangeRepository.ReceiptRow> original =
                new AtomicReference<>();
        when(authority.require("SUBMIT")).thenReturn("hcm.operations.people.update");
        when(repository.requestForMutation(TENANT, REQUEST))
                .thenReturn(Optional.of(submitted));
        when(repository.targetForMutation(
                TENANT, TARGET_PERSON, "PERSON", TARGET_AGGREGATE))
                .thenReturn(Optional.of(target()));
        when(repository.insertReceipt(
                anyLong(), any(), anyString(), anyString(), anyString(), any(),
                anyString(), any(), anyString(), anyLong(), anyString(), anyLong(),
                anyString(), any(), anyLong(), any(), any()))
                .thenAnswer(invocation -> {
                    original.compareAndSet(null, new EmployeeChangeRepository.ReceiptRow(
                            invocation.getArgument(1), invocation.getArgument(0),
                            invocation.getArgument(2), invocation.getArgument(3),
                            invocation.getArgument(4), invocation.getArgument(5),
                            invocation.getArgument(6), invocation.getArgument(7),
                            invocation.getArgument(8), invocation.getArgument(9),
                            invocation.getArgument(10), invocation.getArgument(11),
                            invocation.getArgument(12), invocation.getArgument(13),
                            invocation.getArgument(14), "SUCCEEDED", "SUBMITTED",
                            "{\"requestPublicId\":\"" + REQUEST
                                    + "\",\"requestVersion\":4}",
                            invocation.getArgument(15), NOW));
                    return Optional.empty();
                });
        when(repository.receipt(
                anyLong(), any(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(ignored -> Optional.ofNullable(original.get()));

        EmployeeChangeService.CommandResult replay = service.submit(
                REQUEST, KEY, "\"v:3\"", CORRELATION, null);

        assertThat(replay.receipt().receiptId()).isEqualTo(original.get().receiptId());
        assertThat(replay.receipt().status()).isEqualTo("SUCCEEDED");
        assertThat(replay.entityVersion()).isEqualTo(4L);
        setPepContext(new HcmPepContext.Evidence(
                routeAuthority(), "decision:employee-change:32",
                OffsetDateTime.now().plusMinutes(5), "context:32", "scope:31", "111"));
        EmployeeChangeService.CommandResult reauthorizedReplay = service.submit(
                REQUEST, KEY, "\"v:3\"", CORRELATION, null);
        assertThat(reauthorizedReplay.receipt().receiptId())
                .isEqualTo(original.get().receiptId());
        assertThat(reauthorizedReplay.entityVersion()).isEqualTo(4L);
        assertThatThrownBy(() -> service.submit(
                REQUEST, KEY, "\"v:4\"", CORRELATION, null))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        verify(repository, never()).submit(
                anyLong(), any(), any(), anyString(), anyLong(), any(), anyLong());
        verify(approvalOutbox, never()).enqueueSubmitted(anyLong(), any());
    }

    @Test
    void createReplayAtTargetVersionZeroReturnsOriginalCanonicalVersionOne() {
        EmployeeChangeDtos.CreateRequest request = createRequest();
        UUID requestPublicId = UUID.nameUUIDFromBytes((
                "employee-change-request\n" + TENANT + '\n' + TARGET_PERSON + '\n'
                        + "CREATE_EMPLOYEE_CHANGE_REQUEST\n" + KEY)
                .getBytes(StandardCharsets.UTF_8));
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("method", "POST");
        binding.put("path", "/api/people/v1/hris/employee-change-requests");
        binding.put("requestPublicId", requestPublicId);
        binding.put("ifMatch", 0L);
        binding.put("idempotencyKey", KEY);
        binding.put("correlationId", CORRELATION);
        binding.put("purposeCode", "HR_OPERATIONS");
        binding.put("body", request);
        String digest = canonicalizer.requestDigest(binding);
        EmployeeChangeRepository.ReceiptRow original = replayReceipt(
                "EMPLOYEE_CHANGE_CREATE", "CREATE_EMPLOYEE_CHANGE_REQUEST",
                TARGET_PERSON, requestPublicId, 0L, "HR_OPERATIONS", digest, 1L);
        when(authority.require("CREATE")).thenReturn("hcm.operations.people.update");
        when(repository.receiptForReconciliation(
                TENANT, ACTOR, "CREATE_EMPLOYEE_CHANGE_REQUEST", KEY,
                "EMPLOYEE_CHANGE_CREATE", requestPublicId, CORRELATION))
                .thenReturn(Optional.of(original));

        EmployeeChangeService.CommandResult replay = service.create(
                request, KEY, "\"v:0\"", CORRELATION, null);

        assertThat(replay.entityVersion()).isEqualTo(1L);
        assertThat(replay.receipt().receiptId()).isEqualTo(original.receiptId());
        verify(repository, never()).targetForMutation(anyLong(), any(), anyString(), any());
        verify(repository, never()).insertReceipt(
                anyLong(), any(), anyString(), anyString(), anyString(), any(),
                anyString(), any(), anyString(), anyLong(), anyString(), anyLong(),
                anyString(), any(), anyLong(), any(), any());

        assertThatThrownBy(() -> service.create(
                request, KEY, "\"v:1\"", CORRELATION, null))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    }

    @Test
    void lifecycleCommandsRejectZeroRequestRevisionBeforeReconciliation() {
        when(authority.require("SUBMIT")).thenReturn("hcm.operations.people.update");
        when(authority.require("APPLY"))
                .thenReturn(EmployeeChangeAuthority.APPLY_CAPABILITY);

        assertThatThrownBy(() -> service.submit(
                REQUEST, KEY, "\"v:0\"", CORRELATION, null))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        assertThatThrownBy(() -> service.apply(
                REQUEST, KEY, "\"v:0\"", CORRELATION, null,
                new HcmStepUpHeaders(
                        "challenge", KEY, "decision:employee-change:31", 0L)))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        verify(repository, never()).receiptForReconciliation(
                anyLong(), any(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void submitReconcilesOriginalReceiptBeforeReloadingChangedLifecycle() {
        String path = "/api/people/v1/hris/employee-change-requests/" + REQUEST + "/submit";
        String digest = lifecycleDigest(path, 3L, "HR_OPERATIONS");
        EmployeeChangeRepository.ReceiptRow original = replayReceipt(
                "EMPLOYEE_CHANGE_SUBMIT", "SUBMIT_EMPLOYEE_CHANGE_REQUEST",
                TARGET_PERSON, REQUEST, 3L, "HR_OPERATIONS", digest, 4L);
        when(authority.require("SUBMIT")).thenReturn("hcm.operations.people.update");
        when(repository.receiptForReconciliation(
                TENANT, ACTOR, "SUBMIT_EMPLOYEE_CHANGE_REQUEST", KEY,
                "EMPLOYEE_CHANGE_SUBMIT", REQUEST, CORRELATION))
                .thenReturn(Optional.of(original));

        EmployeeChangeService.CommandResult replay = service.submit(
                REQUEST, KEY, "\"v:3\"", CORRELATION, null);

        assertThat(replay.receipt().receiptId()).isEqualTo(original.receiptId());
        assertThat(replay.entityVersion()).isEqualTo(4L);
        verify(repository, never()).requestForMutation(anyLong(), any());
        verify(approvalOutbox, never()).enqueueSubmitted(anyLong(), any());
    }

    @Test
    void applyReplayDoesNotReconsumeStepUpAndDigestDriftConflicts() {
        String path = "/api/people/v1/hris/employee-change-requests/" + REQUEST + "/apply";
        String digest = lifecycleDigest(path, 5L, "HR_LIFECYCLE");
        EmployeeChangeRepository.ReceiptRow original = replayReceipt(
                "EMPLOYEE_CHANGE_APPLY", "APPLY_EMPLOYEE_CHANGE_REQUEST",
                TARGET_PERSON, REQUEST, 5L, "HR_LIFECYCLE", digest, 7L);
        when(authority.require("APPLY"))
                .thenReturn(EmployeeChangeAuthority.APPLY_CAPABILITY);
        when(repository.receiptForReconciliation(
                TENANT, ACTOR, "APPLY_EMPLOYEE_CHANGE_REQUEST", KEY,
                "EMPLOYEE_CHANGE_APPLY", REQUEST, CORRELATION))
                .thenReturn(Optional.of(original));
        HcmStepUpHeaders consumedEvidence = new HcmStepUpHeaders(
                "already-consumed", KEY, "decision:employee-change:31", 5L);

        EmployeeChangeService.CommandResult replay = service.apply(
                REQUEST, KEY, "\"v:5\"", CORRELATION, null, consumedEvidence);

        assertThat(replay.receipt().receiptId()).isEqualTo(original.receiptId());
        assertThat(replay.entityVersion()).isEqualTo(7L);
        verify(highRisk, never()).requireExact(
                anyString(), anyString(), anyString(), anyLong(), anyString(), any(), any());
        verify(repository, never()).requestForMutation(anyLong(), any());

        assertThatThrownBy(() -> service.apply(
                REQUEST, KEY, "\"v:4\"", CORRELATION, null,
                new HcmStepUpHeaders(
                        "already-consumed", KEY, "decision:employee-change:31", 4L)))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        verify(highRisk, never()).requireExact(
                anyString(), anyString(), anyString(), anyLong(), anyString(), any(), any());
    }

    @Test
    void applyLateClaimRaceReturnsOriginalReceiptWithoutConsumingStepUp() {
        UUID requester = uuid(72);
        EmployeeChangeRepository.RequestRow approved = request(
                "APPROVED", requester, 5L, null);
        String path = "/api/people/v1/hris/employee-change-requests/" + REQUEST + "/apply";
        EmployeeChangeRepository.ReceiptRow original = replayReceipt(
                "EMPLOYEE_CHANGE_APPLY", "APPLY_EMPLOYEE_CHANGE_REQUEST",
                TARGET_PERSON, REQUEST, 5L, "HR_LIFECYCLE",
                lifecycleDigest(path, 5L, "HR_LIFECYCLE"), 7L);
        when(authority.require("APPLY"))
                .thenReturn(EmployeeChangeAuthority.APPLY_CAPABILITY);
        when(repository.requestForMutation(TENANT, REQUEST))
                .thenReturn(Optional.of(approved));
        when(repository.targetForMutation(
                TENANT, TARGET_PERSON, "PERSON", TARGET_AGGREGATE))
                .thenReturn(Optional.of(target()));
        when(repository.insertReceipt(
                anyLong(), any(), anyString(), anyString(), anyString(), any(),
                anyString(), any(), anyString(), anyLong(), anyString(), anyLong(),
                anyString(), any(), anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.receipt(
                TENANT, TARGET_PERSON, "APPLY_EMPLOYEE_CHANGE_REQUEST", KEY,
                "EMPLOYEE_CHANGE_APPLY", CORRELATION))
                .thenReturn(Optional.of(original));

        EmployeeChangeService.CommandResult replay = service.apply(
                REQUEST, KEY, "\"v:5\"", CORRELATION, null,
                new HcmStepUpHeaders(
                        "already-consumed", KEY, "decision:employee-change:31", 5L));

        assertThat(replay.receipt().receiptId()).isEqualTo(original.receiptId());
        assertThat(replay.entityVersion()).isEqualTo(7L);
        verify(highRisk, never()).requireExact(
                anyString(), anyString(), anyString(), anyLong(), anyString(), any(), any());
        verify(repository, never()).beginApply(anyLong(), any(), any(), anyLong());
    }

    @Test
    void applyRejectsRequesterAsApproverBeforeMutation() {
        when(authority.require("APPLY"))
                .thenReturn(EmployeeChangeAuthority.APPLY_CAPABILITY);
        when(repository.requestForMutation(TENANT, REQUEST))
                .thenReturn(Optional.of(request("APPROVED", ACTOR, 5L, null)));

        assertThatThrownBy(() -> service.apply(
                REQUEST, KEY, "\"v:5\"", CORRELATION, null,
                new HcmStepUpHeaders("challenge", KEY, "decision:employee-change:31", 5L)))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.SOD_CONFLICT);

        verify(repository, never()).beginApply(anyLong(), any(), any(), anyLong());
        verify(repository, never()).appendEvent(
                anyLong(), any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void applyMutatesTargetAndEmitsEmployeeChangeAppliedV1() throws Exception {
        UUID requester = uuid(72);
        EmployeeChangeCanonicalizer.CanonicalPatch patch = canonicalizer.canonicalPatch(
                createRequest());
        EmployeeChangeRepository.RequestRow approved = request(
                "APPROVED", requester, 5L, null, patch.digest());
        EmployeeChangeRepository.RequestRow applying = request(
                "APPLYING", requester, 6L, null, patch.digest());
        EmployeeChangeRepository.RequestRow applied = request(
                "APPLIED", requester, 7L, 8L, patch.digest());
        when(authority.require("APPLY"))
                .thenReturn(EmployeeChangeAuthority.APPLY_CAPABILITY);
        when(repository.requestForMutation(TENANT, REQUEST)).thenReturn(Optional.of(approved));
        when(repository.targetForMutation(
                TENANT, TARGET_PERSON, "PERSON", TARGET_AGGREGATE))
                .thenReturn(Optional.of(target()));
        when(repository.insertReceipt(
                eq(TENANT), any(), eq("EMPLOYEE_CHANGE_APPLY"), eq(KEY), anyString(),
                eq(ACTOR), eq("APPLY_EMPLOYEE_CHANGE_REQUEST"), eq(TARGET_PERSON),
                anyString(), anyLong(), eq("HR_LIFECYCLE"), anyLong(), anyString(),
                eq(REQUEST), eq(5L), eq(CORRELATION), eq(NOW)))
                .thenReturn(Optional.of(receipt("RUNNING")));
        when(protector.reveal(TENANT, REQUEST, approved.encryptedPatch()))
                .thenReturn(patch.bytes());
        when(repository.beginApply(TENANT, approved, NOW, USER)).thenReturn(applying);
        when(repository.applyTarget(TENANT, applying, patch.operations(), USER))
                .thenReturn(8L);
        when(repository.markApplied(TENANT, applying, 8L, NOW, USER)).thenReturn(applied);
        when(repository.completeReceipt(
                eq(TENANT), eq(receipt("RUNNING").receiptId()),
                eq("APPLIED"), any(), eq(NOW)))
                .thenReturn(receipt("SUCCEEDED"));

        EmployeeChangeService.CommandResult result = service.apply(
                REQUEST, KEY, "\"v:5\"", CORRELATION, null,
                new HcmStepUpHeaders("challenge", KEY, "decision:employee-change:31", 5L));

        assertThat(result.entityVersion()).isEqualTo(7L);
        verify(highRisk).requireExact(
                eq(EmployeeChangeAuthority.APPLY_CAPABILITY),
                eq("EMPLOYEE_CHANGE_REQUEST"), eq(REQUEST.toString()), eq(5L),
                eq("/api/people/v1/hris/employee-change-requests/" + REQUEST + "/apply"),
                any(), any(HcmStepUpHeaders.class));
        ArgumentCaptor<String> eventPayload = ArgumentCaptor.forClass(String.class);
        verify(repository).appendEvent(
                eq(TENANT), any(), eq(REQUEST), eq("EmployeeChangeApplied.v1"),
                eventPayload.capture(), eq(CORRELATION), eq(NOW));
        JsonNode event = mapper.readTree(eventPayload.getValue());
        assertThat(event.path("requestPublicId").asText()).isEqualTo(REQUEST.toString());
        assertThat(event.path("targetAggregatePublicId").asText())
                .isEqualTo(TARGET_AGGREGATE.toString());
        assertThat(event.path("appliedTargetVersion").asLong()).isEqualTo(8L);
    }

    private EmployeeChangeDtos.CreateRequest createRequest() {
        return new EmployeeChangeDtos.CreateRequest(
                TARGET_PERSON, "PERSON", TARGET_AGGREGATE,
                "PROFILE_UPDATE", LocalDate.of(2026, 10, 12),
                "employee-change.person.v1", 1,
                List.of(new EmployeeChangeDtos.PatchOperation(
                        "replace", "/displayName", "Kim Minsoo")), List.of());
    }

    private EmployeeChangeRepository.TargetRow target() {
        return new EmployeeChangeRepository.TargetRow(
                31L, 101L, TARGET_PERSON, "PERSON", TARGET_AGGREGATE,
                7L, "Employee 31", LocalDate.of(2020, 1, 1), null);
    }

    private EmployeeChangeRepository.RequestRow request(
            String state,
            UUID requester,
            long version,
            Long appliedTargetVersion) {
        String digest = canonicalizer.canonicalPatch(createRequest()).digest();
        return request(state, requester, version, appliedTargetVersion, digest);
    }

    private EmployeeChangeRepository.RequestRow request(
            String state,
            UUID requester,
            long version,
            Long appliedTargetVersion,
            String patchHash) {
        boolean submitted = !"DRAFT".equals(state);
        return new EmployeeChangeRepository.RequestRow(
                21L, REQUEST, 71L, requester, 31L, TARGET_PERSON, 101L,
                "Employee 31", "PERSON", TARGET_AGGREGATE, "PROFILE_UPDATE",
                LocalDate.of(2026, 10, 12), state, 7L,
                "employee-change.person.v1", 1, new byte[] {1, 2, 3},
                patchHash, "test-key", List.of("PERSON_NAME"),
                submitted ? uuid(61) : null,
                submitted ? "employee-change.standard" : null,
                submitted ? 1L : null,
                submitted ? 4L : null,
                "APPROVED".equals(state) || "APPLYING".equals(state)
                        || "APPLIED".equals(state) ? 31L : null,
                "APPROVED".equals(state) || "APPLYING".equals(state)
                        || "APPLIED".equals(state) ? uuid(62) : null,
                "a".repeat(64), 11L, 13L, "b".repeat(64),
                appliedTargetVersion, CORRELATION, version, NOW.minusSeconds(60), USER);
    }

    private EmployeeChangeRepository.ReceiptRow receipt(String state) {
        return new EmployeeChangeRepository.ReceiptRow(
                uuid(81), TENANT, "EMPLOYEE_CHANGE_COMMAND", KEY,
                "c".repeat(64), ACTOR, "EMPLOYEE_CHANGE_ACTION", TARGET_PERSON,
                "d".repeat(64), 11L, "HR_OPERATIONS", 13L, "e".repeat(64),
                REQUEST, 5L, state, null, null, CORRELATION, NOW);
    }

    private EmployeeChangeRepository.ReceiptRow replayReceipt(
            String commandType,
            String action,
            UUID subject,
            UUID target,
            long expectedVersion,
            String purpose,
            String digest,
            long resultVersion) {
        return new EmployeeChangeRepository.ReceiptRow(
                uuid(82), TENANT, commandType, KEY, digest, ACTOR, action, subject,
                "d".repeat(64), 11L, purpose, 13L, "e".repeat(64),
                target, expectedVersion, "SUCCEEDED", "COMPLETED",
                "{\"requestPublicId\":\"" + target + "\",\"requestVersion\":"
                        + resultVersion + "}",
                CORRELATION, NOW);
    }

    private String lifecycleDigest(String path, long expectedVersion, String purpose) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("method", "POST");
        binding.put("path", path);
        binding.put("requestPublicId", REQUEST);
        binding.put("ifMatch", expectedVersion);
        binding.put("idempotencyKey", KEY);
        binding.put("correlationId", CORRELATION);
        binding.put("purposeCode", purpose);
        binding.put("body", Map.of());
        return canonicalizer.requestDigest(binding);
    }

    private HcmPopulationScopeService.ResolvedPopulation population() {
        return new HcmPopulationScopeService.ResolvedPopulation(
                null,
                new HcmPopulationRepository.PopulationScope(
                        99L, null, true, Set.of(),
                        Set.of("DIRECTORY", "EMPLOYMENT"), "policy:31"),
                new HcmPopulationRepository.PopulationEvidence(2L, "population:31"));
    }

    private HcmV3PepRegistry.RouteAuthority routeAuthority() {
        return new HcmV3PepRegistry.RouteAuthority(
                "route.hcm.operations.employee-change-request-apply.action",
                "ACTION", "full-management", false, Set.of(), Set.of(),
                "binding:31", EmployeeChangeAuthority.APPLY_CAPABILITY,
                "STEPUP-MGMT-HIGH-V1", "POST",
                "/api/people/v1/hris/employee-change-requests/{requestPublicId}/apply",
                null, null, null);
    }

    private void setPepContext(HcmPepContext.Evidence evidence) throws Exception {
        Method set = HcmPepContext.class.getDeclaredMethod(
                "set", HcmPepContext.Evidence.class);
        set.setAccessible(true);
        set.invoke(null, evidence);
    }

    private void clearPepContext() throws Exception {
        Method clear = HcmPepContext.class.getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(null);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }
}

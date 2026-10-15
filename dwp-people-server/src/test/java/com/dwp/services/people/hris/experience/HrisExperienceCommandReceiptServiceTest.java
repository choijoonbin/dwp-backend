package com.dwp.services.people.hris.experience;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HrisExperienceCommandReceiptServiceTest {

    private static final long TENANT = 77L;
    private static final UUID ACTOR = UUID.fromString(
            "00000000-0000-4000-8000-000000000077");
    private static final UUID TARGET = UUID.fromString(
            "00000000-0000-4000-8000-000000000078");
    private static final UUID ORGANIZATION = UUID.fromString(
            "00000000-0000-4000-8000-000000000079");
    private static final String REVISION = "population:7:policy";

    private final HrisExperienceCommandReceiptRepository repository =
            mock(HrisExperienceCommandReceiptRepository.class);
    private final HrisExperienceReceiptAuthority authority =
            mock(HrisExperienceReceiptAuthority.class);
    private final HcmPopulationRepository populations = mock(HcmPopulationRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HrisExperienceCommandReceiptService service =
            new HrisExperienceCommandReceiptService(
                    repository, authority, populations, mapper);

    @BeforeEach
    void bindTenant() {
        PeopleRequestContext.set(19L, TENANT, ACTOR, Set.of(), Set.of());
    }

    @AfterEach
    void clearTenant() {
        PeopleRequestContext.clear();
    }

    @Test
    void registryIsSealedToFourAssignmentAndThreeEmployeeTuples() {
        assertThat(HrisExperienceCommandReceiptService.sealedTupleCount()).isEqualTo(7);
    }

    @Test
    void projectsClosedEmployeeResultAndLiteralLocation() {
        var current = operations(Set.of("DIRECTORY", "EMPLOYMENT"), true);
        var row = employeeRow("EMPLOYEE_CHANGE_CREATE",
                "CREATE_EMPLOYEE_CHANGE_REQUEST", "HR_OPERATIONS", "SUCCEEDED",
                "CREATED", "{\"requestPublicId\":\"" + TARGET
                        + "\",\"requestVersion\":1}", TARGET,
                HrisExperienceDigests.sha256(REVISION),
                HrisExperienceDigests.employeeChangeRevision(
                        REVISION + '|' + current.fieldGroups().stream().sorted().toList()));
        when(repository.find(TENANT, row.receiptId())).thenReturn(Optional.of(row));
        when(authority.require(any(), any())).thenReturn(current);
        when(repository.employeeChangeTarget(TENANT, TARGET)).thenReturn(Optional.of(
                new HrisExperienceCommandReceiptRepository.TargetEvidence(
                        TARGET, 91L, ORGANIZATION, 2L, 0L, null, null,
                        java.util.List.of("PERSON_NAME"), null)));

        var result = service.read(row.receiptId());

        assertThat(result.receipt().status()).isEqualTo("SUCCEEDED");
        assertThat(result.resultLocation().toString()).isEqualTo(
                "/hr/operations/people/employee-change-requests?requestPublicId=" + TARGET);
        assertThat(mapper.valueToTree(result.receipt()).size()).isEqualTo(12);

        when(repository.employeeChangeTarget(TENANT, TARGET)).thenReturn(Optional.of(
                new HrisExperienceCommandReceiptRepository.TargetEvidence(
                        TARGET, 91L, ORGANIZATION, 2L, 1L, null, null,
                        java.util.List.of("PERSON_NAME"), null)));
        assertOpaqueNotFound(row.receiptId());
    }

    @Test
    void admitsPrePublishHireWithoutWorkerOnlyInsideCurrentOrganizationScope() {
        var current = operations(Set.of("EMPLOYMENT"), false);
        long fieldRevision = HrisExperienceDigests.assignmentRevision(
                REVISION + '|' + current.fieldGroups().stream().sorted().toList());
        var row = assignmentRow("HRM_ASSIGNMENT_EVENT_VALIDATE",
                "VALIDATE_ASSIGNMENT_EVENT", TARGET, "VALIDATED", fieldRevision,
                "{\"eventPublicId\":\"" + TARGET
                        + "\",\"lifecycleState\":\"VALIDATED\",\"eventVersion\":2}");
        when(repository.find(TENANT, row.receiptId())).thenReturn(Optional.of(row));
        when(authority.require(any(), any())).thenReturn(current);
        when(repository.assignmentTarget(TENANT, TARGET)).thenReturn(Optional.of(
                new HrisExperienceCommandReceiptRepository.TargetEvidence(
                        TARGET, null, ORGANIZATION, 2L, 7L, null, null,
                        java.util.List.of("EMPLOYMENT"), "HIRE")));

        assertThat(service.read(row.receiptId()).resultLocation().toString())
                .isEqualTo("/api/people/v1/hris/assignment-events/" + TARGET);
        verify(populations, never()).containsWorker(anyLong(), any(), anyLong());
    }

    @Test
    void assignmentCreateAllowsVersionZeroAndBindsExpectedOwnerRevision() {
        var current = operations(Set.of("EMPLOYMENT"), true);
        long fieldRevision = HrisExperienceDigests.assignmentRevision(
                REVISION + '|' + current.fieldGroups().stream().sorted().toList());
        String result = "{\"eventPublicId\":\"" + TARGET
                + "\",\"lifecycleState\":\"DRAFT\",\"eventVersion\":0}";
        var accepted = assignmentRow("HRM_ASSIGNMENT_EVENT_CREATE",
                "CREATE_ASSIGNMENT_EVENT", null, "DRAFT", fieldRevision,
                result, 8L);
        when(repository.find(TENANT, accepted.receiptId())).thenReturn(Optional.of(accepted));
        when(authority.require(any(), any())).thenReturn(current);
        when(repository.assignmentTarget(TENANT, TARGET)).thenReturn(Optional.of(
                new HrisExperienceCommandReceiptRepository.TargetEvidence(
                        TARGET, null, ORGANIZATION, 0L, 8L, null, null,
                        java.util.List.of("EMPLOYMENT"), "HIRE")));

        assertThat(service.read(accepted.receiptId()).resultLocation().toString())
                .isEqualTo("/api/people/v1/hris/assignment-events/" + TARGET);

        var staleOwner = assignmentRow("HRM_ASSIGNMENT_EVENT_CREATE",
                "CREATE_ASSIGNMENT_EVENT", null, "DRAFT", fieldRevision,
                result, 7L);
        when(repository.find(TENANT, staleOwner.receiptId()))
                .thenReturn(Optional.of(staleOwner));
        assertOpaqueNotFound(staleOwner.receiptId());
    }

    @Test
    void assignmentTransitionsBindExpectedPlusOneAndAllowLaterCurrentRevision() {
        var current = operations(Set.of("EMPLOYMENT"), true);
        long fieldRevision = HrisExperienceDigests.assignmentRevision(
                REVISION + '|' + current.fieldGroups().stream().sorted().toList());
        var accepted = assignmentRow("HRM_ASSIGNMENT_EVENT_PUBLISH",
                "PUBLISH_ASSIGNMENT_EVENT", TARGET, "PUBLISHED", fieldRevision,
                "{\"eventPublicId\":\"" + TARGET
                        + "\",\"lifecycleState\":\"PUBLISHED\",\"eventVersion\":4}",
                3L);
        when(repository.find(TENANT, accepted.receiptId())).thenReturn(Optional.of(accepted));
        when(authority.require(any(), any())).thenReturn(current);
        when(repository.assignmentTarget(TENANT, TARGET)).thenReturn(Optional.of(
                new HrisExperienceCommandReceiptRepository.TargetEvidence(
                        TARGET, 91L, ORGANIZATION, 6L, 8L, null, null,
                        java.util.List.of("EMPLOYMENT"), "HIRE")));

        assertThat(service.read(accepted.receiptId()).receipt().status())
                .isEqualTo("SUCCEEDED");

        var drifted = assignmentRow("HRM_ASSIGNMENT_EVENT_PUBLISH",
                "PUBLISH_ASSIGNMENT_EVENT", TARGET, "PUBLISHED", fieldRevision,
                "{\"eventPublicId\":\"" + TARGET
                        + "\",\"lifecycleState\":\"PUBLISHED\",\"eventVersion\":5}",
                3L);
        when(repository.find(TENANT, drifted.receiptId())).thenReturn(Optional.of(drifted));
        assertOpaqueNotFound(drifted.receiptId());
    }

    @Test
    void employeeSubmitBindsIncrementAndDurableApprovalRequest() {
        var current = operations(Set.of("DIRECTORY", "EMPLOYMENT"), true);
        UUID approval = UUID.fromString("00000000-0000-4000-8000-000000000081");
        long fields = HrisExperienceDigests.employeeChangeRevision(
                REVISION + '|' + current.fieldGroups().stream().sorted().toList());
        var accepted = employeeRow("EMPLOYEE_CHANGE_SUBMIT",
                "SUBMIT_EMPLOYEE_CHANGE_REQUEST", "HR_OPERATIONS", "SUCCEEDED",
                "SUBMITTED", "{\"requestPublicId\":\"" + TARGET
                        + "\",\"requestVersion\":4,\"approvalRequestId\":\""
                        + approval + "\"}", TARGET, HrisExperienceDigests.sha256(REVISION),
                fields, 3L);
        when(repository.find(TENANT, accepted.receiptId())).thenReturn(Optional.of(accepted));
        when(authority.require(any(), any())).thenReturn(current);
        when(repository.employeeChangeTarget(TENANT, TARGET)).thenReturn(Optional.of(
                new HrisExperienceCommandReceiptRepository.TargetEvidence(
                        TARGET, 91L, ORGANIZATION, 6L, 11L, approval, null,
                        java.util.List.of("PERSON_NAME"), null)));

        assertThat(service.read(accepted.receiptId()).receipt().status())
                .isEqualTo("SUCCEEDED");

        var mismatched = new HrisExperienceCommandReceiptRepository.TargetEvidence(
                TARGET, 91L, ORGANIZATION, 6L, 11L, UUID.randomUUID(), null,
                java.util.List.of("PERSON_NAME"), null);
        when(repository.employeeChangeTarget(TENANT, TARGET))
                .thenReturn(Optional.of(mismatched));
        assertOpaqueNotFound(accepted.receiptId());

        var revisionDrift = employeeRow("EMPLOYEE_CHANGE_SUBMIT",
                "SUBMIT_EMPLOYEE_CHANGE_REQUEST", "HR_OPERATIONS", "SUCCEEDED",
                "SUBMITTED", "{\"requestPublicId\":\"" + TARGET
                        + "\",\"requestVersion\":5,\"approvalRequestId\":\""
                        + approval + "\"}", TARGET, HrisExperienceDigests.sha256(REVISION),
                fields, 3L);
        when(repository.find(TENANT, revisionDrift.receiptId()))
                .thenReturn(Optional.of(revisionDrift));
        when(repository.employeeChangeTarget(TENANT, TARGET)).thenReturn(Optional.of(
                new HrisExperienceCommandReceiptRepository.TargetEvidence(
                        TARGET, 91L, ORGANIZATION, 6L, 11L, approval, null,
                        java.util.List.of("PERSON_NAME"), null)));
        assertOpaqueNotFound(revisionDrift.receiptId());
    }

    @Test
    void employeeApplyBindsIncrementAppliedVersionAndDurableOutboxFact() {
        var current = operations(Set.of("DIRECTORY", "EMPLOYMENT"), true);
        UUID eventId = UUID.fromString("00000000-0000-4000-8000-000000000082");
        long fields = HrisExperienceDigests.employeeChangeRevision(
                REVISION + '|' + current.fieldGroups().stream().sorted().toList());
        var row = employeeRow("EMPLOYEE_CHANGE_APPLY",
                "APPLY_EMPLOYEE_CHANGE_REQUEST", "HR_LIFECYCLE", "SUCCEEDED",
                "APPLIED", "{\"requestPublicId\":\"" + TARGET
                        + "\",\"requestVersion\":6,\"appliedTargetVersion\":8,"
                        + "\"eventId\":\"" + eventId + "\"}", TARGET,
                HrisExperienceDigests.sha256(REVISION), fields, 4L);
        when(repository.find(TENANT, row.receiptId())).thenReturn(Optional.of(row));
        when(authority.require(any(), any())).thenReturn(current);
        var durableTarget = new HrisExperienceCommandReceiptRepository.TargetEvidence(
                TARGET, 91L, ORGANIZATION, 7L, 11L, UUID.randomUUID(), 8L,
                java.util.List.of("PERSON_NAME"), null);
        when(repository.employeeChangeTarget(TENANT, TARGET))
                .thenReturn(Optional.of(durableTarget));
        when(repository.exactAppliedOrigin(
                TENANT, eventId, TARGET, row.receiptId(), row.correlationId(), 6L, 8L))
                .thenReturn(true);

        assertThat(service.read(row.receiptId()).receipt().status()).isEqualTo("SUCCEEDED");

        when(repository.employeeChangeTarget(TENANT, TARGET)).thenReturn(Optional.of(
                new HrisExperienceCommandReceiptRepository.TargetEvidence(
                        TARGET, 91L, ORGANIZATION, 7L, 11L, UUID.randomUUID(), 9L,
                        java.util.List.of("PERSON_NAME"), null)));
        assertOpaqueNotFound(row.receiptId());

        when(repository.employeeChangeTarget(TENANT, TARGET))
                .thenReturn(Optional.of(durableTarget));
        when(repository.exactAppliedOrigin(
                TENANT, eventId, TARGET, row.receiptId(), row.correlationId(), 6L, 8L))
                .thenReturn(false);
        assertOpaqueNotFound(row.receiptId());
    }

    @Test
    void resultProjectionRejectsAdditionalKeysAndTargetMismatchOpaque() {
        var current = operations(Set.of("DIRECTORY", "EMPLOYMENT"), true);
        String population = HrisExperienceDigests.sha256(REVISION);
        long fields = HrisExperienceDigests.employeeChangeRevision(
                REVISION + '|' + current.fieldGroups().stream().sorted().toList());
        var extra = employeeRow("EMPLOYEE_CHANGE_APPLY",
                "APPLY_EMPLOYEE_CHANGE_REQUEST", "HR_LIFECYCLE", "SUCCEEDED",
                "APPLIED", "{\"requestPublicId\":\"" + TARGET
                        + "\",\"requestVersion\":4,\"appliedTargetVersion\":8,"
                        + "\"eventId\":\"00000000-0000-4000-8000-000000000080\","
                        + "\"raw\":true}", TARGET, population, fields);
        when(repository.find(TENANT, extra.receiptId())).thenReturn(Optional.of(extra));
        when(authority.require(any(), any())).thenReturn(current);

        assertOpaqueNotFound(extra.receiptId());
    }

    @Test
    void currentPopulationAndFieldDriftAreIndependentlyOpaque() {
        var current = operations(Set.of("DIRECTORY", "EMPLOYMENT"), true);
        String currentPopulation = HrisExperienceDigests.sha256(REVISION);
        long currentFields = HrisExperienceDigests.employeeChangeRevision(
                REVISION + '|' + current.fieldGroups().stream().sorted().toList());
        var populationDrift = employeeRow("EMPLOYEE_CHANGE_CREATE",
                "CREATE_EMPLOYEE_CHANGE_REQUEST", "HR_OPERATIONS", "SUCCEEDED",
                "CREATED", "{\"requestPublicId\":\"" + TARGET
                        + "\",\"requestVersion\":1}", TARGET,
                "f".repeat(64), currentFields);
        var fieldDrift = employeeRow("EMPLOYEE_CHANGE_CREATE",
                "CREATE_EMPLOYEE_CHANGE_REQUEST", "HR_OPERATIONS", "SUCCEEDED",
                "CREATED", "{\"requestPublicId\":\"" + TARGET
                        + "\",\"requestVersion\":1}", TARGET,
                currentPopulation, currentFields + 1);
        when(repository.find(TENANT, populationDrift.receiptId()))
                .thenReturn(Optional.of(populationDrift));
        when(repository.find(TENANT, fieldDrift.receiptId()))
                .thenReturn(Optional.of(fieldDrift));
        when(authority.require(any(), any())).thenReturn(current);
        when(repository.employeeChangeTarget(TENANT, TARGET)).thenReturn(Optional.of(
                new HrisExperienceCommandReceiptRepository.TargetEvidence(
                        TARGET, 91L, ORGANIZATION, 1L, 0L, null, null,
                        java.util.List.of("PERSON_NAME"), null)));

        assertOpaqueNotFound(populationDrift.receiptId());
        assertOpaqueNotFound(fieldDrift.receiptId());
    }

    @Test
    void absentAndCrossTenantReceiptsAreOpaque() {
        UUID absent = UUID.randomUUID();
        assertOpaqueNotFound(absent);

        PeopleRequestContext.clear();
        PeopleRequestContext.set(19L, TENANT + 1, ACTOR, Set.of(), Set.of());
        assertOpaqueNotFound(absent);
        verify(repository).find(TENANT + 1, absent);
        verifyNoInteractions(authority);
    }

    @Test
    void exactAssignmentTargetOutsideCurrentOrganizationIsOpaque() {
        var current = operations(Set.of("DIRECTORY", "EMPLOYMENT"), false);
        String population = HrisExperienceDigests.sha256(REVISION);
        long fields = HrisExperienceDigests.employeeChangeRevision(
                REVISION + '|' + current.fieldGroups().stream().sorted().toList());
        var row = employeeRow("EMPLOYEE_CHANGE_CREATE",
                "CREATE_EMPLOYEE_CHANGE_REQUEST", "HR_OPERATIONS", "SUCCEEDED",
                "CREATED", "{\"requestPublicId\":\"" + TARGET
                        + "\",\"requestVersion\":1}", TARGET, population, fields);
        UUID outOfScopeOrganization = UUID.fromString(
                "00000000-0000-4000-8000-000000000099");
        when(repository.find(TENANT, row.receiptId())).thenReturn(Optional.of(row));
        when(authority.require(any(), any())).thenReturn(current);
        when(repository.employeeChangeTarget(TENANT, TARGET)).thenReturn(Optional.of(
                new HrisExperienceCommandReceiptRepository.TargetEvidence(
                        TARGET, 92L, outOfScopeOrganization, 1L, 0L, null, null,
                        java.util.List.of("ASSIGNMENT"), null)));
        when(populations.containsWorker(TENANT, current.population().scope(), 92L))
                .thenReturn(true);

        assertOpaqueNotFound(row.receiptId());
    }

    @Test
    void unknownLifecycleIsClosedAndHasNoLocation() {
        var current = operations(Set.of("DIRECTORY", "EMPLOYMENT"), true);
        String population = HrisExperienceDigests.sha256(REVISION);
        long fields = HrisExperienceDigests.employeeChangeRevision(
                REVISION + '|' + current.fieldGroups().stream().sorted().toList());
        var row = employeeRow("EMPLOYEE_CHANGE_CREATE",
                "CREATE_EMPLOYEE_CHANGE_REQUEST", "HR_OPERATIONS", "CORRUPT",
                null, null, TARGET, population, fields);
        when(repository.find(TENANT, row.receiptId())).thenReturn(Optional.of(row));
        when(authority.require(any(), any())).thenReturn(current);
        when(repository.employeeChangeTarget(TENANT, TARGET)).thenReturn(Optional.of(
                new HrisExperienceCommandReceiptRepository.TargetEvidence(
                        TARGET, 91L, ORGANIZATION, 1L, 0L, null, null,
                        java.util.List.of("PERSON_NAME"), null)));

        var result = service.read(row.receiptId());
        assertThat(result.receipt().status()).isEqualTo("RESULT_UNKNOWN");
        assertThat(result.resultLocation()).isNull();
    }

    private void assertOpaqueNotFound(UUID receiptId) {
        assertThatThrownBy(() -> service.read(receiptId))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    private HrisExperienceReceiptAuthority.CurrentAuthority operations(
            Set<String> fields,
            boolean tenantWide) {
        var scope = new HcmPopulationRepository.PopulationScope(
                18L, "manager", tenantWide, Set.of(ORGANIZATION), fields, "policy");
        var population = new HcmPopulationScopeService.ResolvedPopulation(
                null, scope, new HcmPopulationRepository.PopulationEvidence(1L, "population:7"));
        var actor = PeopleRequestContext.require();
        var origin = new HrisExperienceOriginReceiptAuthoritySource.CurrentOriginAuthority(
                TENANT, actor.userId(), ACTOR, "COMMAND", "ACTION", "TARGET",
                "HR_OPERATIONS", "route", "capability", "POST", false, 13L);
        return new HrisExperienceReceiptAuthority.CurrentAuthority(
                actor, population, REVISION, fields, mock(HcmPepContext.Evidence.class), origin);
    }

    private HrisExperienceCommandReceiptRepository.ReceiptRecord employeeRow(
            String command,
            String action,
            String purpose,
            String lifecycle,
            String resultCode,
            String resultReference,
            UUID target,
            String population,
            long fields) {
        return employeeRow(command, action, purpose, lifecycle, resultCode,
                resultReference, target, population, fields, 0L);
    }

    private HrisExperienceCommandReceiptRepository.ReceiptRecord employeeRow(
            String command,
            String action,
            String purpose,
            String lifecycle,
            String resultCode,
            String resultReference,
            UUID target,
            String population,
            long fields,
            long expectedVersion) {
        return new HrisExperienceCommandReceiptRepository.ReceiptRecord(
                UUID.randomUUID(), TENANT, command, "key", "a".repeat(64), ACTOR,
                action, ACTOR, population, fields, purpose, 13L, "b".repeat(64),
                "EMPLOYEE_CHANGE_REQUEST", target, expectedVersion, lifecycle, resultCode,
                resultReference, UUID.randomUUID(), Instant.parse("2026-10-15T00:00:00Z"));
    }

    private HrisExperienceCommandReceiptRepository.ReceiptRecord assignmentRow(
            String command,
            String action,
            UUID target,
            String resultCode,
            long fieldRevision,
            String resultReference) {
        return assignmentRow(command, action, target, resultCode, fieldRevision,
                resultReference, 1L);
    }

    private HrisExperienceCommandReceiptRepository.ReceiptRecord assignmentRow(
            String command,
            String action,
            UUID target,
            String resultCode,
            long fieldRevision,
            String resultReference,
            long expectedVersion) {
        return new HrisExperienceCommandReceiptRepository.ReceiptRecord(
                UUID.randomUUID(), TENANT, command, "key", "a".repeat(64), ACTOR,
                action, ACTOR,
                HrisExperienceDigests.assignmentPopulationDigest(mapper, REVISION),
                fieldRevision, "HR_LIFECYCLE", 13L, "b".repeat(64),
                "EMPLOYMENT_EVENT", target, expectedVersion, "SUCCEEDED", resultCode,
                resultReference, UUID.randomUUID(), Instant.parse("2026-10-15T00:00:00Z"));
    }
}

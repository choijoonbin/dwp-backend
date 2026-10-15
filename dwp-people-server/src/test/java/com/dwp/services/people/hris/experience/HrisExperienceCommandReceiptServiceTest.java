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
                        TARGET, 91L, ORGANIZATION, 2L,
                        java.util.List.of("PERSON_NAME"), null)));

        var result = service.read(row.receiptId());

        assertThat(result.receipt().status()).isEqualTo("SUCCEEDED");
        assertThat(result.resultLocation().toString()).isEqualTo(
                "/hr/operations/people/employee-change-requests?requestPublicId=" + TARGET);
        assertThat(mapper.valueToTree(result.receipt()).size()).isEqualTo(12);
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
                        TARGET, null, ORGANIZATION, 2L,
                        java.util.List.of("EMPLOYMENT"), "HIRE")));

        assertThat(service.read(row.receiptId()).resultLocation().toString())
                .isEqualTo("/api/people/v1/hris/assignment-events/" + TARGET);
        verify(populations, never()).containsWorker(anyLong(), any(), anyLong());
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
    void currentPopulationAndFieldDriftAreOpaque() {
        var current = operations(Set.of("DIRECTORY", "EMPLOYMENT"), true);
        var drifted = employeeRow("EMPLOYEE_CHANGE_CREATE",
                "CREATE_EMPLOYEE_CHANGE_REQUEST", "HR_OPERATIONS", "SUCCEEDED",
                "CREATED", "{\"requestPublicId\":\"" + TARGET
                        + "\",\"requestVersion\":1}", TARGET,
                "f".repeat(64), 22L);
        when(repository.find(TENANT, drifted.receiptId())).thenReturn(Optional.of(drifted));
        when(authority.require(any(), any())).thenReturn(current);
        when(repository.employeeChangeTarget(TENANT, TARGET)).thenReturn(Optional.of(
                new HrisExperienceCommandReceiptRepository.TargetEvidence(
                        TARGET, 91L, ORGANIZATION, 1L,
                        java.util.List.of("PERSON_NAME"), null)));

        assertOpaqueNotFound(drifted.receiptId());
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
                        TARGET, 91L, ORGANIZATION, 1L,
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
        return new HrisExperienceCommandReceiptRepository.ReceiptRecord(
                UUID.randomUUID(), TENANT, command, "key", "a".repeat(64), ACTOR,
                action, ACTOR, population, fields, purpose, 13L, "b".repeat(64),
                "EMPLOYEE_CHANGE_REQUEST", target, 0L, lifecycle, resultCode,
                resultReference, UUID.randomUUID(), Instant.parse("2026-10-15T00:00:00Z"));
    }

    private HrisExperienceCommandReceiptRepository.ReceiptRecord assignmentRow(
            String command,
            String action,
            UUID target,
            String resultCode,
            long fieldRevision,
            String resultReference) {
        return new HrisExperienceCommandReceiptRepository.ReceiptRecord(
                UUID.randomUUID(), TENANT, command, "key", "a".repeat(64), ACTOR,
                action, ACTOR,
                HrisExperienceDigests.assignmentPopulationDigest(mapper, REVISION),
                fieldRevision, "HR_LIFECYCLE", 13L, "b".repeat(64),
                "EMPLOYMENT_EVENT", target, 1L, "SUCCEEDED", resultCode,
                resultReference, UUID.randomUUID(), Instant.parse("2026-10-15T00:00:00Z"));
    }
}

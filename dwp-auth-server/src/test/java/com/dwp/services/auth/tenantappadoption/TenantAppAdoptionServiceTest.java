package com.dwp.services.auth.tenantappadoption;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.service.AppGovernanceAuthorization;
import com.dwp.services.auth.service.IdentityAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantAppAdoptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T05:00:00Z");
    private static final UUID INSTALLATION_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111");
    private static final UUID ASSIGNMENT_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222");

    private final TenantAppAdoptionRepository repository = mock(TenantAppAdoptionRepository.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final AppGovernanceAuthorization authorization = mock(AppGovernanceAuthorization.class);
    private TenantAppAdoptionService service;

    @BeforeEach
    void setUp() {
        service = new TenantAppAdoptionService(
                repository, audit, authorization, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void reportsCompleteInternalCoverageAndExplicitExternalExclusions() {
        when(repository.installations(7L, null, 101)).thenReturn(List.of(installation(
                "INTERNAL_AUTH_CONTROLLED", "ENABLED", 10, 2, 1, 3L, 101L, 102L)));
        when(authorization.requireVisibility(7L, 103L)).thenReturn(
                new AppGovernanceAuthorization.Visibility(true, Set.of(), Set.of(), Set.of()));
        when(authorization.appResourceKeys(7L, 103L, "APP_OWNER"))
                .thenReturn(Set.of("APP.APPROVALS"));
        when(authorization.appResourceKeys(7L, 103L, "APP_ACCESS_APPROVER"))
                .thenReturn(Set.of("APP.APPROVALS"));
        when(authorization.appResourceKeys(7L, 103L, "APP_ACCESS_MANAGER"))
                .thenReturn(Set.of("APP.APPROVALS"));

        TenantAppAdoptionDtos.AdoptionProjection result = service.projection(7L, 103L);

        assertThat(result.coverageState()).isEqualTo("COMPLETE_INTERNAL_OWNERS");
        assertThat(result.includedOwners()).contains("AUTH_WORKFORCE_SEAT_RESERVATION");
        assertThat(result.requestableAppResourceKeys()).containsExactly("APP.APPROVALS");
        assertThat(result.installations().getFirst().allowedActions())
                .containsExactly("REQUEST_ASSIGNMENT");
        assertThat(result.exclusions()).contains(
                "EXTERNAL_SAAS_PROVISIONING", "PRODUCT_RUNTIME_HEALTH_AND_RUNNABILITY");
    }

    @Test
    void projectionOmitsApprovalAndActivationForTheHighValueTargetActor() {
        long subjectActor = 5_001L;
        TenantAppAdoptionDtos.Installation installation = installation(
                "INTERNAL_AUTH_CONTROLLED", "ENABLED", 10, 0, 0, 3L, 4_001L, 4_002L);
        when(repository.assignments(7L, null, null, 101)).thenReturn(List.of(
                assignmentForUser("PENDING_APPROVAL", 0L, 4_001L, null, subjectActor),
                assignmentForUser("APPROVED", 1L, 4_001L, 4_002L, subjectActor)));
        when(repository.requireInstallation(7L, INSTALLATION_ID)).thenReturn(installation);
        when(authorization.requireVisibility(7L, subjectActor)).thenReturn(
                new AppGovernanceAuthorization.Visibility(true, Set.of(), Set.of(), Set.of()));
        when(authorization.appResourceKeys(7L, subjectActor, "APP_ACCESS_APPROVER"))
                .thenReturn(Set.of("APP.APPROVALS"));
        when(authorization.appResourceKeys(7L, subjectActor, "APP_ACCESS_MANAGER"))
                .thenReturn(Set.of("APP.APPROVALS"));

        TenantAppAdoptionDtos.AssignmentPage result = service.assignments(
                7L, subjectActor, null);

        assertThat(result.items()).allSatisfy(assignment ->
                assertThat(assignment.allowedActions())
                        .doesNotContain("APPROVE", "REJECT", "ACTIVATE"));
    }

    @Test
    void refusesToActivateAnExternalServiceWithoutAnExecutorReceipt() {
        when(repository.lockInstallation(7L, INSTALLATION_ID)).thenReturn(installation(
                "EXTERNAL_SERVICE", "APPROVED", 20, 0, 0, 2L, 101L, 102L));

        assertThatThrownBy(() -> service.activateInstallation(
                7L, 103L, null, INSTALLATION_ID,
                new TenantAppAdoptionDtos.ActivationCommand(
                        2L, "Activate after independent external verification.")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));
        verify(repository, never()).transitionInstallation(
                anyLong(), any(), anyLong(), any(), eq("ENABLED"), anyLong(), any(), any(), any());
        verify(authorization).requireAppResponsibility(
                7L, 103L, "APP_ACCESS_MANAGER", "APP.APPROVALS", null,
                "TENANT_APP_INSTALLATION", INSTALLATION_ID.toString());
    }

    @Test
    void enforcesThreeDistinctActorsForInternalActivation() {
        when(repository.lockInstallation(7L, INSTALLATION_ID)).thenReturn(installation(
                "INTERNAL_AUTH_CONTROLLED", "APPROVED", 20, 0, 0, 2L, 101L, 102L));

        assertThatThrownBy(() -> service.activateInstallation(
                7L, 102L, null, INSTALLATION_ID,
                new TenantAppAdoptionDtos.ActivationCommand(
                        2L, "Activate the independently reviewed internal adoption.")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));
    }

    @Test
    void rejectsAssignmentApprovalWhenTheInternalReservationLimitIsReached() {
        TenantAppAdoptionDtos.Assignment pending = assignment(
                "PENDING_APPROVAL", 0L, 201L, null);
        when(repository.requireAssignment(7L, ASSIGNMENT_ID)).thenReturn(pending);
        when(repository.lockInstallation(7L, INSTALLATION_ID)).thenReturn(installation(
                "INTERNAL_AUTH_CONTROLLED", "ENABLED", 2, 2, 2, 4L, 101L, 102L));

        assertThatThrownBy(() -> service.decideAssignment(
                7L, 202L, null, ASSIGNMENT_ID,
                new TenantAppAdoptionDtos.DecisionCommand(
                        0L, "APPROVE", "Approve this workforce access reservation.")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(repository, never()).transitionAssignment(
                anyLong(), any(), anyLong(), any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void rejectsAnAssignmentAtTheExactValidityBoundaryBeforeReleasingOldRows() {
        when(repository.lockInstallation(7L, INSTALLATION_ID)).thenReturn(installation(
                "INTERNAL_AUTH_CONTROLLED", "ENABLED", 10, 0, 0, 4L, 101L, 102L));
        when(repository.activeUserExists(7L, 301L)).thenReturn(true);
        TenantAppAdoptionDtos.CreateAssignmentRequest request =
                new TenantAppAdoptionDtos.CreateAssignmentRequest(
                        INSTALLATION_ID, 301L, NOW,
                        "An exact-boundary request must fail closed before persistence.");

        assertThatThrownBy(() -> service.createAssignment(7L, 201L, null, request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        verify(repository, never()).expireAssignmentsForReplacement(
                anyLong(), any(), anyLong(), anyLong(), any());
        verify(repository, never()).insertAssignment(
                anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void preservesNullableValidityForAnIndefiniteAssignment() {
        when(repository.lockInstallation(7L, INSTALLATION_ID)).thenReturn(installation(
                "INTERNAL_AUTH_CONTROLLED", "ENABLED", 10, 0, 0, 4L, 101L, 102L));
        when(repository.activeUserExists(7L, 301L)).thenReturn(true);
        when(repository.expireAssignmentsForReplacement(
                7L, INSTALLATION_ID, 301L, 201L, NOW)).thenReturn(List.of());
        TenantAppAdoptionDtos.CreateAssignmentRequest request =
                new TenantAppAdoptionDtos.CreateAssignmentRequest(
                        INSTALLATION_ID, 301L, null,
                        "An indefinite request intentionally has no validity end.");
        TenantAppAdoptionDtos.Assignment created = assignment(
                "PENDING_APPROVAL", 0L, 201L, null);
        when(repository.insertAssignment(7L, 201L, request, "NOT_REQUIRED", NOW))
                .thenReturn(created);

        assertThat(service.createAssignment(7L, 201L, null, request)).isEqualTo(created);
        verify(repository).insertAssignment(7L, 201L, request, "NOT_REQUIRED", NOW);
    }

    @Test
    void expiredPendingAssignmentCanBeRejectedButCannotBeApprovedOrAdvertisedAsApprovable() {
        TenantAppAdoptionDtos.Assignment expired = assignment(
                "EXPIRED", 0L, 201L, null, NOW);
        TenantAppAdoptionDtos.Assignment denied = assignment(
                "DENIED", 1L, 201L, 202L, NOW);
        TenantAppAdoptionDtos.Installation installation = installation(
                "INTERNAL_AUTH_CONTROLLED", "ENABLED", 10, 0, 0, 4L, 101L, 102L);
        when(repository.requireAssignment(7L, ASSIGNMENT_ID)).thenReturn(expired, expired);
        when(repository.lockInstallation(7L, INSTALLATION_ID)).thenReturn(installation);
        when(repository.transitionAssignment(
                eq(7L), eq(ASSIGNMENT_ID), eq(0L), eq("PENDING_APPROVAL"), eq("DENIED"),
                eq(202L), any(), eq(null), eq(NOW))).thenReturn(denied);

        assertThatThrownBy(() -> service.decideAssignment(
                7L, 202L, null, ASSIGNMENT_ID,
                new TenantAppAdoptionDtos.DecisionCommand(
                        0L, "APPROVE", "Expired requests must fail closed.")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        TenantAppAdoptionDtos.Assignment result = service.decideAssignment(
                7L, 202L, null, ASSIGNMENT_ID,
                new TenantAppAdoptionDtos.DecisionCommand(
                        0L, "REJECT", "Close the expired request without granting access."));
        assertThat(result.lifecycleState()).isEqualTo("DENIED");

        when(repository.assignments(7L, null, null, 101)).thenReturn(List.of(expired));
        when(repository.requireInstallation(7L, INSTALLATION_ID)).thenReturn(installation);
        when(authorization.requireVisibility(7L, 202L)).thenReturn(
                new AppGovernanceAuthorization.Visibility(true, Set.of(), Set.of(), Set.of()));
        when(authorization.appResourceKeys(7L, 202L, "APP_ACCESS_APPROVER"))
                .thenReturn(Set.of("APP.APPROVALS"));
        assertThat(service.assignments(7L, 202L, null).items()).singleElement()
                .satisfies(value -> assertThat(value.allowedActions())
                        .isEmpty());
    }

    @Test
    void activatesAnApprovedSeatWithAnInternalReceiptAndNoExternalClaim() {
        TenantAppAdoptionDtos.Assignment approved = assignment("APPROVED", 1L, 201L, 202L);
        TenantAppAdoptionDtos.Assignment active = assignment("ACTIVE", 2L, 201L, 202L);
        when(repository.requireAssignment(7L, ASSIGNMENT_ID)).thenReturn(approved);
        when(repository.lockInstallation(7L, INSTALLATION_ID)).thenReturn(installation(
                "INTERNAL_AUTH_CONTROLLED", "ENABLED", 10, 1, 0, 4L, 101L, 102L));
        when(repository.transitionAssignment(
                eq(7L), eq(ASSIGNMENT_ID), eq(1L), eq("APPROVED"), eq("ACTIVE"),
                eq(203L), any(), any(), eq(NOW))).thenReturn(active);

        TenantAppAdoptionDtos.Assignment result = service.activateAssignment(
                7L, 203L, "correlation-seat", ASSIGNMENT_ID,
                new TenantAppAdoptionDtos.ActivationCommand(
                        1L, "Activate the approved internal workforce seat."));

        assertThat(result.lifecycleState()).isEqualTo("ACTIVE");
        assertThat(result.externalSettlementState()).isEqualTo("NOT_REQUIRED");
        verify(repository).appendEvent(
                eq(7L), eq("WORKFORCE_ASSIGNMENT"), eq(ASSIGNMENT_ID),
                eq("ASSIGNMENT_ACTIVATED"), eq(203L), eq("correlation-seat"),
                eq(2L), any());
        verify(authorization).requireAppResponsibility(
                7L, 203L, "APP_ACCESS_MANAGER", "APP.APPROVALS", "correlation-seat",
                "TENANT_APP_WORKFORCE_ASSIGNMENT", ASSIGNMENT_ID.toString());
    }

    @Test
    void refusesToActivateAnExpiredApprovedSeatButStillAllowsRevocation() {
        TenantAppAdoptionDtos.Assignment expired = assignment(
                "APPROVED", 1L, 201L, 202L, NOW);
        when(repository.requireAssignment(7L, ASSIGNMENT_ID)).thenReturn(expired);
        when(repository.lockInstallation(7L, INSTALLATION_ID)).thenReturn(installation(
                "INTERNAL_AUTH_CONTROLLED", "ENABLED", 10, 0, 0, 4L, 101L, 102L));

        assertThatThrownBy(() -> service.activateAssignment(
                7L, 203L, null, ASSIGNMENT_ID,
                new TenantAppAdoptionDtos.ActivationCommand(
                        1L, "Expired approved seats cannot become effective.")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        verify(repository, never()).transitionAssignment(
                anyLong(), any(), anyLong(), any(), eq("ACTIVE"), anyLong(), any(), any(), any());

        TenantAppAdoptionDtos.Assignment future = assignment(
                "APPROVED", 1L, 201L, 202L, NOW.plusSeconds(60), NOW.plusSeconds(3_600));
        when(repository.requireAssignment(7L, ASSIGNMENT_ID)).thenReturn(future);
        assertThatThrownBy(() -> service.activateAssignment(
                7L, 203L, null, ASSIGNMENT_ID,
                new TenantAppAdoptionDtos.ActivationCommand(
                        1L, "Future assignments cannot activate before their start.")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        when(repository.assignments(7L, null, null, 101)).thenReturn(List.of(expired));
        when(repository.requireInstallation(7L, INSTALLATION_ID)).thenReturn(installation(
                "INTERNAL_AUTH_CONTROLLED", "ENABLED", 10, 0, 0, 4L, 101L, 102L));
        when(authorization.requireVisibility(7L, 203L)).thenReturn(
                new AppGovernanceAuthorization.Visibility(true, Set.of(), Set.of(), Set.of()));
        when(authorization.appResourceKeys(7L, 203L, "APP_ACCESS_MANAGER"))
                .thenReturn(Set.of("APP.APPROVALS"));

        assertThat(service.assignments(7L, 203L, null).items()).singleElement()
                .satisfies(value -> assertThat(value.allowedActions())
                        .containsExactly("REVOKE"));
    }

    @Test
    void revokesAnExpiredApprovedSeatUsingItsCurrentStateAsTheAtomicGuard() {
        TenantAppAdoptionDtos.Assignment expired = assignment(
                "APPROVED", 1L, 201L, 202L, NOW);
        TenantAppAdoptionDtos.Assignment revoked = assignment(
                "REVOKED", 2L, 201L, 202L, NOW);
        when(repository.requireAssignment(7L, ASSIGNMENT_ID)).thenReturn(expired);
        when(repository.requireInstallation(7L, INSTALLATION_ID)).thenReturn(installation(
                "INTERNAL_AUTH_CONTROLLED", "ENABLED", 10, 0, 0, 4L, 101L, 102L));
        when(repository.transitionAssignment(
                eq(7L), eq(ASSIGNMENT_ID), eq(1L), eq("APPROVED"), eq("REVOKED"),
                eq(203L), any(), eq(null), eq(NOW))).thenReturn(revoked);

        TenantAppAdoptionDtos.Assignment result = service.revokeAssignment(
                7L, 203L, "correlation-revoke", ASSIGNMENT_ID,
                new TenantAppAdoptionDtos.RevokeCommand(
                        1L, "Release the expired approved seat reservation."));

        assertThat(result.lifecycleState()).isEqualTo("REVOKED");
        verify(repository).transitionAssignment(
                eq(7L), eq(ASSIGNMENT_ID), eq(1L), eq("APPROVED"), eq("REVOKED"),
                eq(203L), any(), eq(null), eq(NOW));
    }

    @Test
    void capsInstallationAndAssignmentPagesWithExplicitCoverage() {
        List<TenantAppAdoptionDtos.Installation> installations = IntStream.range(0, 101)
                .mapToObj(index -> installationWithId(new UUID(0L, index + 1L)))
                .toList();
        List<TenantAppAdoptionDtos.Assignment> assignments = IntStream.range(0, 101)
                .mapToObj(index -> assignmentWithId(new UUID(1L, index + 1L)))
                .toList();
        TenantAppAdoptionDtos.Installation installation = installationWithId(INSTALLATION_ID);
        when(repository.installations(7L, null, 101)).thenReturn(installations);
        when(repository.assignments(7L, null, null, 101)).thenReturn(assignments);
        when(repository.requireInstallation(7L, INSTALLATION_ID)).thenReturn(installation);
        when(authorization.requireVisibility(7L, 103L)).thenReturn(
                new AppGovernanceAuthorization.Visibility(true, Set.of(), Set.of(), Set.of()));

        TenantAppAdoptionDtos.AdoptionProjection projection = service.projection(7L, 103L);
        TenantAppAdoptionDtos.AssignmentPage assignmentPage = service.assignments(7L, 103L, null);

        assertThat(projection.installations()).hasSize(100);
        assertThat(projection.installationsLimit()).isEqualTo(100);
        assertThat(projection.installationsHasMore()).isTrue();
        assertThat(assignmentPage.items()).hasSize(100);
        assertThat(assignmentPage.limit()).isEqualTo(100);
        assertThat(assignmentPage.hasMore()).isTrue();
    }

    @Test
    void targetUserCannotApproveOrActivateTheirOwnAssignmentWithHighValueIds() {
        long subjectActor = 5_001L;
        TenantAppAdoptionDtos.Assignment pending = assignmentForUser(
                "PENDING_APPROVAL", 0L, 4_001L, null, subjectActor);
        TenantAppAdoptionDtos.Assignment approved = assignmentForUser(
                "APPROVED", 1L, 4_001L, 4_002L, subjectActor);
        when(repository.requireAssignment(7L, ASSIGNMENT_ID)).thenReturn(pending, approved);

        assertThatThrownBy(() -> service.decideAssignment(
                7L, subjectActor, null, ASSIGNMENT_ID,
                new TenantAppAdoptionDtos.DecisionCommand(
                        0L, "APPROVE", "The assignment subject cannot approve their own seat.")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));
        assertThatThrownBy(() -> service.activateAssignment(
                7L, subjectActor, null, ASSIGNMENT_ID,
                new TenantAppAdoptionDtos.ActivationCommand(
                        1L, "The assignment subject cannot activate their own seat.")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));

        verify(repository, never()).transitionAssignment(
                anyLong(), any(), anyLong(), any(), any(), anyLong(), any(), any(), any());
    }

    private TenantAppAdoptionDtos.Installation installation(
            String kind,
            String state,
            Integer capacity,
            long reserved,
            long active,
            long version,
            Long requestedBy,
            Long approvedBy) {
        return new TenantAppAdoptionDtos.Installation(
                INSTALLATION_ID, "approvals", "APP.APPROVALS", kind, state,
                "EXTERNAL_SERVICE".equals(kind) ? "UNAVAILABLE" : "NOT_REQUIRED",
                capacity, reserved, active, "A sufficiently detailed installation reason.",
                requestedBy, NOW.minusSeconds(120), approvedBy, NOW.minusSeconds(60),
                "An independent review reason.",
                "ENABLED".equals(state) ? 103L : null,
                "ENABLED".equals(state) ? NOW : null,
                "ENABLED".equals(state) ? UUID.randomUUID() : null,
                version, NOW.minusSeconds(180), NOW, List.of());
    }

    private TenantAppAdoptionDtos.Assignment assignment(
            String state, long version, Long requestedBy, Long approvedBy) {
        return assignmentForUser(state, version, requestedBy, approvedBy, 301L);
    }

    private TenantAppAdoptionDtos.Assignment assignment(
            String state, long version, Long requestedBy, Long approvedBy, Instant validTo) {
        return assignment(state, version, requestedBy, approvedBy, null, validTo);
    }

    private TenantAppAdoptionDtos.Assignment assignment(
            String state,
            long version,
            Long requestedBy,
            Long approvedBy,
            Instant validFrom,
            Instant validTo) {
        TenantAppAdoptionDtos.Assignment value = assignment(
                state, version, requestedBy, approvedBy);
        return new TenantAppAdoptionDtos.Assignment(
                value.assignmentId(), value.installationId(), value.productKey(), value.userId(),
                value.userDisplayName(), value.lifecycleState(), value.seatQuantity(),
                value.sourceType(), value.externalSettlementState(), validFrom, validTo,
                value.justification(), value.requestedBy(), value.approvedBy(), value.approvedAt(),
                value.decisionReason(), value.activatedBy(), value.activatedAt(),
                value.activationReceiptId(), value.revokedBy(), value.revokedAt(),
                value.revocationReason(), value.version(), value.createdAt(), value.updatedAt(),
                value.allowedActions());
    }

    private TenantAppAdoptionDtos.Installation installationWithId(UUID installationId) {
        TenantAppAdoptionDtos.Installation value = installation(
                "INTERNAL_AUTH_CONTROLLED", "ENABLED", 10, 0, 0, 3L, 101L, 102L);
        return new TenantAppAdoptionDtos.Installation(
                installationId, value.productKey(), value.appResourceKey(),
                value.installationKind(), value.lifecycleState(), value.externalExecutorState(),
                value.seatCapacity(), value.reservedSeats(), value.activeSeats(),
                value.justification(), value.requestedBy(), value.submittedAt(),
                value.approvedBy(), value.approvedAt(), value.decisionReason(),
                value.activatedBy(), value.activatedAt(), value.activationReceiptId(),
                value.version(), value.createdAt(), value.updatedAt(), value.allowedActions());
    }

    private TenantAppAdoptionDtos.Assignment assignmentWithId(UUID assignmentId) {
        TenantAppAdoptionDtos.Assignment value = assignment("APPROVED", 1L, 201L, 202L);
        return new TenantAppAdoptionDtos.Assignment(
                assignmentId, value.installationId(), value.productKey(), value.userId(),
                value.userDisplayName(), value.lifecycleState(), value.seatQuantity(),
                value.sourceType(), value.externalSettlementState(), value.validFrom(),
                value.validTo(), value.justification(), value.requestedBy(), value.approvedBy(),
                value.approvedAt(), value.decisionReason(), value.activatedBy(),
                value.activatedAt(), value.activationReceiptId(), value.revokedBy(),
                value.revokedAt(), value.revocationReason(), value.version(), value.createdAt(),
                value.updatedAt(), value.allowedActions());
    }

    private TenantAppAdoptionDtos.Assignment assignmentForUser(
            String state, long version, Long requestedBy, Long approvedBy, Long userId) {
        return new TenantAppAdoptionDtos.Assignment(
                ASSIGNMENT_ID, INSTALLATION_ID, "approvals", userId, "Managed user", state,
                1, "TENANT_DIRECT", "NOT_REQUIRED",
                "ACTIVE".equals(state) ? NOW : null, NOW.plusSeconds(3600),
                "A sufficiently detailed workforce assignment reason.",
                requestedBy, approvedBy, approvedBy == null ? null : NOW.minusSeconds(30),
                approvedBy == null ? null : "An independent assignment review reason.",
                "ACTIVE".equals(state) ? 203L : null,
                "ACTIVE".equals(state) ? NOW : null,
                "ACTIVE".equals(state) ? UUID.randomUUID() : null,
                null, null, null, version, NOW.minusSeconds(60), NOW, List.of());
    }
}

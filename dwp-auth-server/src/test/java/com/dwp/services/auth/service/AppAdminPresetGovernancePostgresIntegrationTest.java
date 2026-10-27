package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AppGovernanceDtos;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Exact-scope governance and concurrency proofs for preset assignments. */
@Testcontainers(disabledWithoutDocker = true)
class AppAdminPresetGovernancePostgresIntegrationTest extends AppAdminPresetPostgresFixture {
    @Test
    void catalogAdminCanReadAndRequestButCannotDecideRevokeOrResolveReviews() {
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.catalogAdmin(), "catalog-request-1",
                        governedRequest(fixture.subject(), "APPROVAL_PUBLISHER")));

        assertThat(service.dashboard(fixture.tenantId(), fixture.catalogAdmin()).catalog())
                .extracting(AppGovernanceDtos.AppAdminPreset::presetCode)
                .contains("APPROVAL_DESIGNER", "APPROVAL_PUBLISHER");
        assertThat(new AppGovernanceService(jdbc, audit)
                .dashboard(fixture.tenantId(), fixture.catalogAdmin()).resourceSets())
                .extracting(AppGovernanceDtos.ResourceSet::resourceSetId)
                .contains(fixture.resourceSetId());
        assertThat(service.assignments(fixture.tenantId(), fixture.catalogAdmin()))
                .extracting(AppGovernanceDtos.AppAdminPresetAssignment::presetAssignmentId)
                .contains(pending.presetAssignmentId());
        assertThatThrownBy(() -> inTransaction(() -> service.decide(
                fixture.tenantId(), fixture.catalogAdmin(), "catalog-decision-1",
                pending.presetAssignmentId(), decision("APPROVED", pending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertBundleStates(pending.presetAssignmentId(), "PENDING_APPROVAL", 2);
        assertThatThrownBy(() -> inTransaction(() -> service.decideReview(
                fixture.tenantId(), fixture.catalogAdmin(), "catalog-review-1",
                UUID.randomUUID(), new AppGovernanceDtos.AppAdminPresetReviewDecisionRequest(
                        "DISMISSED", "The legacy review has documented compensating evidence.", 0L))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> inTransaction(() -> {
            jdbc.update("""
                    UPDATE sys_admin_app_preset_catalog SET version = version + 1
                     WHERE preset_code = 'APPROVAL_PUBLISHER'
                    """);
            return service.decide(
                    fixture.tenantId(), fixture.approver(), "stale-catalog-approve-1",
                    pending.presetAssignmentId(), decision("APPROVED", pending.version()));
        })).isInstanceOfSatisfying(BaseException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        assertBundleStates(pending.presetAssignmentId(), "PENDING_APPROVAL", 2);

        AppGovernanceDtos.AppAdminPresetAssignment approved = inTransaction(() -> service.decide(
                fixture.tenantId(), fixture.approver(), "catalog-approved-1",
                pending.presetAssignmentId(), decision("APPROVED", pending.version())));
        assertThatThrownBy(() -> inTransaction(() -> service.activate(
                fixture.tenantId(), fixture.catalogAdmin(), "catalog-activate-1",
                approved.presetAssignmentId(), activation(approved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> inTransaction(() -> service.revoke(
                fixture.tenantId(), fixture.catalogAdmin(), "catalog-revoke-1",
                approved.presetAssignmentId(), new AppGovernanceDtos.RevokeAppAdminPresetRequest(
                        "Catalog administrators cannot fulfil or revoke this package.",
                        approved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        AppGovernanceDtos.AppAdminPresetAssignment active = inTransaction(() ->
                service.activate(
                        fixture.tenantId(), fixture.fulfiller(), "catalog-activated-1",
                        approved.presetAssignmentId(), activation(approved.version())));
        assertBundleStates(active.presetAssignmentId(), "ACTIVE", 2);
    }

    @Test
    void broadTenantRolesCannotOpenOrMutateAppGovernanceAndGenericWritesStayControlPlaneOnly() {
        Long admin = user(fixture.tenantId(), "broad-admin");
        Long platformAdmin = user(fixture.tenantId(), "broad-platform-admin");
        grantRole(fixture.tenantId(), admin, "ADMIN");
        grantRole(fixture.tenantId(), platformAdmin, "PLATFORM_ADMIN");
        List<Long> broadActors = List.of(fixture.tenantAdmin(), admin, platformAdmin);
        AppGovernanceService generic = new AppGovernanceService(jdbc, audit);

        for (Long broadActor : broadActors) {
            assertThatThrownBy(() -> service.dashboard(fixture.tenantId(), broadActor))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            assertThatThrownBy(() -> generic.dashboard(fixture.tenantId(), broadActor))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            assertThatThrownBy(() -> inTransaction(() -> requestService.requestGoverned(
                    fixture.tenantId(), broadActor, "broad-request-" + broadActor,
                    governedRequest(fixture.subject(), "APPROVAL_DESIGNER"))))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            assertThatThrownBy(() -> inTransaction(() -> generic.createResourceSet(
                    fixture.tenantId(), broadActor, "broad-resource-" + broadActor,
                    new AppGovernanceDtos.CreateResourceSetRequest(
                            "RS_BROAD_" + broadActor, "Forbidden broad set", null,
                            List.of("APP.APPROVALS")))))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }

        assertThatThrownBy(() -> inTransaction(() -> generic.requestAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "generic-specialist-create",
                genericRequest(fixture.subject(), "APP_CONFIG_ADMIN"))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN))
                .hasMessageContaining("preset workflow");
        UUID legacySpecialist = pendingResponsibility(
                fixture.subject(), "APP_CONFIG_ADMIN", fixture.catalogAdmin());
        assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), fixture.approver(), "generic-specialist-decision",
                legacySpecialist, genericDecision("APPROVED", 0L))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN))
                .hasMessageContaining("preset workflow");

        Long controlSubject = user(fixture.tenantId(), "control-reviewer");
        AppGovernanceDtos.Assignment pending = inTransaction(() -> generic.requestAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "generic-control-request",
                genericRequest(controlSubject, "APP_ACCESS_REVIEWER")));
        for (Long forbiddenActor : List.of(
                fixture.catalogAdmin(), fixture.tenantAdmin(), admin,
                platformAdmin, fixture.fulfiller())) {
            assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                    fixture.tenantId(), forbiddenActor,
                    "generic-control-decision-" + forbiddenActor,
                    pending.assignmentId(), genericDecision("APPROVED", pending.version()))))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
        AppGovernanceDtos.Assignment active = inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), fixture.approver(), "generic-control-approved",
                pending.assignmentId(), genericDecision("APPROVED", pending.version())));
        assertThat(active.responsibilityCode()).isEqualTo("APP_ACCESS_REVIEWER");
        assertThat(effectiveDuties(controlSubject)).isEmpty();
        assertThat(generic.resourceRoles(fixture.tenantId(), controlSubject))
                .extracting(AppGovernanceDtos.ResourceRole::responsibilityCode)
                .containsOnly("APP_ACCESS_REVIEWER")
                .doesNotContain("APP_CONFIG_ADMIN");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_app_preset_assignments
                 WHERE tenant_id = ? AND principal_type = 'USER' AND principal_ref = ?
                """, Integer.class, fixture.tenantId(), controlSubject.toString())).isZero();
        for (Long forbiddenActor : List.of(
                fixture.catalogAdmin(), fixture.tenantAdmin(), admin,
                platformAdmin, fixture.approver())) {
            assertThatThrownBy(() -> inTransaction(() -> generic.revokeAssignment(
                    fixture.tenantId(), forbiddenActor,
                    "generic-control-revoke-" + forbiddenActor,
                    active.assignmentId(), genericRevoke(active.version()))))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
        AppGovernanceDtos.Assignment revoked = inTransaction(() -> generic.revokeAssignment(
                fixture.tenantId(), fixture.fulfiller(), "generic-control-revoked",
                active.assignmentId(), genericRevoke(active.version())));
        assertThat(revoked.lifecycleState()).isEqualTo("REVOKED");
    }

    @Test
    void specialistResponsibilityDoesNotExposeTheCompanyGovernanceHub() {
        Long specialist = user(fixture.tenantId(), "product-config-specialist");
        grantResponsibility(
                fixture.tenantId(), specialist, "APP_CONFIG_ADMIN",
                fixture.resourceSetId(), fixture.catalogAdmin());

        assertThatThrownBy(() -> service.dashboard(fixture.tenantId(), specialist))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> new AppGovernanceService(jdbc, audit)
                .dashboard(fixture.tenantId(), specialist))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void catalogAuthorityAloneDesignatesAndRevokesAppOwners() {
        AppGovernanceService generic = new AppGovernanceService(jdbc, audit);
        Long secondCatalogAdmin = user(fixture.tenantId(), "second-catalog-admin");
        grantRole(fixture.tenantId(), secondCatalogAdmin, "APP_CATALOG_ADMIN");
        Long ownerCandidate = user(fixture.tenantId(), "owner-candidate");

        AppGovernanceDtos.Assignment pending = inTransaction(() -> generic.requestAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "owner-catalog-request",
                genericRequest(ownerCandidate, "APP_OWNER")));
        assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), fixture.approver(), "owner-approver-forbidden",
                pending.assignmentId(), genericDecision("APPROVED", pending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "owner-requester-self-approval",
                pending.assignmentId(), genericDecision("APPROVED", pending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), secondCatalogAdmin, "owner-stale-version",
                pending.assignmentId(), genericDecision("APPROVED", pending.version() + 1))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        AppGovernanceDtos.Assignment active = inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), secondCatalogAdmin, "owner-catalog-approved",
                pending.assignmentId(), genericDecision("APPROVED", pending.version())));
        assertThat(active.lifecycleState()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> inTransaction(() -> generic.revokeAssignment(
                fixture.tenantId(), fixture.fulfiller(), "owner-manager-forbidden",
                active.assignmentId(), genericRevoke(active.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        AppGovernanceDtos.Assignment revoked = inTransaction(() -> generic.revokeAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "owner-catalog-revoked",
                active.assignmentId(), genericRevoke(active.version())));
        assertThat(revoked.lifecycleState()).isEqualTo("REVOKED");
    }

    @Test
    void finalOwnerGuardCountsOnlyCurrentlyEffectiveActivePrincipals() {
        AppGovernanceService generic = new AppGovernanceService(jdbc, audit);
        UUID resourceSetId = additionalApprovalsResourceSet(fixture.tenantId());
        Long currentOwner = user(fixture.tenantId(), "effective-current-owner");
        Long replacementOwner = user(fixture.tenantId(), "future-replacement-owner");
        grantResponsibility(
                fixture.tenantId(), currentOwner, "APP_OWNER", resourceSetId,
                fixture.catalogAdmin());
        grantResponsibility(
                fixture.tenantId(), replacementOwner, "APP_OWNER", resourceSetId,
                fixture.catalogAdmin());
        UUID currentAssignment = responsibilityAssignment(
                fixture.tenantId(), currentOwner, "APP_OWNER", resourceSetId);
        UUID replacementAssignment = responsibilityAssignment(
                fixture.tenantId(), replacementOwner, "APP_OWNER", resourceSetId);
        jdbc.update("""
                UPDATE com_admin_role_assignments
                   SET valid_from = statement_timestamp() + INTERVAL '2 days'
                 WHERE admin_role_assignment_id = ?
                """, replacementAssignment);

        long ownerlessBeforeSuspension = generic.dashboard(
                fixture.tenantId(), fixture.catalogAdmin())
                .metrics().resourcesWithoutOwner();
        jdbc.update("""
                UPDATE com_users SET status = 'SUSPENDED'
                 WHERE tenant_id = ? AND user_id = ?
                """, fixture.tenantId(), currentOwner);
        assertThat(generic.dashboard(fixture.tenantId(), fixture.catalogAdmin())
                .metrics().resourcesWithoutOwner())
                .isEqualTo(ownerlessBeforeSuspension + 1);
        jdbc.update("""
                UPDATE com_users SET status = 'ACTIVE'
                 WHERE tenant_id = ? AND user_id = ?
                """, fixture.tenantId(), currentOwner);

        assertThatThrownBy(() -> inTransaction(() -> generic.revokeAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "future-owner-not-effective",
                currentAssignment, genericRevoke(0))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        jdbc.update("""
                UPDATE com_admin_role_assignments
                   SET valid_from = statement_timestamp() - INTERVAL '1 day'
                 WHERE admin_role_assignment_id = ?
                """, replacementAssignment);
        jdbc.update("""
                UPDATE com_users SET status = 'SUSPENDED'
                 WHERE tenant_id = ? AND user_id = ?
                """, fixture.tenantId(), replacementOwner);
        assertThatThrownBy(() -> inTransaction(() -> generic.revokeAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "inactive-owner-not-effective",
                currentAssignment, genericRevoke(0))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        jdbc.update("""
                UPDATE com_users SET status = 'ACTIVE'
                 WHERE tenant_id = ? AND user_id = ?
                """, fixture.tenantId(), replacementOwner);
        assertThat(inTransaction(() -> generic.revokeAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "effective-owner-replacement",
                currentAssignment, genericRevoke(0))).lifecycleState())
                .isEqualTo("REVOKED");

        UUID groupSet = additionalApprovalsResourceSet(fixture.tenantId());
        Long groupProtectedOwner = user(fixture.tenantId(), "group-protected-owner");
        grantResponsibility(
                fixture.tenantId(), groupProtectedOwner, "APP_OWNER", groupSet,
                fixture.catalogAdmin());
        UUID protectedAssignment = responsibilityAssignment(
                fixture.tenantId(), groupProtectedOwner, "APP_OWNER", groupSet);
        Long emptyOwnerGroup = group(fixture.tenantId(), "empty-owner-group");
        grantGroupResponsibility(
                fixture.tenantId(), emptyOwnerGroup, "APP_OWNER", groupSet,
                fixture.catalogAdmin());
        assertThatThrownBy(() -> inTransaction(() -> generic.revokeAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "empty-owner-group",
                protectedAssignment, genericRevoke(0))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        Long activeGroupOwner = user(fixture.tenantId(), "active-group-owner");
        addGroupMember(fixture.tenantId(), emptyOwnerGroup, activeGroupOwner);
        assertThat(inTransaction(() -> generic.revokeAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "effective-owner-group",
                protectedAssignment, genericRevoke(0))).lifecycleState())
                .isEqualTo("REVOKED");
    }

    @Test
    void effectiveUserSodRejectsDirectGroupAndMultipleGroupCrossPrincipalOverlap() {
        AppGovernanceService generic = new AppGovernanceService(jdbc, audit);

        Long directGroupUser = user(fixture.tenantId(), "direct-group-sod-user");
        Long managerGroup = group(fixture.tenantId(), "direct-group-manager");
        addGroupMember(fixture.tenantId(), managerGroup, directGroupUser);
        grantGroupResponsibility(
                fixture.tenantId(), managerGroup, "APP_ACCESS_MANAGER",
                fixture.resourceSetId(), fixture.catalogAdmin());
        assertThatThrownBy(() -> inTransaction(() -> generic.requestAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "direct-group-sod-request",
                genericRequest(directGroupUser, "APP_ACCESS_APPROVER"))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        Long groupDirectUser = user(fixture.tenantId(), "group-direct-sod-user");
        grantResponsibility(
                fixture.tenantId(), groupDirectUser, "APP_ACCESS_MANAGER",
                fixture.resourceSetId(), fixture.catalogAdmin());
        Long reviewerGroup = group(fixture.tenantId(), "group-direct-reviewer");
        addGroupMember(fixture.tenantId(), reviewerGroup, groupDirectUser);
        assertThatThrownBy(() -> inTransaction(() -> generic.requestAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "group-direct-sod-request",
                groupRequest(reviewerGroup, "APP_ACCESS_REVIEWER",
                        fixture.resourceSetId()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        Long multiGroupUser = user(fixture.tenantId(), "multi-group-sod-user");
        Long fulfillerGroup = group(fixture.tenantId(), "multi-group-fulfiller");
        Long approverGroup = group(fixture.tenantId(), "multi-group-approver");
        addGroupMember(fixture.tenantId(), fulfillerGroup, multiGroupUser);
        addGroupMember(fixture.tenantId(), approverGroup, multiGroupUser);
        grantGroupResponsibility(
                fixture.tenantId(), fulfillerGroup, "APP_ACCESS_MANAGER",
                fixture.resourceSetId(), fixture.catalogAdmin());
        assertThatThrownBy(() -> inTransaction(() -> generic.requestAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "multi-group-sod-request",
                groupRequest(approverGroup, "APP_ACCESS_APPROVER",
                        fixture.resourceSetId()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void concurrentCrossPrincipalRequestsCommitExactlyOneSodSide() throws Exception {
        AppGovernanceService generic = new AppGovernanceService(jdbc, audit);
        Long subject = user(fixture.tenantId(), "concurrent-effective-sod-user");
        Long groupId = group(fixture.tenantId(), "concurrent-effective-sod-group");
        addGroupMember(fixture.tenantId(), groupId, subject);
        UUID secondSet = additionalApprovalsResourceSet(fixture.tenantId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AssignmentRequestAttempt> direct = executor.submit(() ->
                    requestConcurrently(
                            generic,
                            genericRequest(subject, "APP_ACCESS_MANAGER"),
                            "concurrent-direct-manager", ready, start));
            Future<AssignmentRequestAttempt> inherited = executor.submit(() ->
                    requestConcurrently(
                            generic,
                            groupRequest(groupId, "APP_ACCESS_APPROVER", secondSet),
                            "concurrent-group-approver", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<AssignmentRequestAttempt> results = List.of(
                    direct.get(30, TimeUnit.SECONDS),
                    inherited.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().filter(AssignmentRequestAttempt::created))
                    .hasSize(1);
            assertThat(results.stream().filter(result ->
                    result.errorCode() == ErrorCode.RESOURCE_CONFLICT)).hasSize(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_role_assignments
                 WHERE tenant_id = ?
                   AND lifecycle_state = 'PENDING_APPROVAL'
                   AND responsibility_code IN (
                       'APP_ACCESS_MANAGER', 'APP_ACCESS_APPROVER')
                   AND (principal_ref = ? OR principal_ref = ?)
                """, Integer.class, fixture.tenantId(), subject.toString(),
                groupId.toString())).isEqualTo(1);
    }

    @Test
    void assignmentRequestRechecksOwnerAuthorityAfterAcquiringTheScopeLock() throws Exception {
        AppGovernanceService generic = new AppGovernanceService(jdbc, audit);
        UUID resourceSetId = additionalApprovalsResourceSet(fixture.tenantId());
        Long owner = user(fixture.tenantId(), "request-authority-owner");
        Long ownerGroup = group(fixture.tenantId(), "request-authority-owner-group");
        addGroupMember(fixture.tenantId(), ownerGroup, owner);
        grantGroupResponsibility(
                fixture.tenantId(), ownerGroup, "APP_OWNER", resourceSetId,
                fixture.catalogAdmin());
        Long subject = user(fixture.tenantId(), "request-authority-subject");
        String applicationName = "core006-request-authority-recheck";
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection authorityRevoker = dataSource.getConnection()) {
            authorityRevoker.setAutoCommit(false);
            lockAssignmentAuthorityBoundary(authorityRevoker, fixture.tenantId());
            Future<AssignmentRequestAttempt> request = executor.submit(() ->
                    requestAsActorConcurrently(
                            generic,
                            owner,
                            genericRequest(subject, "APP_ACCESS_APPROVER", resourceSetId),
                            "request-authority-recheck",
                            applicationName));

            assertThat(awaitBlockedRequest(applicationName)).isEqualTo(1);
            try (PreparedStatement revoke = authorityRevoker.prepareStatement("""
                    DELETE FROM com_group_members
                     WHERE tenant_id = ? AND group_id = ? AND user_id = ?
                    """)) {
                revoke.setLong(1, fixture.tenantId());
                revoke.setLong(2, ownerGroup);
                revoke.setLong(3, owner);
                assertThat(revoke.executeUpdate()).isEqualTo(1);
            }
            authorityRevoker.commit();

            AssignmentRequestAttempt result = request.get(30, TimeUnit.SECONDS);
            assertThat(result.created()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_role_assignments
                 WHERE tenant_id = ? AND principal_type = 'USER' AND principal_ref = ?
                   AND responsibility_code = 'APP_ACCESS_APPROVER'
                   AND resource_set_id = ? AND lifecycle_state = 'PENDING_APPROVAL'
                """, Integer.class, fixture.tenantId(), subject.toString(), resourceSetId))
                .isZero();
    }

    @Test
    void firstExactScopeApproverBootstrapIsOwnerAnchoredOneTimeAndIndependent() {
        AppGovernanceService generic = new AppGovernanceService(jdbc, audit);
        Long secondCatalogAdmin = user(fixture.tenantId(), "bootstrap-catalog-admin");
        grantRole(fixture.tenantId(), secondCatalogAdmin, "APP_CATALOG_ADMIN");

        UUID bootstrapSet = additionalApprovalsResourceSet(fixture.tenantId());
        Long owner = user(fixture.tenantId(), "bootstrap-owner");
        grantResponsibility(
                fixture.tenantId(), owner, "APP_OWNER", bootstrapSet,
                fixture.catalogAdmin());
        Long firstApprover = user(fixture.tenantId(), "bootstrap-first-approver");
        AppGovernanceDtos.Assignment firstPending = inTransaction(() ->
                generic.requestAssignment(
                        fixture.tenantId(), owner, "first-approver-request",
                        genericRequest(
                                firstApprover, "APP_ACCESS_APPROVER", bootstrapSet)));
        assertThat(dashboardAssignment(
                generic, fixture.catalogAdmin(), firstPending.assignmentId())
                .firstApproverBootstrapEligible()).isTrue();
        assertThat(dashboardAssignment(
                generic, secondCatalogAdmin, firstPending.assignmentId())
                .firstApproverBootstrapEligible()).isTrue();
        assertThat(dashboardAssignment(generic, owner, firstPending.assignmentId())
                .firstApproverBootstrapEligible()).isFalse();
        AppGovernanceDtos.Assignment firstActive = inTransaction(() ->
                generic.decideAssignment(
                        fixture.tenantId(), fixture.catalogAdmin(),
                        "first-approver-catalog-decision", firstPending.assignmentId(),
                        genericDecision("APPROVED", firstPending.version())));
        assertThat(firstActive.lifecycleState()).isEqualTo("ACTIVE");
        assertThat(firstActive.approvedBy()).isEqualTo(fixture.catalogAdmin());

        Long secondApprover = user(fixture.tenantId(), "bootstrap-second-approver");
        AppGovernanceDtos.Assignment secondPending = inTransaction(() ->
                generic.requestAssignment(
                        fixture.tenantId(), owner, "second-approver-request",
                        genericRequest(
                                secondApprover, "APP_ACCESS_APPROVER", bootstrapSet)));
        assertThat(dashboardAssignment(
                generic, secondCatalogAdmin, secondPending.assignmentId())
                .firstApproverBootstrapEligible()).isFalse();
        assertThat(dashboardAssignment(
                generic, firstApprover, secondPending.assignmentId())
                .firstApproverBootstrapEligible()).isFalse();
        assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), secondCatalogAdmin,
                "second-approver-catalog-forbidden", secondPending.assignmentId(),
                genericDecision("APPROVED", secondPending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        AppGovernanceDtos.Assignment secondActive = inTransaction(() ->
                generic.decideAssignment(
                        fixture.tenantId(), firstApprover,
                        "second-approver-scoped-decision", secondPending.assignmentId(),
                        genericDecision("APPROVED", secondPending.version())));
        assertThat(secondActive.lifecycleState()).isEqualTo("ACTIVE");

        UUID ownerlessSet = additionalApprovalsResourceSet(fixture.tenantId());
        Long inactiveOwner = user(fixture.tenantId(), "inactive-bootstrap-owner");
        grantResponsibility(
                fixture.tenantId(), inactiveOwner, "APP_OWNER", ownerlessSet,
                fixture.catalogAdmin());
        jdbc.update("""
                UPDATE com_users SET status = 'SUSPENDED'
                 WHERE tenant_id = ? AND user_id = ?
                """, fixture.tenantId(), inactiveOwner);
        Long ownerlessCandidate = user(fixture.tenantId(), "ownerless-approver");
        AppGovernanceDtos.Assignment ownerlessPending = inTransaction(() ->
                generic.requestAssignment(
                        fixture.tenantId(), fixture.catalogAdmin(),
                        "ownerless-approver-request",
                        genericRequest(
                                ownerlessCandidate, "APP_ACCESS_APPROVER", ownerlessSet)));
        assertThat(dashboardAssignment(
                generic, secondCatalogAdmin, ownerlessPending.assignmentId())
                .firstApproverBootstrapEligible()).isFalse();
        assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), secondCatalogAdmin,
                "ownerless-approver-catalog-forbidden", ownerlessPending.assignmentId(),
                genericDecision("APPROVED", ownerlessPending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        UUID selfApprovalSet = additionalApprovalsResourceSet(fixture.tenantId());
        grantResponsibility(
                fixture.tenantId(), secondCatalogAdmin, "APP_OWNER", selfApprovalSet,
                fixture.catalogAdmin());
        Long selfApprovalTarget = user(fixture.tenantId(), "self-approval-target");
        AppGovernanceDtos.Assignment selfPending = inTransaction(() ->
                generic.requestAssignment(
                        fixture.tenantId(), secondCatalogAdmin,
                        "first-approver-self-request",
                        genericRequest(
                                selfApprovalTarget, "APP_ACCESS_APPROVER", selfApprovalSet)));
        assertThat(dashboardAssignment(
                generic, secondCatalogAdmin, selfPending.assignmentId())
                .firstApproverBootstrapEligible()).isFalse();
        assertThat(dashboardAssignment(
                generic, fixture.catalogAdmin(), selfPending.assignmentId())
                .firstApproverBootstrapEligible()).isTrue();
        assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), secondCatalogAdmin,
                "first-approver-self-decision", selfPending.assignmentId(),
                genericDecision("APPROVED", selfPending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void concurrentFirstApproverDecisionsActivateExactlyOneAssignment() throws Exception {
        AppGovernanceService generic = new AppGovernanceService(jdbc, audit);
        Long secondCatalogAdmin = user(fixture.tenantId(), "concurrent-catalog-admin");
        grantRole(fixture.tenantId(), secondCatalogAdmin, "APP_CATALOG_ADMIN");
        UUID resourceSetId = additionalApprovalsResourceSet(fixture.tenantId());
        Long owner = user(fixture.tenantId(), "concurrent-bootstrap-owner");
        grantResponsibility(
                fixture.tenantId(), owner, "APP_OWNER", resourceSetId,
                fixture.catalogAdmin());
        Long firstSubject = user(fixture.tenantId(), "concurrent-first-approver");
        Long secondSubject = user(fixture.tenantId(), "concurrent-second-approver");
        AppGovernanceDtos.Assignment firstPending = inTransaction(() ->
                generic.requestAssignment(
                        fixture.tenantId(), owner, "concurrent-first-request",
                        genericRequest(
                                firstSubject, "APP_ACCESS_APPROVER", resourceSetId)));
        AppGovernanceDtos.Assignment secondPending = inTransaction(() ->
                generic.requestAssignment(
                        fixture.tenantId(), owner, "concurrent-second-request",
                        genericRequest(
                                secondSubject, "APP_ACCESS_APPROVER", resourceSetId)));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection boundaryBlocker = dataSource.getConnection()) {
            boundaryBlocker.setAutoCommit(false);
            lockResourceSetBoundary(boundaryBlocker, fixture.tenantId(), resourceSetId);

            Future<DecisionAttempt> first = executor.submit(() -> decideConcurrently(
                    generic, fixture.catalogAdmin(), firstPending, ready, start));
            Future<DecisionAttempt> second = executor.submit(() -> decideConcurrently(
                    generic, secondCatalogAdmin, secondPending, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(awaitBlockedFirstApproverDecisions()).isEqualTo(2);
            boundaryBlocker.commit();

            List<DecisionAttempt> results = List.of(
                    first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().filter(DecisionAttempt::active)).hasSize(1);
            assertThat(results.stream().filter(
                    result -> result.errorCode() == ErrorCode.FORBIDDEN)).hasSize(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_role_assignments
                 WHERE tenant_id = ? AND resource_set_id = ?
                   AND responsibility_code = 'APP_ACCESS_APPROVER'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class, fixture.tenantId(), resourceSetId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_role_assignments
                 WHERE tenant_id = ? AND resource_set_id = ?
                   AND responsibility_code = 'APP_ACCESS_APPROVER'
                   AND lifecycle_state = 'PENDING_APPROVAL'
                """, Integer.class, fixture.tenantId(), resourceSetId)).isEqualTo(1);
    }

    @Test
    void scopedApproverCannotApproveAControlResponsibilityForTheirOwnGroup() {
        AppGovernanceService generic = new AppGovernanceService(jdbc, audit);
        Long groupId = jdbc.queryForObject("""
                INSERT INTO com_groups (
                    tenant_id, group_key, display_name, source_type, status)
                VALUES (?, ?, 'Approver target group', 'LOCAL', 'ACTIVE')
                RETURNING group_id
                """, Long.class, fixture.tenantId(),
                "SELF_APPROVAL_GROUP_" + UUID.randomUUID());
        jdbc.update("""
                INSERT INTO com_group_members (
                    tenant_id, group_id, user_id, source_type)
                VALUES (?, ?, ?, 'LOCAL')
                """, fixture.tenantId(), groupId, fixture.approver());
        AppGovernanceDtos.Assignment pending = inTransaction(() ->
                generic.requestAssignment(
                        fixture.tenantId(), fixture.catalogAdmin(),
                        "group-control-request",
                        new AppGovernanceDtos.CreateAssignmentRequest(
                                "GROUP", groupId.toString(), "APP_ACCESS_REVIEWER",
                                fixture.resourceSetId(), VALID_TO,
                                "Create a governed reviewer responsibility for the group.")));

        assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), fixture.approver(), "group-self-approval",
                pending.assignmentId(), genericDecision("APPROVED", pending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM com_admin_role_assignments
                 WHERE tenant_id = ? AND admin_role_assignment_id = ?
                """, String.class, fixture.tenantId(), pending.assignmentId()))
                .isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void dashboardsProjectOnlyExactResponsibilityAndReviewerResourceSets() {
        Long scopedViewer = user(fixture.tenantId(), "scoped-viewer");
        grantResponsibility(
                fixture.tenantId(), scopedViewer, "APP_OWNER",
                fixture.resourceSetId(), fixture.catalogAdmin());
        grantResponsibility(
                fixture.tenantId(), scopedViewer, "APP_ACCESS_REVIEWER",
                fixture.resourceSetId(), fixture.catalogAdmin());
        UUID hcmSet = productResourceSet(
                fixture.tenantId(), "APP.HCM", "RS_HCM_SECONDARY", "HCM secondary");
        Long assignedSubject = user(fixture.tenantId(), "scoped-assignment-subject");
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), scopedViewer, "scoped-dashboard-request",
                        governedRequest(assignedSubject, "APPROVAL_AUDITOR")));
        UUID visibleReview = review(
                assignedSubject, "APPROVAL_OPERATIONS_AUDIT",
                "VISIBLE_EXACT_SCOPE", fixture.resourceSetId());
        review(assignedSubject, "APPROVAL_OPERATIONS_AUDIT",
                "HIDDEN_OTHER_SCOPE", hcmSet);

        AppAdminPresetService.DashboardProjection projection =
                service.dashboard(fixture.tenantId(), scopedViewer);
        assertThat(projection.catalog())
                .hasSize(4)
                .allMatch(value -> "APP.APPROVALS".equals(value.appResourceKey()));
        assertThat(projection.assignments())
                .extracting(AppGovernanceDtos.AppAdminPresetAssignment::presetAssignmentId)
                .containsExactly(pending.presetAssignmentId());
        assertThat(projection.reviews())
                .extracting(AppGovernanceDtos.AppAdminPresetReview::reviewId)
                .containsExactly(visibleReview);
        AppGovernanceDtos.AppAdminPresetReview review = projection.reviews().getFirst();
        assertThat(review.resourceSetId()).isEqualTo(fixture.resourceSetId());
        assertThat(review.resourceSetName()).isEqualTo("Approvals");

        AppGovernanceDtos.Dashboard base =
                new AppGovernanceService(jdbc, audit)
                        .dashboard(fixture.tenantId(), scopedViewer);
        assertThat(base.resourceSets())
                .extracting(AppGovernanceDtos.ResourceSet::resourceSetId)
                .containsExactly(fixture.resourceSetId());
        assertThat(base.assignments())
                .allMatch(value -> value.resourceSetId().equals(fixture.resourceSetId()));
        assertThat(base.responsibilities())
                .extracting(AppGovernanceDtos.Responsibility::code)
                .containsExactlyInAnyOrder(
                        "APP_OWNER", "APP_ACCESS_APPROVER",
                        "APP_ACCESS_MANAGER", "APP_ACCESS_REVIEWER")
                .doesNotContain("APP_CONFIG_ADMIN");

        AppGovernanceDtos.Dashboard reviewerDashboard =
                new AppGovernanceService(jdbc, audit)
                        .dashboard(fixture.tenantId(), fixture.reviewer());
        assertThat(reviewerDashboard.responsibilities()).isEmpty();
        assertThat(reviewerDashboard.principals()).isEmpty();
    }

}

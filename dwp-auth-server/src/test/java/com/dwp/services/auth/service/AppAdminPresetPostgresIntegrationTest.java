package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.repository.AppAdminPresetRepository;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Clean PostgreSQL proof for the atomic preset, self-service, and outbox contract. */
@Testcontainers(disabledWithoutDocker = true)
class AppAdminPresetPostgresIntegrationTest extends AppAdminPresetPostgresFixture {

    @Test
    void approvesActivatesAndRevokesOneResponsibilityAndExactDutyPackageAtomically() {
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.requester(), "preset-request-1",
                        governedRequest(fixture.subject(), "APPROVAL_DESIGNER")));

        assertThat(pending.lifecycleState()).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.requestChannel()).isEqualTo("GOVERNANCE");
        assertThat(pending.duties())
                .extracting(AppGovernanceDtos.AppAdminPresetDutyAssignment::dutyCode)
                .containsExactly("APPROVAL_DESIGN_DRAFT", "APPROVAL_POLICY_DRAFT");
        assertBundleStates(pending.presetAssignmentId(), "PENDING_APPROVAL", 2);

        assertThatThrownBy(() -> inTransaction(() -> service.decide(
                fixture.tenantId(), fixture.requester(), "self-approve",
                pending.presetAssignmentId(), decision("APPROVED", pending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));
        assertBundleStates(pending.presetAssignmentId(), "PENDING_APPROVAL", 2);

        AppGovernanceDtos.AppAdminPresetAssignment approved = inTransaction(() -> service.decide(
                fixture.tenantId(), fixture.approver(), "preset-approve-1",
                pending.presetAssignmentId(), decision("APPROVED", pending.version())));
        assertThat(approved.lifecycleState()).isEqualTo("APPROVED");
        assertBundleStates(approved.presetAssignmentId(), "APPROVED", 2);
        assertThat(effectiveDuties(fixture.subject())).isEmpty();
        assertThat(accessRevision(fixture.subject())).isZero();
        assertThatThrownBy(() -> inTransaction(() -> service.activate(
                fixture.tenantId(), fixture.approver(), "approver-activate-1",
                approved.presetAssignmentId(), activation(approved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> inTransaction(() -> service.activate(
                fixture.tenantId(), fixture.requester(), "requester-activate-1",
                approved.presetAssignmentId(), activation(approved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertOutbox(approved.presetAssignmentId(),
                List.of(AppAdminPresetOutboxPublisher.REQUESTED,
                        AppAdminPresetOutboxPublisher.DECIDED), List.of(1L, 2L));

        AppGovernanceDtos.AppAdminPresetAssignment active = inTransaction(() ->
                service.activate(
                        fixture.tenantId(), fixture.fulfiller(), "preset-activate-1",
                        approved.presetAssignmentId(), activation(approved.version())));
        assertThat(active.lifecycleState()).isEqualTo("ACTIVE");
        assertThat(active.activatedBy()).isEqualTo(fixture.fulfiller());
        assertBundleStates(active.presetAssignmentId(), "ACTIVE", 2);
        assertThat(effectiveDuties(fixture.subject()))
                .containsExactlyInAnyOrder(
                        "APPROVAL_DESIGN_DRAFT", "APPROVAL_POLICY_DRAFT");
        assertThat(accessRevision(fixture.subject())).isEqualTo(1);
        assertOutbox(active.presetAssignmentId(),
                List.of(AppAdminPresetOutboxPublisher.REQUESTED,
                        AppAdminPresetOutboxPublisher.DECIDED,
                        AppAdminPresetOutboxPublisher.ACTIVATED), List.of(1L, 2L, 3L));

        AppGovernanceDtos.AppAdminPresetAssignment revoked = inTransaction(() -> service.revoke(
                fixture.tenantId(), fixture.fulfiller(), "preset-revoke-1",
                active.presetAssignmentId(), new AppGovernanceDtos.RevokeAppAdminPresetRequest(
                        "The design responsibility has ended.", active.version())));
        assertThat(revoked.lifecycleState()).isEqualTo("REVOKED");
        assertBundleStates(revoked.presetAssignmentId(), "REVOKED", 2);
        assertThat(effectiveDuties(fixture.subject())).isEmpty();
        assertThat(accessRevision(fixture.subject())).isEqualTo(2);
        assertOutbox(revoked.presetAssignmentId(),
                List.of(AppAdminPresetOutboxPublisher.REQUESTED,
                        AppAdminPresetOutboxPublisher.DECIDED,
                        AppAdminPresetOutboxPublisher.ACTIVATED,
                        AppAdminPresetOutboxPublisher.REVOKED), List.of(1L, 2L, 3L, 4L));
        verify(audit).success(anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.eq("access.app-admin-preset.approved"),
                anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void hrisContractIssuerPresetMintsTheExactRealSessionDutyWithoutEnrollment() {
        UUID hcmSet = productResourceSet(
                fixture.tenantId(), "APP.HCM", "RS_HRIS_CONTRACT_ISSUERS_LE_100",
                "HRIS contract issuers LE 100");
        grantResponsibility(
                fixture.tenantId(), fixture.approver(), "APP_ACCESS_APPROVER",
                hcmSet, fixture.catalogAdmin());
        grantResponsibility(
                fixture.tenantId(), fixture.fulfiller(), "APP_ACCESS_MANAGER",
                hcmSet, fixture.catalogAdmin());

        var request = new AppGovernanceDtos.CreateAppAdminPresetAssignmentRequest(
                "USER", fixture.subject().toString(), "HRIS_CONTRACT_ISSUER", hcmSet,
                VALID_TO, REVIEW_DUE,
                "Assign independently governed employment contract issuance authority.");
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.catalogAdmin(),
                        "hris-contract-issuer-request", request));
        assertThat(pending.duties())
                .extracting(AppGovernanceDtos.AppAdminPresetDutyAssignment::dutyCode)
                .containsExactly("HRIS_DUTY_CONTRACT_ISSUER");

        AppGovernanceDtos.AppAdminPresetAssignment approved = inTransaction(() ->
                service.decide(
                        fixture.tenantId(), fixture.approver(),
                        "hris-contract-issuer-approve", pending.presetAssignmentId(),
                        decision("APPROVED", pending.version())));
        AppGovernanceDtos.AppAdminPresetAssignment active = inTransaction(() ->
                service.activate(
                        fixture.tenantId(), fixture.fulfiller(),
                        "hris-contract-issuer-activate", approved.presetAssignmentId(),
                        activation(approved.version())));

        assertThat(active.lifecycleState()).isEqualTo("ACTIVE");
        ScopedAdminDutyEvidenceService evidence = new ScopedAdminDutyEvidenceService(jdbc);
        assertThat(evidence.effectiveDuties(fixture.tenantId(), fixture.subject()))
                .singleElement()
                .satisfies(duty -> {
                    assertThat(duty.dutyCode()).isEqualTo("HRIS_DUTY_CONTRACT_ISSUER");
                    assertThat(duty.productResourceKey()).isEqualTo("APP.HCM");
                    assertThat(duty.resourceKey())
                            .isEqualTo("ACTION.HRIS_EMPLOYMENT_CONTRACT");
                    assertThat(duty.resourceSetKey())
                            .isEqualTo("RS_HRIS_CONTRACT_ISSUERS_LE_100");
                    assertThat(duty.capabilityAuthorities())
                            .containsEntry(
                                    "hcm.operations.contract.issue",
                                    "ACTION.HRIS_EMPLOYMENT_CONTRACT:ISSUE");
                });
        assertThat(evidence.resourceRoles(fixture.tenantId(), fixture.subject()))
                .singleElement()
                .satisfies(role -> {
                    assertThat(role.responsibilityCode())
                            .isEqualTo("HRIS_DUTY_CONTRACT_ISSUER");
                    assertThat(role.resourceSetKey())
                            .isEqualTo("RS_HRIS_CONTRACT_ISSUERS_LE_100");
                });
        assertThat(evidence.capabilityPermissions(fixture.tenantId(), fixture.subject()))
                .singleElement()
                .satisfies(permission -> {
                    assertThat(permission.getResourceKey())
                            .isEqualTo("ACTION.HRIS_EMPLOYMENT_CONTRACT");
                    assertThat(permission.getPermissionCode()).isEqualTo("ISSUE");
                });
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_tenant_feature_enrollment_changes
                 WHERE tenant_id = ?
                """, Integer.class, fixture.tenantId())).isZero();
    }

    @Test
    void hrisStatutoryOperatorPresetMintsTheExactRealSessionDutyWithoutEnrollment() {
        UUID hcmSet = productResourceSet(
                fixture.tenantId(), "APP.HCM", "RS_HRIS_STATUTORY_OPERATOR_LE_100_PG_7",
                "HRIS statutory operator LE 100 pay group 7");
        grantResponsibility(
                fixture.tenantId(), fixture.approver(), "APP_ACCESS_APPROVER",
                hcmSet, fixture.catalogAdmin());
        grantResponsibility(
                fixture.tenantId(), fixture.fulfiller(), "APP_ACCESS_MANAGER",
                hcmSet, fixture.catalogAdmin());

        var request = new AppGovernanceDtos.CreateAppAdminPresetAssignmentRequest(
                "USER", fixture.subject().toString(), "HRIS_STATUTORY_OPERATOR", hcmSet,
                VALID_TO, REVIEW_DUE,
                "Assign independently governed statutory country-pack activation authority.");
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.catalogAdmin(),
                        "hris-statutory-operator-request", request));
        assertThat(pending.duties())
                .extracting(AppGovernanceDtos.AppAdminPresetDutyAssignment::dutyCode)
                .containsExactly("HRIS_DUTY_STATUTORY_OPERATOR");

        AppGovernanceDtos.AppAdminPresetAssignment approved = inTransaction(() ->
                service.decide(
                        fixture.tenantId(), fixture.approver(),
                        "hris-statutory-operator-approve", pending.presetAssignmentId(),
                        decision("APPROVED", pending.version())));
        AppGovernanceDtos.AppAdminPresetAssignment active = inTransaction(() ->
                service.activate(
                        fixture.tenantId(), fixture.fulfiller(),
                        "hris-statutory-operator-activate", approved.presetAssignmentId(),
                        activation(approved.version())));

        assertThat(active.lifecycleState()).isEqualTo("ACTIVE");
        ScopedAdminDutyEvidenceService evidence = new ScopedAdminDutyEvidenceService(jdbc);
        assertThat(evidence.effectiveDuties(fixture.tenantId(), fixture.subject()))
                .singleElement()
                .satisfies(duty -> {
                    assertThat(duty.dutyCode()).isEqualTo("HRIS_DUTY_STATUTORY_OPERATOR");
                    assertThat(duty.productResourceKey()).isEqualTo("APP.HCM");
                    assertThat(duty.resourceKey())
                            .isEqualTo("ACTION.HRIS_STATUTORY_FILING");
                    assertThat(duty.resourceSetKey())
                            .isEqualTo("RS_HRIS_STATUTORY_OPERATOR_LE_100_PG_7");
                    assertThat(duty.capabilityAuthorities())
                            .containsExactly(Map.entry(
                                    "hcm.operations.statutory.execute",
                                    "ACTION.HRIS_STATUTORY_FILING:EXECUTE"));
                });
        assertThat(evidence.resourceRoles(fixture.tenantId(), fixture.subject()))
                .singleElement()
                .satisfies(role -> {
                    assertThat(role.responsibilityCode())
                            .isEqualTo("HRIS_DUTY_STATUTORY_OPERATOR");
                    assertThat(role.resourceSetKey())
                            .isEqualTo("RS_HRIS_STATUTORY_OPERATOR_LE_100_PG_7");
                });
        assertThat(evidence.capabilityPermissions(fixture.tenantId(), fixture.subject()))
                .singleElement()
                .satisfies(permission -> {
                    assertThat(permission.getResourceKey())
                            .isEqualTo("ACTION.HRIS_STATUTORY_FILING");
                    assertThat(permission.getPermissionCode()).isEqualTo("EXECUTE");
                });
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_tenant_feature_enrollment_changes
                 WHERE tenant_id = ?
                """, Integer.class, fixture.tenantId())).isZero();
    }

    @Test
    void selfServiceForcesActorPendingAndReplaysOnlyAnIdenticalRequest() {
        List<AppGovernanceDtos.AppAdminPresetSelfServiceOption> options =
                requestService.selfServiceOptions(
                        fixture.tenantId(), fixture.member(), "APP.APPROVALS");
        assertThat(options).hasSize(4);
        assertThat(options)
                .allMatch(option -> option.preset().requestable()
                        && option.preset().appResourceKey().equals("APP.APPROVALS")
                        && option.resourceSets().stream().anyMatch(set ->
                                set.resourceSetId().equals(fixture.resourceSetId())));
        List<AppGovernanceDtos.AppAdminPresetSelfServiceOption> hcmOptions =
                requestService.selfServiceOptions(
                        fixture.tenantId(), fixture.member(), "APP.HCM");
        assertThat(hcmOptions)
                .extracting(option -> option.preset().presetCode())
                .containsExactlyInAnyOrder(
                        "HRIS_CONTRACT_ISSUER", "HRIS_STATUTORY_OPERATOR",
                        "HRIS_INTEGRATION_EXECUTOR", "HRIS_INTEGRATION_AUTHOR",
                        "HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR");
        assertThat(hcmOptions).allSatisfy(option -> {
            assertThat(option.preset().requestable()).isTrue();
            assertThat(option.preset().productKey()).isEqualTo("hcm");
            assertThat(option.preset().appResourceKey()).isEqualTo("APP.HCM");
            assertThat(option.resourceSets()).isEmpty();
            assertThatThrownBy(() -> inTransaction(() -> requestService.requestSelfService(
                    fixture.tenantId(), fixture.member(),
                    "hcm-wrong-app-" + option.preset().presetCode(),
                    "self-hcm-boundary-" + option.preset().presetCode(),
                    new AppGovernanceDtos.CreateSelfServicePresetRequest(
                            option.preset().presetCode(), fixture.resourceSetId(),
                            VALID_TO, REVIEW_DUE,
                            "An Approval scope cannot grant an HCM specialist duty."))))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode())
                                    .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
            assertThat(aggregateCount(fixture.member(), option.preset().presetCode())).isZero();
        });
        assertThat(openResponsibilityCount(fixture.member())).isZero();
        assertThat(effectiveDuties(fixture.member())).isEmpty();

        AppGovernanceDtos.CreateSelfServicePresetRequest request =
                new AppGovernanceDtos.CreateSelfServicePresetRequest(
                        "APPROVAL_OPERATOR", fixture.resourceSetId(), VALID_TO,
                        REVIEW_DUE, "Request time-bound approval operations responsibility.");
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestSelfService(
                        fixture.tenantId(), fixture.member(), "self-request-1",
                        "self-service-key-0001", request));
        AppGovernanceDtos.AppAdminPresetAssignment replay = inTransaction(() ->
                requestService.requestSelfService(
                        fixture.tenantId(), fixture.member(), "self-replay-1",
                        "self-service-key-0001", request));

        assertThat(replay.presetAssignmentId()).isEqualTo(pending.presetAssignmentId());
        assertThat(pending.principalType()).isEqualTo("USER");
        assertThat(pending.principalRef()).isEqualTo(fixture.member().toString());
        assertThat(pending.requestChannel()).isEqualTo("SELF_SERVICE");
        assertThat(pending.lifecycleState()).isEqualTo("PENDING_APPROVAL");
        assertThat(aggregateCount(fixture.member(), "APPROVAL_OPERATOR")).isEqualTo(1);
        assertOutbox(pending.presetAssignmentId(),
                List.of(AppAdminPresetOutboxPublisher.REQUESTED), List.of(1L));
        assertThat(service.assignment(
                fixture.tenantId(), fixture.member(), pending.presetAssignmentId()))
                .isEqualTo(pending);
        assertThatThrownBy(() -> service.assignments(fixture.tenantId(), fixture.member()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> inTransaction(() -> requestService.requestSelfService(
                fixture.tenantId(), fixture.member(), "self-duplicate-1",
                "self-service-key-0002", request)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(aggregateCount(fixture.member(), "APPROVAL_OPERATOR")).isEqualTo(1);

        AppGovernanceDtos.CreateSelfServicePresetRequest changed =
                new AppGovernanceDtos.CreateSelfServicePresetRequest(
                        "APPROVAL_OPERATOR", fixture.resourceSetId(), VALID_TO,
                        REVIEW_DUE, "A materially different justification for this key.");
        assertThatThrownBy(() -> inTransaction(() -> requestService.requestSelfService(
                fixture.tenantId(), fixture.member(), "self-conflict-1",
                "self-service-key-0001", changed)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> inTransaction(() -> service.decide(
                fixture.tenantId(), fixture.member(), "self-decision-1",
                pending.presetAssignmentId(), decision("APPROVED", pending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertBundleStates(pending.presetAssignmentId(), "PENDING_APPROVAL", 2);

        AppGovernanceDtos.AppAdminPresetAssignment approved = inTransaction(() -> service.decide(
                fixture.tenantId(), fixture.approver(), "self-approved-1",
                pending.presetAssignmentId(), decision("APPROVED", pending.version())));
        assertThat(approved.lifecycleState()).isEqualTo("APPROVED");
        assertThat(effectiveDuties(fixture.member())).isEmpty();
        assertThatThrownBy(() -> inTransaction(() -> service.activate(
                fixture.tenantId(), fixture.approver(), "self-approver-activate-1",
                approved.presetAssignmentId(), activation(approved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        AppGovernanceDtos.AppAdminPresetAssignment active = inTransaction(() ->
                service.activate(
                        fixture.tenantId(), fixture.fulfiller(), "self-activated-1",
                        approved.presetAssignmentId(), activation(approved.version())));
        assertThat(active.lifecycleState()).isEqualTo("ACTIVE");
        assertThat(effectiveDuties(fixture.member()))
                .containsExactlyInAnyOrder(
                        "APPROVAL_OPERATIONS_EXECUTE", "APPROVAL_SIGNATURE_READ");
    }

    @Test
    void providerIdentityCannotReadOrCreateTenantSelfServicePresets() {
        Long providerUser = jdbc.queryForObject("""
                SELECT user_id
                  FROM com_users
                 WHERE tenant_id = 1 AND email_normalized = 'admin@dwp.local'
                """, Long.class);

        assertThatThrownBy(() -> requestService.selfServiceOptions(
                1L, providerUser, "APP.APPROVALS"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void sodDenialAuditAndOutboxCommitIndependentlyWithCorrelationEvidence() {
        String correlationId = "preset-sod-denied-audit-1";

        assertThatThrownBy(() -> inTransaction(() -> requestService.requestGoverned(
                fixture.tenantId(), fixture.requester(), correlationId,
                governedRequest(fixture.requester(), "APPROVAL_DESIGNER"))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));

        DenialEvent denial = jdbc.queryForObject("""
                SELECT audit_event_id, action, outcome, correlation_id, reason
                  FROM sys_identity_audit_events
                 WHERE tenant_id = ? AND correlation_id = ?
                """, (result, ignored) -> new DenialEvent(
                result.getObject("audit_event_id", UUID.class),
                result.getString("action"), result.getString("outcome"),
                result.getString("correlation_id"), result.getString("reason"), null),
                fixture.tenantId(), correlationId);
        assertThat(denial)
                .extracting(DenialEvent::action, DenialEvent::outcome,
                        DenialEvent::correlationId)
                .containsExactly(
                        "access.app-admin-preset.request-denied",
                        "DENIED", correlationId);
        assertThat(denial.reason()).contains("Self-fulfilment");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_audit_outbox
                 WHERE event_id = ? AND payload ->> 'correlationId' = ?
                """, Integer.class, denial.auditEventId(), correlationId)).isEqualTo(1);
        verify(audit).denied(
                eq(fixture.tenantId()), eq(fixture.requester()),
                eq("access.app-admin-preset.request-denied"),
                eq("APP_ADMIN_PRESET_ASSIGNMENT"),
                eq(fixture.resourceSetId().toString()),
                eq(correlationId),
                eq("Self-fulfilment of an app administrator preset is forbidden."),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void authorizationDenialPersistsMachineReasonCorrelationAndOperation() throws Exception {
        String correlationId = "preset-authority-denied-audit-1";

        assertThatThrownBy(() -> inTransaction(() -> requestService.requestGoverned(
                fixture.tenantId(), fixture.member(), correlationId,
                governedRequest(fixture.subject(), "APPROVAL_DESIGNER"))))
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(exception.getMessage())
                            .isEqualTo("CATALOG_ADMIN_OR_APP_OWNER_REQUIRED");
                });

        DenialEvent denial = jdbc.queryForObject("""
                SELECT audit_event_id, action, outcome, correlation_id, reason,
                       after_snapshot::text AS after_snapshot
                  FROM sys_identity_audit_events
                 WHERE tenant_id = ? AND correlation_id = ?
                """, (result, ignored) -> new DenialEvent(
                result.getObject("audit_event_id", UUID.class),
                result.getString("action"), result.getString("outcome"),
                result.getString("correlation_id"), result.getString("reason"),
                result.getString("after_snapshot")),
                fixture.tenantId(), correlationId);
        assertThat(denial)
                .extracting(DenialEvent::action, DenialEvent::outcome,
                        DenialEvent::correlationId, DenialEvent::reason)
                .containsExactly(
                        "access.app-admin-preset.request-denied",
                        "DENIED", correlationId,
                        "CATALOG_ADMIN_OR_APP_OWNER_REQUIRED");
        assertThat(objectMapper.readTree(denial.afterSnapshot()).path("errorCode").asText())
                .isEqualTo("FORBIDDEN");
        assertThat(objectMapper.readTree(denial.afterSnapshot()).path("operation").asText())
                .isEqualTo("access.app-admin-preset.request-denied");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_audit_outbox
                 WHERE event_id = ? AND payload ->> 'correlationId' = ?
                """, Integer.class, denial.auditEventId(), correlationId)).isEqualTo(1);
    }

    @Test
    void denialAndOutboxFailureNeverLeavePartialResponsibilityOrDutyState() {
        Long deniedSubject = user(fixture.tenantId(), "denied-subject");
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.requester(), "deny-request-1",
                        governedRequest(deniedSubject, "APPROVAL_AUDITOR")));
        AppGovernanceDtos.AppAdminPresetAssignment denied = inTransaction(() -> {
            jdbc.update("""
                    UPDATE sys_admin_app_preset_catalog SET version = version + 1
                     WHERE preset_code = 'APPROVAL_AUDITOR'
                    """);
            AppGovernanceDtos.AppAdminPresetAssignment result = service.decide(
                    fixture.tenantId(), fixture.approver(), "deny-decision-1",
                    pending.presetAssignmentId(), decision("DENIED", pending.version()));
            jdbc.update("""
                    UPDATE sys_admin_app_preset_catalog SET version = version - 1
                     WHERE preset_code = 'APPROVAL_AUDITOR'
                    """);
            return result;
        });
        assertThat(denied.lifecycleState()).isEqualTo("DENIED");
        assertBundleStates(denied.presetAssignmentId(), "DENIED", 1);
        assertThat(effectiveDuties(deniedSubject)).isEmpty();

        Long rollbackSubject = user(fixture.tenantId(), "rollback-subject");
        AppAdminPresetOutboxPublisher failingEvents = mock(AppAdminPresetOutboxPublisher.class);
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(failingEvents).assignment(anyString(), anyLong(), any(),
                        org.mockito.ArgumentMatchers.anyLong(), anyString());
        AppAdminPresetRepository repository = new AppAdminPresetRepository(jdbc, objectMapper);
        AppAdminPresetRequestService failing = new AppAdminPresetRequestService(
                jdbc, repository, new ScopedAdminDutyAssignmentService(jdbc),
                audit, failingEvents);
        assertThatThrownBy(() -> inTransaction(() -> failing.requestGoverned(
                fixture.tenantId(), fixture.requester(), "rollback-request-1",
                governedRequest(rollbackSubject, "APPROVAL_PUBLISHER"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox unavailable");
        assertThat(aggregateCount(rollbackSubject, "APPROVAL_PUBLISHER")).isZero();
        assertThat(openResponsibilityCount(rollbackSubject)).isZero();
        assertThat(openDutyCount(rollbackSubject)).isZero();
    }

    @Test
    void approvedCancellationIsAtomicAndNeverInvalidatesAnIneffectivePrincipal() {
        Long subject = user(fixture.tenantId(), "approved-cancel-subject");
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.requester(), "cancel-request-1",
                        governedRequest(subject, "APPROVAL_OPERATOR")));
        AppGovernanceDtos.AppAdminPresetAssignment approved = inTransaction(() ->
                service.decide(
                        fixture.tenantId(), fixture.approver(), "cancel-approved-1",
                        pending.presetAssignmentId(),
                        decision("APPROVED", pending.version())));

        assertThat(accessRevision(subject)).isZero();
        assertThatThrownBy(() -> inTransaction(() -> service.revoke(
                fixture.tenantId(), fixture.approver(), "cancel-approver-revoke",
                approved.presetAssignmentId(),
                new AppGovernanceDtos.RevokeAppAdminPresetRequest(
                        "The approver cannot cancel through the manager API.",
                        approved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        AppGovernanceDtos.AppAdminPresetAssignment cancelled = inTransaction(() ->
                service.revoke(
                        fixture.tenantId(), fixture.fulfiller(), "cancel-manager-revoke",
                        approved.presetAssignmentId(),
                        new AppGovernanceDtos.RevokeAppAdminPresetRequest(
                                "Cancel the approved package before any access is activated.",
                                approved.version())));

        assertThat(cancelled.lifecycleState()).isEqualTo("REVOKED");
        assertBundleStates(cancelled.presetAssignmentId(), "REVOKED", 2);
        assertThat(effectiveDuties(subject)).isEmpty();
        assertThat(accessRevision(subject)).isZero();
        assertOutbox(cancelled.presetAssignmentId(),
                List.of(AppAdminPresetOutboxPublisher.REQUESTED,
                        AppAdminPresetOutboxPublisher.DECIDED,
                        AppAdminPresetOutboxPublisher.REVOKED), List.of(1L, 2L, 3L));
    }

    @Test
    void activatorMustBeIndependentFromRequesterAndTargetEvenWithExactManagerScope() {
        Long ownerManager = user(fixture.tenantId(), "owner-manager");
        grantResponsibility(
                fixture.tenantId(), ownerManager, "APP_OWNER",
                fixture.resourceSetId(), fixture.catalogAdmin());
        grantResponsibility(
                fixture.tenantId(), ownerManager, "APP_ACCESS_MANAGER",
                fixture.resourceSetId(), fixture.catalogAdmin());
        Long firstSubject = user(fixture.tenantId(), "requester-independence-subject");
        AppGovernanceDtos.AppAdminPresetAssignment requesterPending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), ownerManager, "independent-requester-request",
                        governedRequest(firstSubject, "APPROVAL_DESIGNER")));
        AppGovernanceDtos.AppAdminPresetAssignment requesterApproved = inTransaction(() ->
                service.decide(
                        fixture.tenantId(), fixture.approver(),
                        "independent-requester-approved",
                        requesterPending.presetAssignmentId(),
                        decision("APPROVED", requesterPending.version())));
        assertThatThrownBy(() -> inTransaction(() -> service.activate(
                fixture.tenantId(), ownerManager, "requester-self-fulfil",
                requesterApproved.presetAssignmentId(),
                activation(requesterApproved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));

        AppGovernanceDtos.AppAdminPresetAssignment targetPending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.requester(), "target-request",
                        governedRequest(fixture.fulfiller(), "APPROVAL_OPERATOR")));
        AppGovernanceDtos.AppAdminPresetAssignment targetApproved = inTransaction(() ->
                service.decide(
                        fixture.tenantId(), fixture.approver(), "target-approved",
                        targetPending.presetAssignmentId(),
                        decision("APPROVED", targetPending.version())));
        assertThatThrownBy(() -> inTransaction(() -> service.activate(
                fixture.tenantId(), fixture.fulfiller(), "target-self-fulfil",
                targetApproved.presetAssignmentId(), activation(targetApproved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));

        Long approverSubject = user(fixture.tenantId(), "approver-independence-subject");
        AppGovernanceDtos.AppAdminPresetAssignment approverPending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.requester(), "approver-request",
                        governedRequest(approverSubject, "APPROVAL_AUDITOR")));
        AppGovernanceDtos.AppAdminPresetAssignment approverApproved = inTransaction(() ->
                service.decide(
                        fixture.tenantId(), fixture.approver(), "approver-approved",
                        approverPending.presetAssignmentId(),
                        decision("APPROVED", approverPending.version())));
        jdbc.update("""
                UPDATE com_admin_role_assignments
                   SET responsibility_code = 'APP_ACCESS_MANAGER'
                 WHERE tenant_id = ? AND principal_type = 'USER' AND principal_ref = ?
                   AND responsibility_code = 'APP_ACCESS_APPROVER'
                """, fixture.tenantId(), fixture.approver().toString());
        assertThatThrownBy(() -> inTransaction(() -> service.activate(
                fixture.tenantId(), fixture.approver(), "approver-self-fulfil",
                approverApproved.presetAssignmentId(),
                activation(approverApproved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));
    }

    @Test
    void expiryClosesTheEntireBundleInvalidatesSessionsAndPublishesOneRevision() {
        Long expiringSubject = user(fixture.tenantId(), "expiring-subject");
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.requester(), "expiry-request-1",
                        governedRequest(expiringSubject, "APPROVAL_AUDITOR")));
        AppGovernanceDtos.AppAdminPresetAssignment approved = inTransaction(() -> service.decide(
                fixture.tenantId(), fixture.approver(), "expiry-approve-1",
                pending.presetAssignmentId(), decision("APPROVED", pending.version())));
        AppGovernanceDtos.AppAdminPresetAssignment active = inTransaction(() -> service.activate(
                fixture.tenantId(), fixture.fulfiller(), "expiry-activate-1",
                approved.presetAssignmentId(), activation(approved.version())));

        inTransaction(() -> {
            jdbc.update("""
                    UPDATE com_admin_role_assignments
                       SET valid_from = CURRENT_TIMESTAMP - INTERVAL '20 days',
                           review_due_at = CURRENT_TIMESTAMP - INTERVAL '2 days',
                           valid_to = CURRENT_TIMESTAMP - INTERVAL '1 day'
                     WHERE admin_role_assignment_id = ?
                    """, active.responsibilityAssignmentId());
            jdbc.update("""
                    UPDATE com_admin_scoped_duty_assignments
                       SET valid_from = CURRENT_TIMESTAMP - INTERVAL '20 days',
                           review_due_at = CURRENT_TIMESTAMP - INTERVAL '2 days',
                           valid_to = CURRENT_TIMESTAMP - INTERVAL '1 day'
                     WHERE app_preset_assignment_id = ?
                    """, active.presetAssignmentId());
            jdbc.update("""
                    UPDATE com_admin_app_preset_assignments
                       SET created_at = CURRENT_TIMESTAMP - INTERVAL '30 days',
                           valid_from = CURRENT_TIMESTAMP - INTERVAL '20 days',
                           review_due_at = CURRENT_TIMESTAMP - INTERVAL '2 days',
                           valid_to = CURRENT_TIMESTAMP - INTERVAL '1 day'
                     WHERE app_preset_assignment_id = ?
                    """, active.presetAssignmentId());
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            return null;
        });

        assertThat(inTransaction(() -> service.expireDueAssignments(10))).isEqualTo(1);
        assertBundleStates(active.presetAssignmentId(), "EXPIRED", 1);
        assertThat(effectiveDuties(expiringSubject)).isEmpty();
        assertThat(accessRevision(expiringSubject)).isEqualTo(2);
        assertOutbox(active.presetAssignmentId(),
                List.of(AppAdminPresetOutboxPublisher.REQUESTED,
                        AppAdminPresetOutboxPublisher.DECIDED,
                        AppAdminPresetOutboxPublisher.ACTIVATED,
                        AppAdminPresetOutboxPublisher.EXPIRED), List.of(1L, 2L, 3L, 4L));
    }

    @Test
    void reviewsRequireIndependentGovernorAndExactGovernedDutyScope() {
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.requester(), "review-request-1",
                        governedRequest(fixture.subject(), "APPROVAL_AUDITOR")));
        AppGovernanceDtos.AppAdminPresetAssignment approved = inTransaction(() -> service.decide(
                fixture.tenantId(), fixture.approver(), "review-approve-1",
                pending.presetAssignmentId(), decision("APPROVED", pending.version())));
        inTransaction(() -> service.activate(
                fixture.tenantId(), fixture.fulfiller(), "review-activate-1",
                approved.presetAssignmentId(), activation(approved.version())));

        UUID otherResourceSet = additionalApprovalsResourceSet(fixture.tenantId());
        grantResponsibility(
                fixture.tenantId(), fixture.reviewer(), "APP_ACCESS_REVIEWER",
                otherResourceSet, fixture.catalogAdmin());
        UUID mismatched = review(
                fixture.subject(), "APPROVAL_OPERATIONS_AUDIT",
                "MISMATCHED_PRESET_SCOPE", otherResourceSet);
        assertThatThrownBy(() -> inTransaction(() -> service.decideReview(
                fixture.tenantId(), fixture.reviewer(), "review-mismatch-1", mismatched,
                new AppGovernanceDtos.AppAdminPresetReviewDecisionRequest(
                        "RESOLVED", "A different resource set cannot resolve this review.", 0L))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));
        AppGovernanceDtos.AppAdminPresetReview dismissed = inTransaction(() ->
                service.decideReview(
                        fixture.tenantId(), fixture.reviewer(), "review-dismiss-1", mismatched,
                        new AppGovernanceDtos.AppAdminPresetReviewDecisionRequest(
                                "DISMISSED",
                                "The mismatched migration evidence was independently dismissed.",
                                0L)));
        assertThat(dismissed.lifecycleState()).isEqualTo("DISMISSED");

        UUID exact = review(
                fixture.subject(), "APPROVAL_OPERATIONS_AUDIT",
                "PRESET_WORKFLOW_REVIEW_REQUIRED", fixture.resourceSetId());
        assertThatThrownBy(() -> inTransaction(() -> service.decideReview(
                fixture.tenantId(), fixture.subject(), "review-self-1", exact,
                new AppGovernanceDtos.AppAdminPresetReviewDecisionRequest(
                        "RESOLVED", "A reviewed user cannot resolve their own evidence.", 0L))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));
        AppGovernanceDtos.AppAdminPresetReview resolved = inTransaction(() ->
                service.decideReview(
                        fixture.tenantId(), fixture.reviewer(), "review-resolve-1", exact,
                        new AppGovernanceDtos.AppAdminPresetReviewDecisionRequest(
                                "RESOLVED",
                                "The exact governed preset now supplies reviewed duty evidence.",
                                0L)));
        assertThat(resolved.lifecycleState()).isEqualTo("RESOLVED");
        assertThat(resolved.version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_domain_event_outbox
                 WHERE tenant_id = ? AND aggregate_type = 'APP_ADMIN_PRESET_REVIEW'
                   AND aggregate_id = ? AND event_type = ?
                """, Integer.class, fixture.tenantId(), exact.toString(),
                AppAdminPresetOutboxPublisher.REVIEW_DECIDED)).isEqualTo(1);
    }

    @Test
    void catalogSeparatesActiveApprovalAndHrisPresetsFromUndeclaredDraftPackages() {
        AppAdminPresetRepository repository = new AppAdminPresetRepository(jdbc, objectMapper);
        List<AppGovernanceDtos.AppAdminPreset> catalog = repository.catalog();
        assertThat(catalog).hasSize(19);
        assertThat(catalog.stream().filter(AppGovernanceDtos.AppAdminPreset::requestable))
                .extracting(AppGovernanceDtos.AppAdminPreset::presetCode)
                .containsExactlyInAnyOrder(
                        "APPROVAL_DESIGNER", "APPROVAL_PUBLISHER",
                        "APPROVAL_OPERATOR", "APPROVAL_AUDITOR",
                        "HRIS_CONTRACT_ISSUER", "HRIS_STATUTORY_OPERATOR",
                        "HRIS_INTEGRATION_EXECUTOR", "HRIS_INTEGRATION_AUTHOR",
                        "HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR");
        assertThat(catalog)
                .filteredOn(preset -> preset.requestable() && "hcm".equals(preset.productKey()))
                .allSatisfy(preset -> {
                    assertThat(preset.appResourceKey()).isEqualTo("APP.HCM");
                    assertThat(preset.duties()).hasSize(1);
                })
                .extracting(
                        AppGovernanceDtos.AppAdminPreset::presetCode,
                        preset -> preset.duties().getFirst().dutyCode(),
                        preset -> preset.duties().getFirst().capabilityContractKeys())
                .containsExactlyInAnyOrder(
                        tuple("HRIS_CONTRACT_ISSUER", "HRIS_DUTY_CONTRACT_ISSUER",
                                List.of("hcm.operations.contract.issue")),
                        tuple("HRIS_STATUTORY_OPERATOR", "HRIS_DUTY_STATUTORY_OPERATOR",
                                List.of("hcm.operations.statutory.execute")),
                        tuple("HRIS_INTEGRATION_EXECUTOR", "HRIS_DUTY_INTEGRATION_EXECUTOR",
                                List.of("hcm.integration.workforce-ingestion.execute")),
                        tuple("HRIS_INTEGRATION_AUTHOR", "HRIS_DUTY_INTEGRATION_AUTHOR",
                                List.of("hcm.integration.definition.update")),
                        tuple("HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR",
                                "HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR",
                                List.of("hcm.integration.control-plane.execute")));
        assertThat(catalog.stream().filter(value -> !value.requestable()).toList())
                .hasSize(10)
                .allMatch(value -> "PRESET_DRAFT".equals(value.unavailableReason())
                        && value.appResourceKey() != null && value.duties().isEmpty());
        assertThat(repository.requirePreset("SERVICES_PRESET_CATALOG_PENDING")
                .appResourceKey()).isEqualTo("APP.SERVICES");
        assertThat(catalog).noneMatch(value -> value.productKey().equals("rooms"));
        assertThat(catalog)
                .noneMatch(value -> value.presetCode().contains("ALL_ADMIN"));
        assertThat(catalog.stream().filter(AppGovernanceDtos.AppAdminPreset::requestable))
                .allMatch(value -> value.duties().size() <= 2);

        transactions.execute(status -> {
            jdbc.update("""
                    UPDATE sys_admin_scoped_duty_catalog
                       SET product_resource_key = 'APP.HCM'
                     WHERE duty_code = 'APPROVAL_SIGNATURE_READ'
                    """);
            AppGovernanceDtos.AppAdminPreset mismatched =
                    repository.requirePreset("APPROVAL_OPERATOR");
            assertThat(mismatched.requestable()).isFalse();
            assertThat(mismatched.unavailableReason()).isEqualTo("PRODUCT_RESOURCE_MISMATCH");
            status.setRollbackOnly();
            return null;
        });
    }

}

package com.dwp.services.provider;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.commercial.ProviderCommercialRenewalRepository;
import com.dwp.services.provider.entitlement.EntitlementRepository;
import com.dwp.services.provider.entitlement.TenantEntitlementRepository;
import com.dwp.services.provider.operation.ProviderOperationRepository;
import com.dwp.services.provider.operation.ProviderOperationStepAttemptRepository;
import com.dwp.services.provider.operation.ProviderOperationStepRepository;
import com.dwp.services.provider.provisioning.ProviderProvisioningOrchestrator;
import com.dwp.services.provider.provisioning.TenantMutationOrchestrator;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.support.CustomerApprovalEvidencePolicy;
import com.dwp.services.provider.support.ProviderSupportAccessService;
import com.dwp.services.provider.support.ProviderSupportActivationGate;
import com.dwp.services.provider.support.ProviderSupportRequestRepository;
import com.dwp.services.provider.support.ProviderSupportRequestSecurityPolicy;
import com.dwp.services.provider.support.ProviderSupportSessionRepository;
import com.dwp.services.provider.support.ProviderSupportSessionLifecycleService;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class ProviderSupportLifecycleAcceptancePostgresTest {

    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Set<String> REQUEST_PERMISSIONS = Set.of(
            "ESTATE_READ", "SUPPORT_SESSION_WRITE", "SUPPORT_ACCESS_REVIEW",
            "SUPPORT_POST_REVIEW");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static PGSimpleDataSource dataSource;
    private static PlatformTransactionManager transactionManager;
    private static ProviderControlPlaneService service;
    private static ProviderSupportAccessService accessService;
    private static ProviderSupportRequestRepository requestRepository;
    private static ProviderTenant tenant;

    @BeforeAll
    static void configurePostgresBackedServices() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ProviderAuditService audit = transactional(
                new ProviderAuditService(jdbc, objectMapper), transactionManager,
                ProviderAuditService.class);
        requestRepository = new ProviderSupportRequestRepository(jdbc);
        ProviderSupportSessionRepository sessionRepository =
                new ProviderSupportSessionRepository(jdbc);
        ProviderSupportSessionLifecycleService lifecycleService = transactional(
                new ProviderSupportSessionLifecycleService(
                        sessionRepository, requestRepository),
                transactionManager, ProviderSupportSessionLifecycleService.class);
        ProviderOperationsRepository operationsRepository =
                new ProviderOperationsRepository(jdbc);
        ProviderSupportActivationGate activationGate =
                new ProviderSupportActivationGate(sessionRepository, true);
        ProviderTenantRepository tenantRepository = mock(ProviderTenantRepository.class);
        tenant = seededTenant();
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        ProviderSupportRequestSecurityPolicy securityPolicy =
                new ProviderSupportRequestSecurityPolicy(audit);
        ProviderControlPlaneService target = new ProviderControlPlaneService(
                tenantRepository,
                mock(EntitlementRepository.class),
                mock(TenantEntitlementRepository.class),
                mock(ProviderOperationRepository.class),
                mock(ProviderOperationStepRepository.class),
                mock(ProviderOperationStepAttemptRepository.class),
                mock(ProviderEstateRepository.class),
                operationsRepository,
                mock(ProviderCommercialRenewalRepository.class),
                requestRepository,
                securityPolicy,
                sessionRepository,
                lifecycleService,
                activationGate,
                new CustomerApprovalEvidencePolicy("local", true),
                mock(ProviderProvisioningOrchestrator.class),
                mock(TenantMutationOrchestrator.class),
                audit,
                objectMapper);
        service = transactional(target, transactionManager, ProviderControlPlaneService.class);
        accessService = transactional(
                new ProviderSupportAccessService(
                        sessionRepository, lifecycleService, tenantRepository,
                        audit, activationGate),
                transactionManager,
                ProviderSupportAccessService.class);
    }

    @BeforeEach
    void enableIsolatedSupportFixture() {
        jdbc.update("""
                UPDATE prv_support_scope_catalog
                   SET lifecycle_state = 'ACTIVE', risk_tier = 'L1',
                       requires_customer_approval = TRUE
                 WHERE scope_code = 'TENANT_EXPERIENCE_PREVIEW'
                """);
        jdbc.update("""
                UPDATE prv_tenants
                   SET lifecycle_state = 'ACTIVE', onboarding_state = 'READY', auth_tenant_id = 1
                 WHERE provider_tenant_id = ?
                """, TENANT_ID);
        Long actor = seededOperatorId();
        jdbc.update("""
                UPDATE prv_support_activation_control
                   SET activation_enabled = TRUE,
                       change_reason = 'Enable isolated PT acceptance fixture',
                       change_correlation_id = 'pt-acceptance:setup',
                       changed_by = ?, changed_at = CURRENT_TIMESTAMP,
                       version = version + 1
                 WHERE control_key = 'STANDARD_JIT' AND NOT activation_enabled
                """, actor);
    }

    @AfterEach
    void clearRequestContext() {
        ProviderRequestContext.clear();
    }

    @Test
    void ptA08RejectsIdempotencyKeyReuseWithDifferentPayloadAndPreservesOriginal() {
        ActorFixture requester = newActor("PT-A08 requester");
        String key = "pt-a08-" + UUID.randomUUID();
        ProviderDtos.CreateSupportAccessRequest original = request(
                key, 15, "Inspect the approved tenant experience projection");
        as(requester);

        ProviderDtos.SupportAccessRequestSummary first =
                service.createSupportAccessRequest("pt-a08:create", original);
        ProviderDtos.SupportAccessRequestSummary replay =
                service.createSupportAccessRequest("pt-a08:replay", original);

        assertThat(replay.supportAccessRequestId()).isEqualTo(first.supportAccessRequestId());
        assertThatThrownBy(() -> service.createSupportAccessRequest(
                "pt-a08:conflict",
                request(key, 15, "Changed purpose must not overwrite the original")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        ProviderSupportRequestRepository.SupportAccessRequestRecord stored =
                requestRepository.byId(first.supportAccessRequestId()).orElseThrow();
        assertThat(stored.justification()).isEqualTo(original.justification());
        assertThat(stored.durationMinutes()).isEqualTo(15);
        assertThat(stored.requesterAuthSessionId()).isEqualTo(requester.authSessionId());
        assertThat(requestCount(requester.operatorId(), key)).isEqualTo(1);
        assertThat(auditCount(
                "provider.support-access.requested", first.supportAccessRequestId(), "SUCCESS"))
                .isEqualTo(1);

        as(requester.withAuthSession(UUID.randomUUID()));
        assertThatThrownBy(() -> service.createSupportAccessRequest("pt-a08:sid-conflict", original))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(requestCount(requester.operatorId(), key)).isEqualTo(1);
    }

    @Test
    void ptA09RequesterSelfApprovalIsForbiddenStatePreservingAndAudited() {
        ActorFixture requester = newActor("PT-A09 requester");
        as(requester);
        ProviderDtos.SupportAccessRequestSummary created = service.createSupportAccessRequest(
                "pt-a09:create", request("pt-a09-" + UUID.randomUUID(), 15,
                        "Request independent approval for tenant preview"));

        assertThatThrownBy(() -> service.decideSupportAccessRequest(
                created.supportAccessRequestId(), "pt-a09:self-approval",
                new ProviderDtos.DecideSupportAccessRequest(
                        "APPROVED", "Requester attempted self approval", created.version())))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        ActorFixture independentChecker = newActor("PT-A09 independent checker");
        assertThat(requestRepository.decide(
                created.supportAccessRequestId(), created.version(), requester.operatorId(),
                requester.operatorId(), requester.authSessionId(), "APPROVED",
                "Repository must also reject requester self approval")).isFalse();
        assertThat(requestRepository.decide(
                created.supportAccessRequestId(), created.version(), independentChecker.operatorId(),
                requester.operatorId(), UUID.randomUUID(), "APPROVED",
                "Repository must reject a changed request auth binding")).isFalse();

        ProviderSupportRequestRepository.SupportAccessRequestRecord stored =
                requestRepository.byId(created.supportAccessRequestId()).orElseThrow();
        assertThat(stored.lifecycleState()).isEqualTo("PENDING_APPROVAL");
        assertThat(stored.version()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_support_sessions
                 WHERE support_access_request_id = ?
                """, Integer.class, created.supportAccessRequestId())).isZero();
        assertThat(denialReason(
                "provider.support-access.approval-denied", created.supportAccessRequestId()))
                .isEqualTo("REQUESTER_SELF_APPROVAL");
        assertExtendedOutbox(
                "provider.support-access.approval-denied", created.supportAccessRequestId());
    }

    @Test
    void ptA11ActivationRequiresOriginalRequesterAndAuthSessionAndCreatesOneBoundGrant() {
        ActorFixture requester = newActor("PT-A11 requester");
        ActorFixture approver = newActor("PT-A11 approver");
        ProviderDtos.SupportAccessRequestSummary approved = approvedRequest(
                requester, approver, "pt-a11-" + UUID.randomUUID(), 5);

        as(approver);
        assertThatThrownBy(() -> service.activateSupportAccessRequest(
                approved.supportAccessRequestId(), "pt-a11:wrong-requester",
                new ProviderDtos.ActivateSupportAccessRequest(approved.version())))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(activeSessionCount(approved.supportAccessRequestId())).isZero();

        as(requester.withAuthSession(UUID.randomUUID()));
        assertThatThrownBy(() -> service.activateSupportAccessRequest(
                approved.supportAccessRequestId(), "pt-a11:wrong-sid",
                new ProviderDtos.ActivateSupportAccessRequest(approved.version())))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(activeSessionCount(approved.supportAccessRequestId())).isZero();
        assertThat(requestRepository.byId(approved.supportAccessRequestId()).orElseThrow()
                .lifecycleState()).isEqualTo("APPROVED");
        assertThat(denialReason(
                "provider.support-access.activation-denied", approved.supportAccessRequestId()))
                .isEqualTo("REQUEST_AUTH_SESSION_MISMATCH");

        as(requester);
        ProviderDtos.SupportSessionGrant grant = service.activateSupportAccessRequest(
                approved.supportAccessRequestId(), "pt-a11:activate",
                new ProviderDtos.ActivateSupportAccessRequest(approved.version()));

        assertThat(grant.accessRequest()).isNotNull();
        assertThat(grant.accessRequest().supportAccessRequestId())
                .isEqualTo(approved.supportAccessRequestId());
        assertThat(grant.accessRequest().supportSessionId())
                .isEqualTo(grant.session().supportSessionId());
        assertThat(grant.accessRequest().requesterOwned()).isTrue();
        assertThat(activeSessionCount(approved.supportAccessRequestId())).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT scope_code FROM prv_support_session_scopes
                 WHERE support_session_id = ? ORDER BY scope_code
                """, String.class, grant.session().supportSessionId()))
                .containsExactly("TENANT_EXPERIENCE_PREVIEW");
        assertThat(jdbc.queryForObject("""
                SELECT session.origin_auth_session_id = ?
                       AND session.provider_operator_id = ?
                       AND session.support_access_request_id = ?
                       AND session.provider_tenant_id = request.provider_tenant_id
                       AND session.justification = request.justification
                       AND session.access_mode = request.access_mode
                       AND session.approval_reference = request.approval_reference
                       AND session.customer_approval_required =
                           request.customer_approval_required
                       AND session.risk_tier = request.risk_tier
                       AND session.started_at = request.activated_at
                       AND session.last_used_at = session.started_at
                       AND session.expires_at = session.started_at
                           + make_interval(mins => request.duration_minutes)
                       AND session.created_by = request.requester_operator_id
                       AND session.updated_by = request.requester_operator_id
                  FROM prv_support_sessions session
                  JOIN prv_support_access_requests request
                    ON request.support_access_request_id =
                       session.support_access_request_id
                 WHERE session.support_session_id = ?
                """, Boolean.class, requester.authSessionId(), requester.operatorId(),
                approved.supportAccessRequestId(), grant.session().supportSessionId())).isTrue();
        Long boundedSeconds = jdbc.queryForObject("""
                SELECT FLOOR(EXTRACT(EPOCH FROM (expires_at - started_at)))::BIGINT
                  FROM prv_support_sessions WHERE support_session_id = ?
                """, Long.class, grant.session().supportSessionId());
        assertThat(boundedSeconds).isBetween(295L, 305L);
    }

    @Test
    void ptA13SessionForTenantACannotResolveAnotherTenantService() {
        ActorFixture requester = newActor("PT-A13 requester");
        ActorFixture approver = newActor("PT-A13 approver");
        ProviderDtos.SupportAccessRequestSummary approved = approvedRequest(
                requester, approver, "pt-a13-" + UUID.randomUUID(), 15);
        as(requester);
        ProviderDtos.SupportSessionGrant grant = service.activateSupportAccessRequest(
                approved.supportAccessRequestId(), "pt-a13:activate",
                new ProviderDtos.ActivateSupportAccessRequest(approved.version()));

        assertThat(accessService.resolve(
                grant.sessionToken(), "GET",
                "/api/platform/v1/admin/tenant-experience-preview", "pt-a13:tenant-a")
                .providerTenantId()).isEqualTo(TENANT_ID);
        assertThatThrownBy(() -> accessService.resolve(
                grant.sessionToken(), "GET", "/api/people/v1/people",
                "pt-a13:tenant-b-service"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(denialReason(
                "provider.support-session.access-denied", grant.session().supportSessionId()))
                .isEqualTo("RESOURCE_NOT_ALLOWLISTED");
        assertThat(activeSessionCount(approved.supportAccessRequestId())).isEqualTo(1);
    }

    @Test
    void ptA15AuditOutboxFailureRollsBackActivationAndSurfaces503() {
        ActorFixture requester = newActor("PT-A15 requester");
        ActorFixture approver = newActor("PT-A15 approver");
        ProviderDtos.SupportAccessRequestSummary approved = approvedRequest(
                requester, approver, "pt-a15-" + UUID.randomUUID(), 15);
        String traceId = "15".repeat(16);
        installAuditOutboxFault(traceId);
        try {
            as(requester);
            assertThatThrownBy(() -> service.activateSupportAccessRequest(
                    approved.supportAccessRequestId(), traceId,
                    new ProviderDtos.ActivateSupportAccessRequest(approved.version())))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode())
                                    .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode().getHttpStatus().value())
                                    .isEqualTo(503));
        } finally {
            removeAuditOutboxFault();
        }

        ProviderSupportRequestRepository.SupportAccessRequestRecord stored =
                requestRepository.byId(approved.supportAccessRequestId()).orElseThrow();
        assertThat(stored.lifecycleState()).isEqualTo("APPROVED");
        assertThat(stored.version()).isEqualTo(approved.version());
        assertThat(activeSessionCount(approved.supportAccessRequestId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_audit_events
                 WHERE action = 'provider.support-access.activated'
                   AND correlation_id = ?
                """, Integer.class, traceId)).isZero();
    }

    @Test
    void ptA18RequesterCannotReviewAndIndependentAuditorCompletesLinkedTimeline() {
        ActorFixture requester = newActor("PT-A18 requester");
        ActorFixture approver = newActor("PT-A18 approver");
        ActorFixture auditor = newActor("PT-A18 independent auditor");
        ProviderDtos.SupportAccessRequestSummary approved = approvedRequest(
                requester, approver, "pt-a18-" + UUID.randomUUID(), 5);
        as(requester);
        ProviderDtos.SupportSessionGrant grant = service.activateSupportAccessRequest(
                approved.supportAccessRequestId(), "pt-a18:activate",
                new ProviderDtos.ActivateSupportAccessRequest(approved.version()));
        service.revokeSupportSession(
                grant.session().supportSessionId(), "pt-a18:revoke",
                new ProviderDtos.RevokeSupportSessionRequest(
                        "End the completed support investigation", grant.session().version()));
        ProviderDtos.SupportAccessRequestSummary completed =
                requestRepository.summary(approved.supportAccessRequestId());

        assertThat(completed.lifecycleState()).isEqualTo("COMPLETED");
        assertThat(completed.supportSessionId()).isEqualTo(grant.session().supportSessionId());
        as(requester);
        assertThatThrownBy(() -> service.reviewSupportAccessRequest(
                approved.supportAccessRequestId(), "pt-a18:self-review",
                new ProviderDtos.ReviewSupportAccessRequest(
                        "Requester cannot close their own review", completed.version())))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(requestRepository.byId(approved.supportAccessRequestId()).orElseThrow()
                .lifecycleState()).isEqualTo("COMPLETED");
        assertThat(denialReason(
                "provider.support-access.review-denied", approved.supportAccessRequestId()))
                .isEqualTo("REQUESTER_SELF_POST_REVIEW");

        as(auditor);
        ProviderDtos.SupportAccessRequestSummary reviewed = service.reviewSupportAccessRequest(
                approved.supportAccessRequestId(), "pt-a18:independent-review",
                new ProviderDtos.ReviewSupportAccessRequest(
                        "Timeline, scope, expiry, and revocation evidence reconciled",
                        completed.version()));

        assertThat(reviewed.lifecycleState()).isEqualTo("REVIEWED");
        assertThat(reviewed.postReviewState()).isEqualTo("COMPLETED");
        assertThat(reviewed.postReviewedBy()).isEqualTo(auditor.operatorId());
        assertThat(reviewed.supportSessionId()).isEqualTo(grant.session().supportSessionId());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_audit_events
                 WHERE action = 'provider.support-access.reviewed'
                   AND target_id = ?
                   AND redacted_snapshot ->> 'sessionId' = ?
                """, Integer.class, approved.supportAccessRequestId().toString(),
                grant.session().supportSessionId().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_audit_events
                 WHERE target_id IN (?, ?)
                   AND action IN (
                       'provider.support-access.requested',
                       'provider.support-access.approved',
                       'provider.support-access.activated',
                       'provider.support-session.revoked',
                       'provider.support-access.completed-after-session-end',
                       'provider.support-access.reviewed')
                """, Integer.class, approved.supportAccessRequestId().toString(),
                grant.session().supportSessionId().toString())).isGreaterThanOrEqualTo(6);
        assertExtendedOutbox("provider.support-access.reviewed", approved.supportAccessRequestId());
    }

    @Test
    void expiryAndExtendedAuditCommitBeforeTheOuterAccessDenialRollsBack() {
        ActorFixture requester = newActor("Expiry rollback requester");
        ActorFixture approver = newActor("Expiry rollback approver");
        ProviderDtos.SupportAccessRequestSummary approved = approvedRequest(
                requester, approver, "expiry-rollback-" + UUID.randomUUID(), 15);
        as(requester);
        ProviderDtos.SupportSessionGrant grant = service.activateSupportAccessRequest(
                approved.supportAccessRequestId(), "31".repeat(16),
                new ProviderDtos.ActivateSupportAccessRequest(approved.version()));

        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    UPDATE prv_support_sessions
                       SET last_used_at = statement_timestamp() - INTERVAL '16 minutes'
                     WHERE support_session_id = ?
                    """, grant.session().supportSessionId());
        });

        as(requester);
        assertThatThrownBy(() -> accessService.inspect(grant.sessionToken(), "32".repeat(16)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_sessions
                 WHERE support_session_id = ?
                """, String.class, grant.session().supportSessionId())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, String.class, approved.supportAccessRequestId())).isEqualTo("COMPLETED");
        assertExtendedOutbox(
                "provider.support-session.expired-automatically",
                grant.session().supportSessionId());
        assertExtendedOutbox(
                "provider.support-access.completed-after-session-end",
                approved.supportAccessRequestId());
    }

    private static ProviderDtos.SupportAccessRequestSummary approvedRequest(
            ActorFixture requester,
            ActorFixture approver,
            String key,
            int durationMinutes) {
        as(requester);
        ProviderDtos.SupportAccessRequestSummary created = service.createSupportAccessRequest(
                key + ":create",
                request(key, durationMinutes, "Inspect the approved tenant experience projection"));
        as(approver);
        return service.decideSupportAccessRequest(
                created.supportAccessRequestId(), key + ":approve",
                new ProviderDtos.DecideSupportAccessRequest(
                        "APPROVED", "Independent reviewer verified the bounded request",
                        created.version()));
    }

    private static ProviderDtos.CreateSupportAccessRequest request(
            String key,
            int durationMinutes,
            String justification) {
        return new ProviderDtos.CreateSupportAccessRequest(
                TENANT_ID,
                List.of("TENANT_EXPERIENCE_PREVIEW"),
                durationMinutes,
                justification,
                "CUSTOMER-APPROVAL-" + key,
                key);
    }

    private static ActorFixture newActor(String displayName) {
        long userId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 800_000_000L)
                + 100_000_000L;
        Long operatorId = jdbc.queryForObject("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
                VALUES (1, ?, ?, 'PROVIDER_ADMIN', 'ACTIVE')
                RETURNING provider_operator_id
                """, Long.class, userId, displayName);
        jdbc.update("""
                INSERT INTO prv_operator_role_assignments (
                    provider_operator_id, role_code, lifecycle_state, created_by)
                VALUES (?, 'PROVIDER_ADMIN', 'ACTIVE', ?)
                """, operatorId, operatorId);
        return new ActorFixture(operatorId, userId, UUID.randomUUID(), displayName);
    }

    private static void as(ActorFixture fixture) {
        ProviderRequestContext.set(new ProviderRequestContext.Actor(
                fixture.operatorId(), fixture.userId(), 1L, fixture.displayName(),
                Set.of("PROVIDER_ADMIN"), REQUEST_PERMISSIONS, fixture.authSessionId()));
    }

    private static int requestCount(Long operatorId, String key) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_support_access_requests
                 WHERE requester_operator_id = ? AND request_key = ?
                """, Integer.class, operatorId, key);
    }

    private static int activeSessionCount(UUID requestId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_support_sessions
                 WHERE support_access_request_id = ? AND lifecycle_state = 'ACTIVE'
                """, Integer.class, requestId);
    }

    private static int auditCount(String action, UUID targetId, String outcome) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_audit_events
                 WHERE action = ? AND target_id = ? AND outcome = ?
                """, Integer.class, action, targetId.toString(), outcome);
    }

    private static String denialReason(String action, UUID targetId) {
        return jdbc.queryForObject("""
                SELECT redacted_snapshot ->> 'reasonCode'
                  FROM prv_audit_events
                 WHERE action = ? AND target_id = ? AND outcome = 'DENIED'
                 ORDER BY occurred_at DESC, audit_event_id DESC LIMIT 1
                """, String.class, action, targetId.toString());
    }

    private static void assertExtendedOutbox(String action, UUID targetId) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_audit_events event
                  JOIN sys_audit_outbox outbox ON outbox.event_id = event.audit_event_id
                 WHERE event.action = ? AND event.target_id = ?
                   AND outbox.payload ->> 'retentionClass' = 'EXTENDED'
                """, Integer.class, action, targetId.toString())).isEqualTo(1);
    }

    private static void installAuditOutboxFault(String correlationId) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION pt_fail_provider_audit_outbox()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.payload ->> 'correlationId' = '%s' THEN
                        RAISE EXCEPTION 'PT-A15 provider audit outbox fault injection';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """.formatted(correlationId));
        jdbc.execute("""
                CREATE TRIGGER trg_pt_fail_provider_audit_outbox
                BEFORE INSERT ON sys_audit_outbox
                FOR EACH ROW EXECUTE FUNCTION pt_fail_provider_audit_outbox()
                """);
    }

    private static void removeAuditOutboxFault() {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_pt_fail_provider_audit_outbox ON sys_audit_outbox");
        jdbc.execute("DROP FUNCTION IF EXISTS pt_fail_provider_audit_outbox()");
    }

    private static Long seededOperatorId() {
        return jdbc.queryForObject("""
                SELECT provider_operator_id FROM prv_operators
                 WHERE auth_tenant_id = 1 AND auth_user_id = 900001
                """, Long.class);
    }

    private static ProviderTenant seededTenant() {
        return ProviderTenant.builder()
                .providerTenantId(TENANT_ID)
                .organizationId(jdbc.queryForObject("""
                        SELECT organization_id FROM prv_tenants WHERE provider_tenant_id = ?
                        """, UUID.class, TENANT_ID))
                .tenantKey("skax")
                .displayName("SKAX")
                .environmentKey("production")
                .serviceTier("ENTERPRISE")
                .dataRegion("ap-northeast-2")
                .isolationModel("POOL")
                .lifecycleState("ACTIVE")
                .onboardingState("READY")
                .authTenantId(1L)
                .version(0L)
                .build();
    }

    private static <T> T transactional(
            T target,
            PlatformTransactionManager transactionManager,
            Class<T> contract) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        return contract.cast(proxyFactory.getProxy());
    }

    private record ActorFixture(
            Long operatorId,
            long userId,
            UUID authSessionId,
            String displayName) {

        ActorFixture withAuthSession(UUID value) {
            return new ActorFixture(operatorId, userId, value, displayName);
        }
    }

}

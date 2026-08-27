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
import com.dwp.services.provider.support.ProviderSupportPostReviewEvidenceDtos;
import com.dwp.services.provider.support.ProviderSupportPostReviewEvidenceRepository;
import com.dwp.services.provider.support.ProviderSupportPostReviewEvidenceService;
import com.dwp.services.provider.support.ProviderSupportRequestRepository;
import com.dwp.services.provider.support.ProviderSupportRequestSecurityPolicy;
import com.dwp.services.provider.support.ProviderSupportSessionLifecycleService;
import com.dwp.services.provider.support.ProviderSupportSessionRepository;
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
class ProviderSupportPostReviewEvidencePostgresTest {

    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Set<String> PERMISSIONS = Set.of(
            "ESTATE_READ", "SUPPORT_SESSION_WRITE", "SUPPORT_ACCESS_REVIEW",
            "SUPPORT_POST_REVIEW");
    private static final String PREVIEW_ROUTE =
            "/api/platform/v1/admin/tenant-experience-preview";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager transactionManager;
    private static ProviderControlPlaneService service;
    private static ProviderSupportAccessService accessService;
    private static ProviderSupportPostReviewEvidenceService evidenceService;
    private static ProviderSupportRequestRepository requestRepository;

    @BeforeAll
    static void configureServices() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ProviderAuditService audit = transactional(
                new ProviderAuditService(jdbc, objectMapper), ProviderAuditService.class);
        requestRepository = new ProviderSupportRequestRepository(jdbc);
        ProviderSupportSessionRepository sessionRepository =
                new ProviderSupportSessionRepository(jdbc);
        ProviderSupportSessionLifecycleService lifecycle = transactional(
                new ProviderSupportSessionLifecycleService(sessionRepository, requestRepository),
                ProviderSupportSessionLifecycleService.class);
        ProviderTenantRepository tenants = mock(ProviderTenantRepository.class);
        ProviderTenant tenant = seededTenant();
        when(tenants.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        ProviderSupportActivationGate activationGate =
                new ProviderSupportActivationGate(sessionRepository, true);
        ProviderControlPlaneService target = new ProviderControlPlaneService(
                tenants, mock(EntitlementRepository.class), mock(TenantEntitlementRepository.class),
                mock(ProviderOperationRepository.class), mock(ProviderOperationStepRepository.class),
                mock(ProviderOperationStepAttemptRepository.class),
                mock(ProviderEstateRepository.class), new ProviderOperationsRepository(jdbc),
                mock(ProviderCommercialRenewalRepository.class), requestRepository,
                new ProviderSupportRequestSecurityPolicy(audit), sessionRepository, lifecycle,
                activationGate, new CustomerApprovalEvidencePolicy("local", true),
                mock(ProviderProvisioningOrchestrator.class), mock(TenantMutationOrchestrator.class),
                audit, objectMapper);
        service = transactional(target, ProviderControlPlaneService.class);
        accessService = transactional(
                new ProviderSupportAccessService(
                        sessionRepository, lifecycle, tenants, audit, activationGate),
                ProviderSupportAccessService.class);
        evidenceService = transactional(
                new ProviderSupportPostReviewEvidenceService(
                        new ProviderSupportPostReviewEvidenceRepository(jdbc), lifecycle),
                ProviderSupportPostReviewEvidenceService.class);
    }

    @BeforeEach
    void enableSupport() {
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
        jdbc.update("""
                UPDATE prv_support_activation_control
                   SET activation_enabled = TRUE, changed_by = ?,
                       change_reason = 'Post-review evidence test',
                       change_correlation_id = 'post-review:test',
                       version = version + 1
                 WHERE control_key = 'STANDARD_JIT' AND NOT activation_enabled
                """, seededOperatorId());
    }

    @AfterEach
    void clearContext() {
        ProviderRequestContext.clear();
    }

    @Test
    void exactPreviewDenialIsReadyAndIndependentReviewSucceeds() {
        ActiveFixture fixture = activeFixture("exact-denial");
        as(fixture.requester());
        assertThatThrownBy(() -> accessService.resolve(
                fixture.grant().sessionToken(), "POST", PREVIEW_ROUTE, "42".repeat(16)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("read-only");
        ProviderDtos.SupportAccessRequestSummary completed = complete(fixture);

        as(fixture.auditor());
        ProviderSupportPostReviewEvidenceDtos.Evidence evidence =
                evidenceService.evidence(completed.supportAccessRequestId());
        assertThat(evidence.evidenceComplete()).isTrue();
        assertThat(evidence.readiness()).isEqualTo("READY_NO_USE");
        assertThat(evidence.deniedAttemptCount()).isEqualTo(1);
        assertThat(evidence.events()).singleElement().satisfies(event -> {
            assertThat(event.routeTemplate()).isEqualTo(PREVIEW_ROUTE);
            assertThat(event.reasonCode()).isEqualTo("RESOURCE_NOT_ALLOWLISTED");
            assertThat(event.correlationId()).isEqualTo("42".repeat(16));
        });
        assertThat(review(fixture, completed).lifecycleState()).isEqualTo("REVIEWED");
    }

    @Test
    void legacyResourcePathOnlyExactDenialIsReadyAndAtomicReviewSucceeds() {
        ActiveFixture fixture = activeFixture("legacy-exact-denial");
        insertDeniedEvidence(fixture, "43".repeat(16), """
                {"method":"POST","resourcePath":"%s",
                 "requiredScope":"TENANT_EXPERIENCE_PREVIEW",
                 "reasonCode":"RESOURCE_NOT_ALLOWLISTED"}
                """.formatted(PREVIEW_ROUTE));
        ProviderDtos.SupportAccessRequestSummary completed = complete(fixture);

        as(fixture.auditor());
        ProviderSupportPostReviewEvidenceDtos.Evidence evidence =
                evidenceService.evidence(completed.supportAccessRequestId());
        assertThat(evidence.evidenceComplete()).isTrue();
        assertThat(evidence.readiness()).isEqualTo("READY_NO_USE");
        assertThat(evidence.events()).singleElement().satisfies(event -> {
            assertThat(event.routeTemplate()).isEqualTo(PREVIEW_ROUTE);
            assertThat(event.correlationId()).isEqualTo("43".repeat(16));
        });
        assertThat(review(fixture, completed).lifecycleState()).isEqualTo("REVIEWED");
    }

    @Test
    void nonGetAllowEvidenceBlocksProjectionAndAtomicReview() {
        ActiveFixture fixture = activeFixture("malformed");
        insertEvidence(fixture, TENANT_ID, "51".repeat(16),
                "{\"method\":\"POST\",\"routeTemplate\":\"" + PREVIEW_ROUTE
                        + "\",\"scope\":\"TENANT_EXPERIENCE_PREVIEW\"}", false);
        assertEvidenceAndReviewBlocked(fixture, complete(fixture));
    }

    @Test
    void crossTenantEvidenceBlocksProjectionAndAtomicReview() {
        ActiveFixture fixture = activeFixture("cross-tenant");
        insertEvidence(fixture, UUID.randomUUID(), "52".repeat(16), validSnapshot(), true);
        assertEvidenceAndReviewBlocked(fixture, complete(fixture));
    }

    @Test
    void nonCanonicalCorrelationBlocksProjectionAndAtomicReview() {
        ActiveFixture fixture = activeFixture("bad-correlation");
        insertEvidence(fixture, TENANT_ID, "operator@example.test", validSnapshot(), false);
        assertEvidenceAndReviewBlocked(fixture, complete(fixture));
    }

    private static ActiveFixture activeFixture(String keyPrefix) {
        ActorFixture requester = newActor(keyPrefix + " requester");
        ActorFixture approver = newActor(keyPrefix + " approver");
        ActorFixture auditor = newActor(keyPrefix + " auditor");
        String key = keyPrefix + "-" + UUID.randomUUID();
        as(requester);
        ProviderDtos.SupportAccessRequestSummary created = service.createSupportAccessRequest(
                "31".repeat(16), new ProviderDtos.CreateSupportAccessRequest(
                        TENANT_ID, List.of("TENANT_EXPERIENCE_PREVIEW"), 5,
                        "Inspect the approved tenant experience projection",
                        "CUSTOMER-APPROVAL-" + key, key));
        as(approver);
        ProviderDtos.SupportAccessRequestSummary approved = service.decideSupportAccessRequest(
                created.supportAccessRequestId(), "32".repeat(16),
                new ProviderDtos.DecideSupportAccessRequest(
                        "APPROVED", "Independent bounded approval", created.version()));
        as(requester);
        ProviderDtos.SupportSessionGrant grant = service.activateSupportAccessRequest(
                approved.supportAccessRequestId(), "33".repeat(16),
                new ProviderDtos.ActivateSupportAccessRequest(approved.version()));
        return new ActiveFixture(requester, auditor, grant);
    }

    private static ProviderDtos.SupportAccessRequestSummary complete(ActiveFixture fixture) {
        as(fixture.requester());
        service.revokeSupportSession(
                fixture.grant().session().supportSessionId(), "34".repeat(16),
                new ProviderDtos.RevokeSupportSessionRequest(
                        "Complete the evidence window", fixture.grant().session().version()));
        return requestRepository.summary(fixture.grant().session().supportAccessRequestId());
    }

    private static ProviderDtos.SupportAccessRequestSummary review(
            ActiveFixture fixture,
            ProviderDtos.SupportAccessRequestSummary completed) {
        as(fixture.auditor());
        return service.reviewSupportAccessRequest(
                completed.supportAccessRequestId(), "35".repeat(16),
                new ProviderDtos.ReviewSupportAccessRequest(
                        "Actual-use evidence independently reconciled", completed.version()));
    }

    private static void assertEvidenceAndReviewBlocked(
            ActiveFixture fixture,
            ProviderDtos.SupportAccessRequestSummary completed) {
        as(fixture.auditor());
        assertThat(evidenceService.evidence(completed.supportAccessRequestId()).evidenceComplete())
                .isFalse();
        assertThatThrownBy(() -> review(fixture, completed))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(requestRepository.summary(completed.supportAccessRequestId()).lifecycleState())
                .isEqualTo("COMPLETED");
    }

    private static void insertEvidence(
            ActiveFixture fixture,
            UUID eventTenantId,
            String correlationId,
            String snapshot,
            boolean bypassForeignKey) {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            if (bypassForeignKey) jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    INSERT INTO prv_audit_events (
                        audit_event_id, actor_id, action, target_type, target_id, outcome,
                        correlation_id, redacted_snapshot, provider_operator_id,
                        provider_tenant_id, organization_id, event_category)
                    VALUES (gen_random_uuid(), ?, 'provider.support-session.used',
                        'SUPPORT_SESSION', ?, 'SUCCESS', ?, CAST(? AS jsonb),
                        ?, ?, NULL, 'PRIVILEGED_ACCESS')
                    """, fixture.requester().userId(),
                    fixture.grant().session().supportSessionId().toString(), correlationId,
                    snapshot, fixture.requester().operatorId(), eventTenantId);
        });
    }

    private static void insertDeniedEvidence(
            ActiveFixture fixture,
            String correlationId,
            String snapshot) {
        jdbc.update("""
                INSERT INTO prv_audit_events (
                    audit_event_id, actor_id, action, target_type, target_id, outcome,
                    correlation_id, redacted_snapshot, provider_operator_id,
                    provider_tenant_id, organization_id, event_category)
                VALUES (gen_random_uuid(), ?, 'provider.support-session.access-denied',
                    'SUPPORT_SESSION', ?, 'DENIED', ?, CAST(? AS jsonb),
                    ?, ?, NULL, 'PRIVILEGED_ACCESS')
                """, fixture.requester().userId(),
                fixture.grant().session().supportSessionId().toString(), correlationId,
                snapshot, fixture.requester().operatorId(), TENANT_ID);
    }

    private static String validSnapshot() {
        return "{\"method\":\"GET\",\"routeTemplate\":\"" + PREVIEW_ROUTE
                + "\",\"scope\":\"TENANT_EXPERIENCE_PREVIEW\"}";
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

    private static void as(ActorFixture actor) {
        ProviderRequestContext.set(new ProviderRequestContext.Actor(
                actor.operatorId(), actor.userId(), 1L, actor.displayName(),
                Set.of("PROVIDER_ADMIN"), PERMISSIONS, actor.authSessionId()));
    }

    private static ProviderTenant seededTenant() {
        return ProviderTenant.builder()
                .providerTenantId(TENANT_ID)
                .organizationId(jdbc.queryForObject(
                        "SELECT organization_id FROM prv_tenants WHERE provider_tenant_id = ?",
                        UUID.class, TENANT_ID))
                .tenantKey("skax").displayName("SKAX").environmentKey("production")
                .serviceTier("ENTERPRISE").dataRegion("ap-northeast-2")
                .isolationModel("POOL").lifecycleState("ACTIVE").onboardingState("READY")
                .authTenantId(1L).version(0L).build();
    }

    private static Long seededOperatorId() {
        return jdbc.queryForObject("""
                SELECT provider_operator_id FROM prv_operators
                 WHERE auth_tenant_id = 1 AND auth_user_id = 900001
                """, Long.class);
    }

    private static <T> T transactional(T target, Class<T> contract) {
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
    }

    private record ActiveFixture(
            ActorFixture requester,
            ActorFixture auditor,
            ProviderDtos.SupportSessionGrant grant) {
    }
}

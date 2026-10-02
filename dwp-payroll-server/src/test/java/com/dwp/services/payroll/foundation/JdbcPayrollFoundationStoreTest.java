package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandReceipt;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandType;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CreateConfigurationRequest;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FoundationDefinition;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.Lifecycle;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.MutationResult;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ReceiptStatus;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.UpdateConfigurationRequest;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.VersionCommand;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.GROUP_ID;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.LEGAL_ENTITY_ID;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.currency;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.definition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcPayrollFoundationStoreTest {

    private static final String RUNTIME_USER = "payroll_runtime";
    private static final String RUNTIME_PASSWORD = "runtime-test-password";
    private static final String PUBLISHER_USER = "payroll_projection_publisher";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate adminJdbc;
    private static TransactionTemplate adminTransaction;
    private PayrollFoundationService service;

    @BeforeAll
    static void migrateAndStartRuntimeContext() throws Exception {
        DataSource adminDataSource = dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + RUNTIME_USER
                    + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("CREATE ROLE " + PUBLISHER_USER
                    + " LOGIN NOINHERIT PASSWORD 'publisher-test-password'");
        }
        Flyway.configure()
                .dataSource(adminDataSource)
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "payrollRuntimeRole", RUNTIME_USER,
                        "payrollProjectionPublisherRole", PUBLISHER_USER))
                .load()
                .migrate();
        adminJdbc = new JdbcTemplate(adminDataSource);
        adminTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(adminDataSource));
        context = new AnnotationConfigApplicationContext(DatabaseTestConfiguration.class);
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void reset() {
        adminJdbc.execute("TRUNCATE TABLE pay_legal_entity_scope_members, "
                + "pay_legal_entity_scope_projections, pay_foundation_audit_events, "
                + "pay_foundation_command_receipts, pay_foundation_versions, "
                + "pay_foundation_configurations CASCADE");
        service = new PayrollFoundationService(
                context.getBean(PayrollFoundationStore.class),
                (tenantId, pin) -> java.util.OptionalLong.empty(),
                Clock.fixed(Instant.parse("2026-09-17T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void persistsTenantBoundVersionsReceiptsAndExactAuditActors() {
        PayrollFoundationStore store = context.getBean(PayrollFoundationStore.class);
        FoundationDefinition first = definition(
                LEGAL_ENTITY_ID, GROUP_ID,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                Set.of(currency("USD")), List.of());
        UUID createCommandId = UUID.randomUUID();
        MutationResult created = service.create(
                author(1, 101), createCommandId, "alice-create",
                new CreateConfigurationRequest(first));
        UUID firstId = created.configuration().configurationId();
        UUID simulationCommandId = UUID.randomUUID();
        MutationResult simulated = service.simulate(
                author(1, 202), firstId, simulationCommandId, "bob-simulates-alice",
                new VersionCommand(1));

        assertThat(simulated.configuration().version()).isEqualTo(2);
        assertThat(simulated.configuration().status()).isEqualTo(Lifecycle.SIMULATED);
        Map<String, Object> audit = adminJdbc.queryForMap("""
                SELECT actor_id, subject_id, correlation_id, authority_purpose,
                       legal_entity_scope_digest, policy_revision, authorization_revision
                  FROM pay_foundation_audit_events
                 WHERE event_type = 'FOUNDATION_SIMULATED'
                """);
        assertThat(audit.get("actor_id")).isEqualTo(202L);
        assertThat(audit.get("subject_id")).isEqualTo(101L);
        assertThat(audit.get("correlation_id")).isEqualTo("bob-simulates-alice");
        assertThat(audit.get("authority_purpose")).isEqualTo("PAYROLL_CONFIGURATION");
        assertThat(audit.get("legal_entity_scope_digest"))
                .isEqualTo(PayrollFoundationCanonical.textDigest("*"));
        assertThat(audit.get("policy_revision"))
                .isEqualTo("test-PAYROLL-ACCESS-COMPATIBILITY-v1");
        assertThat(audit.get("authorization_revision"))
                .isEqualTo("test-authorization-revision");
        Map<String, Object> receiptEvidence = adminJdbc.queryForMap("""
                SELECT authority_purpose, legal_entity_scope_digest,
                       policy_revision, authorization_revision
                  FROM pay_foundation_command_receipts
                 WHERE tenant_id = 1 AND command_id = ?
                """, simulationCommandId);
        assertThat(receiptEvidence).containsAllEntriesOf(Map.of(
                "authority_purpose", "PAYROLL_CONFIGURATION",
                "legal_entity_scope_digest", PayrollFoundationCanonical.textDigest("*"),
                "policy_revision", "test-PAYROLL-ACCESS-COMPATIBILITY-v1",
                "authorization_revision", "test-authorization-revision"));
        assertThat(store.byCommand(1, createCommandId).orElseThrow().version()).isEqualTo(1);
        assertThat(store.version(1, firstId, 1).orElseThrow().version()).isEqualTo(1);
        assertThat(store.byCommand(2, createCommandId)).isEmpty();
        MutationResult recoveredCreate = service.receipt(
                author(1, 101), createCommandId);
        assertThat(recoveredCreate.receipt().resultVersion()).isEqualTo(1L);
        assertThat(recoveredCreate.configuration().version()).isEqualTo(1L);
        assertThat(store.current(1, firstId).orElseThrow().version()).isEqualTo(2L);
        assertThatThrownBy(() -> adminJdbc.update("""
                UPDATE pay_foundation_command_receipts
                   SET receipt_status = 'RESULT_UNKNOWN',
                       failure_code = 'RESULT_UNKNOWN',
                       completed_at = now()
                 WHERE tenant_id = 1 AND command_id = ?
                """, createCommandId))
                .isInstanceOf(DataAccessException.class);

        UUID secondLegalEntity = UUID.fromString("10000000-0000-0000-0000-000000000002");
        UUID secondGroup = UUID.fromString("20000000-0000-0000-0000-000000000002");
        FoundationDefinition second = definition(
                secondLegalEntity, secondGroup,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                Set.of(currency("EUR")), List.of());
        MutationResult secondCreated = service.create(
                author(1, 101), UUID.randomUUID(), null,
                new CreateConfigurationRequest(second));
        UUID secondId = secondCreated.configuration().configurationId();
        service.update(
                author(1, 202), secondId, UUID.randomUUID(), "bob-update",
                new UpdateConfigurationRequest(1, second));
        MutationResult secondSimulation = service.simulate(
                author(1, 101), secondId, UUID.randomUUID(), null,
                new VersionCommand(2));

        assertThatThrownBy(() -> service.publish(
                publisher(1, 202), secondId, UUID.randomUUID(), null,
                new VersionCommand(secondSimulation.configuration().version())))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SOD_CONFLICT));
        MutationResult published = service.publish(
                publisher(1, 303), secondId, UUID.randomUUID(), "carol-publish",
                new VersionCommand(secondSimulation.configuration().version()));
        assertThat(published.configuration().status()).isEqualTo(Lifecycle.PUBLISHED);

        assertThat(store.versions(1, secondId))
                .extracting(snapshot -> snapshot.status())
                .containsExactly(
                        Lifecycle.PUBLISHED, Lifecycle.SIMULATED,
                        Lifecycle.DRAFT, Lifecycle.DRAFT);
        assertThat(store.current(2, secondId)).isEmpty();
        assertThat(service.receipt(
                publisher(1, 303), published.receipt().commandId()).receipt())
                .isEqualTo(published.receipt());

        JdbcTemplate runtimeJdbc = new JdbcTemplate(context.getBean(DataSource.class));
        assertThat(runtimeJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_foundation_configurations", Long.class))
                .isZero();
        assertThatThrownBy(() -> runtimeJdbc.update(
                "UPDATE pay_foundation_versions SET lifecycle_state = 'DRAFT'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> runtimeJdbc.update(
                "UPDATE pay_foundation_audit_events SET correlation_id = 'tampered'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> runtimeJdbc.update(
                "UPDATE pay_foundation_command_receipts "
                        + "SET authorization_revision = 'tampered'"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void letsAnUnknownReversalReceiptConvergeToDefinitiveFailure() {
        PayrollFoundationStore store = context.getBean(PayrollFoundationStore.class);
        PayrollFoundationAccess.Actor actor = publisher(1, 303);
        UUID configurationId = UUID.randomUUID();
        UUID publishCommandId = UUID.randomUUID();
        UUID reversalCommandId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-17T09:00:00Z");
        String digest = PayrollFoundationCanonical.requestDigest(
                CommandType.REVERSE, configurationId, 3L, null, publishCommandId);
        CommandReceipt pending = new CommandReceipt(
                actor.tenantId(), actor.actorId(), reversalCommandId, CommandType.REVERSE,
                ReceiptStatus.PENDING, digest, configurationId, null,
                publishCommandId, null, null, actor.purpose(), actor.legalEntityScopeDigest(),
                actor.policyRevision(), actor.authorizationRevision(), now, null);

        store.reserve(pending);
        store.replaceReceipt(pending.unknown(configurationId, 3L, now));
        store.replaceReceipt(pending.reversalFailed(configurationId, 3L, now));

        assertThat(store.receipt(actor.tenantId(), reversalCommandId).orElseThrow().status())
                .isEqualTo(ReceiptStatus.REVERSAL_FAILED);
    }

    @Test
    void ownerProjectionResolvesExactCurrentMembershipAndDrivesServiceScope() {
        Instant now = Instant.parse("2026-09-17T09:00:00Z");
        String scopeKey = "hcm-scope-" + "1".repeat(40);
        String policyRevision = "rollout-" + "b".repeat(64);
        String authorizationRevision = "psr-" + "a".repeat(64);
        UUID projectionId = UUID.randomUUID();
        UUID otherLegalEntity = UUID.fromString(
                "10000000-0000-0000-0000-000000000099");
        UUID otherGroup = UUID.fromString(
                "20000000-0000-0000-0000-000000000099");
        insertScopeProjection(
                1, 101, scopeKey, policyRevision, authorizationRevision,
                projectionId, "pay-legal-scope-r17", now.minusSeconds(60),
                now.plusSeconds(600), LEGAL_ENTITY_ID);

        PayrollFoundationRequestContext.VerifiedSubject viewSubject = verifiedSubject(
                1, 101, PayrollFoundationModels.FoundationAction.VIEW,
                "PAYROLL_AUDIT", scopeKey, policyRevision, authorizationRevision,
                now.plusSeconds(300));
        PayrollLegalEntityScopeResolver.Resolution resolution = context
                .getBean(PayrollLegalEntityScopeResolver.class)
                .resolve(viewSubject);
        PayrollFoundationAccess.Actor viewer = PayrollFoundationAccess.gatewayActor(
                viewSubject.tenantId(), viewSubject.actorId(), viewSubject.action(),
                viewSubject.purpose(), viewSubject.contextScopeKey(),
                viewSubject.policyRevision(), viewSubject.authorizationRevision(),
                resolution, PayrollFoundationAccess.compatibilityPolicy());

        MutationResult first = service.create(
                author(1, 900), UUID.randomUUID(), null,
                new CreateConfigurationRequest(definition(
                        LEGAL_ENTITY_ID, GROUP_ID,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                        Set.of(currency("USD")), List.of())));
        MutationResult second = service.create(
                author(1, 900), UUID.randomUUID(), null,
                new CreateConfigurationRequest(definition(
                        otherLegalEntity, otherGroup,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                        Set.of(currency("EUR")), List.of())));

        assertThat(resolution.legalEntityIds()).containsExactly(LEGAL_ENTITY_ID);
        assertThat(viewer.scope().allLegalEntities()).isFalse();
        assertThat(service.list(viewer).configurations())
                .extracting(configuration -> configuration.definition().legalEntity().id())
                .containsExactly(LEGAL_ENTITY_ID);
        assertThat(service.get(viewer, first.configuration().configurationId())
                .definition().legalEntity().id()).isEqualTo(LEGAL_ENTITY_ID);
        assertThatThrownBy(() -> service.get(
                viewer, second.configuration().configurationId()))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));

        PayrollFoundationRequestContext.VerifiedSubject editSubject = verifiedSubject(
                1, 101, PayrollFoundationModels.FoundationAction.EDIT,
                "PAYROLL_CONFIGURATION", scopeKey, policyRevision,
                authorizationRevision, now.plusSeconds(300));
        PayrollFoundationAccess.Actor editor = PayrollFoundationAccess.gatewayActor(
                editSubject.tenantId(), editSubject.actorId(), editSubject.action(),
                editSubject.purpose(), editSubject.contextScopeKey(),
                editSubject.policyRevision(), editSubject.authorizationRevision(),
                context.getBean(PayrollLegalEntityScopeResolver.class).resolve(editSubject),
                PayrollFoundationAccess.compatibilityPolicy());
        FoundationDefinition forbiddenDefinition = definition(
                otherLegalEntity, UUID.randomUUID(),
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31),
                Set.of(currency("EUR")), List.of());
        assertThatThrownBy(() -> service.create(
                editor, UUID.randomUUID(), null,
                new CreateConfigurationRequest(forbiddenDefinition)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.update(
                editor, second.configuration().configurationId(), UUID.randomUUID(), null,
                new UpdateConfigurationRequest(
                        second.configuration().version(), forbiddenDefinition)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));

        JdbcTemplate runtimeJdbc = new JdbcTemplate(context.getBean(DataSource.class));
        assertThatThrownBy(() -> runtimeJdbc.update("""
                UPDATE pay_legal_entity_scope_projections
                   SET status = 'REVOKED'
                 WHERE tenant_id = 1 AND projection_id = ?
                """, projectionId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> runtimeJdbc.update("""
                INSERT INTO pay_legal_entity_scope_members (
                    tenant_id, projection_id, legal_entity_id)
                VALUES (1, ?, ?)
                """, projectionId, otherLegalEntity))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> adminJdbc.update("""
                UPDATE pay_legal_entity_scope_projections
                   SET projection_revision = 'tampered'
                 WHERE tenant_id = 1 AND projection_id = ?
                """, projectionId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> adminJdbc.update("""
                UPDATE pay_legal_entity_scope_members
                   SET legal_entity_id = ?
                 WHERE tenant_id = 1 AND projection_id = ?
                   AND legal_entity_id = ?
                """, otherLegalEntity, projectionId, LEGAL_ENTITY_ID))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> adminJdbc.update("""
                DELETE FROM pay_legal_entity_scope_members
                 WHERE tenant_id = 1 AND projection_id = ?
                """, projectionId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void ownerProjectionRejectsMissingExpiredRevisionActorAndTenantMembership() {
        Instant now = Instant.parse("2026-09-17T09:00:00Z");
        String policyRevision = "rollout-" + "b".repeat(64);
        String authorizationRevision = "psr-" + "a".repeat(64);
        PayrollLegalEntityScopeResolver resolver =
                context.getBean(PayrollLegalEntityScopeResolver.class);
        String expiredScope = "hcm-scope-" + "2".repeat(40);
        insertScopeProjection(
                1, 101, expiredScope, policyRevision, authorizationRevision,
                UUID.randomUUID(), "expired-r1", now.minusSeconds(600),
                now.minusSeconds(1), LEGAL_ENTITY_ID);
        String otherRevisionScope = "hcm-scope-" + "3".repeat(40);
        insertScopeProjection(
                1, 101, otherRevisionScope, policyRevision, "psr-" + "c".repeat(64),
                UUID.randomUUID(), "other-auth-r1", now.minusSeconds(60),
                now.plusSeconds(600), LEGAL_ENTITY_ID);
        String otherTenantScope = "hcm-scope-" + "4".repeat(40);
        insertScopeProjection(
                2, 101, otherTenantScope, policyRevision, authorizationRevision,
                UUID.randomUUID(), "tenant-2-r1", now.minusSeconds(60),
                now.plusSeconds(600), LEGAL_ENTITY_ID);
        String emptyScope = "hcm-scope-" + "5".repeat(40);
        insertBuildingScopeProjection(
                1, 101, emptyScope, policyRevision, authorizationRevision,
                UUID.randomUUID(), "empty-r1", now.minusSeconds(60),
                now.plusSeconds(600));
        String revokedScope = "hcm-scope-" + "6".repeat(40);
        UUID revokedProjectionId = UUID.randomUUID();
        insertScopeProjection(
                1, 101, revokedScope, policyRevision, authorizationRevision,
                revokedProjectionId, "revoked-r1", now.minusSeconds(60),
                now.plusSeconds(600), LEGAL_ENTITY_ID);
        adminJdbc.update("""
                UPDATE pay_legal_entity_scope_projections
                   SET status = 'REVOKED', valid_until = ?
                 WHERE tenant_id = 1 AND projection_id = ?
                """, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), revokedProjectionId);

        List<PayrollFoundationRequestContext.VerifiedSubject> rejected = List.of(
                verifiedSubject(1, 101, PayrollFoundationModels.FoundationAction.VIEW,
                        "PAYROLL_AUDIT", "hcm-scope-" + "0".repeat(40),
                        policyRevision, authorizationRevision, now.plusSeconds(60)),
                verifiedSubject(1, 101, PayrollFoundationModels.FoundationAction.VIEW,
                        "PAYROLL_AUDIT", expiredScope,
                        policyRevision, authorizationRevision, now.plusSeconds(60)),
                verifiedSubject(1, 101, PayrollFoundationModels.FoundationAction.VIEW,
                        "PAYROLL_AUDIT", otherRevisionScope,
                        policyRevision, authorizationRevision, now.plusSeconds(60)),
                verifiedSubject(1, 202, PayrollFoundationModels.FoundationAction.VIEW,
                        "PAYROLL_AUDIT", otherRevisionScope,
                        policyRevision, "psr-" + "c".repeat(64), now.plusSeconds(60)),
                verifiedSubject(1, 101, PayrollFoundationModels.FoundationAction.VIEW,
                        "PAYROLL_AUDIT", otherTenantScope,
                        policyRevision, authorizationRevision, now.plusSeconds(60)),
                verifiedSubject(1, 101, PayrollFoundationModels.FoundationAction.VIEW,
                        "PAYROLL_AUDIT", emptyScope,
                        policyRevision, authorizationRevision, now.plusSeconds(60)),
                verifiedSubject(1, 101, PayrollFoundationModels.FoundationAction.VIEW,
                        "PAYROLL_AUDIT", revokedScope,
                        policyRevision, authorizationRevision, now.plusSeconds(60)),
                verifiedSubject(2, 101, PayrollFoundationModels.FoundationAction.VIEW,
                        "PAYROLL_AUDIT", otherTenantScope,
                        policyRevision, authorizationRevision, now.minusSeconds(1)));

        for (PayrollFoundationRequestContext.VerifiedSubject subject : rejected) {
            assertThatThrownBy(() -> resolver.resolve(subject))
                    .isInstanceOfSatisfying(BaseException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        }
    }

    @Test
    void buildingProjectionIsInvisibleUntilACompleteMembershipIsSealed() {
        Instant now = Instant.parse("2026-09-17T09:00:00Z");
        String scopeKey = "hcm-scope-" + "7".repeat(40);
        String policyRevision = "rollout-" + "b".repeat(64);
        String authorizationRevision = "psr-" + "a".repeat(64);
        UUID projectionId = UUID.randomUUID();
        UUID secondLegalEntity = UUID.fromString(
                "10000000-0000-0000-0000-000000000077");
        PayrollFoundationRequestContext.VerifiedSubject subject = verifiedSubject(
                1, 101, PayrollFoundationModels.FoundationAction.VIEW,
                "PAYROLL_AUDIT", scopeKey, policyRevision, authorizationRevision,
                now.plusSeconds(300));
        PayrollLegalEntityScopeResolver resolver =
                context.getBean(PayrollLegalEntityScopeResolver.class);

        adminTransaction.executeWithoutResult(transaction -> {
            insertBuildingScopeProjection(
                    1, 101, scopeKey, policyRevision, authorizationRevision,
                    projectionId, "pay-building-r1", now.minusSeconds(60),
                    now.plusSeconds(600));
            insertScopeMember(1, projectionId, LEGAL_ENTITY_ID);
            insertScopeMember(1, projectionId, secondLegalEntity);
        });

        assertThatThrownBy(() -> resolver.resolve(subject))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));

        adminJdbc.update("""
                UPDATE pay_legal_entity_scope_projections
                   SET status = 'ACTIVE'
                 WHERE tenant_id = 1 AND projection_id = ?
                """, projectionId);

        assertThat(resolver.resolve(subject).legalEntityIds())
                .containsExactlyInAnyOrder(LEGAL_ENTITY_ID, secondLegalEntity);
    }

    @Test
    void sealRejectsEmptyOrDirectActiveProjectionAndActiveMemberAppend() {
        Instant now = Instant.parse("2026-09-17T09:00:00Z");
        String policyRevision = "rollout-" + "b".repeat(64);
        String authorizationRevision = "psr-" + "a".repeat(64);
        UUID emptyProjectionId = UUID.randomUUID();
        String emptyScopeKey = "hcm-scope-" + "8".repeat(40);
        insertBuildingScopeProjection(
                1, 101, emptyScopeKey, policyRevision, authorizationRevision,
                emptyProjectionId, "pay-empty-r1", now.minusSeconds(60),
                now.plusSeconds(600));

        assertThatThrownBy(() -> adminJdbc.update("""
                UPDATE pay_legal_entity_scope_projections
                   SET status = 'ACTIVE'
                 WHERE tenant_id = 1 AND projection_id = ?
                """, emptyProjectionId))
                .isInstanceOf(DataAccessException.class);
        assertThat(adminJdbc.queryForObject("""
                SELECT status
                  FROM pay_legal_entity_scope_projections
                 WHERE tenant_id = 1 AND projection_id = ?
                """, String.class, emptyProjectionId)).isEqualTo("BUILDING");
        assertThatThrownBy(() -> adminJdbc.update("""
                UPDATE pay_legal_entity_scope_projections
                   SET status = 'REVOKED', valid_until = ?
                 WHERE tenant_id = 1 AND projection_id = ?
                """, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), emptyProjectionId))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> adminJdbc.update("""
                INSERT INTO pay_legal_entity_scope_projections (
                    tenant_id, projection_id, actor_id, context_scope_key,
                    policy_revision, authorization_revision, projection_revision,
                    status, valid_from, valid_until, recorded_at)
                VALUES (1, ?, 101, ?, ?, ?, 'pay-direct-active-r1',
                        'ACTIVE', ?, ?, ?)
                """, UUID.randomUUID(), "hcm-scope-" + "9".repeat(40), policyRevision,
                authorizationRevision,
                OffsetDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now.plusSeconds(600), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC)))
                .isInstanceOf(DataAccessException.class);

        insertScopeMember(1, emptyProjectionId, LEGAL_ENTITY_ID);
        adminJdbc.update("""
                UPDATE pay_legal_entity_scope_projections
                   SET status = 'ACTIVE'
                 WHERE tenant_id = 1 AND projection_id = ?
                """, emptyProjectionId);
        assertThatThrownBy(() -> adminJdbc.update("""
                UPDATE pay_legal_entity_scope_projections
                   SET status = 'BUILDING'
                 WHERE tenant_id = 1 AND projection_id = ?
                """, emptyProjectionId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertScopeMember(
                1, emptyProjectionId,
                UUID.fromString("10000000-0000-0000-0000-000000000088")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void terminalTransitionsKeepSealedMembersAndLeaveResolution() {
        Instant now = Instant.parse("2026-09-17T09:00:00Z");
        String policyRevision = "rollout-" + "b".repeat(64);
        String authorizationRevision = "psr-" + "a".repeat(64);
        PayrollLegalEntityScopeResolver resolver =
                context.getBean(PayrollLegalEntityScopeResolver.class);

        for (String terminalStatus : List.of("SUPERSEDED", "REVOKED")) {
            UUID projectionId = UUID.randomUUID();
            String scopeKey = terminalStatus.equals("SUPERSEDED")
                    ? "hcm-scope-" + "a".repeat(40)
                    : "hcm-scope-" + "b".repeat(40);
            insertScopeProjection(
                    1, 101, scopeKey, policyRevision, authorizationRevision,
                    projectionId, "pay-terminal-" + terminalStatus.toLowerCase(),
                    now.minusSeconds(60), now.plusSeconds(600), LEGAL_ENTITY_ID);

            adminJdbc.update("""
                    UPDATE pay_legal_entity_scope_projections
                       SET status = ?, valid_until = ?
                     WHERE tenant_id = 1 AND projection_id = ?
                    """, terminalStatus, OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                    projectionId);

            assertThat(adminJdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM pay_legal_entity_scope_members
                     WHERE tenant_id = 1 AND projection_id = ?
                    """, Long.class, projectionId)).isEqualTo(1L);
            PayrollFoundationRequestContext.VerifiedSubject subject = verifiedSubject(
                    1, 101, PayrollFoundationModels.FoundationAction.VIEW,
                    "PAYROLL_AUDIT", scopeKey, policyRevision, authorizationRevision,
                    now.plusSeconds(300));
            assertThatThrownBy(() -> resolver.resolve(subject))
                    .isInstanceOfSatisfying(BaseException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        }
    }

    @Test
    void cleanMigrationForcesRlsAndKeepsRuntimeProjectionAccessSelectOnly() {
        assertThat(adminJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM flyway_schema_history
                 WHERE version = '4' AND success
                """, Long.class)).isEqualTo(1L);
        assertThat(adminJdbc.queryForObject("""
                SELECT bool_and(relrowsecurity AND relforcerowsecurity)
                  FROM pg_class
                 WHERE relname IN (
                     'pay_legal_entity_scope_projections',
                     'pay_legal_entity_scope_members')
                """, Boolean.class)).isTrue();

        JdbcTemplate runtimeJdbc = new JdbcTemplate(context.getBean(DataSource.class));
        for (String table : List.of(
                "pay_legal_entity_scope_projections",
                "pay_legal_entity_scope_members")) {
            assertThat(runtimeJdbc.queryForObject(
                    "SELECT has_table_privilege(current_user, ?, 'SELECT')",
                    Boolean.class, table)).isTrue();
            for (String privilege : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
                assertThat(runtimeJdbc.queryForObject(
                        "SELECT has_table_privilege(current_user, ?, ?)",
                        Boolean.class, table, privilege)).isFalse();
            }
        }
    }

    @Test
    void derivedScopeMigrationRejectsLegacyRowsWithoutRewritingThem() throws IOException {
        String sql = derivedScopeMigrationSql();

        assertThat(sql)
                .contains("PAY contains legacy/noncanonical scope keys")
                .contains("FROM pay_legal_entity_scope_projections")
                .contains("ALTER COLUMN context_scope_key TYPE VARCHAR(50)")
                .contains("CHECK (context_scope_key ~ '^hcm-scope-[0-9a-f]{40}$')")
                .doesNotContain("UPDATE pay_legal_entity_scope_projections")
                .doesNotContain("DELETE FROM pay_legal_entity_scope_projections");
    }

    private void insertScopeProjection(
            long tenantId,
            long actorId,
            String contextScopeKey,
            String policyRevision,
            String authorizationRevision,
            UUID projectionId,
            String projectionRevision,
            Instant validFrom,
            Instant validUntil,
            UUID... legalEntityIds) {
        adminTransaction.executeWithoutResult(transaction -> {
            insertBuildingScopeProjection(
                    tenantId, actorId, contextScopeKey, policyRevision,
                    authorizationRevision, projectionId, projectionRevision,
                    validFrom, validUntil);
            for (UUID legalEntityId : legalEntityIds) {
                insertScopeMember(tenantId, projectionId, legalEntityId);
            }
            adminJdbc.update("""
                    UPDATE pay_legal_entity_scope_projections
                       SET status = 'ACTIVE'
                     WHERE tenant_id = ? AND projection_id = ?
                    """, tenantId, projectionId);
        });
    }

    private static String derivedScopeMigrationSql() throws IOException {
        try (InputStream input = JdbcPayrollFoundationStoreTest.class.getClassLoader()
                .getResourceAsStream(
                        "db/migration/V4__pay_adopt_derived_hcm_scope_keys.sql")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void insertBuildingScopeProjection(
            long tenantId,
            long actorId,
            String contextScopeKey,
            String policyRevision,
            String authorizationRevision,
            UUID projectionId,
            String projectionRevision,
            Instant validFrom,
            Instant validUntil) {
        adminJdbc.update("""
                INSERT INTO pay_legal_entity_scope_projections (
                    tenant_id, projection_id, actor_id, context_scope_key,
                    policy_revision, authorization_revision, projection_revision,
                    status, valid_from, valid_until, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'BUILDING', ?, ?, ?)
                """, tenantId, projectionId, actorId, contextScopeKey,
                policyRevision, authorizationRevision, projectionRevision,
                OffsetDateTime.ofInstant(validFrom, ZoneOffset.UTC),
                validUntil == null
                        ? null : OffsetDateTime.ofInstant(validUntil, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(validFrom, ZoneOffset.UTC));
    }

    private void insertScopeMember(long tenantId, UUID projectionId, UUID legalEntityId) {
        adminJdbc.update("""
                INSERT INTO pay_legal_entity_scope_members (
                    tenant_id, projection_id, legal_entity_id)
                VALUES (?, ?, ?)
                """, tenantId, projectionId, legalEntityId);
    }

    private PayrollFoundationRequestContext.VerifiedSubject verifiedSubject(
            long tenantId,
            long actorId,
            PayrollFoundationModels.FoundationAction action,
            String purpose,
            String scopeKey,
            String policyRevision,
            String authorizationRevision,
            Instant revalidateAt) {
        return new PayrollFoundationRequestContext.VerifiedSubject(
                tenantId, actorId, action, purpose,
                "psc-" + "d".repeat(64), scopeKey,
                policyRevision, authorizationRevision, revalidateAt,
                "route.hcm.operations.payroll-foundation-test");
    }

    private PayrollFoundationAccess.Actor author(long tenant, long actor) {
        return PayrollFoundationAccess.actor(
                tenant, actor, "CONFIGURATION_AUTHOR",
                "APP.HRIS:VIEW PAYROLL_FOUNDATION:VIEW PAYROLL_FOUNDATION:EDIT "
                        + "PAYROLL_FOUNDATION:SIMULATE",
                "PAYROLL_CONFIGURATION", "*");
    }

    private PayrollFoundationAccess.Actor publisher(long tenant, long actor) {
        return PayrollFoundationAccess.actor(
                tenant, actor, "CONFIGURATION_PUBLISHER",
                "APP.HRIS:VIEW PAYROLL_FOUNDATION:VIEW PAYROLL_FOUNDATION:PUBLISH "
                        + "PAYROLL_FOUNDATION:REVERSE PAYROLL_FOUNDATION:RECONCILE",
                "PAYROLL_CONFIGURATION", "*");
    }

    private static DataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, username, password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }

    @EnableTransactionManagement(proxyTargetClass = true)
    static class DatabaseTestConfiguration {

        @Bean
        DataSource dataSource() {
            return JdbcPayrollFoundationStoreTest.dataSource(
                    POSTGRES.getJdbcUrl(), RUNTIME_USER, RUNTIME_PASSWORD);
        }

        @Bean
        NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
            return new NamedParameterJdbcTemplate(dataSource);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().findAndAddModules().build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        PayrollFoundationStore payrollFoundationStore(
                NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
            return new JdbcPayrollFoundationStore(jdbc, objectMapper);
        }

        @Bean
        PayrollLegalEntityScopeResolver payrollLegalEntityScopeResolver(
                NamedParameterJdbcTemplate jdbc) {
            return new JdbcPayrollLegalEntityScopeResolver(
                    jdbc,
                    Clock.fixed(
                            Instant.parse("2026-09-17T09:00:00Z"),
                            ZoneOffset.UTC));
        }
    }
}

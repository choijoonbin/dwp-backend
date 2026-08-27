package com.dwp.services.provider.support;

import com.dwp.services.provider.security.ProviderOperatorService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ProviderSupportAuthorityReconciliationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void databaseTimePulseContainsAGrantAfterItsAuthorityNaturallyExpires() {
        PGSimpleDataSource dataSource = dataSource();
        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long operatorId = newSupportOperator(jdbc);
        UUID requestId = pendingRequest(dataSource, jdbc, operatorId);
        jdbc.update("""
                UPDATE prv_operator_role_assignments
                   SET valid_to = statement_timestamp() + INTERVAL '1 second'
                 WHERE provider_operator_id = ?
                   AND role_code = 'PROVIDER_SUPPORT'
                """, operatorId);
        Timestamp before = reconciliationTime(jdbc);

        jdbc.execute("SELECT pg_sleep(1.1)");
        reconcile(dataSource);

        assertThat(reconciliationTime(jdbc)).isAfter(before);
        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, cancellation_origin
                  FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, requestId))
                .containsEntry("lifecycle_state", "CANCELLED")
                .containsEntry("cancellation_origin", "AUTOMATIC_AUTHORITY_CONTAINMENT");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_audit_events event
                  JOIN sys_audit_outbox outbox
                    ON outbox.event_id = event.audit_event_id
                 WHERE event.action =
                       'provider.support-access.cancelled-for-authority-loss'
                   AND event.target_id = ?
                   AND event.redacted_snapshot ->> 'reason' =
                       'Support owner authority became unavailable'
                   AND event.redacted_snapshot ->> 'reasonCode' =
                       'OWNER_SUPPORT_AUTHORITY_UNAVAILABLE'
                   AND outbox.tenant_id > 0
                   AND outbox.payload ->> 'action' = event.action
                   AND outbox.payload -> 'afterState' = event.redacted_snapshot
                   AND outbox.payload ->> 'retentionClass' = 'EXTENDED'
                """, Integer.class, requestId.toString())).isEqualTo(1);

        assertCatalogAuthorityMatchesDatabase(jdbc);
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private Long newSupportOperator(JdbcTemplate jdbc) {
        long authUserId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 800_000_000L)
                + 100_000_000L;
        Long operatorId = jdbc.queryForObject("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name,
                    role_code, lifecycle_state)
                VALUES (1, ?, 'Authority pulse operator',
                        'PROVIDER_SUPPORT', 'ACTIVE')
                RETURNING provider_operator_id
                """, Long.class, authUserId);
        jdbc.update("""
                INSERT INTO prv_operator_role_assignments (
                    provider_operator_id, role_code, lifecycle_state, created_by)
                VALUES (?, 'PROVIDER_SUPPORT', 'ACTIVE', ?)
                """, operatorId, operatorId);
        return operatorId;
    }

    private void assertCatalogAuthorityMatchesDatabase(JdbcTemplate jdbc) {
        Long operatorId = newSupportOperator(jdbc);
        Long authUserId = jdbc.queryForObject("""
                SELECT auth_user_id FROM prv_operators
                 WHERE provider_operator_id = ?
                """, Long.class, operatorId);
        ProviderOperatorService operatorService = new ProviderOperatorService(jdbc);
        assertThat(operatorService.activeOperator(1L, authUserId)).get()
                .satisfies(actor -> assertThat(actor.permissions())
                        .contains("SUPPORT_SESSION_WRITE"));

        try {
            jdbc.update("""
                    UPDATE prv_operator_permission_catalog
                       SET risk_tier = 'L2', updated_at = statement_timestamp()
                     WHERE permission_code = 'SUPPORT_SESSION_WRITE'
                    """);

            assertThat(operatorService.activeOperator(1L, authUserId)).get()
                    .satisfies(actor -> assertThat(actor.permissions())
                            .doesNotContain("SUPPORT_SESSION_WRITE"));
            assertThat(jdbc.queryForObject(
                    "SELECT prv_operator_has_effective_support_authority(?)",
                    Boolean.class,
                    operatorId)).isFalse();
        } finally {
            jdbc.update("""
                    UPDATE prv_operator_permission_catalog
                       SET risk_tier = 'L3', updated_at = statement_timestamp()
                     WHERE permission_code = 'SUPPORT_SESSION_WRITE'
                    """);
        }
    }

    private UUID pendingRequest(
            DataSource dataSource,
            JdbcTemplate jdbc,
            Long operatorId) {
        UUID requestId = UUID.randomUUID();
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(ignored -> {
                    jdbc.update("""
                            INSERT INTO prv_support_access_requests (
                                support_access_request_id, provider_tenant_id,
                                requester_operator_id, requester_auth_session_id,
                                lifecycle_state, justification, duration_minutes,
                                approval_reference, customer_approval_required, risk_tier,
                                request_key, request_fingerprint, decision_due_at,
                                created_by, updated_by)
                            VALUES (?, '00000000-0000-0000-0000-000000000001', ?, ?,
                                    'PENDING_APPROVAL',
                                    'Database clock reconciliation test', 15,
                                    'CUSTOMER-APPROVAL-TEST', TRUE, 'L1', ?, ?,
                                    statement_timestamp() + INTERVAL '1 hour', ?, ?)
                            """, requestId, operatorId, UUID.randomUUID(),
                            "authority-pulse-" + requestId, "a".repeat(64),
                            operatorId, operatorId);
                    jdbc.update("""
                            INSERT INTO prv_support_access_request_scopes (
                                support_access_request_id, scope_code)
                            VALUES (?, 'TENANT_EXPERIENCE_PREVIEW')
                            """, requestId);
                });
        return requestId;
    }

    private Timestamp reconciliationTime(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT authority_reconciled_at
                  FROM prv_support_activation_control
                 WHERE control_key = 'STANDARD_JIT'
                """, Timestamp.class);
    }

    private void reconcile(DataSource dataSource) {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.register(TransactionConfiguration.class);
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
            context.registerBean(PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(dataSource));
            context.registerBean(ProviderSupportSessionRepository.class);
            context.registerBean(ProviderSupportAuthorityReconciliationService.class);
            context.refresh();
            context.getBean(ProviderSupportAuthorityReconciliationService.class).reconcile();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }
}

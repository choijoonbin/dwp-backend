package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class NavigationPolicyPostgresTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-14T04:00:00Z");
    private static final UUID LEGAL_ENTITY =
            UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final String DIGEST = "a".repeat(64);
    private static final JsonMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static NavigationPolicyService service;

    @BeforeAll
    static void migrateExactLease() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .baselineVersion("325")
                .target("326")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.baseline();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcNavigationPolicyRepository repository =
                new JdbcNavigationPolicyRepository(jdbc, MAPPER);
        service = new NavigationPolicyService(
                repository, request -> request.requestedLegalEmployerPublicId(),
                new NavigationPolicyEligibilityProvider() {
                    @Override
                    public Set<String> installedNodeIds(long tenantId) {
                        return NavigationPolicyCanonical.NODE_IDS;
                    }

                    @Override
                    public Set<String> pageAuthorizedNodeIds(Set<String> permissions) {
                        return NavigationPolicyCanonical.NODE_IDS;
                    }
                },
                tenantId -> new NavigationPolicyRolloutProvider.RuntimeEnrollment(
                        true, RolloutMode.ENFORCED), evidence -> true, MAPPER,
                Clock.fixed(Instant.parse("2026-10-14T04:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void lifecycleIsIdempotentAtomicImmutableAndProducesRevisionPinnedProjection() {
        AccessContext author = author(101, null);
        AccessContext checker = checker(202, null);
        PolicyDocument baseline = NavigationPolicyCanonicalTest.policy(
                ScopeKind.TENANT, null, false);
        DraftCommand create = draft(baseline, 0);

        CommandReceipt created = transaction(() -> service.createDraft(
                author, command("create-tenant-policy", false), create));
        CommandReceipt replay = transaction(() -> service.createDraft(
                author, command("create-tenant-policy", false), create));
        assertThat(replay).isEqualTo(created);

        CommandReceipt validated = transaction(() -> service.validate(
                author, command("validate-tenant-policy", false), created.revisionId(),
                lifecycle(0)));
        CommandReceipt submitted = transaction(() -> service.submit(
                author, command("submit-tenant-policy", false), created.revisionId(),
                lifecycle(validated.objectVersion())));
        CommandReceipt approved = transaction(() -> service.approve(
                checker, command("approve-tenant-policy", true), created.revisionId(),
                lifecycle(submitted.objectVersion())));
        CommandReceipt published = transaction(() -> service.publish(
                checker, command("publish-tenant-policy", true), created.revisionId(),
                lifecycle(approved.objectVersion())));

        assertThat(published.lifecycleState()).isEqualTo(LifecycleState.PUBLISHED);
        assertThat(transaction(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_navigation_policy_command_receipts
                 WHERE tenant_id = 42 AND policy_id = ?
                """, Integer.class, published.policyId()))).isEqualTo(5);
        assertThat(transaction(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_navigation_policy_audit
                 WHERE tenant_id = 42 AND policy_id = ?
                """, Integer.class, published.policyId()))).isEqualTo(5);
        assertThat(transaction(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_navigation_policy_outbox
                 WHERE tenant_id = 42 AND policy_id = ?
                """, Integer.class, published.policyId()))).isEqualTo(1);

        EffectiveProjection projection = transaction(() -> service.projection(
                runtime(RolloutMode.ENFORCED), NavigationPolicyCanonical.WORKBENCH_ID));
        assertThat(projection.enforcementState()).isEqualTo(EnforcementState.ENFORCED);
        assertThat(projection.policyEnforced()).isTrue();
        assertThat(projection.revisionPin()).startsWith("nav-");
        assertThat(projection.groups()).hasSize(7);
        assertThat(projection.groups().getFirst().nodes())
                .extracting(EffectiveNode::nodeId)
                .doesNotContain("BASE:hr-operations-home");

        CommandReceipt restored = transaction(() -> service.restore(
                checker, command("restore-tenant-policy", true), created.revisionId(),
                new RestoreCommand(published.objectVersion(), DIGEST, "INCIDENT_RECOVERY")));
        assertThat(restored.revisionId()).isNotEqualTo(created.revisionId());
        assertThat(restored.revisionNumber()).isEqualTo(2);
        assertThat(transaction(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_navigation_policy_outbox
                 WHERE tenant_id = 42 AND policy_id = ?
                """, Integer.class, published.policyId()))).isEqualTo(2);

        assertThatThrownBy(() -> transaction(() -> {
            jdbc.update("""
                    UPDATE hris_navigation_policy_revisions
                       SET payload_digest = ?
                     WHERE tenant_id = 42 AND revision_id = ?
                    """, "b".repeat(64), restored.revisionId());
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void legalEntityOverlayCannotReenableTenantDenialAndEnforcedMissingFailsClosed() {
        PolicyDocument hostileOverlay = NavigationPolicyCanonicalTest.policy(
                ScopeKind.LEGAL_ENTITY, LEGAL_ENTITY, true);
        assertThatThrownBy(() -> transaction(() -> service.createDraft(
                author(303, LEGAL_ENTITY), command("hostile-overlay-create", false),
                draft(hostileOverlay, 0))))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("OVERLAY_REENABLES_NODE");

        EffectiveProjection missing = transaction(() -> service.projection(
                new RuntimeContext(
                        99, 303, UUID.randomUUID(), "APP.HCM:VIEW", "TENANT_ADMIN",
                        scopeKey(), null, NOW.plusHours(1)),
                NavigationPolicyCanonical.WORKBENCH_ID));
        assertThat(missing.enforcementState())
                .isEqualTo(EnforcementState.DENIED_MISSING_POLICY);
        assertThat(missing.groups()).isEmpty();
    }

    @Test
    void forceRlsHidesOtherTenantPolicyRows() {
        assertThat(jdbc.queryForObject("""
                SELECT bool_and(relforcerowsecurity)
                  FROM pg_class
                 WHERE relname LIKE 'hris_navigation_policy_%' AND relkind = 'r'
                """, Boolean.class)).isTrue();
        jdbc.execute("DROP ROLE IF EXISTS hris_navigation_policy_runtime_test");
        jdbc.execute("CREATE ROLE hris_navigation_policy_runtime_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO hris_navigation_policy_runtime_test");
        jdbc.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public "
                + "TO hris_navigation_policy_runtime_test");
        Integer visible = transactions.execute(ignored -> {
            jdbc.execute("SET LOCAL ROLE hris_navigation_policy_runtime_test");
            jdbc.queryForObject("SELECT set_config('dwp.tenant_id', '99', true)", String.class);
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM hris_navigation_policy_heads", Integer.class);
        });
        assertThat(visible).isZero();
    }

    private static DraftCommand draft(PolicyDocument policy, long expectedVersion) {
        return new DraftCommand(
                policy.scopeKind(), policy.legalEntityPublicId(),
                policy.legalEntityPublicId(), policy.rolloutMode(), policy.workbenchId(),
                policy.groups(), expectedVersion, DIGEST);
    }

    private static LifecycleCommand lifecycle(long version) {
        return new LifecycleCommand(version, DIGEST, "GOVERNED_POLICY_CHANGE");
    }

    private static AccessContext author(long subjectId, UUID legalEntity) {
        return access(subjectId, legalEntity,
                "APP.HCM:VIEW,CONFIG.HRIS_NAVIGATION_POLICY:VIEW,"
                        + "CONFIG.HRIS_NAVIGATION_POLICY:UPDATE");
    }

    private static AccessContext checker(long subjectId, UUID legalEntity) {
        return access(subjectId, legalEntity,
                "APP.HCM:VIEW,CONFIG.HRIS_NAVIGATION_POLICY:VIEW,"
                        + "ACTION.HRIS_NAVIGATION_POLICY_PUBLISH:PUBLISH");
    }

    private static AccessContext access(long subjectId, UUID legalEntity, String permissions) {
        return new AccessContext(
                42, subjectId, UUID.nameUUIDFromBytes(("subject-" + subjectId).getBytes()),
                permissions, "TENANT_ADMIN", "hcm-management-context", scopeKey(),
                "decision-7", NOW.plusHours(1), PURPOSE);
    }

    private static RuntimeContext runtime(RolloutMode mode) {
        return new RuntimeContext(
                42, 101, UUID.randomUUID(), "APP.HCM:VIEW", "TENANT_ADMIN",
                scopeKey(), null, NOW.plusHours(1));
    }

    private static CommandContext command(String key, boolean stepUp) {
        String padded = key.length() < 16 ? key + "-idempotency-key" : key;
        return new CommandContext(
                padded, UUID.nameUUIDFromBytes(key.getBytes()),
                stepUp ? "decision-7" : null, stepUp ? "fresh-step-up-proof" : null,
                "POST", "/test", null);
    }

    private static String scopeKey() {
        return "hcm-scope-" + "a".repeat(40);
    }

    private static <T> T transaction(Supplier<T> work) {
        return transactions.execute(ignored -> work.get());
    }
}

package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class NavigationPolicyAggregateV2PostgresTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-17T04:00:00Z");
    private static final String DIGEST = "a".repeat(64);
    private static final long AUTHORITY_FIELD_REVISION = 4_663_670_355_146_917_040L;
    private static final long AUTHORITY_AUTHORIZATION_REVISION =
            1_991_387_878_219_104_048L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static NavigationPolicyAggregateV2LifecycleService lifecycle;
    private static NavigationPolicyAggregateV2Service runtime;

    @BeforeAll
    static void migrateExactLease() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .baselineVersion("325").target("329").cleanDisabled(false).load();
        flyway.clean();
        flyway.baseline();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        JdbcNavigationPolicyAggregateV2Repository repository =
                new JdbcNavigationPolicyAggregateV2Repository(jdbc, mapper);
        lifecycle = new NavigationPolicyAggregateV2LifecycleService(
                repository, request -> request.requestedLegalEmployerPublicId(),
                evidence -> true, mapper, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        NavigationPolicyEligibilityProvider eligibility =
                new NavigationPolicyEligibilityProvider() {
                    @Override
                    public Set<String> installedNodeIds(long tenantId) {
                        return NavigationPolicyAggregateV2Canonical.NODE_IDS;
                    }

                    @Override
                    public Set<String> deployedNodeIds(long tenantId) {
                        return NavigationPolicyAggregateV2Canonical.NODE_IDS;
                    }

                    @Override
                    public Set<String> pageAuthorizedNodeIds(Set<String> permissions) {
                        return NavigationPolicyAggregateV2Canonical.NODE_IDS;
                    }
                };
        runtime = new NavigationPolicyAggregateV2Service(
                repository, request -> request.requestedLegalEmployerPublicId(),
                eligibility, tenantId ->
                new NavigationPolicyRolloutProvider.RuntimeEnrollment(
                        true, RolloutMode.ENFORCED),
                mapper, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    @Test
    void lifecycleAtomicallyPinsExactPublishedRevisionAndImmutableEvidence() {
        AggregatePolicyDocument policy =
                NavigationPolicyAggregateV2CanonicalTest.policy(ScopeKind.TENANT, null);
        AggregateDraftCommand create = new AggregateDraftCommand(
                ScopeKind.TENANT, null, null, RolloutMode.ENFORCED,
                policy.workbenches(), 0L, DIGEST);

        CommandReceipt created = transaction(() -> lifecycle.createDraft(
                author(101), command("create-policy", false), create));
        CommandReceipt replay = transaction(() -> lifecycle.createDraft(
                author(101), command("create-policy", false), create));
        assertThat(replay).isEqualTo(created);
        CommandReceipt validated = transaction(() -> lifecycle.validate(
                author(101), command("validate-policy", false), created.revisionId(),
                lifecycleCommand(created.objectVersion())));
        CommandReceipt submitted = transaction(() -> lifecycle.submit(
                author(101), command("submit-policy", false), created.revisionId(),
                lifecycleCommand(validated.objectVersion())));
        CommandReceipt approved = transaction(() -> lifecycle.approve(
                checker(202), command("approve-policy", true), created.revisionId(),
                lifecycleCommand(submitted.objectVersion())));
        CommandReceipt published = transaction(() -> lifecycle.publish(
                checker(202), command("publish-policy", true), created.revisionId(),
                lifecycleCommand(approved.objectVersion())));

        assertThat(published.lifecycleState()).isEqualTo(LifecycleState.PUBLISHED);
        assertThat(transaction(() -> jdbc.queryForMap("""
                SELECT e.revision_id, e.payload_digest, r.lifecycle_state,
                       e.published_at AS effective_published_at,
                       r.published_at AS revision_published_at,
                       r.topology_revision, r.generated_ia_sha256
                  FROM hris_navigation_policy_v2_effective e
                  JOIN hris_navigation_policy_v2_revisions r
                    ON (r.tenant_id, r.policy_id, r.revision, r.revision_id,
                        r.payload_digest) =
                       (e.tenant_id, e.policy_id, e.revision, e.revision_id,
                        e.payload_digest)
                 WHERE e.tenant_id = 42
                """)))
                .containsEntry("revision_id", published.revisionId())
                .containsEntry("lifecycle_state", "PUBLISHED")
                .containsEntry("topology_revision",
                        NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION)
                .containsEntry("generated_ia_sha256",
                        NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256);
        assertThat(transaction(() -> jdbc.queryForMap("""
                SELECT e.published_at AS effective_published_at,
                       r.published_at AS revision_published_at
                  FROM hris_navigation_policy_v2_effective e
                  JOIN hris_navigation_policy_v2_revisions r
                    ON (r.tenant_id, r.policy_id, r.revision, r.revision_id,
                        r.payload_digest) =
                       (e.tenant_id, e.policy_id, e.revision, e.revision_id,
                        e.payload_digest)
                 WHERE e.tenant_id = 42
                """)))
                .satisfies(row -> assertThat(row.get("effective_published_at"))
                        .isEqualTo(row.get("revision_published_at")));

        EffectiveNavigation projection = transaction(() -> runtime.projection(runtimeContext()));
        assertThat(projection.enforcementState()).isEqualTo(EnforcementState.ENFORCED);
        assertThat(projection.workbenches()).hasSize(7);
        assertThat(projection.revisionPin()).matches("^nav-v2-[a-f0-9]{64}$");
        assertThat(projection.tenantRevisionId()).isEqualTo(published.revisionId());

        CommandReceipt restored = transaction(() -> lifecycle.restore(
                checker(202), command("restore-policy", true), created.revisionId(),
                new RestoreCommand(
                        published.objectVersion(), DIGEST, "INCIDENT_RECOVERY")));
        assertThat(restored.revisionId()).isNotEqualTo(published.revisionId());
        assertThat(restored.revisionNumber()).isEqualTo(2);
        assertThat(transaction(() -> jdbc.queryForMap("""
                SELECT e.revision_id, e.published_at AS effective_published_at,
                       r.published_at AS revision_published_at,
                       r.restored_from_revision
                  FROM hris_navigation_policy_v2_effective e
                  JOIN hris_navigation_policy_v2_revisions r
                    ON (r.tenant_id, r.policy_id, r.revision, r.revision_id,
                        r.payload_digest) =
                       (e.tenant_id, e.policy_id, e.revision, e.revision_id,
                        e.payload_digest)
                 WHERE e.tenant_id = 42
                """)))
                .containsEntry("revision_id", restored.revisionId())
                .containsEntry("restored_from_revision", 1L)
                .satisfies(row -> assertThat(row.get("effective_published_at"))
                        .isEqualTo(row.get("revision_published_at")));
        assertThat(transaction(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_navigation_policy_v2_outbox
                 WHERE tenant_id = 42 AND policy_id = ?
                """, Integer.class, restored.policyId()))).isEqualTo(2);

        assertThatThrownBy(() -> transaction(() -> {
            jdbc.update("DELETE FROM hris_navigation_policy_v2_outbox "
                    + "WHERE tenant_id = 42 AND policy_id = ?", published.policyId());
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> transaction(() -> {
            jdbc.update("""
                    UPDATE hris_navigation_policy_v2_effective
                       SET payload_digest = ? WHERE tenant_id = 42 AND policy_id = ?
                    """, "f".repeat(64), published.policyId());
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> transaction(() -> {
            jdbc.update("""
                    UPDATE hris_navigation_policy_v2_effective
                       SET scope_kind = 'LEGAL_ENTITY', legal_entity_public_id = ?
                     WHERE tenant_id = 42 AND policy_id = ?
                    """, UUID.fromString(
                    "00000000-0000-0000-0000-000000000501"), published.policyId());
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);

        Integer bridgeTriggers = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                 WHERE c.relname = 'hris_navigation_policy_revisions'
                   AND t.tgname LIKE '%v2%'
                """, Integer.class);
        assertThat(bridgeTriggers).isZero();
    }

    private static LifecycleCommand lifecycleCommand(long version) {
        return new LifecycleCommand(version, DIGEST, "GOVERNED_POLICY_CHANGE");
    }

    private static AccessContext author(long subjectId) {
        return access(subjectId, "APP.HCM:VIEW,CONFIG.HRIS_NAVIGATION_POLICY:VIEW,"
                + "CONFIG.HRIS_NAVIGATION_POLICY:UPDATE");
    }

    private static AccessContext checker(long subjectId) {
        return access(subjectId, "APP.HCM:VIEW,CONFIG.HRIS_NAVIGATION_POLICY:VIEW,"
                + "ACTION.HRIS_NAVIGATION_POLICY_PUBLISH:PUBLISH");
    }

    private static AccessContext access(long subjectId, String permissions) {
        return new AccessContext(
                42, subjectId, UUID.nameUUIDFromBytes(("subject-" + subjectId).getBytes()),
                permissions, "TENANT_ADMIN", "hcm-management-context", scopeKey(),
                "decision-7", NOW.plusHours(1),
                NavigationPolicyAggregateV2LifecycleService.PURPOSE);
    }

    private static RuntimeContext runtimeContext() {
        return new RuntimeContext(
                42, 101, UUID.randomUUID(), "APP.HCM:VIEW", "TENANT_ADMIN",
                scopeKey(), null, NOW.plusHours(1));
    }

    private static CommandContext command(String key, boolean stepUp) {
        String route = originRoute(key);
        var authority = new com.dwp.services.platform.security.HcmPlatformPepRegistry
                .RouteAuthority(route, "ACTION", "POST", "/test", "test-profile",
                stepUp, "test-capability", "ENABLED", null);
        return new CommandContext(
                key + "-idempotency-key", UUID.nameUUIDFromBytes(key.getBytes()),
                stepUp ? "decision-7" : null,
                stepUp ? "fresh-step-up-proof" : null,
                "POST", "/test", authority, route, "hcm-management-context",
                scopeKey(), "decision-7", "b".repeat(64),
                AUTHORITY_FIELD_REVISION, AUTHORITY_AUTHORIZATION_REVISION);
    }

    private static String originRoute(String key) {
        if (key.contains("validate")) {
            return "route.hcm.configuration.navigation-policy-v2-draft-validate.action";
        }
        if (key.contains("submit")) {
            return "route.hcm.configuration.navigation-policy-v2-draft-submit.action";
        }
        if (key.contains("approve")) {
            return "route.hcm.configuration.navigation-policy-v2-revision-approve.action";
        }
        if (key.contains("publish")) {
            return "route.hcm.configuration.navigation-policy-v2-revision-publish.action";
        }
        if (key.contains("restore")) {
            return "route.hcm.configuration.navigation-policy-v2-revision-restore.action";
        }
        return "route.hcm.configuration.navigation-policy-v2-draft-create.action";
    }

    private static String scopeKey() {
        return "hcm-scope-" + "a".repeat(40);
    }

    private static <T> T transaction(Supplier<T> work) {
        return transactions.execute(ignored -> work.get());
    }
}

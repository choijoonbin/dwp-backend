package com.dwp.services.auth.hrisservicegrant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class HrisServiceGrantAuthorityMigrationPostgresTest {
    @Container private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final List<String> TABLES = List.of(
            "auth_hris_service_grant_sets",
            "auth_hris_service_grant_bindings",
            "auth_hris_service_authority_proof_replays",
            "auth_hris_service_grant_quiesce_fences",
            "auth_hris_service_grant_drain_activities");
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static final String DIGEST_C = "c".repeat(64);
    private static final String DIGEST_D = "d".repeat(64);
    private static final String RSA_MODULUS =
            "1WuF1l_k-pcnIjxEQTyeJX1et0auB46t3l7NjeyRte9_oVL66Rq8Uo11rzv9iBlL"
            + "DkvTmj0GXDbaWbeWAStunX3OzvKzqn_vczThwE7YRCLi_l7zuyQwwiw74m0oeYm"
            + "Ti_IyE8vALsioFiCMDkfJoLQVAkowCuoKJXP0NpMkklO-EFxQf-OGa2Fx70DewRS"
            + "FA8sNMxyMbXC6YQV02JVN_QJdfcd9AdsqCd6BCTwBRj1uGaJAPIi43aMBSLr7ja"
            + "oU7RHvR9AtFwQLby_WmTNd4U3Iw4p0kdhcCSRJZahwcx-o47n5ziZ7BhENVBpnG"
            + "_sLGPkylV6fNL4R-gAfNDQoxQ";

    @Test
    void createsFiveEmptyForcedRlsTablesUsingTheSealedTenantKey() throws Exception {
        var source = migrated();
        var jdbc = new JdbcTemplate(source);

        for (String table : TABLES) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))
                    .as(table).isZero();
            assertThat(jdbc.queryForMap("""
                    SELECT relrowsecurity, relforcerowsecurity
                      FROM pg_class
                     WHERE oid = ('public.' || ?)::regclass
                    """, table)).containsEntry("relrowsecurity", true)
                    .containsEntry("relforcerowsecurity", true);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM pg_policies
                     WHERE schemaname = 'public' AND tablename = ?
                       AND qual LIKE '%dwp.auth.hrm013.tenant%'
                       AND with_check LIKE '%dwp.auth.hrm013.tenant%'
                    """, Integer.class, table)).isOne();
        }

        jdbc.update("INSERT INTO com_tenants (tenant_id, code, name) VALUES (2, 'rls-two', 'RLS Two')");
        insertReplay(jdbc, 1L, DIGEST_A);
        insertReplay(jdbc, 2L, DIGEST_B);
        UUID tenantTwoGrant = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID tenantTwoFence = UUID.fromString("00000000-0000-0000-0000-000000000202");
        OffsetDateTime tenantTwoEffectiveAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            insertGrant(connection, 2L, tenantTwoGrant, 1L, 1L, 1L,
                    "ADMIT", null, null, null, DIGEST_C);
            insertBinding(connection, 2L, tenantTwoGrant,
                    "SVC-PEP-APR-002", "dwp-people-server", "dwp-approval-server",
                    "HRIS_SEPARATION_TASK_WAIVER_REQUEST", "people-key", "approval-key");
            insertBinding(connection, 2L, tenantTwoGrant,
                    "SVC-PEP-HRM-010", "dwp-approval-server", "dwp-people-server",
                    "HRIS_SEPARATION_TASK_WAIVER_DECISION", "approval-key", "people-key");
            insertBinding(connection, 2L, tenantTwoGrant,
                    "SVC-AUT-HRM-013", "dwp-approval-server", "dwp-auth-server",
                    "HRIS_SEPARATION_REQUESTER_IDENTITY_RESOLVE", "approval-key", "auth-key");
            connection.commit();
            insertFence(connection, 2L, tenantTwoFence, tenantTwoGrant, 1L,
                    "QUIESCING", null, tenantTwoEffectiveAt);
            insertDrain(connection, 2L, tenantTwoFence, tenantTwoGrant, 1L,
                    tenantTwoEffectiveAt.minusSeconds(1), OffsetDateTime.now(ZoneOffset.UTC));
            connection.commit();
        }
        jdbc.execute("CREATE ROLE hris_v271_runtime NOLOGIN");
        jdbc.execute("GRANT SELECT, UPDATE ON ALL TABLES IN SCHEMA public TO hris_v271_runtime");

        try (Connection connection = source.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("SET ROLE hris_v271_runtime");
            statement.execute("SELECT set_config('dwp.auth.hrm013.tenant', '1', false)");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT tenant_id FROM auth_hris_service_authority_proof_replays")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(1L);
                assertThat(rows.next()).isFalse();
            }
            for (String table : TABLES) {
                assertThat(statement.executeUpdate(
                        "UPDATE " + table + " SET tenant_id = tenant_id WHERE tenant_id = 2"))
                        .as(table + " cross-tenant write").isZero();
            }
            statement.execute("SELECT set_config('dwp.auth.hrm013.tenant', '2', false)");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT tenant_id FROM auth_hris_service_authority_proof_replays")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(2L);
                assertThat(rows.next()).isFalse();
            }
            statement.execute("RESET ROLE");
        }
    }

    @Test
    void admitsOnlyAnExactAtomicWaiverDependencyGroupAndKeepsItImmutable() throws Exception {
        var source = migrated();
        UUID grantSetId = UUID.fromString("10000000-0000-0000-0000-000000000001");

        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            insertGrant(connection, grantSetId, 1L, 10L, 20L,
                    "ADMIT", null, null, null, DIGEST_A);
            insertBinding(connection, grantSetId,
                    "SVC-PEP-APR-002", "dwp-people-server", "dwp-approval-server",
                    "HRIS_SEPARATION_TASK_WAIVER_REQUEST", "people-key", "approval-key");
            insertBinding(connection, grantSetId,
                    "SVC-PEP-HRM-010", "dwp-approval-server", "dwp-people-server",
                    "HRIS_SEPARATION_TASK_WAIVER_DECISION", "approval-key", "people-key");
            insertBinding(connection, grantSetId,
                    "SVC-AUT-HRM-013", "dwp-approval-server", "dwp-auth-server",
                    "HRIS_SEPARATION_REQUESTER_IDENTITY_RESOLVE", "approval-key", "auth-key");
            connection.commit();
        }

        var jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auth_hris_service_grant_sets", Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auth_hris_service_grant_bindings", Integer.class))
                .isEqualTo(3);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE auth_hris_service_grant_sets SET grant_digest = ?
                 WHERE grant_set_id = ?::uuid
                """, DIGEST_B, grantSetId.toString()))
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM auth_hris_service_grant_bindings
                 WHERE grant_set_id = ?::uuid
                """, grantSetId.toString()))
                .rootCause().hasMessageContaining("append-only");

        UUID fenceOne = UUID.fromString("10000000-0000-0000-0000-000000000011");
        UUID fenceTwo = UUID.fromString("10000000-0000-0000-0000-000000000012");
        UUID fenceThree = UUID.fromString("10000000-0000-0000-0000-000000000013");
        UUID nextGrantSetId = UUID.fromString("10000000-0000-0000-0000-000000000002");
        OffsetDateTime firstEffectiveAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(2);
        try (Connection connection = source.getConnection()) {
            insertFence(connection, fenceOne, grantSetId, 1L, "QUIESCING",
                    null, firstEffectiveAt);
            UUID firstDrain = insertDrain(connection, fenceOne, grantSetId, 1L,
                    firstEffectiveAt.minusSeconds(1),
                    OffsetDateTime.parse("2000-01-01T00:00:00Z"));
            assertDatabaseRecordedNow(jdbc, firstDrain);
            insertFence(connection, fenceTwo, grantSetId, 2L, "CUTOVER",
                    fenceOne, firstEffectiveAt);
        }
        assertThatThrownBy(() -> {
            try (Connection connection = source.getConnection()) {
                insertDrain(connection, fenceOne, grantSetId, 2L,
                        firstEffectiveAt.minusSeconds(1), OffsetDateTime.now(ZoneOffset.UTC));
            }
        }).hasMessageContaining("current quiesce fence");

        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            insertGrant(connection, nextGrantSetId, 2L, 11L, 21L,
                    "ADMIT", grantSetId, DIGEST_C, null, DIGEST_B);
            insertBinding(connection, nextGrantSetId,
                    "SVC-PEP-APR-002", "dwp-people-server", "dwp-approval-server",
                    "HRIS_SEPARATION_TASK_WAIVER_REQUEST", "people-key-v2", "approval-key-v2");
            insertBinding(connection, nextGrantSetId,
                    "SVC-PEP-HRM-010", "dwp-approval-server", "dwp-people-server",
                    "HRIS_SEPARATION_TASK_WAIVER_DECISION", "approval-key-v2", "people-key-v2");
            insertBinding(connection, nextGrantSetId,
                    "SVC-AUT-HRM-013", "dwp-approval-server", "dwp-auth-server",
                    "HRIS_SEPARATION_REQUESTER_IDENTITY_RESOLVE", "approval-key-v2", "auth-key-v2");
            connection.commit();
        }

        OffsetDateTime secondEffectiveAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        assertThatThrownBy(() -> {
            try (Connection connection = source.getConnection()) {
                insertFence(connection, fenceThree, grantSetId, 3L, "QUIESCING",
                        fenceTwo, secondEffectiveAt);
            }
        }).hasMessageContaining("current ADMIT grant set");

        try (Connection connection = source.getConnection()) {
            insertFence(connection, fenceThree, nextGrantSetId, 3L, "QUIESCING",
                    fenceTwo, secondEffectiveAt);
            UUID secondDrain = insertDrain(connection, fenceThree, nextGrantSetId, 1L,
                    secondEffectiveAt.minusSeconds(1),
                    OffsetDateTime.parse("2099-01-01T00:00:00Z"));
            assertDatabaseRecordedNow(jdbc, secondDrain);
        }

        assertThatThrownBy(() -> {
            try (Connection connection = source.getConnection()) {
                connection.setAutoCommit(false);
                insertGrant(connection,
                        UUID.fromString("10000000-0000-0000-0000-000000000003"),
                        3L, 30L, 40L, "ADMIT", grantSetId,
                        DIGEST_B, null, DIGEST_C);
                connection.commit();
            }
        }).hasMessageContaining("immediate predecessor");
    }

    @Test
    void rejectsPartialWaiverGroupAndDuplicateReplayDigest() throws Exception {
        var source = migrated();
        UUID grantSetId = UUID.fromString("20000000-0000-0000-0000-000000000001");

        assertThatThrownBy(() -> {
            try (Connection connection = source.getConnection()) {
                connection.setAutoCommit(false);
                insertGrant(connection, grantSetId, 1L, 10L, 20L,
                        "ADMIT", null, null, null, DIGEST_A);
                insertBinding(connection, grantSetId,
                        "SVC-PEP-APR-002", "dwp-people-server", "dwp-approval-server",
                        "HRIS_SEPARATION_TASK_WAIVER_REQUEST", "people-key", "approval-key");
                connection.commit();
            }
        }).hasMessageContaining("waiver dependency group");

        assertThatThrownBy(() -> {
            try (Connection connection = source.getConnection()) {
                connection.setAutoCommit(false);
                UUID pairGrant = UUID.fromString("20000000-0000-0000-0000-000000000002");
                insertGrant(connection, pairGrant, 1L, 10L, 20L,
                        "ADMIT", null, null, null, DIGEST_A);
                insertBinding(connection, pairGrant,
                        "SVC-PEP-TIM-004", "dwp-people-server", "dwp-time-server",
                        "HRIS_SEPARATION_TIME_TASK_DISPATCH", "people-key", "time-key");
                connection.commit();
            }
        }).hasMessageContaining("exact pairs");

        assertInvalidCallerJwk(source,
                UUID.fromString("20000000-0000-0000-0000-000000000003"),
                jwkWithModulus("people-key", Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(new byte[256])));
        assertInvalidCallerJwk(source,
                UUID.fromString("20000000-0000-0000-0000-000000000004"),
                """
                        {"alg":null,"e":"AQAB","kid":"people-key","kty":"RSA","n":"%s","use":"sig"}
                        """.formatted(RSA_MODULUS).strip());
        assertInvalidCallerJwk(source,
                UUID.fromString("20000000-0000-0000-0000-000000000005"),
                jwkWithModulus("people-key", RSA_MODULUS + "="));

        var jdbc = new JdbcTemplate(source);
        insertReplay(jdbc, 1L, DIGEST_A);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO auth_hris_service_authority_proof_replays (
                    tenant_id, proof_scope, binding_id, requesting_workload_id,
                    token_kind, token_digest, expires_at, reserved_at)
                VALUES (1, 'AUTHORITY_RESOLVE', 'SVC-AUT-HRM-011',
                        'dwp-people-server', 'REQUEST_NONCE', ?,
                        CURRENT_TIMESTAMP + INTERVAL '30 seconds', CURRENT_TIMESTAMP)
                """, DIGEST_A)).rootCause().hasMessageContaining(
                        "uk_auth_hris_proof_digest");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE auth_hris_service_authority_proof_replays
                   SET expires_at = expires_at + INTERVAL '1 second'
                 WHERE tenant_id = 1 AND token_digest = ?
                """, DIGEST_A)).rootCause().hasMessageContaining("cannot be updated");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM auth_hris_service_authority_proof_replays
                 WHERE tenant_id = 1 AND token_digest = ?
                """, DIGEST_A)).rootCause().hasMessageContaining("retention has not elapsed");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO auth_hris_service_authority_proof_replays (
                    tenant_id, proof_scope, binding_id, requesting_workload_id,
                    token_kind, token_digest, expires_at, reserved_at)
                VALUES (1, 'AUTHORITY_RESOLVE', 'SVC-AUT-HRM-011',
                        'dwp-people-server', 'JTI', ?,
                        CURRENT_TIMESTAMP + INTERVAL '301 seconds', CURRENT_TIMESTAMP)
                """, DIGEST_C)).rootCause().hasMessageContaining("DB reservation time");

        OffsetDateTime reservationLowerBound = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        jdbc.update("""
                INSERT INTO auth_hris_service_authority_proof_replays (
                    tenant_id, proof_scope, binding_id, requesting_workload_id,
                    token_kind, token_digest, expires_at, reserved_at)
                VALUES (1, 'AUTHORITY_RESOLVE', 'SVC-AUT-HRM-011',
                        'dwp-people-server', 'JTI', ?,
                        CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                        TIMESTAMPTZ '2000-01-01T00:00:00Z')
                """, DIGEST_D);
        assertThat(jdbc.queryForObject("""
                SELECT reserved_at FROM auth_hris_service_authority_proof_replays
                 WHERE tenant_id = 1 AND token_digest = ?
                """, OffsetDateTime.class, DIGEST_D)).isBetween(
                        reservationLowerBound,
                        OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO auth_hris_service_authority_proof_replays (
                    tenant_id, proof_scope, binding_id, requesting_workload_id,
                    token_kind, token_digest, expires_at, reserved_at)
                VALUES (1, 'AUTHORITY_RESOLVE', 'SVC-AUT-HRM-011',
                        'dwp-people-server', 'JTI', ?,
                        CURRENT_TIMESTAMP - INTERVAL '10 seconds',
                        CURRENT_TIMESTAMP - INTERVAL '20 seconds')
                """, DIGEST_B)).rootCause().hasMessageContaining("DB reservation time");
        insertExpiredReplayFixture(source, DIGEST_B);
        assertThat(jdbc.update("""
                DELETE FROM auth_hris_service_authority_proof_replays
                 WHERE tenant_id = 1 AND token_digest = ?
                """, DIGEST_B)).isOne();
    }

    private PGSimpleDataSource migrated() {
        var source = source();
        Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).target("271").load().clean();
        Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .target("271").load().migrate();
        return source;
    }

    private void insertReplay(JdbcTemplate jdbc, long tenantId, String digest) {
        jdbc.update("""
                INSERT INTO auth_hris_service_authority_proof_replays (
                    tenant_id, proof_scope, binding_id, requesting_workload_id,
                    token_kind, token_digest, expires_at, reserved_at)
                VALUES (?, 'AUTHORITY_RESOLVE', 'SVC-AUT-HRM-011',
                        'dwp-people-server', 'JTI', ?,
                        CURRENT_TIMESTAMP + INTERVAL '30 seconds', CURRENT_TIMESTAMP)
                """, tenantId, digest);
    }

    private void insertExpiredReplayFixture(PGSimpleDataSource source, String digest)
            throws Exception {
        try (Connection connection = source.getConnection()) {
            try (var control = connection.createStatement()) {
                control.execute("SET session_replication_role = replica");
            }
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO auth_hris_service_authority_proof_replays (
                            tenant_id, proof_scope, binding_id, requesting_workload_id,
                            token_kind, token_digest, expires_at, reserved_at)
                        VALUES (1, 'AUTHORITY_RESOLVE', 'SVC-AUT-HRM-011',
                                'dwp-people-server', 'JTI', ?,
                                CURRENT_TIMESTAMP - INTERVAL '10 seconds',
                                CURRENT_TIMESTAMP - INTERVAL '20 seconds')
                        """)) {
                    statement.setString(1, digest);
                    statement.executeUpdate();
                }
            } finally {
                try (var control = connection.createStatement()) {
                    control.execute("SET session_replication_role = origin");
                }
            }
        }
    }

    private void insertGrant(Connection connection, UUID grantSetId,
            long grantRevision, long tenantGrantRevision, long authorizationRevision,
            String decision, UUID predecessor, String readinessDigest,
            String incidentDigest, String grantDigest) throws Exception {
        insertGrant(connection, 1L, grantSetId, grantRevision, tenantGrantRevision,
                authorizationRevision, decision, predecessor, readinessDigest,
                incidentDigest, grantDigest);
    }

    private void insertGrant(Connection connection, long tenantId, UUID grantSetId,
            long grantRevision, long tenantGrantRevision, long authorizationRevision,
            String decision, UUID predecessor, String readinessDigest,
            String incidentDigest, String grantDigest) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO auth_hris_service_grant_sets (
                    grant_set_id, tenant_id, grant_revision, tenant_grant_revision,
                    authorization_revision, decision, supersedes_grant_set_id,
                    rotation_readiness_digest, incident_evidence_digest,
                    grant_digest, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, grantSetId);
            statement.setLong(2, tenantId);
            statement.setLong(3, grantRevision);
            statement.setLong(4, tenantGrantRevision);
            statement.setLong(5, authorizationRevision);
            statement.setString(6, decision);
            statement.setObject(7, predecessor);
            statement.setString(8, readinessDigest);
            statement.setString(9, incidentDigest);
            statement.setString(10, grantDigest);
            statement.setObject(11, OffsetDateTime.now(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private void insertBinding(Connection connection, UUID grantSetId,
            String bindingId, String caller, String owner, String purpose,
            String callerKey, String ownerKey) throws Exception {
        insertBinding(connection, 1L, grantSetId, bindingId, caller, owner,
                purpose, callerKey, ownerKey);
    }

    private void insertBinding(Connection connection, long tenantId, UUID grantSetId,
            String bindingId, String caller, String owner, String purpose,
            String callerKey, String ownerKey) throws Exception {
        insertBindingWithJwks(connection, tenantId, grantSetId, bindingId, caller,
                owner, purpose, callerKey, jwk(callerKey), ownerKey, jwk(ownerKey));
    }

    private void insertBindingWithJwks(Connection connection, long tenantId,
            UUID grantSetId, String bindingId, String caller, String owner,
            String purpose, String callerKey, String callerJwk, String ownerKey,
            String ownerJwk) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO auth_hris_service_grant_bindings (
                    grant_binding_id, tenant_id, grant_set_id, binding_id,
                    caller_workload_id, caller_spiffe_id, caller_signing_key_id,
                    caller_signing_public_jwk, owner_workload_id, owner_spiffe_id,
                    owner_signing_key_id, owner_signing_public_jwk, purpose,
                    binding_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setLong(2, tenantId);
            statement.setObject(3, grantSetId);
            statement.setString(4, bindingId);
            statement.setString(5, caller);
            statement.setString(6, "spiffe://dwp/service/" + caller);
            statement.setString(7, callerKey);
            statement.setString(8, callerJwk);
            statement.setString(9, owner);
            statement.setString(10, "spiffe://dwp/service/" + owner);
            statement.setString(11, ownerKey);
            statement.setString(12, ownerJwk);
            statement.setString(13, purpose);
            statement.setString(14, DIGEST_B);
            statement.executeUpdate();
        }
    }

    private void insertFence(Connection connection, UUID fenceId, UUID oldGrantSetId,
            long revision, String state, UUID predecessor, OffsetDateTime effectiveAt)
            throws Exception {
        insertFence(connection, 1L, fenceId, oldGrantSetId, revision, state,
                predecessor, effectiveAt);
    }

    private void insertFence(Connection connection, long tenantId, UUID fenceId,
            UUID oldGrantSetId, long revision, String state, UUID predecessor,
            OffsetDateTime effectiveAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO auth_hris_service_grant_quiesce_fences (
                    quiesce_fence_id, tenant_id, old_grant_set_id, binding_id,
                    caller_workload_id, owner_workload_id, purpose, quiesce_revision,
                    lifecycle_state, supersedes_quiesce_fence_id, effective_at,
                    successor_command_correlation_id, fence_digest, recorded_at)
                VALUES (?, ?, ?, 'SVC-PEP-APR-002', 'dwp-people-server',
                        'dwp-approval-server', 'HRIS_SEPARATION_TASK_WAIVER_REQUEST',
                        ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, fenceId);
            statement.setLong(2, tenantId);
            statement.setObject(3, oldGrantSetId);
            statement.setLong(4, revision);
            statement.setString(5, state);
            statement.setObject(6, predecessor);
            statement.setObject(7, effectiveAt);
            statement.setObject(8, UUID.randomUUID());
            statement.setString(9, DIGEST_C);
            statement.setObject(10, OffsetDateTime.now(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private UUID insertDrain(Connection connection, UUID fenceId, UUID oldGrantSetId,
            long activityRevision, OffsetDateTime rootCreatedAt,
            OffsetDateTime suppliedRecordedAt) throws Exception {
        return insertDrain(connection, 1L, fenceId, oldGrantSetId, activityRevision,
                rootCreatedAt, suppliedRecordedAt);
    }

    private UUID insertDrain(Connection connection, long tenantId, UUID fenceId,
            UUID oldGrantSetId, long activityRevision, OffsetDateTime rootCreatedAt,
            OffsetDateTime suppliedRecordedAt) throws Exception {
        long tenantGrantRevision;
        long authorizationRevision;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tenant_grant_revision, authorization_revision
                  FROM auth_hris_service_grant_sets
                 WHERE tenant_id = ? AND grant_set_id = ?
                """)) {
            statement.setLong(1, tenantId);
            statement.setObject(2, oldGrantSetId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                tenantGrantRevision = row.getLong(1);
                authorizationRevision = row.getLong(2);
            }
        }

        UUID drainId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO auth_hris_service_grant_drain_activities (
                    drain_activity_id, tenant_id, quiesce_fence_id, old_grant_set_id,
                    tenant_grant_revision, authorization_revision, binding_id,
                    caller_workload_id, owner_workload_id, purpose,
                    attesting_service_role, service_workload_id, activity_revision,
                    continuation_kind, root_work_item_id, root_created_at,
                    root_payload_sha256, continuation_event_id, continuation_revision,
                    continuation_payload_sha256, resolver_proof_sha256,
                    correlation_id, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, 'SVC-PEP-APR-002', 'dwp-people-server',
                        'dwp-approval-server', 'HRIS_SEPARATION_TASK_WAIVER_REQUEST',
                        'CALLER', 'dwp-people-server', ?, 'ROOT_DELIVERY', ?, ?, ?, ?,
                        0, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, drainId);
            statement.setLong(2, tenantId);
            statement.setObject(3, fenceId);
            statement.setObject(4, oldGrantSetId);
            statement.setLong(5, tenantGrantRevision);
            statement.setLong(6, authorizationRevision);
            statement.setLong(7, activityRevision);
            statement.setObject(8, UUID.randomUUID());
            statement.setObject(9, rootCreatedAt);
            statement.setString(10, DIGEST_A);
            statement.setObject(11, UUID.randomUUID());
            statement.setString(12, DIGEST_B);
            statement.setString(13, DIGEST_C);
            statement.setObject(14, UUID.randomUUID());
            statement.setObject(15, suppliedRecordedAt);
            statement.executeUpdate();
        }
        return drainId;
    }

    private void assertDatabaseRecordedNow(JdbcTemplate jdbc, UUID drainId) {
        OffsetDateTime recordedAt = jdbc.queryForObject("""
                SELECT recorded_at FROM auth_hris_service_grant_drain_activities
                 WHERE drain_activity_id = ?
                """, OffsetDateTime.class, drainId);
        assertThat(recordedAt).isBetween(
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(10),
                OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1));
    }

    private void assertInvalidCallerJwk(PGSimpleDataSource source, UUID grantSetId,
            String callerJwk) {
        assertThatThrownBy(() -> {
            try (Connection connection = source.getConnection()) {
                connection.setAutoCommit(false);
                insertGrant(connection, grantSetId, 1L, 10L, 20L,
                        "ADMIT", null, null, null, DIGEST_A);
                insertBindingWithJwks(connection, 1L, grantSetId,
                        "SVC-TST-JWK-001", "dwp-people-server", "dwp-auth-server",
                        "HRIS_JWK_CONSTRAINT_TEST", "people-key", callerJwk,
                        "auth-key", jwk("auth-key"));
            }
        }).hasMessageContaining("ck_auth_hris_binding_caller_jwk");
    }

    private String jwk(String keyId) {
        return jwkWithModulus(keyId, RSA_MODULUS);
    }

    private String jwkWithModulus(String keyId, String modulus) {
        return """
                {"alg":"RS256","e":"AQAB","kid":"%s","kty":"RSA","n":"%s","use":"sig"}
                """.formatted(keyId, modulus).strip();
    }

    private PGSimpleDataSource source() {
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}

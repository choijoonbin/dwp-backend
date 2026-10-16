package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventOutboxRepository;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySeals;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferDtos.*;
import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferRepository.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ControlledTransferMigrationPostgresTest {

    private static final String SCOPE =
            "hcm-scope-3b21f50dc0bc6974f09f6bcb2b987f677ce5b7a5";
    private static final String OTHER_SCOPE = "hcm-scope-" + "1".repeat(40);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void v327ToV328UpgradeCreatesTheAggregateWithForcedRlsAndGuards() {
        String schema = "sys009_upgrade";
        PGSimpleDataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public");

        Flyway through327 = flyway(source, schema, "327");
        through327.clean();
        through327.migrate();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = ? AND table_name = 'sys_controlled_file_transfers'
                """, Integer.class, schema)).isZero();

        flyway(source, schema, "328").migrate();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                 WHERE table_schema = ?
                   AND table_name = 'sys_controlled_file_transfers'
                   AND constraint_name IN (
                     'ck_sys_controlled_file_transfer_direction_policy',
                     'ck_sys_controlled_file_transfer_snapshot_binding',
                     'ck_sys_controlled_file_transfer_purpose',
                     'uq_sys_controlled_file_transfer_idempotency')
                """, Integer.class, schema)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT relrowsecurity AND relforcerowsecurity
                  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = ? AND c.relname = 'sys_controlled_file_transfers'
                """, Boolean.class, schema)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.triggers
                 WHERE event_object_schema = ?
                   AND event_object_table = 'sys_controlled_file_transfers'
                   AND event_manipulation IN ('INSERT', 'UPDATE', 'DELETE')
                """, Integer.class, schema)).isEqualTo(3);
    }

    @Test
    void legalStateGraphRequiresCasAndMakesIdentityAndTerminalEvidenceImmutable() {
        Fixture fixture = fixture("sys009_state_graph");
        PreparedTransfer legal = transfer(access(SUBJECT_ONE, "state-graph-legal-0001"),
                UUID.randomUUID(), "a".repeat(64));
        fixture.transactions.execute(ignored -> fixture.repository.create(legal));

        fixture.jdbc.update("""
                UPDATE sys_controlled_file_transfers
                   SET state = 'SCANNING', lease_owner = 'scanner-worker',
                       lease_token_digest = repeat('1', 64),
                       lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                       version = version + 1
                 WHERE public_id = ?
                """, legal.transferPublicId());
        fixture.jdbc.update("""
                UPDATE sys_controlled_file_transfers
                   SET state = 'READY', lease_owner = NULL,
                       lease_token_digest = NULL, lease_expires_at = NULL,
                       version = version + 1
                 WHERE public_id = ?
                """, legal.transferPublicId());
        fixture.jdbc.update("""
                UPDATE sys_controlled_file_transfers
                   SET state = 'IN_PROGRESS', lease_owner = 'delivery-worker',
                       lease_token_digest = repeat('2', 64),
                       lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                       version = version + 1
                 WHERE public_id = ?
                """, legal.transferPublicId());
        fixture.jdbc.update("""
                UPDATE sys_controlled_file_transfers
                   SET state = 'COMPLETED', completed_at = CURRENT_TIMESTAMP,
                       lease_owner = NULL, lease_token_digest = NULL,
                       lease_expires_at = NULL,
                       version = version + 1
                 WHERE public_id = ?
                """, legal.transferPublicId());
        assertThat(fixture.jdbc.queryForObject("""
                SELECT state || ':' || version FROM sys_controlled_file_transfers
                 WHERE public_id = ?
                """, String.class, legal.transferPublicId())).isEqualTo("COMPLETED:5");
        assertThat(fixture.jdbc.queryForObject("""
                SELECT updated_at > requested_at FROM sys_controlled_file_transfers
                 WHERE public_id = ?
                """, Boolean.class, legal.transferPublicId())).isTrue();
        assertThatThrownBy(() -> fixture.jdbc.update("""
                UPDATE sys_controlled_file_transfers
                   SET state = 'FAILED', failure_code = 'LATE',
                       completed_at = NULL, version = version + 1
                 WHERE public_id = ?
                """, legal.transferPublicId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fixture.jdbc.update(
                "DELETE FROM sys_controlled_file_transfers WHERE public_id = ?",
                legal.transferPublicId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        PreparedTransfer illegal = transfer(
                access(SUBJECT_ONE, "state-graph-illegal-01"),
                UUID.randomUUID(), "b".repeat(64));
        fixture.transactions.execute(ignored -> fixture.repository.create(illegal));
        assertThatThrownBy(() -> fixture.jdbc.update("""
                UPDATE sys_controlled_file_transfers
                   SET state = 'READY', version = version + 1
                 WHERE public_id = ?
                """, illegal.transferPublicId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fixture.jdbc.update("""
                UPDATE sys_controlled_file_transfers
                   SET state = 'SCANNING', lease_owner = 'scanner-worker',
                       lease_token_digest = repeat('3', 64),
                       lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                       version = version + 2
                 WHERE public_id = ?
                """, illegal.transferPublicId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fixture.jdbc.update("""
                UPDATE sys_controlled_file_transfers
                   SET public_id = ?, state = 'SCANNING',
                       lease_owner = 'scanner-worker',
                       lease_token_digest = repeat('4', 64),
                       lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                       version = version + 1
                 WHERE public_id = ?
                """, UUID.randomUUID(), illegal.transferPublicId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void repositoryReplayEventAndRollbackAreAtomicAndExact() {
        Fixture fixture = fixture("sys009_repository");
        PreparedTransfer first = transfer(
                access(SUBJECT_ONE, "controlled-transfer-0001"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                "f".repeat(64));
        Receipt receipt = fixture.transactions.execute(
                ignored -> fixture.repository.create(first));
        Receipt replayed = fixture.transactions.execute(ignored ->
                fixture.repository.create(copy(first, first.access(),
                        UUID.randomUUID(), "f".repeat(64))));

        assertThat(replayed).isEqualTo(receipt);
        assertThat(fixture.jdbc.queryForObject(
                "SELECT count(*) FROM sys_controlled_file_transfers", Integer.class))
                .isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject(
                "SELECT count(*) FROM sys_hris_command_receipts", Integer.class))
                .isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject(
                "SELECT idempotency_key FROM sys_hris_command_receipts", String.class))
                .isEqualTo("ct1:SYS:b0025418248e4bf6fdddafcdf1302cd3b2503c7e494fd0ef39049f0a3a7467dc")
                .hasSize(72)
                .isNotEqualTo(first.access().idempotencyKey());
        assertThat(fixture.jdbc.queryForObject(
                "SELECT authority_population_scope_digest FROM sys_hris_command_receipts",
                String.class)).isEqualTo(
                HrisExplorerAuthoritySeals.populationScopeDigest(SCOPE));
        assertThat(fixture.jdbc.queryForObject(
                "SELECT access_authority_digest FROM sys_controlled_file_transfers",
                String.class)).isEqualTo(first.access().authorityDigest());
        TransferPage currentScope = fixture.transactions.execute(ignored ->
                fixture.repository.catalog(
                        first.access(), OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1),
                        null, null, 10));
        assertThat(currentScope.items())
                .extracting(CatalogItem::publicId)
                .containsExactly(first.transferPublicId());
        TransferPage wrongScope = fixture.transactions.execute(ignored ->
                fixture.repository.catalog(
                        access(SUBJECT_ONE, "unused-catalog-key", OTHER_SCOPE),
                        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1),
                        null, null, 10));
        assertThat(wrongScope.items()).isEmpty();
        assertThat(fixture.jdbc.queryForObject(
                "SELECT count(*) FROM sys_domain_event_outbox", Integer.class))
                .isEqualTo(1);
        assertThat(fixture.jdbc.queryForList("""
                SELECT jsonb_object_keys(payload -> 'data') AS key
                  FROM sys_domain_event_outbox ORDER BY key
                """, String.class)).containsExactly(
                "byteSize", "egressPolicyRevision", "mediaType",
                "metadataSnapshotDigest", "objectDigest", "objectVersion",
                "occurredAt", "ownerModule", "purposeCode", "receiptDigest",
                "state", "transferKind", "transferPublicId");
        assertThat(fixture.jdbc.queryForObject("""
                SELECT payload ->> 'aggregateType' FROM sys_domain_event_outbox
                """, String.class)).isEqualTo("CONTROLLED_TRANSFER");
        assertThat(fixture.jdbc.queryForObject(
                "SELECT payload::text FROM sys_domain_event_outbox", String.class))
                .doesNotContain(first.objectRef(), first.storageETag(), first.scannerKey());

        AccessSeal anotherSubject = access(
                SUBJECT_TWO, first.access().idempotencyKey());
        assertThatThrownBy(() -> fixture.transactions.execute(ignored ->
                fixture.repository.create(copy(first, anotherSubject,
                        UUID.randomUUID(), first.requestDigest()))))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("IDEMPOTENCY_CONFLICT");

        fixture.jdbc.execute("""
                CREATE OR REPLACE FUNCTION reject_sys009_event()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'event rejected' USING ERRCODE = '23514'; END;
                $$
                """);
        fixture.jdbc.execute("""
                CREATE TRIGGER reject_sys009_event
                BEFORE INSERT ON sys_domain_event_outbox
                FOR EACH ROW EXECUTE FUNCTION reject_sys009_event()
                """);
        PreparedTransfer rejected = transfer(
                access(SUBJECT_ONE, "controlled-transfer-0002"),
                UUID.randomUUID(), "e".repeat(64));
        assertThatThrownBy(() -> fixture.transactions.execute(
                ignored -> fixture.repository.create(rejected)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.jdbc.queryForObject(
                "SELECT count(*) FROM sys_controlled_file_transfers", Integer.class))
                .isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject(
                "SELECT count(*) FROM sys_hris_command_receipts", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void nonOwnerRoleCannotBypassTenantRlsAndTransactionRechecksFreshness() {
        Fixture fixture = fixture("sys009_rls");
        PreparedTransfer first = transfer(
                access(SUBJECT_ONE, "controlled-transfer-rls1"),
                UUID.randomUUID(), "f".repeat(64));
        fixture.transactions.execute(ignored -> fixture.repository.create(first));

        String role = "sys009_reader_" + UUID.randomUUID().toString().replace("-", "");
        fixture.jdbc.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
        fixture.jdbc.execute("GRANT USAGE ON SCHEMA " + fixture.schema + " TO " + role);
        fixture.jdbc.execute("GRANT SELECT ON sys_controlled_file_transfers TO " + role);
        Integer hidden = fixture.transactions.execute(ignored -> {
            fixture.jdbc.execute("SET LOCAL ROLE " + role);
            fixture.jdbc.queryForObject(
                    "SELECT set_config('dwp.tenant_id', '7', true)", String.class);
            return fixture.jdbc.queryForObject(
                    "SELECT count(*) FROM sys_controlled_file_transfers", Integer.class);
        });
        Integer visible = fixture.transactions.execute(ignored -> {
            fixture.jdbc.execute("SET LOCAL ROLE " + role);
            fixture.jdbc.queryForObject(
                    "SELECT set_config('dwp.tenant_id', '42', true)", String.class);
            return fixture.jdbc.queryForObject(
                    "SELECT count(*) FROM sys_controlled_file_transfers", Integer.class);
        });
        assertThat(hidden).isZero();
        assertThat(visible).isEqualTo(1);

        PreparedTransfer stale = copy(first,
                access(SUBJECT_ONE, "controlled-transfer-stale"),
                UUID.randomUUID(), "d".repeat(64));
        stale = copyWithFreshUntil(stale,
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        PreparedTransfer expired = stale;
        assertThatThrownBy(() -> fixture.transactions.execute(
                ignored -> fixture.repository.create(expired)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("EXPIRED_IN_TRANSACTION");
        assertThat(fixture.jdbc.queryForObject(
                "SELECT count(*) FROM sys_controlled_file_transfers", Integer.class))
                .isEqualTo(1);
    }

    private Fixture fixture(String schema) {
        PGSimpleDataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public");
        Flyway flyway = flyway(source, schema, "328");
        flyway.clean();
        flyway.migrate();
        source.setCurrentSchema(schema);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        DomainEventOutboxRepository outbox = new DomainEventOutboxRepository(
                new NamedParameterJdbcTemplate(source), json);
        ControlledTransferDomainEvents events = new ControlledTransferDomainEvents(
                new DomainEventRecorder(outbox, contracts, json), contracts, json);
        JdbcControlledTransferRepository repository =
                new JdbcControlledTransferRepository(jdbc, events, json);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        return new Fixture(schema, jdbc, repository, transactions);
    }

    private AccessSeal access(UUID subject, String idempotencyKey) {
        return access(subject, idempotencyKey, SCOPE);
    }

    private AccessSeal access(
            UUID subject, String idempotencyKey, String contextScopeKey) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String population = ControlledTransferService.populationScopeDigest(
                42, subject, "CONNECTOR_SET", contextScopeKey, "decision-1");
        String authority = ControlledTransferService.authorityDigest(
                42, subject, ControlledTransferService.CREATE_ROUTE, "TENANT",
                contextScopeKey, "CONNECTOR_SET", population,
                ControlledTransferService.CREATE_FIELD_GROUPS, 7, 9,
                "decision-1", ControlledTransferService.PURPOSE,
                now.plusHours(1));
        return new AccessSeal(
                42, subject, population, "CONNECTOR_SET",
                ControlledTransferService.CREATE_FIELD_GROUPS, 7, 9,
                ControlledTransferService.CREATE_ROUTE, "TENANT", contextScopeKey,
                "decision-1", ControlledTransferService.PURPOSE, authority,
                now.plusHours(1), UUID.randomUUID(), idempotencyKey);
    }

    private PreparedTransfer transfer(
            AccessSeal access, UUID publicId, String requestDigest) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new PreparedTransfer(
                publicId, access, OwnerModule.SYS, TransferKind.INGEST, null, null,
                "obj_1234567890abcdef", "v1", "b".repeat(64), 10,
                "application/json", "OBJECT_STORE", "c".repeat(64),
                "1".repeat(64), now.plusMinutes(30), "etag-1",
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "scanner", "1", now.minusMinutes(1), "d".repeat(64),
                now.plusMinutes(30), null, null, Classification.CONFIDENTIAL,
                now.plusHours(1), requestDigest, now);
    }

    private PreparedTransfer copy(
            PreparedTransfer source, AccessSeal access, UUID publicId,
            String requestDigest) {
        return new PreparedTransfer(
                publicId, access, source.ownerModule(), source.transferKind(),
                source.recipientPolicyRef(), source.egressPolicyRevision(),
                source.objectRef(), source.objectVersion(), source.objectDigest(),
                source.byteSize(), source.mediaType(), source.metadataAuthority(),
                source.metadataSnapshotDigest(), source.metadataAuthorityDigest(),
                source.metadataAuthorityFreshUntil(), source.storageETag(),
                source.uploadSessionId(), source.scannerKey(), source.scannerVersion(),
                source.scannedAt(), source.scanAuthorityDigest(),
                source.scanAuthorityFreshUntil(), source.egressAuthorityDigest(),
                source.egressAuthorityFreshUntil(), source.classification(),
                source.expiresAt(), requestDigest, source.requestedAt());
    }

    private PreparedTransfer copyWithFreshUntil(
            PreparedTransfer source, OffsetDateTime freshUntil) {
        return new PreparedTransfer(
                source.transferPublicId(), source.access(), source.ownerModule(),
                source.transferKind(), source.recipientPolicyRef(),
                source.egressPolicyRevision(), source.objectRef(), source.objectVersion(),
                source.objectDigest(), source.byteSize(), source.mediaType(),
                source.metadataAuthority(), source.metadataSnapshotDigest(),
                source.metadataAuthorityDigest(), freshUntil, source.storageETag(),
                source.uploadSessionId(), source.scannerKey(), source.scannerVersion(),
                source.scannedAt(), source.scanAuthorityDigest(),
                source.scanAuthorityFreshUntil(), source.egressAuthorityDigest(),
                source.egressAuthorityFreshUntil(), source.classification(),
                source.expiresAt(), source.requestDigest(), source.requestedAt());
    }

    private Flyway flyway(PGSimpleDataSource source, String schema, String target) {
        return Flyway.configure()
                .dataSource(source)
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private static final UUID SUBJECT_ONE =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SUBJECT_TWO =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    private record Fixture(
            String schema,
            JdbcTemplate jdbc,
            JdbcControlledTransferRepository repository,
            TransactionTemplate transactions) {
    }
}

package com.dwp.services.platform.savedview;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class SavedViewRepositoryPostgresIntegrationTest {

    private static final long TENANT_ID = 1L;
    private static final long SOURCE_USER_ID = 900002L;
    private static final long TARGET_USER_ID = 4L;
    private static final long ACTOR_USER_ID = 900018L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static SavedViewRepository repository;
    private static SavedViewLifecycleHistoryRepository lifecycleHistory;
    private static SavedViewOwnershipConflictRepository ownershipConflicts;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrateSchema() {
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
        repository = new SavedViewRepository(
                new NamedParameterJdbcTemplate(dataSource),
                new ObjectMapper().findAndRegisterModules());
        lifecycleHistory = new SavedViewLifecycleHistoryRepository(
                new NamedParameterJdbcTemplate(dataSource));
        ownershipConflicts = new SavedViewOwnershipConflictRepository(
                new NamedParameterJdbcTemplate(dataSource));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void loadFixture() {
        jdbc.execute("""
                TRUNCATE TABLE usr_saved_view_lifecycle_commands,
                    usr_saved_view_ownership_transfers,
                    usr_saved_view_transfer_batches,
                    usr_saved_view_preferences,
                    usr_saved_views CASCADE
                """);
        new ResourceDatabasePopulator(
                new ClassPathResource("savedview/custody-fixture.sql"))
                .execute(dataSource);
    }

    @Test
    void transfersAllViewsAtomicallyAndPreservesImmutableEvidence() {
        SavedViewDtos.OwnershipTransfer result = transactions.execute(status -> {
            List<SavedViewRepository.Row> views =
                    repository.ownedActiveForUpdate(TENANT_ID, SOURCE_USER_ID);
            return repository.transfer(
                    TENANT_ID,
                    ACTOR_USER_ID,
                    UUID.fromString("30000000-0000-4000-8000-000000000001"),
                    "퇴직 사용자",
                    "새 담당자",
                    transferRequest("postgres-transfer", "TRANSFER", TARGET_USER_ID,
                            null, views.size()),
                    "b".repeat(64),
                    views);
        });

        assertThat(result).isNotNull();
        assertThat(result.transferredCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM usr_saved_views
                 WHERE tenant_id = 1 AND owner_user_id = 4
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM usr_saved_view_preferences
                 WHERE tenant_id = 1 AND user_id = 900002
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM usr_saved_view_ownership_transfers
                 WHERE tenant_id = 1
                """, Integer.class)).isEqualTo(3);
        assertThat(result.sourceOwnerDisplayName()).isEqualTo("퇴직 사용자");
        assertThat(result.targetOwnerDisplayName()).isEqualTo("새 담당자");
        assertThat(result.reason()).isEqualTo("PostgreSQL integration custody verification");

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE usr_saved_view_transfer_batches
                   SET source_reference = 'MUTATION-NOT-ALLOWED'
                 WHERE transfer_batch_id = ?
                """, result.transferBatchId()))
                .rootCause()
                .hasMessageContaining("append-only");
    }

    @Test
    void rollsBackTheEntireBatchWhenOneVersionChanges() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            List<SavedViewRepository.Row> current =
                    repository.ownedActiveForUpdate(TENANT_ID, SOURCE_USER_ID);
            List<SavedViewRepository.Row> stale = new ArrayList<>(current);
            SavedViewRepository.Row second = stale.get(1);
            stale.set(1, copyWithVersion(second, second.version() + 1));
            repository.transfer(
                    TENANT_ID,
                    ACTOR_USER_ID,
                    UUID.fromString("30000000-0000-4000-8000-000000000002"),
                    "퇴직 사용자",
                    "새 담당자",
                    transferRequest("postgres-rollback", "TRANSFER", TARGET_USER_ID,
                            null, stale.size()),
                    "c".repeat(64),
                    stale);
        })).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM usr_saved_views
                 WHERE tenant_id = 1 AND owner_user_id = 900002
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM usr_saved_view_transfer_batches",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM usr_saved_view_ownership_transfers",
                Integer.class)).isZero();
    }

    @Test
    void retainsThenRecoversExtendsAndArchivesOrphanedViews() {
        OffsetDateTime initialRetention = OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS)
                .plusDays(7);
        transactions.executeWithoutResult(status -> {
            List<SavedViewRepository.Row> views =
                    repository.ownedActiveForUpdate(TENANT_ID, SOURCE_USER_ID);
            repository.transfer(
                    TENANT_ID,
                    ACTOR_USER_ID,
                    UUID.fromString("30000000-0000-4000-8000-000000000003"),
                    "퇴직 사용자",
                    null,
                    transferRequest("postgres-retain", "RETAIN_ORPHANED", null,
                            initialRetention, views.size()),
                    "d".repeat(64),
                    views);
        });

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM usr_saved_views
                 WHERE tenant_id = 1 AND lifecycle_state = 'ORPHANED'
                   AND owner_user_id IS NULL
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM usr_saved_view_preferences",
                Integer.class)).isZero();

        List<SavedViewDtos.OrphanedView> orphans = repository.orphaned(TENANT_ID);
        SavedViewDtos.OrphanedView reassign = orphan(orphans,
                "10000000-0000-4000-8000-000000000001");
        SavedViewDtos.OrphanedView extend = orphan(orphans,
                "10000000-0000-4000-8000-000000000002");
        SavedViewDtos.OrphanedView archive = orphan(orphans,
                "10000000-0000-4000-8000-000000000003");

        SavedViewDtos.OrphanLifecycleResult reassigned = lifecycle(
                reassign, "postgres-reassign", "REASSIGN", TARGET_USER_ID,
                "새 담당자", null);
        OffsetDateTime extendedUntil = OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS)
                .plusDays(21);
        SavedViewDtos.OrphanLifecycleResult extended = lifecycle(
                extend, "postgres-extend", "EXTEND_RETENTION", null,
                null, extendedUntil);
        SavedViewDtos.OrphanLifecycleResult archived = lifecycle(
                archive, "postgres-archive", "ARCHIVE_NOW", null,
                null, null);

        assertThat(reassigned.newLifecycleState()).isEqualTo("ACTIVE");
        assertThat(reassigned.targetOwnerUserId()).isEqualTo(TARGET_USER_ID);
        assertThat(reassigned.savedViewName()).isEqualTo("퇴직자 개인 업무함");
        assertThat(reassigned.surfaceKey()).isEqualTo("workspace.work");
        assertThat(reassigned.scope()).isEqualTo("PERSONAL");
        assertThat(extended.newLifecycleState()).isEqualTo("ORPHANED");
        assertThat(extended.nextRetentionUntil()).isEqualTo(extendedUntil);
        assertThat(archived.newLifecycleState()).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM usr_saved_view_lifecycle_commands",
                Integer.class)).isEqualTo(3);
        List<SavedViewDtos.OrphanLifecycleResult> recent =
                lifecycleHistory.latest(TENANT_ID, 2);
        assertThat(recent).hasSize(2).isSortedAccordingTo(
                (left, right) -> right.createdAt().compareTo(left.createdAt()));
        assertThat(lifecycleHistory.latest(999L, 100)).isEmpty();
        jdbc.update("""
                UPDATE usr_saved_views
                   SET name = 'Reassigned view renamed later',
                       surface_key = 'workspace.apps',
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND saved_view_id = ?
                """, TENANT_ID, reassigned.savedViewId());
        SavedViewDtos.OrphanLifecycleResult immutableSnapshot = lifecycleHistory
                .latest(TENANT_ID, 100).stream()
                .filter(action -> action.commandId().equals(reassigned.commandId()))
                .findFirst()
                .orElseThrow();
        assertThat(immutableSnapshot.savedViewName()).isEqualTo("퇴직자 개인 업무함");
        assertThat(immutableSnapshot.surfaceKey()).isEqualTo("workspace.work");
        assertThat(immutableSnapshot.scope()).isEqualTo("PERSONAL");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM usr_saved_view_lifecycle_commands
                 WHERE command_id = ?
                """, reassigned.commandId()))
                .rootCause()
                .hasMessageContaining("append-only");
    }

    @Test
    void boundsEachAutomaticRetentionArchiveBatch() {
        jdbc.update("""
                INSERT INTO usr_saved_views (
                    saved_view_id, tenant_id, surface_key, owner_user_id,
                    name, scope, configuration, lifecycle_state, retention_until,
                    version, created_by, updated_by)
                SELECT gen_random_uuid(), 1, 'workspace.work', NULL,
                       'Expired retained view ' || series, 'PERSONAL', '{}'::jsonb,
                       'ORPHANED', CURRENT_TIMESTAMP - INTERVAL '1 day',
                       0, 900002, 0
                  FROM generate_series(1, 300) AS series
                """);

        List<SavedViewRepository.RetentionRow> batch = transactions.execute(status ->
                repository.expiredOrphansForUpdate(OffsetDateTime.now()));

        assertThat(batch).hasSize(250);
    }

    @Test
    void detectsOnlyTenantScopedPersonalNameAndSurfaceConflicts() {
        insertPersonalView(2L, TARGET_USER_ID, "workspace.work", "퇴직자 개인 업무함");
        insertPersonalView(TENANT_ID, TARGET_USER_ID, "workspace.apps", "퇴직자 개인 업무함");
        insertPersonalView(TENANT_ID, TARGET_USER_ID, "workspace.work", "다른 이름");

        assertThat(ownershipConflicts.transferConflicts(
                TENANT_ID, SOURCE_USER_ID, TARGET_USER_ID)).isEmpty();

        UUID existingId = insertPersonalView(
                TENANT_ID, TARGET_USER_ID, "workspace.work", "퇴직자 개인 업무함".toUpperCase());
        List<SavedViewDtos.OwnershipNameConflict> conflicts =
                ownershipConflicts.transferConflicts(
                        TENANT_ID, SOURCE_USER_ID, TARGET_USER_ID);

        assertThat(conflicts).singleElement().satisfies(conflict -> {
            assertThat(conflict.incomingSavedViewId()).isEqualTo(
                    UUID.fromString("10000000-0000-4000-8000-000000000001"));
            assertThat(conflict.surfaceKey()).isEqualTo("workspace.work");
            assertThat(conflict.existingTargetSavedViewId()).isEqualTo(existingId);
        });

        UUID orphanConflict = insertPersonalView(
                TENANT_ID, TARGET_USER_ID, "workspace.apps", "임시 보존 앱 보기");
        assertThat(ownershipConflicts.orphanReassignConflicts(
                TENANT_ID,
                UUID.fromString("10000000-0000-4000-8000-000000000004"),
                List.of(TARGET_USER_ID))).singleElement().satisfies(conflict -> {
                assertThat(conflict.scope()).isEqualTo("PERSONAL");
                assertThat(conflict.existingOwnerUserId()).isEqualTo(TARGET_USER_ID);
                assertThat(conflict.evidence().existingTargetSavedViewId())
                        .isEqualTo(orphanConflict);
            });
    }

    @Test
    void mirrorsTeamAndTenantPartialUniqueIndexesForOrphanRecovery() {
        UUID teamRef = UUID.randomUUID();
        UUID teamOrphan = insertSharedView(
                TENANT_ID, "workspace.activity", "운영팀 공유 현황", "TEAM",
                teamRef, "ORPHANED");
        UUID activeTeam = insertSharedView(
                TENANT_ID, "workspace.activity", "운영팀 공유 현황", "TEAM",
                teamRef, "ACTIVE");
        UUID tenantOrphan = insertSharedView(
                TENANT_ID, "people.workforce-directory", "충돌 조직 공용 보기", "TENANT",
                null, "ORPHANED");
        UUID activeTenant = insertSharedView(
                TENANT_ID, "people.workforce-directory", "충돌 조직 공용 보기", "TENANT",
                null, "ACTIVE");

        assertThat(ownershipConflicts.orphanReassignConflicts(
                TENANT_ID, teamOrphan, List.of(TARGET_USER_ID)))
                .singleElement()
                .satisfies(conflict -> {
                    assertThat(conflict.scope()).isEqualTo("TEAM");
                    assertThat(conflict.evidence().existingTargetSavedViewId())
                            .isEqualTo(activeTeam);
                });
        assertThat(ownershipConflicts.orphanReassignConflicts(
                TENANT_ID, tenantOrphan, List.of(TARGET_USER_ID)))
                .singleElement()
                .satisfies(conflict -> {
                    assertThat(conflict.scope()).isEqualTo("TENANT");
                    assertThat(conflict.evidence().existingTargetSavedViewId())
                            .isEqualTo(activeTenant);
                });
        assertThat(repository.orphaned(TENANT_ID).stream()
                .filter(view -> view.savedViewId().equals(teamOrphan)
                        || view.savedViewId().equals(tenantOrphan))
                .allMatch(view -> SavedViewOwnershipConflictPolicy
                        .SHARED_REASSIGNMENT_BLOCK_REASON
                        .equals(view.reassignmentBlockReason())))
                .isTrue();
    }

    @Test
    void databaseRaceStillRejectsActivatingAConflictingSharedOrphan() {
        UUID teamRef = UUID.randomUUID();
        UUID teamOrphan = insertSharedView(
                TENANT_ID, "workspace.activity", "Race conflict", "TEAM",
                teamRef, "ORPHANED");
        insertSharedView(
                TENANT_ID, "workspace.activity", "RACE CONFLICT", "TEAM",
                teamRef, "ACTIVE");
        SavedViewRepository.Row before = repository.orphan(
                TENANT_ID, teamOrphan).orElseThrow();

        assertThatThrownBy(() -> transactions.execute(status ->
                repository.applyOrphanLifecycle(
                        TENANT_ID,
                        ACTOR_USER_ID,
                        UUID.randomUUID(),
                        before,
                        new SavedViewRepository.LifecycleCommand(
                                "postgres-shared-race", "REASSIGN", TARGET_USER_ID,
                                "새 담당자", null, "OWNER_CORRECTION",
                                "Verifying the database race fallback",
                                "CASE-SHARED-RACE", before.version(), "f".repeat(64)))))
                .rootCause()
                .hasMessageContaining("uk_usr_saved_views_team_name");
    }

    @Test
    void readsPlanEligibilitySnapshotsWithoutCrossTenantOrLifecycleLeakage() {
        assertThat(repository.ownedActive(TENANT_ID, SOURCE_USER_ID))
                .hasSize(3)
                .allMatch(view -> "ACTIVE".equals(view.lifecycleState()));

        UUID orphanId = UUID.fromString("10000000-0000-4000-8000-000000000004");
        assertThat(repository.orphan(TENANT_ID, orphanId))
                .get()
                .extracting(SavedViewRepository.Row::lifecycleState)
                .isEqualTo("ORPHANED");
        assertThat(repository.orphan(2L, orphanId)).isEmpty();
    }

    private SavedViewDtos.OrphanLifecycleResult lifecycle(
            SavedViewDtos.OrphanedView view,
            String idempotencyKey,
            String action,
            Long targetOwner,
            String targetName,
            OffsetDateTime nextRetention) {
        return transactions.execute(status -> {
            SavedViewRepository.Row before = repository.orphanForUpdate(
                    TENANT_ID, view.savedViewId()).orElseThrow();
            return repository.applyOrphanLifecycle(
                    TENANT_ID,
                    ACTOR_USER_ID,
                    UUID.randomUUID(),
                    before,
                    new SavedViewRepository.LifecycleCommand(
                            idempotencyKey,
                            action,
                            targetOwner,
                            targetName,
                            nextRetention,
                            "OFFBOARDING",
                            "PostgreSQL lifecycle recovery verification",
                            "CASE-POSTGRES-1",
                            before.version(),
                            "e".repeat(64)));
        });
    }

    private SavedViewDtos.OwnershipTransferRequest transferRequest(
            String idempotencyKey,
            String disposition,
            Long targetOwner,
            OffsetDateTime retentionUntil,
            int expectedCount) {
        return new SavedViewDtos.OwnershipTransferRequest(
                idempotencyKey,
                SOURCE_USER_ID,
                disposition,
                targetOwner,
                "OFFBOARDING",
                "PostgreSQL integration custody verification",
                "HR-POSTGRES-1",
                retentionUntil,
                expectedCount,
                "a".repeat(64));
    }

    private UUID insertPersonalView(
            long tenantId, long ownerUserId, String surfaceKey, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO usr_saved_views (
                    saved_view_id, tenant_id, surface_key, owner_user_id,
                    name, scope, configuration, lifecycle_state,
                    version, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'PERSONAL', '{}'::jsonb, 'ACTIVE', 0, ?, ?)
                """, id, tenantId, surfaceKey, ownerUserId, name,
                ownerUserId, ownerUserId);
        return id;
    }

    private UUID insertSharedView(
            long tenantId,
            String surfaceKey,
            String name,
            String scope,
            UUID ownerGroupRef,
            String lifecycleState) {
        UUID id = UUID.randomUUID();
        Long ownerUserId = "ACTIVE".equals(lifecycleState) ? TARGET_USER_ID : null;
        OffsetDateTime retentionUntil = "ORPHANED".equals(lifecycleState)
                ? OffsetDateTime.now().plusDays(14)
                : null;
        jdbc.update("""
                INSERT INTO usr_saved_views (
                    saved_view_id, tenant_id, surface_key, owner_user_id, owner_group_ref,
                    name, scope, configuration, lifecycle_state, retention_until,
                    version, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, ?, 0, ?, ?)
                """, id, tenantId, surfaceKey, ownerUserId, ownerGroupRef,
                name, scope, lifecycleState, retentionUntil,
                ACTOR_USER_ID, ACTOR_USER_ID);
        return id;
    }

    private SavedViewDtos.OrphanedView orphan(
            List<SavedViewDtos.OrphanedView> views,
            String id) {
        UUID expected = UUID.fromString(id);
        return views.stream()
                .filter(view -> expected.equals(view.savedViewId()))
                .findFirst()
                .orElseThrow();
    }

    private SavedViewRepository.Row copyWithVersion(
            SavedViewRepository.Row row,
            long version) {
        return new SavedViewRepository.Row(
                row.id(), row.surfaceKey(), row.name(), row.scope(), row.ownerUserId(),
                row.ownerGroupRef(), row.lifecycleState(), row.retentionUntil(),
                Map.copyOf(row.configuration()), version, row.favorite(), row.defaultView(),
                row.lastUsedAt(), row.createdAt(), row.updatedAt());
    }
}

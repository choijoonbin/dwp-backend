package com.dwp.services.provider.provisioning;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ProviderTenantPlacementPostgresTest {

    private static final Set<String> SERVICES =
            Set.of("auth", "platform", "people", "asset-storage");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ProviderTenantPlacementRepository repository;

    @BeforeEach
    void migrateDatabase() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new ProviderTenantPlacementRepository(jdbc);
    }

    @Test
    void selectionFailsClosedForZeroCellIsolationAndCapacityBeforeAnyInsert() {
        String noCellRegion = region("zero", "ACTIVE");
        UUID noCellTenant = tenant(noCellRegion);
        assertPlacementRejected(noCellTenant, noCellRegion, "POOL");

        String isolationRegion = region("isolation", "ACTIVE");
        cell(isolationRegion, "incompatible", "ACTIVE", "[\"SILO\"]", 2);
        UUID isolationTenant = tenant(isolationRegion);
        assertPlacementRejected(isolationTenant, isolationRegion, "POOL");

        String drainingRegion = region("draining", "DRAINING");
        cell(drainingRegion, "active-cell", "ACTIVE", "[\"POOL\"]", 2);
        UUID drainingRegionTenant = tenant(drainingRegion);
        assertPlacementRejected(drainingRegionTenant, drainingRegion, "POOL");

        String capacityRegion = region("capacity", "ACTIVE");
        UUID fullCell = cell(capacityRegion, "full", "ACTIVE", "[\"POOL\"]", 1);
        UUID occupant = tenant(capacityRegion);
        jdbc.update("""
                INSERT INTO prv_tenant_service_instances (
                    provider_tenant_id, service_key, deployment_cell_id, lifecycle_state)
                VALUES (?, 'auth', ?, 'READY')
                """, occupant, fullCell);
        UUID capacityTenant = tenant(capacityRegion);
        assertPlacementRejected(capacityTenant, capacityRegion, "POOL");

        assertThat(instanceCount(noCellTenant)).isZero();
        assertThat(instanceCount(isolationTenant)).isZero();
        assertThat(instanceCount(drainingRegionTenant)).isZero();
        assertThat(instanceCount(capacityTenant)).isZero();
    }

    @Test
    void newPlacementUsesOnlyActiveCellAndExactReuseAllowsDrainingButRejectsNullCell() {
        String region = region("reuse", "ACTIVE");
        cell(region, "a-draining", "DRAINING", "[\"POOL\"]", 2);
        UUID activeCell = cell(region, "b-active", "ACTIVE", "[\"POOL\"]", 2);
        UUID tenantId = tenant(region);

        ProviderTenantPlacementRepository.TenantPlacement created = transaction.execute(status ->
                repository.initializeOrValidate(
                        tenantId, region, "POOL", SERVICES, 1L, true));

        assertThat(created).isNotNull();
        assertThat(created.cellId()).isEqualTo(activeCell);
        assertThat(created.serviceCount()).isEqualTo(4);
        assertThat(created.reused()).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_tenant_service_instances
                 WHERE provider_tenant_id = ? AND deployment_cell_id = ?
                """, Integer.class, tenantId, activeCell)).isEqualTo(4);
        assertThat(jdbc.queryForList("""
                SELECT service_key FROM prv_tenant_service_instances
                 WHERE provider_tenant_id = ? ORDER BY service_key
                """, String.class, tenantId)).containsExactlyInAnyOrderElementsOf(SERVICES);

        jdbc.update("UPDATE prv_deployment_cells SET lifecycle_state = 'DRAINING' WHERE deployment_cell_id = ?",
                activeCell);
        ProviderTenantPlacementRepository.TenantPlacement reused = transaction.execute(status ->
                repository.initializeOrValidate(
                        tenantId, region, "POOL", SERVICES, 1L, false));
        assertThat(reused).isNotNull();
        assertThat(reused.reused()).isTrue();
        assertThat(instanceCount(tenantId)).isEqualTo(4);

        jdbc.update("""
                UPDATE prv_tenant_service_instances SET lifecycle_state = 'READY', applied_schema_version = 1
                 WHERE provider_tenant_id = ? AND service_key = 'auth'
                """, tenantId);
        assertPlacementRejected(tenantId, region, "POOL", false);
        jdbc.update("""
                UPDATE prv_tenant_service_instances
                   SET lifecycle_state = 'PROVISIONING', applied_schema_version = NULL
                 WHERE provider_tenant_id = ? AND service_key = 'auth'
                """, tenantId);
        jdbc.update("UPDATE prv_deployment_cells SET lifecycle_state = 'RETIRED' WHERE deployment_cell_id = ?",
                activeCell);
        assertPlacementRejected(tenantId, region, "POOL", false);
        jdbc.update("UPDATE prv_deployment_cells SET lifecycle_state = 'DRAINING' WHERE deployment_cell_id = ?",
                activeCell);
        jdbc.update("""
                UPDATE prv_tenant_service_instances SET deployment_cell_id = NULL
                 WHERE provider_tenant_id = ? AND service_key = 'people'
                """, tenantId);
        assertPlacementRejected(tenantId, region, "POOL", false);
    }

    @Test
    void regionLockPreventsTwoTenantsFromOverbookingTheLastCapacitySlot() throws Exception {
        String region = region("concurrency", "ACTIVE");
        UUID cellId = cell(region, "single-slot", "ACTIVE", "[\"POOL\"]", 1);
        UUID firstTenant = tenant(region);
        UUID secondTenant = tenant(region);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() ->
                    concurrentPlacement(firstTenant, region, ready, start));
            Future<Boolean> second = executor.submit(() ->
                    concurrentPlacement(secondTenant, region, ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT provider_tenant_id)
                      FROM prv_tenant_service_instances
                     WHERE deployment_cell_id = ? AND lifecycle_state <> 'RETIRED'
                    """, Integer.class, cellId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM prv_tenant_service_instances
                     WHERE deployment_cell_id = ?
                    """, Integer.class, cellId)).isEqualTo(4);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void catalogSwapAfterSnapshotCannotChangeTheInsertedServiceSet() throws Exception {
        String region = region("catalog-snapshot", "ACTIVE");
        UUID cellId = cell(region, "snapshot-cell", "ACTIVE", "[\"POOL\"]", 2);
        UUID tenantId = tenant(region);
        String replacement = "replacement-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO prv_service_catalog (
                    service_key, display_name, service_kind, criticality,
                    provisioning_order, lifecycle_state, capability_metadata)
                SELECT ?, 'Replacement workforce projection', service_kind, criticality,
                       provisioning_order, 'RETIRED', capability_metadata
                  FROM prv_service_catalog
                 WHERE service_key = 'people'
                """, replacement);

        CountDownLatch cellLocked = new CountDownLatch(1);
        CountDownLatch releaseCell = new CountDownLatch(1);
        CountDownLatch placementStarted = new CountDownLatch(1);
        AtomicInteger placementBackend = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> blocker = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.queryForObject("""
                        SELECT deployment_cell_id
                          FROM prv_deployment_cells
                         WHERE deployment_cell_id = ?
                         FOR UPDATE
                        """, UUID.class, cellId);
                cellLocked.countDown();
                await(releaseCell);
            }));
            assertThat(cellLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<ProviderTenantPlacementRepository.TenantPlacement> placement =
                    executor.submit(() -> transaction.execute(status -> {
                        placementBackend.set(jdbc.queryForObject(
                                "SELECT pg_backend_pid()", Integer.class));
                        placementStarted.countDown();
                        return repository.initializeOrValidate(
                                tenantId, region, "POOL", SERVICES, 1L, true);
                    }));
            assertThat(placementStarted.await(5, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLock(placementBackend.get());

            transaction.executeWithoutResult(status -> jdbc.update("""
                    UPDATE prv_service_catalog
                       SET lifecycle_state = CASE
                               WHEN service_key = 'people' THEN 'RETIRED'
                               ELSE 'ACTIVE'
                           END
                     WHERE service_key IN ('people', ?)
                    """, replacement));
            releaseCell.countDown();

            ProviderTenantPlacementRepository.TenantPlacement created = placement.get();
            blocker.get();
            assertThat(created.serviceCount()).isEqualTo(SERVICES.size());
            assertThat(jdbc.queryForList("""
                    SELECT service_key
                      FROM prv_tenant_service_instances
                     WHERE provider_tenant_id = ?
                     ORDER BY service_key
                    """, String.class, tenantId))
                    .containsExactlyInAnyOrderElementsOf(SERVICES)
                    .doesNotContain(replacement);
        } finally {
            releaseCell.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void readyUpdateRequiresAnExistingExactServiceAndPositiveSchema() {
        String region = region("updates", "ACTIVE");
        cell(region, "updates", "ACTIVE", "[\"POOL\"]", 2);
        UUID tenantId = tenant(region);
        transaction.executeWithoutResult(status -> repository.initializeOrValidate(
                tenantId, region, "POOL", SERVICES, 1L, true));

        assertThatThrownBy(() -> repository.updateServiceInstance(
                tenantId, "missing", "READY", "missing:1", 1, "{}", 1L))
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> repository.updateServiceInstance(
                tenantId, "auth", "READY", "1", 0, "{}", 1L))
                .hasMessageContaining("positive applied schema");
    }

    private boolean concurrentPlacement(
            UUID tenantId,
            String region,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            transaction.executeWithoutResult(status -> repository.initializeOrValidate(
                    tenantId, region, "POOL", SERVICES, 1L, true));
            return true;
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    private void awaitDatabaseLock(int backendPid) throws InterruptedException {
        for (int attempt = 0; attempt < 500; attempt++) {
            Boolean waiting = jdbc.queryForObject("""
                    SELECT wait_event_type = 'Lock'
                      FROM pg_stat_activity
                     WHERE pid = ?
                    """, Boolean.class, backendPid);
            if (Boolean.TRUE.equals(waiting)) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("Placement transaction did not reach the deterministic cell lock.");
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out while coordinating the placement race.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Placement race was interrupted.", exception);
        }
    }

    private void assertPlacementRejected(UUID tenantId, String region, String isolationModel) {
        assertPlacementRejected(tenantId, region, isolationModel, true);
    }

    private void assertPlacementRejected(
            UUID tenantId,
            String region,
            String isolationModel,
            boolean allowCreate) {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                repository.initializeOrValidate(
                        tenantId, region, isolationModel, SERVICES, 1L, allowCreate)))
                .hasMessageContaining("placement");
    }

    private String region(String label, String lifecycleState) {
        String key = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO prv_regions (region_key, display_name, lifecycle_state)
                VALUES (?, ?, ?)
                """, key, label, lifecycleState);
        return key;
    }

    private UUID cell(
            String region,
            String label,
            String lifecycleState,
            String isolationModels,
            int capacity) {
        return jdbc.queryForObject("""
                INSERT INTO prv_deployment_cells (
                    cell_key, region_key, display_name, lifecycle_state,
                    supported_isolation_models, placement_capacity)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                RETURNING deployment_cell_id
                """, UUID.class, region + "-" + label, region, label,
                lifecycleState, isolationModels, capacity);
    }

    private UUID tenant(String region) {
        UUID organizationId = jdbc.queryForObject("""
                INSERT INTO prv_organizations (organization_key, display_name)
                VALUES (?, 'Placement organization') RETURNING organization_id
                """, UUID.class, "org-" + UUID.randomUUID().toString().substring(0, 12));
        return jdbc.queryForObject("""
                INSERT INTO prv_tenants (
                    tenant_key, organization_id, display_name, service_tier, data_region,
                    isolation_model, lifecycle_state, onboarding_state, environment_key)
                VALUES (?, ?, 'Placement tenant', 'ENTERPRISE', ?, 'POOL',
                        'PROVISIONING', 'CONTROL_PLANE_READY', 'production')
                RETURNING provider_tenant_id
                """, UUID.class, "tenant-" + UUID.randomUUID().toString().substring(0, 12),
                organizationId, region);
    }

    private int instanceCount(UUID tenantId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_tenant_service_instances WHERE provider_tenant_id = ?
                """, Integer.class, tenantId);
    }
}

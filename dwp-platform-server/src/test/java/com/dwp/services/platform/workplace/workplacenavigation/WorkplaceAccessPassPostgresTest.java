package com.dwp.services.platform.workplace.workplacenavigation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceAccessPassDtos.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceAccessPassPostgresTest {
    private static final long ACTOR = 19_279L;
    private static final Instant FIXED = Instant.parse("2026-09-17T02:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
    private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager transactionManager;
    private static long nextTenant = 9_927_900L;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load().migrate();
        jdbc = new JdbcTemplate(source);
        transactionManager = new DataSourceTransactionManager(source);
    }

    @Test
    void issuePairAndIdempotentReplayPersistNoRawCredentialMaterial() {
        Fixture fixture = fixture("PAIR");
        WorkplaceDeviceService devices = devices(fixture);
        WorkplaceAccessPassService service = service(devices);

        AccessPassCommandResult issued = issue(service, fixture, "issue-pair");
        assertThat(issued.oneTimeCredential()).startsWith("DWP1.");
        assertThat(issued.pairingCode())
                .matches("^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{12}$");
        assertThat(jdbc.queryForMap("""
                SELECT credential_sha256, pairing_code_hash, pairing_code_salt
                  FROM wp_navigation_access_passes
                 WHERE tenant_id=? AND pass_id=?
                """, fixture.tenantId(), issued.pass().passId()))
                .doesNotContainValue(issued.oneTimeCredential())
                .doesNotContainValue(issued.pairingCode());

        AccessPassPairingReceipt paired = service.pair(
                fixture.tenantId(), fixture.deviceId(), fixture.deviceIdentity(), "pair-once",
                "corr-pair-once",
                new AccessPassPairingRequest(issued.pass().passId(), issued.pairingCode()));
        AccessPassPairingReceipt replayed = service.pair(
                fixture.tenantId(), fixture.deviceId(), fixture.deviceIdentity(), "pair-once",
                "corr-pair-once",
                new AccessPassPairingRequest(issued.pass().passId(), issued.pairingCode()));

        assertThat(replayed.pairingReceiptId()).isEqualTo(paired.pairingReceiptId());
        assertThat(replayed.correlationId()).isEqualTo("corr-pair-once");
        assertThat(replayed.replayed()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_navigation_access_pass_pairing_receipts
                 WHERE tenant_id=? AND pass_id=?
                """, Integer.class, fixture.tenantId(), issued.pass().passId())).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT pairing_code_hash, pairing_code_salt, qr_enabled,
                       pairing_consumed_at, pairing_device_id
                  FROM wp_navigation_access_passes
                 WHERE tenant_id=? AND pass_id=?
                """, fixture.tenantId(), issued.pass().passId()))
                .containsEntry("qr_enabled", false)
                .containsEntry("pairing_device_id", fixture.deviceId())
                .containsEntry("pairing_code_hash", null)
                .containsEntry("pairing_code_salt", null);
        assertThat(service.auditEvents(
                fixture.tenantId(), ACTOR, issued.pass().passId(), 20))
                .extracting(AccessPassAuditEvent::action)
                .contains("navigation.access-pass.issue", "navigation.access-pass.paired");
        assertThat(service.context(
                fixture.tenantId(), ACTOR, fixture.siteId(), fixture.resourceId()).pass())
                .matches(pass -> !pass.pairingAvailable() && !pass.qrEnabled());
    }

    @Test
    void fifthInvalidPairingAttemptLocksAndErasesTheVerifier() {
        Fixture fixture = fixture("LOCK");
        WorkplaceAccessPassService service = service(devices(fixture));
        AccessPassCommandResult issued = issue(service, fixture, "issue-lock");

        for (int attempt = 1; attempt <= 5; attempt++) {
            String key = "wrong-pair-" + attempt;
            assertThatThrownBy(() -> service.pair(
                    fixture.tenantId(), fixture.deviceId(), fixture.deviceIdentity(), key,
                    "corr-" + key,
                    new AccessPassPairingRequest(issued.pass().passId(), "222222222222")))
                    .isInstanceOfSatisfying(BaseException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }

        assertThat(jdbc.queryForMap("""
                SELECT pairing_attempt_count, pairing_locked_at, pairing_code_hash,
                       pairing_code_salt, qr_enabled
                  FROM wp_navigation_access_passes
                 WHERE tenant_id=? AND pass_id=?
                """, fixture.tenantId(), issued.pass().passId()))
                .containsEntry("pairing_attempt_count", 5)
                .containsEntry("pairing_code_hash", null)
                .containsEntry("pairing_code_salt", null)
                .containsEntry("qr_enabled", false);
        assertThatThrownBy(() -> service.pair(
                fixture.tenantId(), fixture.deviceId(), fixture.deviceIdentity(), "after-lock",
                "corr-after-lock",
                new AccessPassPairingRequest(issued.pass().passId(), issued.pairingCode())))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_navigation_access_pass_pairing_receipts
                 WHERE tenant_id=? AND pass_id=?
                """, Integer.class, fixture.tenantId(), issued.pass().passId())).isZero();
    }

    private static AccessPassCommandResult issue(
            WorkplaceAccessPassService service, Fixture fixture, String key) {
        AccessPassPreview preview = service.preview(
                fixture.tenantId(), ACTOR, key + "-preview",
                new AccessPassPreviewRequest(AccessPassCommandType.ISSUE, null,
                        fixture.siteId(), fixture.floorId(), fixture.resourceId(), fixture.poiId(), 0));
        assertThat(preview.eligible()).isTrue();
        return service.execute(fixture.tenantId(), ACTOR, key,
                new ConfirmAccessPassCommandRequest(
                        preview.previewId(), 0, "Enter the reserved room", true),
                "corr-" + key);
    }

    private static WorkplaceAccessPassService service(WorkplaceDeviceService devices) {
        WorkplaceAccessPassRepository repository = transactionalProxy(
                new WorkplaceAccessPassRepository(
                        jdbc, new ObjectMapper().findAndRegisterModules()),
                WorkplaceAccessPassRepository.class);
        return transactionalProxy(new WorkplaceAccessPassService(
                repository, devices, CLOCK, new SecureRandom()), WorkplaceAccessPassService.class);
    }

    private static WorkplaceDeviceService devices(Fixture fixture) {
        WorkplaceDeviceService devices = mock(WorkplaceDeviceService.class);
        when(devices.providerTruth(fixture.tenantId())).thenReturn(List.of(
                provider(ProviderCapability.NFC, "verified-nfc"),
                provider(ProviderCapability.SPEED_GATE, "verified-gate")));
        DeviceView device = new DeviceView(
                fixture.deviceId(), "Wayfinding kiosk", DeviceType.STATUS_BOARD,
                RegistrationState.BOUND, fixture.siteId(), fixture.floorId(), null,
                "Kiosk 19", "19.1", "3.0", "policy-19", NOW,
                ConnectivityState.ONLINE, NOW, NOW, FreshnessState.FRESH, null,
                true, 3, NOW);
        DeviceProjection projection = new DeviceProjection(
                DeviceType.STATUS_BOARD, null,
                new StatusBoardProjection(device, List.of(), 0, 0, 0,
                        null, FreshnessState.FRESH, NOW));
        when(devices.projection(
                fixture.tenantId(), fixture.deviceId(), fixture.deviceIdentity()))
                .thenReturn(projection);
        return devices;
    }

    private static ProviderTruth provider(ProviderCapability capability, String code) {
        return new ProviderTruth(capability, code, ProviderTruthState.HEALTHY,
                8, 8L, "evidence:" + capability.name(), NOW, NOW, NOW,
                null, 2, NOW);
    }

    private static Fixture fixture(String suffix) {
        long tenantId = ++nextTenant;
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID graphId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID poiId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        String deviceIdentity = "access-pass-device-credential-" + tenantId;
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id, tenant_id, tenant_key, display_name, lifecycle_state,
                    data_region, isolation_model, created_by, updated_by)
                VALUES (?, ?, ?, 'Access pass test', 'ACTIVE', 'kr', 'POOL', ?, ?)
                """, UUID.randomUUID(), tenantId, "access-pass-" + tenantId, ACTOR, ACTOR);
        jdbc.update("INSERT INTO wp_tenant_policies(tenant_id) VALUES(?)", tenantId);
        jdbc.update("""
                INSERT INTO wp_sites(site_id, tenant_id, site_code, name_ko, name_en)
                VALUES (?, ?, ?, '출입 테스트', 'Access test')
                """, siteId, tenantId, "PASS_" + suffix + "_" + tenantId);
        jdbc.update("""
                INSERT INTO wp_floors(floor_id, tenant_id, site_id, floor_number, name_ko, name_en)
                VALUES (?, ?, ?, 19, '19층', '19F')
                """, floorId, tenantId, siteId);
        jdbc.update("""
                INSERT INTO wp_resources(
                    resource_id, tenant_id, floor_id, resource_code,
                    name_ko, name_en, resource_type)
                VALUES (?, ?, ?, ?, '회의실 19A', 'Room 19A', 'ROOM')
                """, resourceId, tenantId, floorId, "ROOM_" + suffix + "_" + tenantId);
        jdbc.update("""
                INSERT INTO wp_navigation_graph_revisions(
                    graph_revision_id, tenant_id, site_id, revision_number, lifecycle_state,
                    content_hash, change_summary, version, submitted_at, submitted_by,
                    published_at, published_by, created_by, updated_by)
                VALUES (?, ?, ?, 1, 'PUBLISHED', ?, 'Access route', 2, ?, ?, ?, ?, ?, ?)
                """, graphId, tenantId, siteId, "a".repeat(64), NOW, ACTOR,
                NOW, ACTOR, ACTOR, ACTOR);
        jdbc.update("""
                INSERT INTO wp_navigation_nodes(
                    node_id, tenant_id, graph_revision_id, floor_id, node_code, node_kind,
                    position_x, position_y, accessible)
                VALUES (?, ?, ?, ?, 'ROOM', 'POI', 20, 20, TRUE)
                """, nodeId, tenantId, graphId, floorId);
        jdbc.update("""
                INSERT INTO wp_navigation_pois(
                    poi_id, tenant_id, graph_revision_id, node_id, site_id, floor_id,
                    resource_id, category, name_ko, name_en, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ROOM', '회의실 19A', 'Room 19A', TRUE)
                """, poiId, tenantId, graphId, nodeId, siteId, floorId, resourceId);
        jdbc.update("""
                INSERT INTO wp_bookings(
                    tenant_id, resource_id, user_id, booked_for_display_name, purpose,
                    starts_at, ends_at, booking_status, policy_snapshot, policy_snapshot_hash,
                    require_check_in_snapshot, check_in_lead_minutes_snapshot,
                    auto_release_minutes_snapshot, booking_retention_days_snapshot)
                VALUES (?, ?, ?, 'Access pass owner', 'Reserved room access', ?, ?, 'RESERVED',
                        '{}'::jsonb, encode(digest('{}'::jsonb::text,'sha256'),'hex'),
                        FALSE, 15, 0, 365)
                """, tenantId, resourceId, ACTOR, NOW.minusMinutes(5), NOW.plusMinutes(45));
        jdbc.update("""
                INSERT INTO wp_navigation_devices(
                    device_id, tenant_id, device_identity_sha256, display_name, device_type,
                    registration_state, site_id, floor_id, hardware_model, os_version,
                    app_version, policy_version, heartbeat_at, schedule_source_at,
                    schedule_received_at, version)
                VALUES (?, ?, ?, 'Wayfinding kiosk', 'STATUS_BOARD', 'BOUND', ?, ?,
                        'Kiosk 19', '19.1', '3.0', 'policy-19', ?, ?, ?, 3)
                """, deviceId, tenantId, "d".repeat(64), siteId, floorId, NOW, NOW, NOW);
        return new Fixture(
                tenantId, siteId, floorId, resourceId, poiId, deviceId, deviceIdentity);
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    private static <T> T transactionalProxy(T target, Class<T> type) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy(type.getClassLoader());
    }

    private record Fixture(
            long tenantId,
            UUID siteId,
            UUID floorId,
            UUID resourceId,
            UUID poiId,
            UUID deviceId,
            String deviceIdentity) { }
}

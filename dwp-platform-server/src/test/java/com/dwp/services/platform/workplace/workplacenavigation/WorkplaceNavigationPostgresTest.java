package com.dwp.services.platform.workplace.workplacenavigation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceDeviceCommandProvider.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceNavigationPostgresTest {
    private static final Instant FIXED = Instant.parse("2026-09-16T12:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
    private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);
    private static final long ACTOR = 19_001L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static PlatformTransactionManager transactionManager;
    private static WorkplaceNavigationRepository navigationRepository;
    private static WorkplaceDeviceRepository deviceRepository;
    private static WorkplaceNavigationService navigation;
    private static long nextTenant = 9_990_000L;

    @BeforeAll
    static void migrateAndBuildServices() {
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
        transaction = new TransactionTemplate(transactionManager);
        navigationRepository = new WorkplaceNavigationRepository(jdbc);
        deviceRepository = new WorkplaceDeviceRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        navigation = new WorkplaceNavigationService(navigationRepository, CLOCK);
    }

    @Test
    void publishedGraphRoutesAccessibilityAndRestrictedZonesWithoutSyntheticFallback() {
        Fixture fixture = fixture("ROUTE");
        UUID originNode = UUID.randomUUID();
        UUID elevatorNode = UUID.randomUUID();
        UUID restrictedNode = UUID.randomUUID();
        UUID originPoi = UUID.randomUUID();
        UUID destinationPoi = UUID.randomUUID();
        String restrictedPermission = "APP.WORKPLACE.ZONE." + fixture.zoneId() + ":ACCESS";
        CreateGraphRequest request = graphRequest(fixture, originNode, elevatorNode,
                restrictedNode, originPoi, destinationPoi, restrictedPermission);

        GraphRevisionView draft = tx(() -> navigation.createGraph(fixture.tenantId(), ACTOR,
                "graph-create", "corr-graph", request));
        assertThatThrownBy(() -> tx(() -> navigation.publishGraph(
                fixture.tenantId(), ACTOR, draft.graphRevisionId(),
                "graph-publish-before-review", "corr-early-publish",
                new PublishGraphRequest(draft.version(), "Publish without review", true))))
                .isInstanceOf(BaseException.class);
        GraphRevisionView reviewed = tx(() -> navigation.submitForReview(
                fixture.tenantId(), ACTOR, draft.graphRevisionId(), "graph-review",
                "corr-review", new GraphTransitionRequest(
                        draft.version(), "Review verified graph", true)));
        GraphRevisionView published = tx(() -> navigation.publishGraph(fixture.tenantId(), ACTOR,
                draft.graphRevisionId(), "graph-publish", "corr-publish",
                new PublishGraphRequest(reviewed.version(), "Publish verified graph", true)));

        RouteProjection denied = tx(() -> navigation.route(fixture.tenantId(), fixture.siteId(),
                originPoi, destinationPoi, true, true, java.util.Set.of()));
        RouteProjection allowed = tx(() -> navigation.route(fixture.tenantId(), fixture.siteId(),
                originPoi, destinationPoi, true, true, java.util.Set.of(restrictedPermission)));

        assertThat(published.state()).isEqualTo(GraphState.PUBLISHED);
        assertThat(denied.outcome()).isEqualTo(RouteOutcome.ACCESS_DENIED);
        assertThat(denied.steps()).isEmpty();
        assertThat(allowed.outcome()).isEqualTo(RouteOutcome.GUIDED);
        assertThat(allowed.steps()).extracting(RouteStep::travelMode)
                .containsExactly(TravelMode.WALK, TravelMode.ELEVATOR);
        assertThat(tx(() -> navigation.createGraph(fixture.tenantId(), ACTOR,
                "graph-create", "ignored", request)).graphRevisionId())
                .isEqualTo(draft.graphRevisionId());
    }

    @Test
    void deviceBindingPrivacyProviderTruthAndUnknownRecoveryAreDurable() throws Exception {
        Fixture fixture = fixture("DEVICE");
        ControlledProvider provider = new ControlledProvider();
        WorkplaceDeviceService devices = transactionalProxy(new WorkplaceDeviceService(
                deviceRepository, Optional.of(provider), CLOCK));
        WorkplaceDeviceCommandWorker worker = new WorkplaceDeviceCommandWorker(
                devices, Optional.of(provider));
        String identity = "controlled-device-identity-material-123456789";
        DeviceView pending = tx(() -> devices.register(fixture.tenantId(), identity,
                new DeviceRegistrationRequest("Room panel", DeviceType.ROOM_PANEL,
                        "Panel X", "DeviceOS 3")));
        DeviceView approved = tx(() -> devices.approve(fixture.tenantId(), ACTOR,
                pending.deviceId(), "approve-device", new VersionedAdminCommand(
                        pending.version(), "Approve known hardware", true), "corr-approve"));
        DeviceView bound = tx(() -> devices.bind(fixture.tenantId(), ACTOR, pending.deviceId(),
                "bind-device", new BindDeviceRequest(approved.version(), fixture.siteId(), fixture.floorId(),
                        fixture.resourceId(), true, "Bind to room", true), "corr-bind"));
        DeviceView online = tx(() -> devices.heartbeat(fixture.tenantId(), pending.deviceId(), identity,
                new DeviceHeartbeatRequest(bound.version(), "19.4", "policy-8", NOW,
                        NOW.minusMinutes(1), null)));
        jdbc.update("""
                INSERT INTO wp_bookings(
                    tenant_id,resource_id,user_id,booked_for_display_name,purpose,
                    starts_at,ends_at,booking_status,policy_snapshot,policy_snapshot_hash,
                    require_check_in_snapshot,check_in_lead_minutes_snapshot,
                    auto_release_minutes_snapshot,booking_retention_days_snapshot)
                VALUES(?,?,?,'Sensitive Person','Confidential board meeting',
                       ?,?,'RESERVED','{}'::jsonb,
                       encode(digest('{}'::jsonb::text,'sha256'),'hex'),FALSE,15,0,365)
                """, fixture.tenantId(), fixture.resourceId(), ACTOR,
                NOW.minusMinutes(5), NOW.plusMinutes(55));

        DeviceProjection projection = tx(() -> devices.projection(
                fixture.tenantId(), pending.deviceId(), identity));
        assertThat(projection.roomPanel().current().title()).isEqualTo("Reserved");
        assertThat(projection.roomPanel().current().organizer()).isNull();
        assertThat(projection.roomPanel().current().privacyMasked()).isTrue();
        assertThat(online.connectivity()).isEqualTo(ConnectivityState.ONLINE);
        assertThat(online.version()).isEqualTo(bound.version());

        txRun(() -> deviceRepository.configureProvider(fixture.tenantId(),
                ProviderCapability.MDM, "CONTROLLED_MDM", 1, true, NOW));
        assertThat(tx(() -> devices.providerTruth(fixture.tenantId())).stream()
                .filter(item -> item.capability() == ProviderCapability.MDM).findFirst().orElseThrow()
                .state()).isEqualTo(ProviderTruthState.CONFIGURED_UNVERIFIED);
        tx(() -> devices.observeProvider(new ProviderObservation(fixture.tenantId(),
                ProviderCapability.MDM, "CONTROLLED_MDM", 1, ProviderReportedState.HEALTHY,
                "evidence:controlled-mdm", NOW.minusSeconds(5), NOW, NOW, null)));

        DeviceCommandPreview preview = tx(() -> devices.preview(fixture.tenantId(), ACTOR,
                pending.deviceId(), "preview-sync", "corr-preview-sync",
                new DeviceCommandPreviewRequest(DeviceCommandType.FORCE_SYNC,
                        online.version(), Map.of())));
        DeviceCommandReceipt accepted = tx(() -> devices.execute(fixture.tenantId(), ACTOR,
                pending.deviceId(), "sync-command", "corr-sync",
                new ExecuteDeviceCommandRequest(preview.previewId(), online.version(),
                        "Force verified schedule sync", true)));
        assertThat(accepted.state()).isEqualTo(DeviceCommandState.ACCEPTED);

        // Rotate the active configuration after durable acceptance. Dispatch and every later
        // lookup must retain the v1 provider/credential snapshot owned by this command.
        txRun(() -> deviceRepository.configureProvider(fixture.tenantId(),
                ProviderCapability.MDM, "CONTROLLED_MDM", 2, true, NOW));
        tx(() -> devices.observeProvider(new ProviderObservation(fixture.tenantId(),
                ProviderCapability.MDM, "CONTROLLED_MDM", 2, ProviderReportedState.HEALTHY,
                "evidence:controlled-mdm-v2", NOW.minusSeconds(4), NOW, NOW, null)));

        provider.executeOutcome = new ProviderCommandOutcome(
                OutcomeState.RESULT_UNKNOWN, "operation:sync", "PROVIDER_TIMEOUT");
        ExecutorService dispatchers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = dispatchers.submit(worker::dispatchNext);
            Future<Boolean> second = dispatchers.submit(worker::dispatchNext);
            assertThat(List.of(first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        } finally {
            dispatchers.shutdownNow();
        }
        DeviceCommandReceipt unknown = tx(() -> devices.receipt(
                fixture.tenantId(), accepted.commandId()));
        assertThat(unknown.state()).isEqualTo(DeviceCommandState.RESULT_UNKNOWN);
        assertThat(unknown.recoveryByGetOnly()).isTrue();

        provider.statusOutcome = new ProviderCommandOutcome(
                OutcomeState.SUCCEEDED, "operation:sync", "SYNCED");
        jdbc.update("""
                UPDATE wp_navigation_device_outbox
                   SET delivery_state='PROCESSING', next_attempt_at=?, updated_at=?
                 WHERE tenant_id=? AND command_id=?
                """, NOW, NOW.minusMinutes(10), fixture.tenantId(), accepted.commandId());
        assertThat(devices.recoverStaleDispatches(Duration.ofMinutes(2))).isEqualTo(1);
        assertThat(worker.reconcileNext()).isTrue();
        DeviceCommandReceipt recovered = tx(() -> devices.receipt(
                fixture.tenantId(), accepted.commandId()));
        assertThat(recovered.state()).isEqualTo(DeviceCommandState.SUCCEEDED);
        assertThat(provider.executeCalls).hasValue(1);
        assertThat(provider.statusCalls).hasValue(1);
        assertThat(provider.dispatchedBindings).extracting(ProviderBinding::configurationVersion)
                .containsExactly(1L);
        assertThat(provider.lookedUpBindings).extracting(ProviderBinding::configurationVersion)
                .containsExactly(1L);

        DeviceCommandPreview legacyPreview = tx(() -> devices.preview(
                fixture.tenantId(), ACTOR, pending.deviceId(), "preview-legacy-sync",
                "corr-preview-legacy-sync", new DeviceCommandPreviewRequest(
                        DeviceCommandType.FORCE_SYNC, online.version(), Map.of())));
        DeviceCommandReceipt legacy = tx(() -> devices.execute(
                fixture.tenantId(), ACTOR, pending.deviceId(), "legacy-sync-command",
                "corr-legacy-sync", new ExecuteDeviceCommandRequest(
                        legacyPreview.previewId(), online.version(),
                        "Recover a pre-upgrade ambiguous command", true)));
        jdbc.update("""
                UPDATE wp_navigation_device_commands
                   SET provider_code=NULL, provider_configuration_version=NULL,
                       credential_reference=NULL, command_state='RESULT_UNKNOWN',
                       result_code='LEGACY_PROVIDER_BINDING_MISSING', completed_at=NULL,
                       version=version+1, updated_at=?
                 WHERE tenant_id=? AND command_id=?
                """, NOW, fixture.tenantId(), legacy.commandId());
        jdbc.update("""
                UPDATE wp_navigation_device_outbox
                   SET delivery_state='RESULT_UNKNOWN', attempt_count=0,
                       next_attempt_at=?, updated_at=?
                 WHERE tenant_id=? AND command_id=?
                """, NOW, NOW, fixture.tenantId(), legacy.commandId());
        int executeCallsBeforeLegacyRecovery = provider.executeCalls.get();
        int statusCallsBeforeLegacyRecovery = provider.statusCalls.get();
        provider.statusOutcome = new ProviderCommandOutcome(
                OutcomeState.FAILED, "operation:legacy", "PROVIDER_CLAIMS_FAILED");

        assertThat(worker.reconcileNext()).isTrue();

        DeviceCommandReceipt legacyUnknown = tx(() -> devices.receipt(
                fixture.tenantId(), legacy.commandId()));
        assertThat(legacyUnknown.state()).isEqualTo(DeviceCommandState.RESULT_UNKNOWN);
        assertThat(legacyUnknown.completedAt()).isNull();
        assertThat(legacyUnknown.recoveryByGetOnly()).isTrue();
        assertThat(provider.executeCalls).hasValue(executeCallsBeforeLegacyRecovery);
        assertThat(provider.statusCalls).hasValue(statusCallsBeforeLegacyRecovery);
        Map<String, Object> firstBackoff = jdbc.queryForMap("""
                SELECT attempt_count, delivery_state,
                       EXTRACT(EPOCH FROM (next_attempt_at - ?::timestamptz))::integer
                           AS backoff_seconds
                  FROM wp_navigation_device_outbox
                 WHERE tenant_id=? AND command_id=?
                """, NOW, fixture.tenantId(), legacy.commandId());
        assertThat(firstBackoff.get("delivery_state")).isEqualTo("RESULT_UNKNOWN");
        assertThat(((Number) firstBackoff.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(((Number) firstBackoff.get("backoff_seconds")).intValue()).isEqualTo(5);

        jdbc.update("""
                UPDATE wp_navigation_device_outbox SET next_attempt_at=?
                 WHERE tenant_id=? AND command_id=?
                """, NOW, fixture.tenantId(), legacy.commandId());
        assertThat(worker.reconcileNext()).isTrue();
        Map<String, Object> secondBackoff = jdbc.queryForMap("""
                SELECT attempt_count,
                       EXTRACT(EPOCH FROM (next_attempt_at - ?::timestamptz))::integer
                           AS backoff_seconds
                  FROM wp_navigation_device_outbox
                 WHERE tenant_id=? AND command_id=?
                """, NOW, fixture.tenantId(), legacy.commandId());
        assertThat(((Number) secondBackoff.get("attempt_count")).intValue()).isEqualTo(2);
        assertThat(((Number) secondBackoff.get("backoff_seconds")).intValue()).isEqualTo(10);
        jdbc.update("""
                UPDATE wp_navigation_device_outbox
                   SET attempt_count=9, next_attempt_at=?
                 WHERE tenant_id=? AND command_id=?
                """, NOW, fixture.tenantId(), legacy.commandId());
        assertThat(worker.reconcileNext()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT EXTRACT(EPOCH FROM (next_attempt_at - ?::timestamptz))::integer
                  FROM wp_navigation_device_outbox
                 WHERE tenant_id=? AND command_id=?
                """, Integer.class, NOW, fixture.tenantId(), legacy.commandId()))
                .isEqualTo(900);
        jdbc.update("""
                UPDATE wp_navigation_device_outbox SET next_attempt_at=?
                 WHERE tenant_id=? AND command_id=?
                """, NOW, fixture.tenantId(), legacy.commandId());
        assertThat(worker.reconcileNext()).isFalse();
        assertThat(tx(() -> devices.receipt(fixture.tenantId(), legacy.commandId())).state())
                .isEqualTo(DeviceCommandState.RESULT_UNKNOWN);
        assertThat(provider.executeCalls).hasValue(executeCallsBeforeLegacyRecovery);
        assertThat(provider.statusCalls).hasValue(statusCallsBeforeLegacyRecovery);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_navigation_audit_events
                 WHERE tenant_id=? AND resource_id=?
                   AND action='navigation.device.command.failed'
                """, Long.class, fixture.tenantId(), legacy.commandId())).isZero();
        assertThat(tx(() -> deviceRepository.activeSafetyFrame(
                fixture.tenantId(), pending.deviceId()))).isEmpty();

        DeviceCommandPreview safetyPreview = tx(() -> devices.preview(fixture.tenantId(), ACTOR,
                pending.deviceId(), "preview-safety", "corr-preview-safety",
                new DeviceCommandPreviewRequest(
                        DeviceCommandType.SAFETY_TAKEOVER, online.version(),
                        Map.of("message", "Evacuate now", "direction", "Use the east exit"))));
        tx(() -> devices.execute(fixture.tenantId(), ACTOR, pending.deviceId(),
                "safety-command", "corr-safety", new ExecuteDeviceCommandRequest(
                        safetyPreview.previewId(), online.version(), "Activate safety frame", true)));
        provider.executeOutcome = new ProviderCommandOutcome(
                OutcomeState.SUCCEEDED, "operation:safety", "ACTIVATED");
        assertThat(worker.dispatchNext()).isTrue();
        SafetyFrame activeFrame = tx(() -> devices.projection(
                fixture.tenantId(), pending.deviceId(), identity)).roomPanel().safetyFrame();
        assertThat(activeFrame.issuedByActorId()).isEqualTo(ACTOR);
        Fixture otherTenant = fixture("SAFETY_OTHER");
        assertThat(tx(() -> deviceRepository.activeSafetyFrame(
                otherTenant.tenantId(), pending.deviceId()))).isEmpty();

        long clearingActor = ACTOR + 1;
        DeviceCommandPreview clearPreview = tx(() -> devices.preview(
                fixture.tenantId(), clearingActor, pending.deviceId(),
                "preview-clear-safety", "corr-preview-clear-safety",
                new DeviceCommandPreviewRequest(
                        DeviceCommandType.CLEAR_SAFETY, online.version(), Map.of())));
        tx(() -> devices.execute(fixture.tenantId(), clearingActor, pending.deviceId(),
                "clear-safety-command", "corr-clear-safety", new ExecuteDeviceCommandRequest(
                        clearPreview.previewId(), online.version(), "Clear safety frame", true)));
        provider.executeOutcome = new ProviderCommandOutcome(
                OutcomeState.SUCCEEDED, "operation:clear-safety", "CLEARED");
        assertThat(worker.dispatchNext()).isTrue();
        assertThat(tx(() -> deviceRepository.activeSafetyFrame(
                fixture.tenantId(), pending.deviceId()))).isEmpty();
        Map<String, Object> clearedFrame = jdbc.queryForMap("""
                SELECT issued_by_actor_id,cleared_by
                  FROM wp_navigation_safety_frames
                 WHERE tenant_id=? AND device_id=?
                """, fixture.tenantId(), pending.deviceId());
        assertThat(((Number) clearedFrame.get("issued_by_actor_id")).longValue())
                .isEqualTo(ACTOR);
        assertThat(((Number) clearedFrame.get("cleared_by")).longValue())
                .isEqualTo(clearingActor);
        assertThat(tx(() -> devices.commands(fixture.tenantId(), pending.deviceId())))
                .extracting(DeviceCommandReceipt::commandId)
                .contains(accepted.commandId());
        assertThat(tx(() -> devices.auditEvents(fixture.tenantId(), pending.deviceId())))
                .extracting(DeviceAuditEvent::action)
                .contains("navigation.device.approved", "navigation.device.bound",
                        "navigation.device.command.accepted",
                        "navigation.device.command.succeeded",
                        "navigation.device.safety.activated",
                        "navigation.device.safety.cleared");
        assertThat(tx(() -> devices.auditEvents(fixture.tenantId(), pending.deviceId())))
                .filteredOn(event -> event.action().equals("navigation.device.safety.cleared"))
                .extracting(DeviceAuditEvent::actorUserId)
                .containsExactly(clearingActor);
    }

    @Test
    void administratorWritesSerializeReplayAcrossRestartAndRejectPayloadDrift() throws Exception {
        Fixture fixture = fixture("ADMIN_COMMAND");
        WorkplaceDeviceService firstProcess = new WorkplaceDeviceService(
                deviceRepository, Optional.empty(), CLOCK);
        String identity = "admin-idempotency-device-identity-material-123456789";
        DeviceView pending = tx(() -> firstProcess.register(fixture.tenantId(), identity,
                new DeviceRegistrationRequest("Admin panel", DeviceType.ROOM_PANEL,
                        "Panel X", "DeviceOS 3")));
        VersionedAdminCommand approval = new VersionedAdminCommand(
                pending.version(), "Approve verified hardware", true);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<DeviceView> duplicate = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return tx(() -> firstProcess.approve(fixture.tenantId(), ACTOR,
                        pending.deviceId(), "concurrent-approve", approval, "corr-approve"));
            };
            Future<DeviceView> left = executor.submit(duplicate);
            Future<DeviceView> right = executor.submit(duplicate);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(left.get(20, TimeUnit.SECONDS))
                    .isEqualTo(right.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        WorkplaceDeviceService restarted = new WorkplaceDeviceService(
                new WorkplaceDeviceRepository(jdbc, new ObjectMapper().findAndRegisterModules()),
                Optional.empty(), CLOCK);
        DeviceView approved = tx(() -> restarted.approve(fixture.tenantId(), ACTOR,
                pending.deviceId(), "concurrent-approve", approval, "corr-after-restart"));
        assertThat(approved.registrationState()).isEqualTo(RegistrationState.APPROVED);
        assertThatThrownBy(() -> tx(() -> restarted.approve(fixture.tenantId(), ACTOR,
                pending.deviceId(), "concurrent-approve", new VersionedAdminCommand(
                        pending.version(), "Changed retry payload", true), "corr-conflict")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        BindDeviceRequest binding = new BindDeviceRequest(approved.version(), fixture.siteId(),
                fixture.floorId(), fixture.resourceId(), true, "Bind approved panel", true);
        DeviceView bound = tx(() -> firstProcess.bind(fixture.tenantId(), ACTOR,
                pending.deviceId(), "bind-panel", binding, "corr-bind"));
        assertThat(tx(() -> restarted.bind(fixture.tenantId(), ACTOR, pending.deviceId(),
                "bind-panel", binding, "corr-bind-retry"))).isEqualTo(bound);

        ProviderConfigurationRequest providerRequest = new ProviderConfigurationRequest(
                "managed-mdm", 3, true, "Configure governed MDM", true);
        ProviderTruth configured = tx(() -> firstProcess.configureProvider(
                fixture.tenantId(), ACTOR, ProviderCapability.MDM, "configure-mdm",
                providerRequest, "corr-provider"));
        assertThat(tx(() -> restarted.configureProvider(fixture.tenantId(), ACTOR,
                ProviderCapability.MDM, "configure-mdm", providerRequest,
                "corr-provider-retry"))).isEqualTo(configured);

        Map<String, String> firstPayload = new LinkedHashMap<>();
        firstPayload.put("message", "Evacuate now");
        firstPayload.put("direction", "Use the east exit");
        Map<String, String> reorderedPayload = new LinkedHashMap<>();
        reorderedPayload.put("direction", "Use the east exit");
        reorderedPayload.put("message", "Evacuate now");
        DeviceCommandPreview createdPreview = tx(() -> firstProcess.preview(
                fixture.tenantId(), ACTOR, pending.deviceId(), "preview-safety",
                "corr-preview", new DeviceCommandPreviewRequest(
                        DeviceCommandType.SAFETY_TAKEOVER, bound.version(), firstPayload)));
        DeviceCommandPreview replayedPreview = tx(() -> restarted.preview(
                fixture.tenantId(), ACTOR, pending.deviceId(), "preview-safety",
                "corr-preview-retry", new DeviceCommandPreviewRequest(
                        DeviceCommandType.SAFETY_TAKEOVER, bound.version(), reorderedPayload)));
        assertThat(replayedPreview).isEqualTo(createdPreview);
        assertThatThrownBy(() -> tx(() -> restarted.configureProvider(
                fixture.tenantId(), ACTOR, ProviderCapability.GRAPH, "preview-safety",
                new ProviderConfigurationRequest("graph", 1, true,
                        "Reuse another command key", true), "corr-cross-command")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_navigation_admin_commands
                 WHERE tenant_id=? AND actor_user_id=?
                """, Integer.class, fixture.tenantId(), ACTOR)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_navigation_admin_commands command
                  JOIN wp_navigation_audit_events audit
                    ON audit.tenant_id=command.tenant_id
                   AND audit.audit_event_id=command.audit_event_id
                 WHERE command.tenant_id=? AND command.actor_user_id=?
                """, Integer.class, fixture.tenantId(), ACTOR)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_navigation_audit_events
                 WHERE tenant_id=? AND action='navigation.device.approved'
                """, Integer.class, fixture.tenantId())).isEqualTo(1);
    }

    @Test
    void migrationAndTenantCompositeBindingsFailClosedWithoutCredentialColumns() {
        Fixture first = fixture("TENANT_A");
        Fixture second = fixture("TENANT_B");
        assertThat(jdbc.queryForObject("""
                SELECT success FROM flyway_schema_history WHERE version='263'
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT success FROM flyway_schema_history WHERE version='270'
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT success FROM flyway_schema_history WHERE version='276'
                """, Boolean.class)).isTrue();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wp_navigation_devices(
                    device_id,tenant_id,device_identity_sha256,display_name,device_type,
                    registration_state,site_id,floor_id,resource_id,hardware_model,os_version)
                VALUES(?,?,?,'Cross tenant','ROOM_PANEL','BOUND',?,?,?,'X','1')
                """, UUID.randomUUID(), first.tenantId(), "a".repeat(64),
                second.siteId(), second.floorId(), second.resourceId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema='public' AND table_name LIKE 'wp_navigation_%'
                """, String.class)).noneMatch(name -> name.matches(
                ".*(secret|plaintext|pin|access_token|refresh_token|credential_value).*"));
    }

    private static CreateGraphRequest graphRequest(
            Fixture fixture,
            UUID origin,
            UUID elevator,
            UUID destination,
            UUID originPoi,
            UUID destinationPoi,
            String permission) {
        return new CreateGraphRequest(fixture.siteId(), 1, "b".repeat(64),
                "Accessible restricted route", List.of(
                new NavigationNodeInput(origin, fixture.floorId(), "ENTRY", NodeKind.ENTRY,
                        BigDecimal.TEN, BigDecimal.TEN, true, null),
                new NavigationNodeInput(elevator, fixture.floorId(), "LIFT", NodeKind.ELEVATOR,
                        BigDecimal.valueOf(20), BigDecimal.valueOf(20), true, null),
                new NavigationNodeInput(destination, fixture.floorId(), "ROOM", NodeKind.POI,
                        BigDecimal.valueOf(30), BigDecimal.valueOf(30), true, fixture.zoneId())),
                List.of(
                        new NavigationEdgeInput(UUID.randomUUID(), origin, elevator, 20,
                                TravelMode.WALK, true, true, null),
                        new NavigationEdgeInput(UUID.randomUUID(), elevator, destination, 30,
                                TravelMode.ELEVATOR, true, true, permission)),
                List.of(
                        new PoiInput(originPoi, origin, fixture.floorId(), null, PoiCategory.ENTRY,
                                "입구", "Entry", "입구", "Entry"),
                        new PoiInput(destinationPoi, destination, fixture.floorId(),
                                fixture.resourceId(), PoiCategory.ROOM,
                                "회의실", "Room", "엘리베이터 옆", "By the elevator")),
                "Publish controlled graph", true);
    }

    private static Fixture fixture(String suffix) {
        long tenantId = ++nextTenant;
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,
                    data_region,isolation_model,created_by,updated_by)
                VALUES(?,?,?,'Navigation test','ACTIVE','kr','POOL',?,?)
                """, UUID.randomUUID(), tenantId, "navigation-" + tenantId, ACTOR, ACTOR);
        jdbc.update("INSERT INTO wp_tenant_policies(tenant_id) VALUES(?)", tenantId);
        jdbc.update("""
                INSERT INTO wp_sites(site_id,tenant_id,site_code,name_ko,name_en)
                VALUES(?,?,?,'길찾기 테스트','Navigation test')
                """, siteId, tenantId, "NAV_" + suffix + "_" + tenantId);
        jdbc.update("""
                INSERT INTO wp_floors(floor_id,tenant_id,site_id,floor_number,name_ko,name_en)
                VALUES(?,?,?,12,'12층','12F')
                """, floorId, tenantId, siteId);
        jdbc.update("""
                INSERT INTO wp_resources(
                    resource_id,tenant_id,floor_id,resource_code,name_ko,name_en,resource_type)
                VALUES(?,?,?,?, '회의실 12A','Room 12A','ROOM')
                """, resourceId, tenantId, floorId, "ROOM_" + suffix + "_" + tenantId);
        jdbc.update("""
                INSERT INTO wp_zones(
                    zone_id,tenant_id,floor_id,zone_code,name_ko,name_en,zone_type,
                    created_by,updated_by)
                VALUES(?,?,?,?,'제한 구역','Restricted','RESTRICTED',?,?)
                """, zoneId, tenantId, floorId, "SECURE_" + tenantId, ACTOR, ACTOR);
        return new Fixture(tenantId, siteId, floorId, resourceId, zoneId);
    }

    private static <T> T tx(Supplier<T> supplier) {
        return transaction.execute(ignored -> supplier.get());
    }

    private static void txRun(Runnable runnable) {
        transaction.executeWithoutResult(ignored -> runnable.run());
    }

    @SuppressWarnings("unchecked")
    private static WorkplaceDeviceService transactionalProxy(WorkplaceDeviceService target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        return (WorkplaceDeviceService) factory.getProxy(
                WorkplaceDeviceService.class.getClassLoader());
    }

    private record Fixture(
            long tenantId, UUID siteId, UUID floorId, UUID resourceId, UUID zoneId) { }

    private static final class ControlledProvider implements WorkplaceDeviceCommandProvider {
        private final AtomicInteger executeCalls = new AtomicInteger();
        private final AtomicInteger statusCalls = new AtomicInteger();
        private final List<ProviderBinding> dispatchedBindings = new CopyOnWriteArrayList<>();
        private final List<ProviderBinding> lookedUpBindings = new CopyOnWriteArrayList<>();
        private ProviderCommandOutcome executeOutcome;
        private ProviderCommandOutcome statusOutcome;

        @Override
        public Optional<ProviderBinding> binding(String providerCode, long configurationVersion) {
            return Optional.of(new ProviderBinding(providerCode, configurationVersion,
                    "secret-manager://workplace/controlled-mdm-v" + configurationVersion));
        }

        @Override
        public boolean ready(ProviderBinding binding) {
            return true;
        }

        @Override
        public ProviderCommandOutcome execute(ProviderCommand command) {
            executeCalls.incrementAndGet();
            dispatchedBindings.add(command.binding());
            return executeOutcome;
        }

        @Override
        public ProviderCommandOutcome status(ProviderCommand command) {
            statusCalls.incrementAndGet();
            lookedUpBindings.add(command.binding());
            return statusOutcome == null ? executeOutcome : statusOutcome;
        }

        @Override
        public Optional<ProviderRuntimeObservation> observe(ProviderObservationCommand command) {
            return Optional.empty();
        }
    }
}

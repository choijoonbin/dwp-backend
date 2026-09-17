package com.dwp.services.platform.workplace.safetyoperations;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyDispatchRecoveryRepository.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyDispatchProvider.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyIncidentRepository.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class SafetyOperationsPostgresTest {
    private static final Instant FIXED = Instant.parse("2026-09-16T12:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
    private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);
    private static final long ACTOR = 20_001L;
    private static final long APPROVER = 20_002L;
    private static final long SUBJECT = 20_101L;
    private static final String DECISION = "psr-" + "a".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final AtomicLong TENANTS = new AtomicLong(9_992_000L);
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static ObjectMapper mapper;
    private static SafetyAudienceService audiences;
    private static SafetyIncidentRepository incidents;
    private static SafetyClosureRepository closures;
    private static SafetyConnectorService connectors;
    private static SafetyPreviewService previews;
    private static SafetyOperationsService operations;
    private static SafetyDispatchRecoveryRepository recovery;

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
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        mapper = new ObjectMapper().findAndRegisterModules();
        SafetyAudienceRepository audienceRepository = new SafetyAudienceRepository(jdbc, mapper);
        audiences = new SafetyAudienceService(audienceRepository, Duration.ofMinutes(5), CLOCK);
        incidents = new SafetyIncidentRepository(jdbc, mapper);
        closures = new SafetyClosureRepository(jdbc, mapper);
        connectors = new SafetyConnectorService(incidents, mapper, Duration.ofMinutes(5), CLOCK);
        previews = new SafetyPreviewService(audiences, incidents, closures, connectors,
                mapper, Duration.ofMinutes(10), CLOCK);
        operations = service(CLOCK);
        recovery = new SafetyDispatchRecoveryRepository(jdbc, mapper, incidents);
    }

    @Test
    void screen20SafetyLifecycleIsDurableTenantScopedRecoverableAndExportable()
            throws Exception {
        assertThat(jdbc.queryForObject("""
                SELECT success FROM flyway_schema_history WHERE version='264'
                """, Boolean.class)).isTrue();
        Fixture fixture = fixture("PRIMARY", true);
        insertUnknownVisitor(fixture);
        txRun(() -> audiences.observePresence(new PresenceObservation(UUID.randomUUID(),
                fixture.tenantId(), SafetyAudienceRepository.sha256("user:" + SUBJECT), SUBJECT,
                "S**", fixture.siteId(), fixture.floorId(), fixture.zoneId(), "PRESENT",
                "ble:subject", "evidence:ble:subject", NOW.minusSeconds(30), NOW, 1)));

        ActivationPreviewRequest activationRequest = new ActivationPreviewRequest(
                "FIRE", Severity.CRITICAL, fixture.siteId(), List.of(fixture.floorId()),
                List.of(fixture.zoneId()), "Evacuate immediately", "Use the north exit",
                "Assembly lot A", List.of(DeliveryChannel.APP_PUSH, DeliveryChannel.SMS),
                List.of(), "Validate immutable audience before activation", true);
        List<ActivationPreviewResult> concurrent = concurrentActivationPreviews(
                fixture.tenantId(), activationRequest);
        assertThat(concurrent).extracting(result -> result.preview().activationPreviewId())
                .containsOnly(concurrent.getFirst().preview().activationPreviewId());
        assertThat(concurrent).extracting(result -> result.receipt().idempotentReplay())
                .containsExactlyInAnyOrder(false, true);
        ActivationPreview preview = concurrent.getFirst().preview();
        assertThat(preview.audience().finalTargetCount()).isEqualTo(1);
        assertThat(preview.audience().unknownCount()).isEqualTo(1);
        assertThat(preview.audience().members().stream().filter(AudienceMember::included)
                .findFirst().orElseThrow().sources())
                .contains(AudienceSourceKind.RESERVATION, AudienceSourceKind.ACTUAL_PRESENCE);
        String unknownKey = preview.audience().members().stream()
                .filter(AudienceMember::unknownIdentity).findFirst().orElseThrow()
                .subjectKeySha256();
        assertThat(unknownKey).isEqualTo(SafetyAudienceRepository.sha256(
                "guest:" + fixture.guestId()));
        assertThatThrownBy(() -> tx(() -> previews.activation(fixture.tenantId(), ACTOR,
                "activation-preview-key", changedActivation(activationRequest), "corr-preview")))
                .isInstanceOf(BaseException.class);

        ActivateIncidentRequest activateRequest = new ActivateIncidentRequest(
                preview.activationPreviewId(), "Activate confirmed emergency", true);
        List<IncidentCommandResult> activations = concurrentActivations(
                fixture.tenantId(), activateRequest);
        assertThat(activations).extracting(result -> result.incident().incidentId())
                .containsOnly(activations.getFirst().incident().incidentId());
        assertThat(activations).extracting(result -> result.receipt().idempotentReplay())
                .containsExactlyInAnyOrder(false, true);
        IncidentCommandResult activated = activations.getFirst();
        assertThatThrownBy(() -> tx(() -> operations.activate(fixture.tenantId(), ACTOR,
                "activate-key", new ActivateIncidentRequest(preview.activationPreviewId(),
                        "Different activation reason", true), "corr-conflict")))
                .isInstanceOf(BaseException.class);
        UUID incidentId = activated.incident().incidentId();
        assertThat(activated.incident().audience().members().stream()
                .filter(AudienceMember::unknownIdentity).findFirst().orElseThrow()
                .subjectKeySha256()).isEqualTo(unknownKey);
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM wp_safety_dispatch_batches
                 WHERE tenant_id=? AND incident_id=? AND batch_kind='ACTIVATION'
                """, Integer.class, fixture.tenantId(), incidentId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_safety_dispatch_attempts a
                  JOIN wp_safety_dispatch_batches b USING (tenant_id,dispatch_batch_id)
                 WHERE b.tenant_id=? AND b.incident_id=? AND b.batch_kind='ACTIVATION'
                """, Integer.class, fixture.tenantId(), incidentId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_safety_outbox WHERE tenant_id=?
                """, Integer.class, fixture.tenantId())).isPositive();

        verifyConnectorTruth(fixture.tenantId());
        verifyOfflineAndProviderRecovery(fixture.tenantId(), incidentId);

        MessageRequest firstMessage = new MessageRequest(1, null,
                "Proceed to assembly lot A", "Broadcast assembly instruction", true);
        MessageCommandResult originalMessage = tx(() -> operations.adminMessage(
                fixture.tenantId(), ACTOR, incidentId, "message-key-1", firstMessage,
                "corr-message-1"));
        tx(() -> operations.adminMessage(fixture.tenantId(), ACTOR, incidentId,
                "message-key-2", new MessageRequest(1, null, "Await headcount",
                        "Broadcast follow-up", true), "corr-message-2"));
        MessageCommandResult replayedMessage = tx(() -> operations.adminMessage(
                fixture.tenantId(), ACTOR, incidentId, "message-key-1", firstMessage,
                "corr-ignored"));
        assertThat(replayedMessage.message().messageId())
                .isEqualTo(originalMessage.message().messageId());
        assertThat(replayedMessage.message().maskedBody())
                .isEqualTo("Proceed to assembly lot A");

        ScopeRevisionPreviewRequest scopeRequest = new ScopeRevisionPreviewRequest(1,
                List.of(fixture.floorId()), List.of(fixture.zoneId()), "Evacuate and report",
                List.of(), "Refresh scope from all sources", true);
        ScopePreviewCommandResult scope = tx(() -> previews.scope(fixture.tenantId(), ACTOR,
                incidentId, "scope-preview-key", scopeRequest, "corr-scope-preview"));
        ScopePreviewCommandResult scopeReplay = tx(() -> previews.scope(fixture.tenantId(), ACTOR,
                incidentId, "scope-preview-key", scopeRequest, "corr-ignored"));
        assertThat(scopeReplay.preview().scopeRevisionId())
                .isEqualTo(scope.preview().scopeRevisionId());
        assertThat(scopeReplay.receipt().idempotentReplay()).isTrue();
        assertThatThrownBy(() -> tx(() -> previews.scope(fixture.tenantId(), ACTOR, incidentId,
                "scope-preview-key", new ScopeRevisionPreviewRequest(1,
                        List.of(fixture.floorId()), List.of(fixture.zoneId()), "Changed",
                        List.of(), "Refresh scope from all sources", true), "corr")))
                .isInstanceOf(BaseException.class);
        IncidentCommandResult revised = tx(() -> operations.applyScope(fixture.tenantId(), ACTOR,
                incidentId, "scope-apply-key", new ApplyScopeRevisionRequest(
                        scope.preview().scopeRevisionId(), 1, "Apply verified scope", true),
                "corr-scope-apply"));
        assertThat(revised.incident().version()).isEqualTo(2);
        assertThatThrownBy(() -> tx(() -> previews.scope(fixture.tenantId(), ACTOR, incidentId,
                "scope-stale-key", scopeRequest, "corr-stale")))
                .isInstanceOf(BaseException.class);

        AudienceMember subject = revised.incident().audience().members().stream()
                .filter(AudienceMember::included).findFirst().orElseThrow();
        AssemblyCommandResult assembly = tx(() -> operations.confirmAssembly(
                fixture.tenantId(), ACTOR, incidentId, "assembly-key",
                new AssemblyConfirmationRequest(2, subject.subjectKeySha256(), SUBJECT, true,
                        NOW, "camera:assembly:A", 0, "Observed at assembly point", true),
                "corr-assembly"));
        assertThat(assembly.confirmation().confirmed()).isTrue();
        assertThat(tx(() -> operations.confirmAssembly(fixture.tenantId(), ACTOR, incidentId,
                "assembly-key", new AssemblyConfirmationRequest(2,
                        subject.subjectKeySha256(), SUBJECT, true, NOW, "camera:assembly:A", 0,
                        "Observed at assembly point", true), "corr-ignored"))
                .receipt().idempotentReplay()).isTrue();

        ClosurePreviewRequest closureRequest = new ClosurePreviewRequest(2,
                "Assess remaining SOS, responses and delivery failures", true);
        ClosurePreviewCommandResult closurePreview = tx(() -> previews.closure(
                fixture.tenantId(), ACTOR, incidentId, "closure-preview-key",
                closureRequest, "corr-closure-preview"));
        assertThat(closurePreview.preview().warnings())
                .contains("NO_RESPONSE_REMAINS", "DELIVERY_FAILURE_OR_UNKNOWN_REMAINS")
                .doesNotContain("ASSEMBLY_CONFIRMATION_PENDING");
        assertThat(tx(() -> previews.closure(fixture.tenantId(), ACTOR, incidentId,
                "closure-preview-key", closureRequest, "corr-ignored"))
                .receipt().idempotentReplay()).isTrue();
        assertThatThrownBy(() -> tx(() -> previews.closure(fixture.tenantId(), ACTOR, incidentId,
                "closure-preview-key", new ClosurePreviewRequest(2, "Different", true), "corr")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> tx(() -> previews.closure(fixture.tenantId(), ACTOR, incidentId,
                "closure-stale-key", new ClosurePreviewRequest(1, "Stale", true), "corr")))
                .isInstanceOf(BaseException.class);

        ClosureRequestInput selfApproval = new ClosureRequestInput(
                closurePreview.preview().closurePreviewId(), 2, ACTOR,
                "Emergency stabilized", "Security follow-up", "Request closure", true);
        assertThatThrownBy(() -> tx(() -> operations.requestClosure(fixture.tenantId(), ACTOR,
                incidentId, "closure-self-key", selfApproval, "corr-self")))
                .isInstanceOf(BaseException.class);
        ClosureCommandResult requested = tx(() -> operations.requestClosure(
                fixture.tenantId(), ACTOR, incidentId, "closure-request-key",
                new ClosureRequestInput(closurePreview.preview().closurePreviewId(), 2, APPROVER,
                        "Emergency stabilized", "Security follow-up and welfare calls",
                        "Request independent closure approval", true), "corr-close-request"));
        IncidentCommandResult closed = tx(() -> operations.approveClosure(
                fixture.tenantId(), APPROVER, incidentId, requested.closure().closureRequestId(),
                "closure-approve-key", new ClosureApprovalInput(3, 1, true,
                        "Reviewed residual SOS and follow-up plan", "Approve closure", true),
                "corr-close-approve"));
        assertThat(closed.incident().state()).isEqualTo(IncidentState.CLOSED);
        assertThat(closed.incident().version()).isEqualTo(4);
        ClosureCommandResult closureReplay = tx(() -> operations.requestClosure(
                fixture.tenantId(), ACTOR, incidentId, "closure-request-key",
                new ClosureRequestInput(closurePreview.preview().closurePreviewId(), 2, APPROVER,
                        "Emergency stabilized", "Security follow-up and welfare calls",
                        "Request independent closure approval", true), "corr-ignored"));
        assertThat(closureReplay.closure().closureRequestId())
                .isEqualTo(requested.closure().closureRequestId());
        assertThat(closureReplay.closure().state()).isEqualTo("APPROVED");
        assertThat(tx(() -> operations.report(fixture.tenantId(), incidentId)).summary())
                .containsEntry("assemblyConfirmed", 1);

        verifyExports(fixture.tenantId(), incidentId, closed.incident().version());
        Fixture other = fixture("OTHER", false);
        assertThatThrownBy(() -> operations.incident(other.tenantId(), incidentId))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wp_safety_closure_approvals(
                    closure_approval_id,tenant_id,closure_request_id,approver_user_id,
                    approved,approval_reason,approved_at) VALUES(?,?,?,?,TRUE,'cross tenant',?)
                """, UUID.randomUUID(), other.tenantId(), requested.closure().closureRequestId(),
                APPROVER, NOW)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_safety_audit_events
                 WHERE tenant_id=? AND correlation_id IS NOT NULL
                """, Integer.class, fixture.tenantId())).isGreaterThan(5);
    }

    @Test
    void providerSnapshotFencesConcurrentClaimsAndRecoversCrashByStableAttemptIdentity()
            throws Exception {
        assertThat(jdbc.queryForObject("""
                SELECT success FROM flyway_schema_history WHERE version='275'
                """, Boolean.class)).isTrue();
        Fixture fixture = fixture("PROVIDER_RECOVERY", true);
        txRun(() -> audiences.observePresence(new PresenceObservation(UUID.randomUUID(),
                fixture.tenantId(), SafetyAudienceRepository.sha256("user:" + SUBJECT), SUBJECT,
                "S**", fixture.siteId(), fixture.floorId(), fixture.zoneId(), "PRESENT",
                "ble:provider-recovery", "evidence:provider-recovery", NOW.minusSeconds(20),
                NOW, 1)));
        ActivationPreviewResult preview = tx(() -> previews.activation(fixture.tenantId(), ACTOR,
                "provider-recovery-preview", new ActivationPreviewRequest(
                        "FIRE", Severity.CRITICAL, fixture.siteId(), List.of(fixture.floorId()),
                        List.of(fixture.zoneId()), "Evacuate", "Use the north exit", "Lot A",
                        List.of(DeliveryChannel.APP_PUSH), List.of(),
                        "Exercise provider recovery", true), "corr-provider-preview"));
        tx(() -> operations.activate(fixture.tenantId(), ACTOR, "provider-recovery-activate",
                new ActivateIncidentRequest(preview.preview().activationPreviewId(),
                        "Confirmed activation", true), "corr-provider-activate"));

        DispatchWorkRow work = incidents.queuedDispatches(20).stream()
                .filter(item -> item.tenantId() == fixture.tenantId()).findFirst().orElseThrow();
        SafetyDispatchProvider.ProviderContext binding = new SafetyDispatchProvider.ProviderContext(
                DeliveryChannel.APP_PUSH, "SAFETY_RECOVERY", 12,
                "secret-manager://workplace/safety-recovery/v12");
        CountDownLatch start = new CountDownLatch(1);
        List<Boolean> claims;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Supplier<Boolean> claim = () -> tx(() -> incidents.claimDispatch(
                    fixture.tenantId(), work.attemptId(), binding, NOW,
                    NOW.minusMinutes(2)));
            Future<Boolean> first = executor.submit(() -> { start.await(); return claim.get(); });
            Future<Boolean> second = executor.submit(() -> { start.await(); return claim.get(); });
            start.countDown();
            claims = List.of(first.get(), second.get());
        }
        assertThat(claims).containsExactlyInAnyOrder(true, false);
        assertThat(jdbc.queryForMap("""
                SELECT provider_code,provider_configuration_version,
                       provider_credential_reference
                  FROM wp_safety_dispatch_attempts
                 WHERE tenant_id=? AND dispatch_attempt_id=?
                """, fixture.tenantId(), work.attemptId()))
                .containsEntry("provider_code", "SAFETY_RECOVERY")
                .containsEntry("provider_configuration_version", 12L)
                .containsEntry("provider_credential_reference",
                        "secret-manager://workplace/safety-recovery/v12");

        CrashRecoveryProvider provider = new CrashRecoveryProvider();
        SafetyDispatchService service = new SafetyDispatchService(incidents, recovery,
                connectors, List.of(provider), transaction, null, Duration.ofMinutes(2), CLOCK);
        DispatchResult accepted = provider.dispatch(new DispatchRequest(
                work.attemptId(), work.tenantId(), work.incidentId(), work.channel(),
                work.subjectKey(), work.subjectUserId(), work.severity(), work.message(),
                work.safetyAction(), binding));
        assertThat(accepted.state()).isEqualTo(AttemptState.DELIVERED);
        assertThat(provider.dispatchCalls).hasValue(1);

        jdbc.update("""
                UPDATE wp_safety_dispatch_attempts
                   SET dispatch_started_at=?,updated_at=?
                 WHERE tenant_id=? AND dispatch_attempt_id=?
                """, NOW.minusMinutes(3), NOW.minusMinutes(3),
                fixture.tenantId(), work.attemptId());
        assertThat(incidents.dispatchable(20, NOW.minusMinutes(2))).noneMatch(
                item -> item.attemptId().equals(work.attemptId()));
        assertThat(tx(() -> recovery.recoverStaleDispatches(
                20, NOW.minusMinutes(2), NOW))).isEqualTo(1);
        assertThat(provider.dispatchCalls).hasValue(1);
        assertThat(jdbc.queryForObject("""
                SELECT attempt_state FROM wp_safety_dispatch_attempts
                 WHERE tenant_id=? AND dispatch_attempt_id=?
                """, String.class, fixture.tenantId(), work.attemptId()))
                .isEqualTo("RESULT_UNKNOWN");
        RecoveryWorkRow recoveryWork = recovery.pending(20, NOW).stream()
                .filter(item -> item.attemptId().equals(work.attemptId()))
                .findFirst().orElseThrow();
        assertThat(recoveryWork.providerOperationReference()).isNull();
        assertThat(recoveryWork.providerContext()).isEqualTo(binding);
        assertThat(service.reconcile(recoveryWork)).isTrue();
        assertThat(provider.dispatchCalls).hasValue(1);
        assertThat(provider.lookupCalls).hasValue(1);
        assertThat(jdbc.queryForObject("""
                SELECT attempt_state FROM wp_safety_dispatch_attempts
                 WHERE tenant_id=? AND dispatch_attempt_id=?
                """, String.class, fixture.tenantId(), work.attemptId()))
                .isEqualTo("DELIVERED");
    }

    private static void verifyConnectorTruth(long tenantId) {
        assertThat(tx(() -> connectors.truth(tenantId)))
                .allMatch(truth -> truth.state() == ConnectorTruthState.NOT_CONFIGURED);
        ConnectorConfigurationRequest configuration = new ConnectorConfigurationRequest(
                ConnectorKind.EBS, "CONTROLLED_EBS", 2, 0, true,
                "Bind controlled EBS provider", true);
        List<ConnectorCommandResult> configuredCommands = concurrentConnectorConfigurations(
                tenantId, configuration);
        assertThat(configuredCommands).extracting(result -> result.receipt().idempotentReplay())
                .containsExactlyInAnyOrder(false, true);
        ConnectorCommandResult configured = configuredCommands.getFirst();
        assertThat(configured.connector().state())
                .isEqualTo(ConnectorTruthState.CONFIGURED_UNVERIFIED);
        assertThatThrownBy(() -> tx(() -> connectors.configure(tenantId, ACTOR,
                "connector-key", new ConnectorConfigurationRequest(ConnectorKind.EBS,
                        "DIFFERENT_EBS", 2, 0, true, "Different binding", true), "corr")))
                .isInstanceOf(BaseException.class);
        tx(() -> connectors.observe(new ConnectorObservation(tenantId, ConnectorKind.EBS,
                "CONTROLLED_EBS", 1, ProviderReportedState.READY, "evidence:mismatch",
                NOW.minusSeconds(5), NOW, NOW, null)));
        assertThat(tx(() -> connectors.truth(tenantId)).stream()
                .filter(value -> value.kind() == ConnectorKind.EBS).findFirst().orElseThrow()
                .state()).isEqualTo(ConnectorTruthState.CONFIGURED_UNVERIFIED);
        tx(() -> connectors.observe(new ConnectorObservation(tenantId, ConnectorKind.EBS,
                "CONTROLLED_EBS", 2, ProviderReportedState.READY, "evidence:verified",
                NOW.minusSeconds(5), NOW, NOW, null)));
        assertThat(tx(() -> connectors.truth(tenantId)).stream()
                .filter(value -> value.kind() == ConnectorKind.EBS).findFirst().orElseThrow()
                .state()).isEqualTo(ConnectorTruthState.READY);
    }

    private static void verifyOfflineAndProviderRecovery(long tenantId, UUID incidentId) {
        List<DispatchWorkRow> queued = incidents.queuedDispatches(20).stream()
                .filter(work -> work.tenantId() == tenantId && work.incidentId().equals(incidentId))
                .toList();
        DispatchWorkRow app = queued.stream()
                .filter(work -> work.channel() == DeliveryChannel.APP_PUSH).findFirst().orElseThrow();
        DispatchWorkRow sms = queued.stream()
                .filter(work -> work.channel() == DeliveryChannel.SMS).findFirst().orElseThrow();
        UUID batchId = jdbc.queryForObject("""
                SELECT dispatch_batch_id FROM wp_safety_dispatch_attempts
                 WHERE tenant_id=? AND dispatch_attempt_id=?
                """, UUID.class, tenantId, app.attemptId());
        int initialVersion = jdbc.queryForObject("""
                SELECT version FROM wp_safety_dispatch_batches
                 WHERE tenant_id=? AND dispatch_batch_id=?
                """, Integer.class, tenantId, batchId);
        assertThat(tx(() -> incidents.markOffline(
                tenantId, app.attemptId(), "NOT_CLAIMED", NOW))).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT version FROM wp_safety_dispatch_batches
                 WHERE tenant_id=? AND dispatch_batch_id=?
                """, Integer.class, tenantId, batchId)).isEqualTo(initialVersion);
        assertThat(tx(() -> incidents.claimDispatch(tenantId, app.attemptId(), NOW))).isTrue();
        assertThat(tx(() -> incidents.markOffline(
                tenantId, app.attemptId(), "PROVIDER_OFFLINE", NOW))).isTrue();
        int afterOffline = jdbc.queryForObject("""
                SELECT version FROM wp_safety_dispatch_batches
                 WHERE tenant_id=? AND dispatch_batch_id=?
                """, Integer.class, tenantId, batchId);
        assertThat(tx(() -> incidents.markOffline(
                tenantId, app.attemptId(), "DUPLICATE", NOW))).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT version FROM wp_safety_dispatch_batches
                 WHERE tenant_id=? AND dispatch_batch_id=?
                """, Integer.class, tenantId, batchId)).isEqualTo(afterOffline);
        for (int retry = 1; retry < 3; retry++) {
            OffsetDateTime retryAt = NOW.plusMinutes(retry);
            assertThat(tx(() -> incidents.claimDispatch(tenantId, app.attemptId(), retryAt))).isTrue();
            assertThat(tx(() -> incidents.markOffline(
                    tenantId, app.attemptId(), "PROVIDER_OFFLINE", retryAt))).isTrue();
        }
        assertThat(jdbc.queryForObject("""
                SELECT offline_retry_count FROM wp_safety_dispatch_attempts
                 WHERE tenant_id=? AND dispatch_attempt_id=?
                """, Integer.class, tenantId, app.attemptId())).isEqualTo(3);
        assertThat(incidents.queuedDispatches(100)).noneMatch(
                work -> work.attemptId().equals(app.attemptId()));

        ControlledProvider provider = new ControlledProvider();
        SafetyDispatchService dispatch = new SafetyDispatchService(incidents, recovery,
                connectors, List.of(provider), transaction, CLOCK);
        assertThat(dispatch.dispatch(sms)).isTrue();
        assertThat(provider.dispatchCalls).hasValue(1);
        assertThat(jdbc.queryForObject("""
                SELECT attempt_state FROM wp_safety_dispatch_attempts
                 WHERE tenant_id=? AND dispatch_attempt_id=?
                """, String.class, tenantId, sms.attemptId())).isEqualTo("RESULT_UNKNOWN");
        jdbc.update("""
                UPDATE wp_safety_dispatch_attempts SET next_reconcile_at=?
                 WHERE tenant_id=? AND dispatch_attempt_id=?
                """, NOW.minusSeconds(1), tenantId, sms.attemptId());
        RecoveryWorkRow work = recovery.pending(10, NOW).stream()
                .filter(row -> row.attemptId().equals(sms.attemptId())).findFirst().orElseThrow();
        assertThat(dispatch.reconcile(work)).isTrue();
        assertThat(provider.dispatchCalls).hasValue(1);
        assertThat(provider.lookupCalls).hasValue(1);
        assertThat(jdbc.queryForObject("""
                SELECT attempt_state FROM wp_safety_dispatch_attempts
                 WHERE tenant_id=? AND dispatch_attempt_id=?
                """, String.class, tenantId, sms.attemptId())).isEqualTo("DELIVERED");
    }

    private static void verifyExports(long tenantId, UUID incidentId, long version)
            throws Exception {
        GuardedExportRequest csvRequest = new GuardedExportRequest(version, ExportFormat.CSV,
                "Regulator, \"review\"", "Provide signed incident evidence", true);
        ExportCommandResult csv = tx(() -> operations.createExport(tenantId, ACTOR, incidentId,
                "export-csv-key", csvRequest, "corr-export-csv",
                DECISION));
        ExportContent csvContent = tx(() -> operations.export(
                tenantId, ACTOR, csv.export().exportId(), "corr-download-csv"));
        String text = new String(csvContent.payload(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("field,value\r\n")
                .contains("\"purpose\",\"Regulator, \"\"review\"\"\"")
                .contains("requestedBy").contains("stepUpDecision").contains("generatedAt");
        assertThat(csv.export().sha256())
                .isEqualTo(SafetyRepositorySupport.sha256(csvContent.payload()));
        assertThat(csv.export().requestedBy()).isEqualTo(ACTOR);
        assertThat(csv.export().correlationId()).isEqualTo("corr-export-csv");
        assertThat(csv.export().stepUpEvidence())
                .isEqualTo(DECISION);
        assertThat(tx(() -> operations.createExport(tenantId, ACTOR, incidentId,
                "export-csv-key", csvRequest, "ignored",
                DECISION)).receipt().idempotentReplay()).isTrue();

        GuardedExportRequest pdfRequest = new GuardedExportRequest(version, ExportFormat.PDF,
                "Post incident review", "Archive signed evidence", true);
        ExportCommandResult pdf = tx(() -> operations.createExport(tenantId, ACTOR, incidentId,
                "export-pdf-key", pdfRequest, "corr-export-pdf",
                DECISION));
        ExportContent pdfContent = tx(() -> operations.export(
                tenantId, ACTOR, pdf.export().exportId(), "corr-download-pdf"));
        try (var document = Loader.loadPDF(pdfContent.payload())) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(new PDFTextStripper().getText(document))
                    .contains("purpose: Post incident review", "requestedBy: " + ACTOR,
                            "stepUpDecision: " + DECISION,
                            "correlationId: corr-export-pdf");
        }
        assertThat(jdbc.queryForObject("""
                SELECT snapshot->>'purpose' FROM wp_safety_audit_events
                 WHERE tenant_id=? AND resource_id=? AND action='safety.export.created'
                """, String.class, tenantId, pdf.export().exportId()))
                .isEqualTo("Post incident review");
        assertThat(jdbc.queryForObject("""
                SELECT snapshot->>'stepUpEvidence' FROM wp_safety_audit_events
                 WHERE tenant_id=? AND resource_id=? AND action='safety.export.created'
                """, String.class, tenantId, pdf.export().exportId())).isEqualTo(DECISION);
        assertThat(jdbc.queryForObject("""
                SELECT actor_user_id FROM wp_safety_audit_events
                 WHERE tenant_id=? AND resource_id=? AND action='safety.export.created'
                """, Long.class, tenantId, pdf.export().exportId())).isEqualTo(ACTOR);
        assertThat(jdbc.queryForObject("""
                SELECT correlation_id FROM wp_safety_audit_events
                 WHERE tenant_id=? AND resource_id=? AND action='safety.export.created'
                """, String.class, tenantId, pdf.export().exportId()))
                .isEqualTo("corr-export-pdf");
        SafetyOperationsService future = service(Clock.fixed(
                FIXED.plus(Duration.ofHours(1)), ZoneOffset.UTC));
        assertThatThrownBy(() -> tx(() -> future.export(
                tenantId, ACTOR, pdf.export().exportId(), "corr-expired")))
                .isInstanceOf(BaseException.class);
    }

    private static List<ActivationPreviewResult> concurrentActivationPreviews(
            long tenantId, ActivationPreviewRequest request) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Supplier<ActivationPreviewResult> call = () -> tx(() -> previews.activation(
                    tenantId, ACTOR, "activation-preview-key", request, "corr-preview"));
            Future<ActivationPreviewResult> first = executor.submit(() -> {
                start.await();
                return call.get();
            });
            Future<ActivationPreviewResult> second = executor.submit(() -> {
                start.await();
                return call.get();
            });
            start.countDown();
            return List.of(first.get(), second.get());
        }
    }

    private static List<IncidentCommandResult> concurrentActivations(
            long tenantId, ActivateIncidentRequest request) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Supplier<IncidentCommandResult> call = () -> tx(() -> operations.activate(
                    tenantId, ACTOR, "activate-key", request, "corr-activate"));
            Future<IncidentCommandResult> first = executor.submit(() -> {
                start.await();
                return call.get();
            });
            Future<IncidentCommandResult> second = executor.submit(() -> {
                start.await();
                return call.get();
            });
            start.countDown();
            return List.of(first.get(), second.get());
        }
    }

    private static List<ConnectorCommandResult> concurrentConnectorConfigurations(
            long tenantId, ConnectorConfigurationRequest request) {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Supplier<ConnectorCommandResult> call = () -> tx(() -> connectors.configure(
                    tenantId, ACTOR, "connector-key", request, "corr-connector"));
            Future<ConnectorCommandResult> first = executor.submit(() -> {
                start.await();
                return call.get();
            });
            Future<ConnectorCommandResult> second = executor.submit(() -> {
                start.await();
                return call.get();
            });
            start.countDown();
            return List.of(first.get(), second.get());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static ActivationPreviewRequest changedActivation(ActivationPreviewRequest request) {
        return new ActivationPreviewRequest(request.incidentType(), request.severity(),
                request.siteId(), request.floorIds(), request.zoneIds(), "Changed message",
                request.safetyAction(), request.assemblyPoint(), request.channels(),
                request.excludedSubjectKeys(), request.reason(), true);
    }

    private static SafetyOperationsService service(Clock clock) {
        return new SafetyOperationsService(audiences, incidents, closures, connectors,
                new SafetyExportDocumentFactory(), mapper, Duration.ofMinutes(15), clock);
    }

    private static Fixture fixture(String suffix, boolean withBooking) {
        long tenantId = TENANTS.incrementAndGet();
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,
                    data_region,isolation_model,created_by,updated_by)
                VALUES(?,?,?,'Safety test','ACTIVE','kr','POOL',?,?)
                """, UUID.randomUUID(), tenantId, "safety-" + suffix + "-" + tenantId,
                ACTOR, ACTOR);
        jdbc.update("INSERT INTO wp_tenant_policies(tenant_id) VALUES(?)", tenantId);
        jdbc.update("""
                INSERT INTO wp_sites(site_id,tenant_id,site_code,name_ko,name_en)
                VALUES(?,?,?,'안전 테스트','Safety test')
                """, siteId, tenantId, "SAFETY_" + suffix + "_" + tenantId);
        jdbc.update("""
                INSERT INTO wp_floors(floor_id,tenant_id,site_id,floor_number,name_ko,name_en)
                VALUES(?,?,?,20,'20층','20F')
                """, floorId, tenantId, siteId);
        jdbc.update("""
                INSERT INTO wp_zones(zone_id,tenant_id,floor_id,zone_code,name_ko,name_en,zone_type,
                                     created_by,updated_by)
                VALUES(?,?,?,?,'안전 구역','Safety zone','GENERAL',?,?)
                """, zoneId, tenantId, floorId, "SAFETY_ZONE_" + tenantId, ACTOR, ACTOR);
        jdbc.update("""
                INSERT INTO wp_resources(
                    resource_id,tenant_id,floor_id,resource_code,name_ko,name_en,resource_type,
                    zone_id)
                VALUES(?,?,?,?, '안전실','Safety room','ROOM',?)
                """, resourceId, tenantId, floorId, "SAFETY_ROOM_" + tenantId, zoneId);
        if (withBooking) jdbc.update("""
                INSERT INTO wp_bookings(
                    tenant_id,resource_id,user_id,booked_for_display_name,purpose,
                    starts_at,ends_at,booking_status,policy_snapshot,policy_snapshot_hash,
                    require_check_in_snapshot,check_in_lead_minutes_snapshot,
                    auto_release_minutes_snapshot,booking_retention_days_snapshot)
                VALUES(?,?,?,'Safety User','Emergency exercise',?,?,'RESERVED','{}'::jsonb,
                       encode(digest('{}'::jsonb::text,'sha256'),'hex'),FALSE,15,0,365)
                """, tenantId, resourceId, SUBJECT, NOW.minusMinutes(30), NOW.plusMinutes(30));
        return new Fixture(tenantId, siteId, floorId, resourceId, zoneId, UUID.randomUUID());
    }

    private static void insertUnknownVisitor(Fixture fixture) {
        UUID previewId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_visit_access_zones(
                    zone_id,tenant_id,site_id,zone_code,name,access_level,
                    provider_mapping_reference,allowed_visit_types)
                VALUES(?,?,?,?,'Safety visitor zone','STANDARD','provider:zone','[\"VENDOR\"]'::jsonb)
                """, fixture.zoneId(), fixture.tenantId(), fixture.siteId(),
                "VISITOR_ZONE_" + fixture.tenantId());
        jdbc.update("""
                INSERT INTO wp_visit_previews(
                    preview_id,tenant_id,actor_user_id,reservation_authority,reservation_id,
                    reservation_version,visit_type,site_id,starts_at,ends_at,zone_ids,
                    guest_fingerprint,approval_required,nda_required,identity_verification_required,
                    minimum_collection_fields,visitor_truth_snapshot,access_truth_snapshot,
                    eligible,limitations,expires_at)
                VALUES(?,?,?,'WORKPLACE',?,0,'VENDOR',?,?,?,?::jsonb,?,FALSE,FALSE,FALSE,
                       '[]'::jsonb,'{}'::jsonb,'{}'::jsonb,TRUE,'[]'::jsonb,?)
                """, previewId, fixture.tenantId(), ACTOR, fixture.resourceId(),
                fixture.siteId(), NOW.minusMinutes(10), NOW.plusMinutes(50),
                mapper.valueToTree(List.of(fixture.zoneId())).toString(), "c".repeat(64),
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
        jdbc.update("""
                INSERT INTO wp_visits(
                    visit_id,tenant_id,requester_user_id,preview_id,reservation_authority,
                    reservation_id,reservation_version,visit_type,site_id,starts_at,ends_at,
                    visit_state,approval_required,updated_at)
                VALUES(?,?,?,?,'WORKPLACE',?,0,'VENDOR',?,?,?,'ARRIVED',FALSE,?)
                """, visitId, fixture.tenantId(), ACTOR, previewId, fixture.resourceId(),
                fixture.siteId(), NOW.minusMinutes(10), NOW.plusMinutes(50), NOW);
        jdbc.update("""
                INSERT INTO wp_visit_guests(
                    guest_id,tenant_id,visit_id,masked_label,purpose,
                    field_retention_expires_at,search_token_sha256)
                VALUES(?,?,?,'G**','Delivery','{}'::jsonb,NULL)
                """, fixture.guestId(), fixture.tenantId(), visitId);
        jdbc.update("""
                INSERT INTO wp_visit_zone_selections(tenant_id,visit_id,zone_id) VALUES(?,?,?)
                """, fixture.tenantId(), visitId, fixture.zoneId());
    }

    private static <T> T tx(Supplier<T> supplier) {
        return transaction.execute(ignored -> supplier.get());
    }

    private static void txRun(Runnable runnable) {
        transaction.executeWithoutResult(ignored -> runnable.run());
    }

    private record Fixture(long tenantId, UUID siteId, UUID floorId,
                           UUID resourceId, UUID zoneId, UUID guestId) { }

    private static final class ControlledProvider implements SafetyDispatchProvider {
        private final AtomicInteger dispatchCalls = new AtomicInteger();
        private final AtomicInteger lookupCalls = new AtomicInteger();

        @Override
        public boolean supports(DeliveryChannel channel) {
            return channel == DeliveryChannel.SMS;
        }

        @Override
        public DispatchResult dispatch(DispatchRequest request) {
            dispatchCalls.incrementAndGet();
            return new DispatchResult(AttemptState.RESULT_UNKNOWN,
                    "provider-operation-sms", "TIMEOUT", "accepted:sms");
        }

        @Override
        public DispatchResult lookupStatus(LookupRequest request) {
            lookupCalls.incrementAndGet();
            return new DispatchResult(AttemptState.DELIVERED,
                    request.providerOperationReference(), "DELIVERED", "receipt:sms");
        }
    }

    private static final class CrashRecoveryProvider implements SafetyDispatchProvider {
        private final AtomicInteger dispatchCalls = new AtomicInteger();
        private final AtomicInteger lookupCalls = new AtomicInteger();

        @Override
        public boolean supports(DeliveryChannel channel) {
            return channel == DeliveryChannel.APP_PUSH;
        }

        @Override
        public DispatchResult dispatch(DispatchRequest request) {
            dispatchCalls.incrementAndGet();
            return new DispatchResult(AttemptState.DELIVERED,
                    "provider-operation-recovered", "DELIVERED", "evidence:provider-accepted");
        }

        @Override
        public DispatchResult lookupStatus(LookupRequest request) {
            lookupCalls.incrementAndGet();
            return new DispatchResult(AttemptState.DELIVERED, "provider-operation-recovered",
                    "DELIVERED", "evidence:provider-recovered");
        }
    }
}

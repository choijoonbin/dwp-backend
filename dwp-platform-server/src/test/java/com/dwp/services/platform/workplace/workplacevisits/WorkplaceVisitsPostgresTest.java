package com.dwp.services.platform.workplace.workplacevisits;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitProviderPort.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceVisitsPostgresTest {
    private static final Instant FIXED = Instant.parse("2026-09-16T12:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
    private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);
    private static final long ACTOR = 17_001L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static WorkplaceVisitRepository repository;
    private static WorkplaceVisitService visits;
    private static WorkplaceVisitManagementService management;
    private static WorkplaceVisitRetentionService retention;
    private static WorkplaceVisitMaintenanceService maintenance;
    private static WorkplaceVisitProviderResultService providerResults;
    private static long nextTenant = 9_970_000L;

    @BeforeAll
    static void migrateAndBuildActualServices() {
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
        repository = new WorkplaceVisitRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        visits = new WorkplaceVisitService(repository, CLOCK, List.of(
                new ControlledGuestRefVerifier()));
        management = new WorkplaceVisitManagementService(repository, CLOCK);
        retention = new WorkplaceVisitRetentionService(repository, CLOCK);
        maintenance = new WorkplaceVisitMaintenanceService(repository, CLOCK);
        providerResults = new WorkplaceVisitProviderResultService(repository, CLOCK);
    }

    @Test
    void lifecycleUsesDurableProviderOutcomesAndSeparateMaskedProjections() {
        Fixture fixture = fixture(true, true, true);
        VisitPreviewRequest previewRequest = previewRequest(fixture);
        VisitPreview preview = tx(() -> visits.preview(
                fixture.tenant(), ACTOR, "preview-lifecycle", previewRequest,
                "corr-preview-lifecycle"));
        VisitPreview previewReplay = tx(() -> visits.preview(
                fixture.tenant(), ACTOR, "preview-lifecycle", previewRequest, "ignored"));
        assertThat(previewReplay.previewId()).isEqualTo(preview.previewId());
        VisitPreviewRequest changedPreview = new VisitPreviewRequest(previewRequest.reservation(),
                previewRequest.visitType(), previewRequest.siteId(), previewRequest.startsAt(),
                previewRequest.endsAt(), previewRequest.zoneIds(), previewRequest.guests(),
                "Changed preview reason", true);
        assertThatThrownBy(() -> tx(() -> visits.preview(fixture.tenant(), ACTOR,
                "preview-lifecycle", changedPreview, "corr-preview-conflict")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(preview.eligible()).isTrue();
        assertThat(preview.visitorProvider().state()).isEqualTo(ProviderTruthState.READY);
        assertThat(preview.accessProvider().state()).isEqualTo(ProviderTruthState.READY);

        CreateVisitRequest create = new CreateVisitRequest(preview.previewId(), 1,
                guests(), "Invite governed guests", true);
        VisitCommandResult created = tx(() -> visits.create(
                fixture.tenant(), ACTOR, "create-visit", create, "corr-create"));
        VisitCommandResult replay = tx(() -> visits.create(
                fixture.tenant(), ACTOR, "create-visit", create, "ignored"));
        assertThat(replay.receipt().replayed()).isTrue();
        assertThat(replay.receipt().commandId()).isEqualTo(created.receipt().commandId());
        assertThatThrownBy(() -> tx(() -> visits.create(fixture.tenant(), ACTOR,
                "create-visit", new CreateVisitRequest(preview.previewId(), 1, guests(),
                        "Changed fingerprint", true), "corr-conflict")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        UUID visitId = created.visit().visitId();
        RequesterVisit requester = tx(() -> visits.requesterVisit(
                fixture.tenant(), ACTOR, visitId, "corr-requester-read"));
        AdminVisit admin = tx(() -> visits.adminVisit(
                fixture.tenant(), ACTOR + 9, visitId, "corr-admin-read"));
        assertThat(requester.guests()).allSatisfy(guest -> assertThat(guest.opaqueRef()).isNull());
        assertThat(admin.guests()).extracting(GuestRefView::opaqueRef)
                .containsExactly("vault://guest/opaque-17001");

        VisitCommandResult invite = tx(() -> visits.sendInvitation(fixture.tenant(), ACTOR,
                visitId, "invite", command(created.visit().version(), "Send invitation"),
                "corr-invite"));
        assertThat(invite.visit().state()).isEqualTo(VisitState.INVITED);
        assertThat(invite.receipt().state()).isEqualTo(CommandState.ACCEPTED);
        UUID inviteOutbox = outboxId(fixture, visitId, "SEND_INVITATION");
        assertThat(outboxState(fixture, inviteOutbox)).isEqualTo("PENDING");

        FakeProviderPort port = new FakeProviderPort();
        port.invitation = success("evidence:invitation-17001");
        WorkplaceVisitProviderWorker worker = worker(port);
        assertThat(tx(() -> worker.processOne(fixture.tenant(), inviteOutbox))).isTrue();
        AdminVisit awaitingApproval = tx(() -> visits.adminVisit(
                fixture.tenant(), ACTOR + 9, visitId, "corr-after-invite"));
        assertThat(awaitingApproval.state()).isEqualTo(VisitState.APPROVAL_PENDING);

        AdminVisitCommandResult approved = tx(() -> visits.approve(fixture.tenant(), ACTOR + 9,
                visitId, "approve", new ApprovalCommand(awaitingApproval.version(), true,
                        "Approved by security", true), "corr-approve"));
        VisitCommandResult access = tx(() -> visits.requestAccess(fixture.tenant(), ACTOR,
                visitId, "access", command(approved.visit().version(), "Issue least access"),
                "corr-access"));
        assertThat(access.visit().state()).isEqualTo(VisitState.ACCESS_PENDING);
        assertThat(access.receipt().state()).isEqualTo(CommandState.ACCEPTED);
        UUID accessOutbox = outboxId(fixture, visitId, "REQUEST_ACCESS");
        port.access = success("evidence:access-17001");
        assertThat(tx(() -> worker.processOne(fixture.tenant(), accessOutbox))).isTrue();
        RequesterVisit ready = tx(() -> visits.requesterVisit(
                fixture.tenant(), ACTOR, visitId, "corr-ready"));
        assertThat(ready.state()).isEqualTo(VisitState.READY);
        assertThat(outboxState(fixture, accessOutbox)).isEqualTo("DELIVERED");

        KioskDevice device = createReadyDevice(fixture);
        WorkplaceVisitKioskService kiosk = new WorkplaceVisitKioskService(
                repository, management, CLOCK);
        VisitCommandResult arrived = tx(() -> kiosk.arrive(
                fixture.tenant(), fixture.deviceCredential(),
                visitId, "arrive", command(ready.version(), "Guest arrived"), "corr-arrive"));
        assertThat(arrived.visit().state()).isEqualTo(VisitState.ARRIVED);
        jdbc.update("""
                UPDATE wp_visits SET starts_at=?,ends_at=? WHERE tenant_id=? AND visit_id=?
                """, NOW.minusHours(2), NOW.minusHours(1), fixture.tenant(), visitId);
        assertThat(tx(() -> maintenance.markOverstays(fixture.tenant(), 10))).isOne();
        long overstayVersion = visits.adminVisit(
                fixture.tenant(), ACTOR + 9, visitId, "corr-overstay").version();
        VisitCommandResult checkedOut = tx(() -> kiosk.checkout(
                fixture.tenant(), fixture.deviceCredential(), visitId, "checkout",
                command(overstayVersion, "Guest left"), "corr-checkout"));
        assertThat(checkedOut.visit().state()).isEqualTo(VisitState.CHECKED_OUT);
        assertThat(device.state()).isEqualTo(KioskState.READY);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_visit_outbox
                 WHERE tenant_id=? AND visit_id=? AND operation_type='REVOKE_ACCESS'
                """, Long.class, fixture.tenant(), visitId)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_visit_audit_events
                 WHERE tenant_id=? AND visit_id=?
                """, Long.class, fixture.tenant(), visitId)).isGreaterThanOrEqualTo(8);
    }

    @Test
    void resultUnknownUsesStatusLookupWithoutRepeatingAccessMutation() {
        Fixture fixture = fixture(false, true, true);
        VisitPreview preview = tx(() -> visits.preview(
                fixture.tenant(), ACTOR, "preview-unknown", previewRequest(fixture),
                "corr-preview-unknown"));
        VisitCommandResult created = tx(() -> visits.create(fixture.tenant(), ACTOR,
                "create-unknown", new CreateVisitRequest(preview.previewId(), 1, guests(),
                        "Create status recovery visit", true), "corr-create-unknown"));
        UUID visitId = created.visit().visitId();
        tx(() -> visits.sendInvitation(fixture.tenant(), ACTOR, visitId, "invite-unknown",
                command(created.visit().version(), "Invite"), "corr-invite-unknown"));
        FakeProviderPort port = new FakeProviderPort();
        WorkplaceVisitProviderWorker worker = worker(port);
        port.invitation = success("evidence:invite-unknown");
        UUID invitation = outboxId(fixture, visitId, "SEND_INVITATION");
        tx(() -> worker.processOne(fixture.tenant(), invitation));
        RequesterVisit approved = tx(() -> visits.requesterVisit(
                fixture.tenant(), ACTOR, visitId, "corr-approved"));
        assertThat(approved.state()).isEqualTo(VisitState.APPROVED);

        tx(() -> visits.requestAccess(fixture.tenant(), ACTOR, visitId, "access-unknown",
                command(approved.version(), "Request access"), "corr-access-unknown"));
        UUID access = outboxId(fixture, visitId, "REQUEST_ACCESS");
        port.access = new ProviderOutcome(OutcomeState.RESULT_UNKNOWN,
                "operation:access-unknown", "PROVIDER_TIMEOUT");
        tx(() -> worker.processOne(fixture.tenant(), access));
        RequesterVisit unknown = tx(() -> visits.requesterVisit(
                fixture.tenant(), ACTOR, visitId, "corr-unknown"));
        assertThat(unknown.state()).isEqualTo(VisitState.RESULT_UNKNOWN);
        assertThat(unknown.recoveryByGetOnly()).isTrue();
        assertThat(outboxState(fixture, access)).isEqualTo("RESULT_UNKNOWN");

        AdminVisitCommandResult lookup = tx(() -> visits.retryAccess(
                fixture.tenant(), ACTOR + 9, visitId, "status-only",
                command(unknown.version(), "Query prior provider operation"), "corr-status"));
        assertThat(lookup.receipt().state()).isEqualTo(CommandState.ACCEPTED);
        UUID status = outboxId(fixture, visitId, "CHECK_PROVIDER_STATUS");
        assertThat(repository.outbox(fixture.tenant(), status).orElseThrow().relatedOutboxId())
                .isEqualTo(access);
        port.lookup = new ProviderOutcome(OutcomeState.RESULT_UNKNOWN,
                "operation:access-still-pending", "PROVIDER_PENDING");
        tx(() -> worker.processOne(fixture.tenant(), status));
        assertThat(outboxState(fixture, status)).isEqualTo("RETRY");
        port.lookup = success("evidence:access-reconciled");
        tx(() -> worker.processOne(fixture.tenant(), status));
        assertThat(port.accessDispatches).hasValue(1);
        assertThat(port.lookups).hasValue(2);
        assertThat(tx(() -> visits.requesterVisit(
                fixture.tenant(), ACTOR, visitId, "corr-recovered")).state())
                .isEqualTo(VisitState.READY);
    }

    @Test
    void legacyInFlightWithoutSnapshotNeverUsesCurrentBindingOrRedispatches() {
        Fixture fixture = fixture(false, true, true);
        VisitPreview preview = tx(() -> visits.preview(
                fixture.tenant(), ACTOR, "preview-legacy-unresolved", previewRequest(fixture),
                "corr-preview-legacy-unresolved"));
        VisitCommandResult created = tx(() -> visits.create(fixture.tenant(), ACTOR,
                "create-legacy-unresolved", new CreateVisitRequest(preview.previewId(), 1,
                        guests(), "Preserve ambiguous pre-snapshot delivery", true),
                "corr-create-legacy-unresolved"));
        UUID visitId = created.visit().visitId();
        tx(() -> visits.sendInvitation(fixture.tenant(), ACTOR, visitId,
                "invite-legacy-unresolved",
                command(created.visit().version(), "Legacy provider mutation"),
                "corr-invite-legacy-unresolved"));
        UUID invitation = outboxId(fixture, visitId, "SEND_INVITATION");
        jdbc.update("""
                UPDATE wp_visit_outbox
                   SET provider_kind=NULL,provider_code=NULL,
                       provider_configuration_version=NULL
                 WHERE tenant_id=? AND outbox_id=?
                """, fixture.tenant(), invitation);

        FakeProviderPort port = new FakeProviderPort();
        WorkplaceVisitProviderWorker worker = worker(port);
        assertThat(tx(() -> worker.processOne(fixture.tenant(), invitation))).isTrue();

        assertThat(port.invitationDispatches).hasValue(0);
        assertThat(outboxState(fixture, invitation)).isEqualTo("RESULT_UNKNOWN");
        UUID lookup = outboxId(fixture, visitId, "CHECK_PROVIDER_STATUS");
        assertThat(tx(() -> worker.processOne(fixture.tenant(), lookup))).isTrue();
        assertThat(port.lookups).hasValue(0);
        assertThat(outboxState(fixture, lookup)).isEqualTo("RETRY");
        assertThat(tx(() -> visits.requesterVisit(fixture.tenant(), ACTOR, visitId,
                "corr-legacy-remains-unknown")).state()).isEqualTo(VisitState.RESULT_UNKNOWN);
    }

    @Test
    void staleProcessingRecoversByStatusLookupWithoutRepeatingProviderMutation() {
        Fixture fixture = fixture(false, true, true);
        VisitPreview preview = tx(() -> visits.preview(
                fixture.tenant(), ACTOR, "preview-restart", previewRequest(fixture),
                "corr-preview-restart"));
        VisitCommandResult created = tx(() -> visits.create(fixture.tenant(), ACTOR,
                "create-restart", new CreateVisitRequest(preview.previewId(), 1, guests(),
                        "Create restart-safe visit", true), "corr-create-restart"));
        UUID visitId = created.visit().visitId();
        tx(() -> visits.sendInvitation(fixture.tenant(), ACTOR, visitId, "invite-restart",
                command(created.visit().version(), "Invite once"), "corr-invite-restart"));
        UUID invitation = outboxId(fixture, visitId, "SEND_INVITATION");
        assertThat(repository.claimOutbox(fixture.tenant(), invitation,
                NOW.minusMinutes(10))).isTrue();

        FakeProviderPort port = new FakeProviderPort();
        port.lookup = success("evidence:invitation-reconciled");
        WorkplaceVisitProviderWorker worker = worker(port);
        WorkplaceVisitProviderMaintenance maintenance = new WorkplaceVisitProviderMaintenance(
                repository, providerResults, worker, true, 50,
                java.time.Duration.ofMinutes(2), CLOCK);

        assertThat(maintenance.recoverStale()).isOne();
        assertThat(outboxState(fixture, invitation)).isEqualTo("RESULT_UNKNOWN");
        assertThat(worker.processPending(500)).isPositive();

        assertThat(port.invitationDispatches).hasValue(0);
        assertThat(port.lookups).hasValue(1);
        assertThat(tx(() -> visits.requesterVisit(
                fixture.tenant(), ACTOR, visitId, "corr-restart-reconciled")).state())
                .isEqualTo(VisitState.APPROVED);
    }

    @Test
    void lateAccessSuccessPreservesCancellationAndQueuesSameBindingCompensation() {
        Fixture fixture = fixture(false, true, true);
        VisitPreview preview = tx(() -> visits.preview(
                fixture.tenant(), ACTOR, "preview-late-access", previewRequest(fixture),
                "corr-preview-late-access"));
        VisitCommandResult created = tx(() -> visits.create(fixture.tenant(), ACTOR,
                "create-late-access", new CreateVisitRequest(preview.previewId(), 1, guests(),
                        "Create late access visit", true), "corr-create-late-access"));
        UUID visitId = created.visit().visitId();
        tx(() -> visits.sendInvitation(fixture.tenant(), ACTOR, visitId, "invite-late-access",
                command(created.visit().version(), "Invite"), "corr-invite-late-access"));
        FakeProviderPort port = new FakeProviderPort();
        WorkplaceVisitProviderWorker worker = worker(port);
        tx(() -> worker.processOne(fixture.tenant(),
                outboxId(fixture, visitId, "SEND_INVITATION")));
        RequesterVisit approved = tx(() -> visits.requesterVisit(
                fixture.tenant(), ACTOR, visitId, "corr-approved-late-access"));
        VisitCommandResult access = tx(() -> visits.requestAccess(
                fixture.tenant(), ACTOR, visitId, "request-late-access",
                command(approved.version(), "Request access"), "corr-request-late-access"));
        UUID accessOutbox = outboxId(fixture, visitId, "REQUEST_ACCESS");
        assertThat(repository.claimOutbox(fixture.tenant(), accessOutbox, NOW)).isTrue();
        VisitCommandResult cancelled = tx(() -> visits.cancel(fixture.tenant(), ACTOR, visitId,
                "cancel-late-access", command(access.visit().version(), "Cancel while pending"),
                "corr-cancel-late-access"));

        tx(() -> {
            providerResults.apply(fixture.tenant(), accessOutbox,
                    success("evidence:late-access-issued"));
            return true;
        });

        assertThat(cancelled.visit().state()).isEqualTo(VisitState.CANCELLED);
        assertThat(tx(() -> visits.requesterVisit(fixture.tenant(), ACTOR, visitId,
                "corr-still-cancelled")).state()).isEqualTo(VisitState.CANCELLED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_visit_outbox
                 WHERE tenant_id=? AND visit_id=? AND operation_type='REVOKE_ACCESS'
                   AND provider_kind='ACCESS' AND provider_code='ACCESS_TEST'
                   AND provider_configuration_version=1
                """, Long.class, fixture.tenant(), visitId)).isEqualTo(2L);
        jdbc.update("""
                UPDATE wp_visit_provider_bindings
                   SET configuration_version=2,observed_configuration_version=2,
                       source_at=?,received_at=?,last_success_at=?,version=version+1
                 WHERE tenant_id=? AND provider_kind='ACCESS'
                """, NOW, NOW, NOW, fixture.tenant());
        List<UUID> revocations = jdbc.queryForList("""
                SELECT outbox_id FROM wp_visit_outbox
                 WHERE tenant_id=? AND visit_id=? AND operation_type='REVOKE_ACCESS'
                   AND delivery_state='PENDING'
                """, UUID.class, fixture.tenant(), visitId);
        revocations.forEach(outbox -> tx(() -> worker.processOne(fixture.tenant(), outbox)));
        assertThat(port.revocationDispatches).hasValue(2);
        assertThat(revocations).allSatisfy(outbox ->
                assertThat(outboxState(fixture, outbox)).isEqualTo("DELIVERED"));
    }

    @Test
    void immutableBindingSnapshotBlocksDispatchAfterProviderConfigurationChanges() {
        Fixture fixture = fixture(false, true, false);
        VisitPreview preview = tx(() -> visits.preview(
                fixture.tenant(), ACTOR, "preview-binding-change", previewRequest(fixture),
                "corr-preview-binding-change"));
        VisitCommandResult created = tx(() -> visits.create(fixture.tenant(), ACTOR,
                "create-binding-change", new CreateVisitRequest(preview.previewId(), 1, guests(),
                        "Create binding snapshot visit", true), "corr-create-binding-change"));
        UUID visitId = created.visit().visitId();
        tx(() -> visits.sendInvitation(fixture.tenant(), ACTOR, visitId, "invite-binding-change",
                command(created.visit().version(), "Invite with frozen binding"),
                "corr-invite-binding-change"));
        UUID invitation = outboxId(fixture, visitId, "SEND_INVITATION");
        jdbc.update("""
                UPDATE wp_visit_provider_bindings
                   SET configuration_version=2,observed_configuration_version=2,
                       source_at=?,received_at=?,last_success_at=?,version=version+1
                 WHERE tenant_id=? AND provider_kind='VISITOR'
                """, NOW, NOW, NOW, fixture.tenant());
        FakeProviderPort port = new FakeProviderPort();

        assertThat(tx(() -> worker(port).processOne(fixture.tenant(), invitation))).isTrue();

        assertThat(port.invitationDispatches).hasValue(0);
        assertThat(outboxState(fixture, invitation)).isEqualTo("DEAD_LETTER");
        RequesterVisit visit = tx(() -> visits.requesterVisit(
                fixture.tenant(), ACTOR, visitId, "corr-binding-failed"));
        assertThat(visit.state()).isEqualTo(VisitState.INVITED);
        assertThat(visit.timeline()).extracting(TimelineItem::detailCode)
                .contains("PROVIDER_BINDING_CHANGED_OR_NOT_READY");
    }

    @Test
    void providerUnconfiguredVersionTenantAndRetentionControlsFailClosed() {
        Fixture fixture = fixture(true, false, false);
        WorkplaceVisitService failClosedWithoutVerifier =
                new WorkplaceVisitService(repository, CLOCK, List.of());
        VisitPreview unverified = tx(() -> failClosedWithoutVerifier.preview(
                fixture.tenant(), ACTOR, "preview-unverified", previewRequest(fixture),
                "corr-preview-unverified"));
        assertThat(unverified.eligible()).isFalse();
        assertThat(unverified.limitations()).contains("GUEST_REF_VERIFIER_NOT_CONFIGURED");
        assertThatThrownBy(() -> tx(() -> failClosedWithoutVerifier.create(
                fixture.tenant(), ACTOR, "unverified-create",
                new CreateVisitRequest(unverified.previewId(), 1, guests(),
                        "Must not trust arbitrary opaque ref", true), "corr-unverified")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        VisitPreview preview = tx(() -> visits.preview(
                fixture.tenant(), ACTOR, "preview-manual", previewRequest(fixture),
                "corr-preview-manual"));
        assertThat(preview.limitations())
                .contains("VISITOR_NOT_CONFIGURED", "ACCESS_NOT_CONFIGURED");
        VisitCommandResult created = tx(() -> visits.create(fixture.tenant(), ACTOR,
                "create-manual", new CreateVisitRequest(preview.previewId(), 1, guests(),
                        "Create manual visit", true), "corr-manual"));
        UUID visitId = created.visit().visitId();
        assertThatThrownBy(() -> tx(() -> visits.sendInvitation(fixture.tenant(), ACTOR,
                visitId, "invite-manual", command(created.visit().version(), "Invite"),
                "corr-invite-manual")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(tx(() -> visits.requesterVisit(
                fixture.tenant(), ACTOR, visitId, "corr-still-preview")).state())
                .isEqualTo(VisitState.PREVIEWED);
        assertThatThrownBy(() -> tx(() -> visits.cancel(fixture.tenant(), ACTOR, visitId,
                "bad-version", command(99, "Cancel"), "corr-version")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> visits.requesterVisit(
                fixture.tenant(), ACTOR + 1, visitId, "corr-other-user"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        Fixture other = fixture(true, false, false);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wp_visit_zone_selections(tenant_id,visit_id,zone_id)
                VALUES(?,?,?)
                """, other.tenant(), visitId, fixture.zoneId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                UPDATE wp_visit_guests SET field_retention_expires_at=?::jsonb
                 WHERE tenant_id=? AND visit_id=?
                """, "{\"name\":\"2026-09-15T00:00:00Z\"}", fixture.tenant(), visitId);
        assertThat(tx(() -> retention.purgeExpiredGuestReferences(fixture.tenant(), 10))).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT opaque_guest_ref IS NULL AND search_token_sha256 IS NULL
                  FROM wp_visit_guests WHERE tenant_id=? AND visit_id=?
                """, Boolean.class, fixture.tenant(), visitId)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_visit_audit_events
                 WHERE tenant_id=? AND action='workplace.visit.retention.purged'
                """, Long.class, fixture.tenant())).isOne();
        List<String> visitColumns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema='public' AND table_name LIKE 'wp_visit_%'
                """, String.class);
        assertThat(visitColumns).doesNotContain("guest_snapshot").noneMatch(name -> name.matches(
                ".*(secret|credential|qr|nfc|badge|raw_name|email|phone|identity_document).*"));
        assertThat(jdbc.queryForObject("""
                SELECT guest_fingerprint FROM wp_visit_previews
                 WHERE tenant_id=? AND preview_id=?
                """, String.class, fixture.tenant(), preview.previewId()))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void managementApisCreateUpdateAndAttestVersionedOperationalData() {
        Fixture fixture = fixture(true, false, false);
        VisitPolicyRequest createPolicy = new VisitPolicyRequest("CONTRACTOR", true, true,
                true, LocalTime.of(8, 0), LocalTime.of(18, 0), List.of("name"),
                14, 0, true, "Create contractor policy", true);
        ManagementResult<VisitPolicy> policy = tx(() -> management.createPolicy(
                fixture.tenant(), ACTOR, "policy-create", createPolicy, "corr-policy"));
        ManagementResult<VisitPolicy> policyReplay = tx(() -> management.createPolicy(
                fixture.tenant(), ACTOR, "policy-create", createPolicy, "ignored"));
        assertThat(policyReplay.receipt().commandId()).isEqualTo(policy.receipt().commandId());
        assertThat(policyReplay.receipt().replayed()).isTrue();
        VisitPolicy updatedPolicy = tx(() -> management.updatePolicy(fixture.tenant(), ACTOR,
                policy.item().policyId(), "policy-update", new VisitPolicyRequest("CONTRACTOR",
                        true, true, true, LocalTime.of(7, 30), LocalTime.of(18, 30),
                        List.of("name"), 21, 1, true, "Extend policy hours", true),
                "corr-policy-update")).item();
        assertThat(updatedPolicy.version()).isEqualTo(2);
        assertThat(management.policyImpact(fixture.tenant(), updatedPolicy.policyId())
                .currentVersion()).isEqualTo(2);

        ManagementResult<AccessZone> zone = tx(() -> management.createZone(fixture.tenant(),
                ACTOR, "zone-create", new AccessZoneRequest(fixture.siteId(),
                        "CONTRACTOR_" + fixture.tenant(), "Contractor lobby", "ESCORTED",
                        "zone-map:contractor", List.of("CONTRACTOR"), 0, true,
                        "Create contractor zone", true), "corr-zone"));
        AccessZone updatedZone = tx(() -> management.updateZone(fixture.tenant(), ACTOR,
                zone.item().zoneId(), "zone-update", new AccessZoneRequest(fixture.siteId(),
                        zone.item().zoneCode(), "Contractor reception", "ESCORTED",
                        "zone-map:contractor-v2", List.of("CONTRACTOR"), 1, true,
                        "Update contractor zone", true), "corr-zone-update")).item();
        assertThat(updatedZone.version()).isEqualTo(2);

        ManagementResult<ProviderBinding> provider = tx(() -> management.createProvider(
                fixture.tenant(), ACTOR, "provider-create", new ProviderBindingRequest(
                        ProviderKind.VISITOR, "VISITOR_MANAGED", 1, "Security",
                        "Use visitor desk", 0, true, "Create visitor binding", true),
                "corr-provider"));
        ProviderBinding updatedProvider = tx(() -> management.updateProvider(fixture.tenant(),
                ACTOR, provider.item().bindingId(), "provider-update",
                new ProviderBindingRequest(ProviderKind.VISITOR, "VISITOR_MANAGED", 2,
                        "Security", "Use visitor desk", 1, true,
                        "Update provider version", true), "corr-provider-update")).item();
        ProviderBinding readyProvider = tx(() -> management.recordProviderEvidence(
                fixture.tenant(), ACTOR, updatedProvider.bindingId(), "provider-test",
                new ProviderEvidenceRequest(2, 2, ProviderTruthState.READY,
                        "evidence:managed-provider", NOW.minusMinutes(1), NOW,
                        NOW.minusMinutes(1), "Attest provider", true),
                "corr-provider-test")).item();
        assertThat(readyProvider.state()).isEqualTo(ProviderTruthState.READY);

        ManagementResult<KioskDevice> device = tx(() -> management.createDevice(
                fixture.tenant(), ACTOR, "managed-device-create", new KioskDeviceRequest(
                        "b".repeat(64), fixture.siteId(), updatedPolicy.policyId(), "privacy-v1",
                        0, true, "Register device", true), "corr-device-create"));
        KioskDevice updatedDevice = tx(() -> management.updateDevice(fixture.tenant(), ACTOR,
                device.item().deviceId(), "managed-device-update", new KioskDeviceRequest(
                        "c".repeat(64), fixture.siteId(), updatedPolicy.policyId(), "privacy-v2",
                        1, true, "Rotate device identity", true), "corr-device-update")).item();
        assertThat(updatedDevice.version()).isEqualTo(2);
        assertThat(management.policies(fixture.tenant())).hasSize(2);
        assertThat(management.zones(fixture.tenant())).hasSize(2);
        assertThat(management.providers(fixture.tenant())).singleElement()
                .satisfies(item -> assertThat(item.state()).isEqualTo(ProviderTruthState.READY));
        assertThat(management.devices(fixture.tenant())).singleElement()
                .satisfies(item -> assertThat(item.privacyNoticeVersion()).isEqualTo("privacy-v2"));
    }

    @Test
    void kioskCredentialIsHashedAndBoundToOneTenant() {
        Fixture owner = fixture(false, true, true);
        Fixture other = fixture(false, true, true);
        KioskDevice ready = createReadyDevice(owner);
        WorkplaceVisitKioskService kiosk = new WorkplaceVisitKioskService(
                repository, management, CLOCK);

        KioskDevice valid = tx(() -> kiosk.session(
                owner.tenant(), owner.deviceCredential()));
        KioskDevice forgedHash = tx(() -> kiosk.session(
                owner.tenant(), owner.deviceHash()));
        KioskDevice wrongTenant = tx(() -> kiosk.session(
                other.tenant(), owner.deviceCredential()));

        assertThat(valid.deviceId()).isEqualTo(ready.deviceId());
        assertThat(valid.state()).isEqualTo(KioskState.READY);
        assertThat(forgedHash.state()).isEqualTo(KioskState.UNREGISTERED);
        assertThat(wrongTenant.state()).isEqualTo(KioskState.UNREGISTERED);
        assertThatThrownBy(() -> tx(() -> kiosk.heartbeat(
                other.tenant(), owner.deviceCredential(), ready.deviceId(),
                "wrong-tenant-heartbeat",
                new KioskHeartbeatRequest(ready.version(), "privacy-v1", true, NOW),
                "corr-wrong-tenant")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void concurrentDuplicateCreateReturnsOneDurableReceipt() throws Exception {
        Fixture fixture = fixture(false, true, true);
        VisitPreview preview = tx(() -> visits.preview(fixture.tenant(), ACTOR,
                "preview-duplicate", previewRequest(fixture), "corr-preview-duplicate"));
        CreateVisitRequest request = new CreateVisitRequest(
                preview.previewId(), 1, guests(), "Create once", true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<VisitCommandResult> command = () -> {
                ready.countDown();
                start.await();
                return tx(() -> visits.create(fixture.tenant(), ACTOR,
                        "same-create-key", request, "corr-duplicate"));
            };
            Future<VisitCommandResult> first = executor.submit(command);
            Future<VisitCommandResult> second = executor.submit(command);
            ready.await();
            start.countDown();
            List<VisitCommandResult> results = List.of(first.get(), second.get());
            assertThat(results).extracting(result -> result.receipt().commandId())
                    .containsOnly(results.get(0).receipt().commandId());
            assertThat(results).filteredOn(result -> result.receipt().replayed()).hasSize(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM wp_visits WHERE tenant_id=? AND preview_id=?
                    """, Long.class, fixture.tenant(), preview.previewId())).isOne();
        }
    }

    @Test
    void parallelApprovalAllowsExactlyOneVersionWinner() throws Exception {
        Fixture fixture = fixture(true, true, true);
        VisitPreview preview = tx(() -> visits.preview(
                fixture.tenant(), ACTOR, "preview-parallel", previewRequest(fixture),
                "corr-preview-parallel"));
        UUID visitId = tx(() -> visits.create(fixture.tenant(), ACTOR, "parallel-create",
                new CreateVisitRequest(preview.previewId(), 1, guests(), "Create", true),
                "corr-create")).visit().visitId();
        long createdVersion = visits.adminVisit(fixture.tenant(), ACTOR, visitId, null).version();
        tx(() -> visits.sendInvitation(fixture.tenant(), ACTOR, visitId, "parallel-invite",
                command(createdVersion, "Invite"), "corr-invite"));
        FakeProviderPort port = new FakeProviderPort();
        port.invitation = success("evidence:parallel-invite");
        WorkplaceVisitProviderWorker worker = worker(port);
        tx(() -> worker.processOne(fixture.tenant(),
                outboxId(fixture, visitId, "SEND_INVITATION")));
        long version = visits.adminVisit(fixture.tenant(), ACTOR, visitId, null).version();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Object> one = () -> concurrentApproval(
                    fixture, visitId, version, "parallel-one", ready, start);
            Callable<Object> two = () -> concurrentApproval(
                    fixture, visitId, version, "parallel-two", ready, start);
            Future<Object> first = executor.submit(one);
            Future<Object> second = executor.submit(two);
            ready.await();
            start.countDown();
            List<Object> outcomes = List.of(first.get(), second.get());
            assertThat(outcomes).filteredOn(AdminVisitCommandResult.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(BaseException.class::isInstance).singleElement()
                    .satisfies(value -> assertThat(((BaseException) value).getErrorCode())
                            .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        }
        assertThat(visits.adminVisit(fixture.tenant(), ACTOR, visitId, null).state())
                .isEqualTo(VisitState.APPROVED);
    }

    private static Fixture fixture(boolean approvalRequired, boolean visitor, boolean access) {
        long tenant = ++nextTenant;
        UUID site = UUID.randomUUID();
        UUID floor = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        UUID policy = UUID.randomUUID();
        UUID zone = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,
                    data_region,isolation_model,created_by,updated_by)
                VALUES(?,?,?,'Visit test','ACTIVE','kr','POOL',?,?)
                """, UUID.randomUUID(), tenant, "workplace-visits-" + tenant, ACTOR, ACTOR);
        jdbc.update("INSERT INTO wp_tenant_policies(tenant_id) VALUES(?)", tenant);
        jdbc.update("""
                INSERT INTO wp_sites(site_id,tenant_id,site_code,name_ko,name_en)
                VALUES(?,?,?,'방문 테스트','Visit test')
                """, site, tenant, "VISIT_SITE_" + tenant);
        jdbc.update("""
                INSERT INTO wp_floors(floor_id,tenant_id,site_id,floor_number,name_ko,name_en)
                VALUES(?,?,?,17,'17층','17F')
                """, floor, tenant, site);
        jdbc.update("""
                INSERT INTO wp_resources(
                    resource_id,tenant_id,floor_id,resource_code,name_ko,name_en,resource_type)
                VALUES(?,?,?,?, '방문 회의실','Visit room','ROOM')
                """, resource, tenant, floor, "VISIT_ROOM_" + tenant);
        UUID booking = jdbc.queryForObject("""
                INSERT INTO wp_bookings(
                    tenant_id,resource_id,user_id,booked_for_display_name,starts_at,ends_at,
                    booking_status,policy_snapshot,policy_snapshot_hash,
                    require_check_in_snapshot,check_in_lead_minutes_snapshot,
                    auto_release_minutes_snapshot,booking_retention_days_snapshot)
                VALUES(?,?,?,'Visit host',?,?,'RESERVED','{}'::jsonb,
                       encode(digest('{}'::jsonb::text,'sha256'),'hex'),FALSE,15,0,365)
                RETURNING booking_id
                """, UUID.class, tenant, resource, ACTOR, NOW.plusDays(1),
                NOW.plusDays(1).plusHours(2));
        jdbc.update("""
                INSERT INTO wp_visit_policies(
                    policy_id,tenant_id,visit_type,approval_required,nda_required,
                    identity_verification_required,allowed_from,allowed_until,
                    minimum_collection_fields,retention_days)
                VALUES(?,?,'BUSINESS',?,TRUE,TRUE,'08:00','20:00','["name"]'::jsonb,30)
                """, policy, tenant, approvalRequired);
        jdbc.update("""
                INSERT INTO wp_visit_access_zones(
                    zone_id,tenant_id,site_id,zone_code,name,access_level,
                    provider_mapping_reference,allowed_visit_types)
                VALUES(?,?,?,?,'Reception','ESCORTED','zone-map:reception',
                       '["BUSINESS"]'::jsonb)
                """, zone, tenant, site, "RECEPTION_" + tenant);
        if (visitor) provider(tenant, ProviderKind.VISITOR, "VISITOR_TEST");
        if (access) provider(tenant, ProviderKind.ACCESS, "ACCESS_TEST");
        String deviceCredential = "kiosk-device-credential-material-" + tenant;
        return new Fixture(tenant, site, resource, booking, policy, zone, deviceCredential,
                WorkplaceVisitManagementService.identityHash(deviceCredential));
    }

    private static void provider(long tenant, ProviderKind kind, String code) {
        jdbc.update("""
                INSERT INTO wp_visit_provider_bindings(
                    binding_id,tenant_id,provider_kind,provider_code,configuration_version,
                    observed_configuration_version,reported_state,evidence_reference,
                    last_success_at,source_at,received_at,manual_owner,manual_procedure)
                VALUES(?,?,?,?,1,1,'READY',?, ?,?,?, 'Security','Use manual desk')
                """, UUID.randomUUID(), tenant, kind.name(), code,
                "evidence:" + kind.name().toLowerCase(), NOW.minusMinutes(1),
                NOW.minusMinutes(1), NOW);
    }

    private static VisitPreviewRequest previewRequest(Fixture fixture) {
        return new VisitPreviewRequest(new ReservationReference(
                ReservationAuthority.WORKPLACE, fixture.bookingId(), 0), "BUSINESS",
                fixture.siteId(), NOW.plusDays(1), NOW.plusDays(1).plusHours(2),
                List.of(fixture.zoneId()), guests(), "Preview governed visit", true);
    }

    private static List<GuestRefInput> guests() {
        return List.of(new GuestRefInput("vault://guest/opaque-17001", "K**",
                "Business meeting", Map.of("name", NOW.plusDays(20))));
    }

    private static VersionCommand command(long version, String reason) {
        return new VersionCommand(version, reason, true);
    }

    private static KioskDevice createReadyDevice(Fixture fixture) {
        ManagementResult<KioskDevice> created = tx(() -> management.createDevice(
                fixture.tenant(), ACTOR + 9, "device-create",
                new KioskDeviceRequest(fixture.deviceHash(), fixture.siteId(), fixture.policyId(),
                        "privacy-v1", 0, true, "Register kiosk", true), "corr-device"));
        WorkplaceVisitKioskService kiosk = new WorkplaceVisitKioskService(
                repository, management, CLOCK);
        return tx(() -> kiosk.heartbeat(fixture.tenant(), fixture.deviceCredential(),
                created.item().deviceId(), "heartbeat",
                new KioskHeartbeatRequest(created.item().version(), "privacy-v1", true, NOW),
                "corr-heartbeat"));
    }

    private static WorkplaceVisitProviderWorker worker(FakeProviderPort port) {
        return new WorkplaceVisitProviderWorker(repository, providerResults, List.of(port), CLOCK);
    }

    private static ProviderOutcome success(String evidence) {
        return new ProviderOutcome(OutcomeState.SUCCEEDED, evidence, null);
    }

    private static UUID outboxId(Fixture fixture, UUID visitId, String operation) {
        return repository.latestOutbox(fixture.tenant(), visitId, operation).orElseThrow().id();
    }

    private static String outboxState(Fixture fixture, UUID outboxId) {
        return repository.outbox(fixture.tenant(), outboxId).orElseThrow().deliveryState();
    }

    private static Object concurrentApproval(Fixture fixture, UUID visitId, long version,
                                             String key, CountDownLatch ready,
                                             CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            return tx(() -> visits.approve(fixture.tenant(), ACTOR + 9, visitId, key,
                    new ApprovalCommand(version, true, "Parallel approval", true), key));
        } catch (BaseException error) {
            return error;
        }
    }

    private static <T> T tx(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private static final class FakeProviderPort implements WorkplaceVisitProviderPort {
        private ProviderOutcome invitation = success("evidence:invitation-default");
        private ProviderOutcome access = success("evidence:access-default");
        private ProviderOutcome lookup = success("evidence:lookup-default");
        private final AtomicInteger invitationDispatches = new AtomicInteger();
        private final AtomicInteger accessDispatches = new AtomicInteger();
        private final AtomicInteger revocationDispatches = new AtomicInteger();
        private final AtomicInteger lookups = new AtomicInteger();

        @Override public boolean supports(ProviderKind kind, String providerCode) { return true; }

        @Override
        public ProviderOutcome dispatch(ProviderOperation operation) {
            if ("SEND_INVITATION".equals(operation.operationType())) {
                invitationDispatches.incrementAndGet();
                return invitation;
            }
            if ("REQUEST_ACCESS".equals(operation.operationType())) {
                accessDispatches.incrementAndGet();
                return access;
            }
            if ("REVOKE_ACCESS".equals(operation.operationType())) {
                revocationDispatches.incrementAndGet();
            }
            return success("evidence:provider-operation");
        }

        @Override
        public ProviderOutcome lookup(ProviderOperation originalOperation) {
            lookups.incrementAndGet();
            return lookup;
        }
    }

    private static final class ControlledGuestRefVerifier
            implements WorkplaceVisitGuestRefVerificationPort {
        @Override public boolean supports(String opaqueGuestRef) {
            return opaqueGuestRef.startsWith("vault://guest/");
        }

        @Override public Verification verify(VerificationRequest request) {
            boolean valid = request.tenantId() > 0
                    && request.fieldRetentionExpiresAt().values().stream()
                    .allMatch(expiry -> expiry.isAfter(NOW));
            return new Verification(valid, valid ? "evidence:guest-ref-verified" : null,
                    valid ? null : "GUEST_REF_INVALID");
        }
    }

    private record Fixture(long tenant, UUID siteId, UUID resourceId, UUID bookingId,
                           UUID policyId, UUID zoneId, String deviceCredential,
                           String deviceHash) { }
}

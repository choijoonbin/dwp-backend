package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordRetentionDtos.ControlInput;
import com.dwp.services.meeting.videomeeting.audit.MeetingRecordRetentionAuditRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MeetingRecordRetentionPostgresTest extends MeetingWorkspacePostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Override PostgreSQLContainer<?> postgres() { return POSTGRES; }
    private MeetingRecordDispositionRepository records;
    private MeetingRecordRetentionProperties properties;
    private MeetingRecordRetentionHealthRepository health;
    private MeetingRecordRetentionTransactions transactions;
    private MeetingRecordRetentionService worker;
    private MeetingRecordRetentionControlService controls;
    private MeetingRecordRetentionAuditRecorder retentionAudit;
    private UUID record;

    @BeforeEach
    void retention() {
        records = new MeetingRecordDispositionRepository(jdbc);
        properties = new MeetingRecordRetentionProperties();
        health = new MeetingRecordRetentionHealthRepository(jdbc);
        retentionAudit = spy(new MeetingRecordRetentionAuditRecorder(new AuditOutboxRecorder(
                new NamedParameterJdbcTemplate(dataSource), mapper, "dwp-meeting-server", "test", "test")));
        var guard = new MeetingRecordRetentionGuard(jdbc, records);
        controls = new MeetingRecordRetentionControlService(records, guard, properties,
                new MeetingWorkspaceCommands(jdbc, mapper), retentionAudit);
        var target = new MeetingRecordRetentionTransactions(health, records, guard,
                new MeetingRecordPurgeRepository(jdbc), retentionAudit);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        transactions = (MeetingRecordRetentionTransactions) proxy.getProxy();
        worker = new MeetingRecordRetentionService(properties, health, records, transactions);
        record = expiredRecord(1);
    }

    @Test
    void defaultDisabledAndMissingIndividualAuthorizationDeleteNothing() {
        long before = count("vm_meetings");
        assertThat(properties.isEnabled()).isFalse();
        assertThat(worker.ready()).isFalse();
        authorize(); publishAudit();
        assertThat(worker.purgeExpired()).isZero();
        assertThat(count("vm_meetings")).isEqualTo(before);
        own(() -> controls.update(record, input(1, true, false), "hold-before-enable"));
        properties.setEnabled(true);
        assertThat(worker.purgeExpired()).isZero();
        assertThat(count("vm_meetings")).isEqualTo(before);
        assertThat(count("vm_meeting_record_deletion_evidence")).isZero();
    }

    @Test
    void exactExpiredAuthorizedRecordPurgesAtomicallyAndReplaySurvivesParentDeletion() {
        UUID untouched = expiredRecord(1), otherTenant = expiredRecord(2);
        authorize(); publishAudit(); properties.setEnabled(true);
        long audits = count("sys_audit_outbox");
        assertThat(worker.purgeExpired()).isOne();
        assertThat(records.snapshot(1, record, false)).isNull();
        assertThat(records.snapshot(1, untouched, false)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vm_meetings WHERE tenant_id=2 AND meeting_id=?", Long.class, otherTenant)).isOne();
        assertThat(own(() -> controls.read(record)).state()).isEqualTo("PURGED");
        assertThat(own(() -> controls.update(record, input(0, false, true), "authorize-record-001")).state()).isEqualTo("PURGED");
        assertThat(count("vm_meeting_record_deletion_evidence")).isOne();
        assertThat(count("vm_meeting_record_dispositions")).isOne();
        assertThat(count("sys_audit_outbox")).isEqualTo(audits + 1);
        assertThat(count("vm_meeting_workspace_commands")).isOne();
        assertThat(worker.purgeExpired()).isZero();
        assertThat(worker.ready()).isTrue();
        assertThatThrownBy(() -> own(() -> controls.update(record, input(1, true, false), "after-purge-hold")))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void unexpiredAndLiveRecordsCannotBeAuthorized() {
        jdbc.update("UPDATE vm_meetings SET updated_at=clock_timestamp() WHERE meeting_id=?", record);
        assertThatThrownBy(this::authorize).isInstanceOf(BaseException.class);
        jdbc.update("UPDATE vm_meetings SET updated_at=clock_timestamp()-INTERVAL '1200 days', lifecycle_state='SCHEDULED' WHERE meeting_id=?", record);
        assertThatThrownBy(this::authorize).isInstanceOf(BaseException.class);
        assertThat(count("vm_meeting_record_dispositions")).isZero();
        assertThat(count("sys_audit_outbox")).isZero();
    }

    @Test
    void legalHoldRevokesAuthorizationAndContradictoryCommandsAreRejected() {
        authorize();
        var held = own(() -> controls.update(record, input(1, true, false), "record-hold-001"));
        assertThat(held.state()).isEqualTo("HELD");
        assertThat(held.purgeAuthorized()).isFalse();
        assertThatThrownBy(() -> own(() -> controls.update(record, input(2, true, true), "unsafe-hold-001")))
                .isInstanceOf(BaseException.class);
        publishAudit(); properties.setEnabled(true);
        assertThat(worker.purgeExpired()).isZero();
        assertThat(records.snapshot(1, record, false)).isNotNull();
    }

    @Test
    void meetingAndPolicySnapshotChangesRequireFreshAuthorization() {
        authorize(); publishAudit(); properties.setEnabled(true);
        jdbc.update("UPDATE vm_meetings SET version=version+1 WHERE meeting_id=?", record);
        assertBlocked("AUTHORIZATION_SNAPSHOT_CHANGED");
        jdbc.update("UPDATE vm_meetings SET version=version-1 WHERE meeting_id=?", record);
        jdbc.update("UPDATE vm_tenant_policies SET version=version+1 WHERE tenant_id=1");
        assertBlocked("AUTHORIZATION_SNAPSHOT_CHANGED");
    }

    @Test
    void authorizationAndRelatedAuditMustBeActuallyPublished() {
        authorize(); properties.setEnabled(true);
        assertBlocked("AUTHORIZATION_AUDIT_NOT_PUBLISHED");
        publishAudit();
        own(() -> { retentionAudit.control(1, record, 3, 999, false, false); return null; });
        assertBlocked("RELATED_AUDIT_NOT_PUBLISHED");
        publishAudit();
        assertThat(worker.purgeExpired()).isOne();
    }

    @Test
    void missingPreviouslyPublishedAuditIsNotGuessedAsPreserved() {
        authorize(); publishAudit(); properties.setEnabled(true);
        jdbc.update("DELETE FROM sys_audit_outbox WHERE event_id=?", records.control(1, record, false).auditId());
        assertBlocked("AUTHORIZATION_AUDIT_NOT_PUBLISHED");
    }

    @Test
    void unprovenDeletedArtifactOrRemainingLocatorCannotBePurged() {
        UUID artifact = artifact("RECORDING", "DELETED");
        authorize(); publishAudit(); properties.setEnabled(true);
        assertBlocked("EXTERNAL_ARTIFACT_DELETION_UNPROVEN");
        jdbc.update("UPDATE vm_meeting_artifacts SET artifact_state='UNAVAILABLE', object_key='private-object', storage_provider='S3' WHERE artifact_id=?", artifact);
        assertBlocked("EXTERNAL_ARTIFACT_DELETION_UNPROVEN");
    }

    @Test
    void activeOrUnresolvedProviderAndInvitationWorkBlocksDeletion() {
        jdbc.update("""
                INSERT INTO vm_meeting_invitation_outbox(event_id,tenant_id,meeting_id,event_type,aggregate_version,invitation_revision)
                VALUES (?,1,?,'MEETING_CANCELLED',0,1)
                """, UUID.randomUUID(), record);
        authorize(); publishAudit(); properties.setEnabled(true);
        assertBlocked("CONTENT_PROCESSING_ACTIVE_OR_UNRESOLVED");
        jdbc.update("UPDATE vm_meeting_invitation_outbox SET delivery_state='CANCELLED' WHERE meeting_id=?", record);
        jdbc.update("UPDATE vm_meetings SET provider='LIVEKIT' WHERE meeting_id=?", record);
        assertBlocked("PROVIDER_ROOM_DELETION_UNPROVEN");
    }

    @Test
    void reportLegalHoldAndUnshreddedCiphertextBlockParentPurge() {
        UUID report = report(true);
        authorize(); publishAudit(); properties.setEnabled(true);
        assertBlocked("REPORT_LEGAL_HOLD");
        jdbc.update("UPDATE vm_meeting_intelligence_reports SET legal_hold=FALSE WHERE report_id=?", report);
        assertBlocked("REPORT_DELETION_UNPROVEN");
        deleteReport(report);
        assertThat(worker.purgeExpired()).isOne();
        String evidence = jdbc.queryForObject("SELECT receipt_manifest::text FROM vm_meeting_record_deletion_evidence WHERE meeting_id=?", String.class, record);
        assertThat(evidence).contains(report.toString()).doesNotContain("ciphertext", "Private", "secret-transcript", "d".repeat(64));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vm_meeting_intelligence_deletions WHERE meeting_id=?", Long.class, record)).isZero();
    }

    @Test
    void auditInsertionFailureRollsBackParentChildrenEvidenceAndTombstone() {
        UUID report = report(false); deleteReport(report);
        authorize(); publishAudit(); properties.setEnabled(true);
        doThrow(new IllegalStateException("unavailable")).when(retentionAudit).purged(anyLong(), any(), anyString(), any(), any());
        assertThat(worker.purgeExpired()).isEqualTo(-1);
        assertThat(records.snapshot(1, record, false)).isNotNull();
        assertThat(records.control(1, record, false).purgedAt()).isNull();
        assertThat(count("vm_meeting_record_deletion_evidence")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vm_meeting_intelligence_deletions WHERE report_id=?", Long.class, report)).isOne();
        assertThat(worker.ready()).isFalse();
        assertThat(health.read().lastFailure()).isNotNull();
    }

    @Test
    void controlAuditFailureRollsBackAuthorizationAndCommandReceipt() {
        doThrow(new IllegalStateException("unavailable")).when(retentionAudit).control(anyLong(), any(), anyLong(), anyLong(), anyBoolean(), anyBoolean());
        assertThatThrownBy(this::authorize).isInstanceOf(IllegalStateException.class);
        assertThat(count("vm_meeting_record_dispositions")).isZero();
        assertThat(count("vm_meeting_workspace_commands")).isZero();
    }

    @Test
    void tenantPermissionSupportAndReplayAuthorityAreRechecked() {
        assertThatThrownBy(() -> as(2, 3, all(), () -> controls.read(record))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> as(1, 3, Set.of("APP.MEETINGS:VIEW"), () -> controls.read(record))).isInstanceOf(BaseException.class);
        authorize();
        assertThatThrownBy(() -> as(1, 3, Set.of("ADMIN.MEETINGS:VIEW"),
                () -> controls.update(record, input(0, false, true), "authorize-record-001"))).isInstanceOf(BaseException.class);
        MeetingRequestContext.set(new MeetingRequestContext.Subject(3, 1, null, "Support", Set.of("PROVIDER_SUPPORT"), all(), Set.of()));
        try { assertThatThrownBy(() -> controls.read(record)).isInstanceOf(BaseException.class); }
        finally { MeetingRequestContext.clear(); }
        assertThat(count("vm_meeting_record_dispositions")).isOne();
    }

    @Test
    void idempotencyBindsActorTargetPayloadAndReplaysCurrentControl() {
        authorize();
        assertThatThrownBy(() -> own(() -> controls.update(record, input(0, true, false), "authorize-record-001"))).isInstanceOf(BaseException.class);
        UUID other = expiredRecord(1);
        assertThatThrownBy(() -> own(() -> controls.update(other, input(0, false, true), "authorize-record-001"))).isInstanceOf(BaseException.class);
        own(() -> controls.update(record, input(1, true, false), "new-hold-001"));
        assertThat(own(() -> controls.update(record, input(0, false, true), "authorize-record-001")).state()).isEqualTo("HELD");
        assertThatThrownBy(() -> as(1, 4, all(), () -> controls.update(record, input(0, false, true), "authorize-record-001")))
                .isInstanceOf(BaseException.class);
        assertThat(count("vm_meeting_workspace_commands")).isEqualTo(2);
    }

    @Test
    void concurrentSameVersionControlsHaveOneWinnerAndSameKeyHasOneReceipt() throws Exception {
        assertThat(raceControls("race-a-001", "race-b-001")).containsExactlyInAnyOrder("SAVED", "CONFLICT");
        assertThat(count("vm_meeting_record_dispositions")).isOne();
        assertThat(count("vm_meeting_workspace_commands")).isOne();
        record = expiredRecord(1);
        assertThat(raceControls("same-key-001", "same-key-001")).containsExactly("SAVED", "SAVED");
        assertThat(count("vm_meeting_workspace_commands")).isEqualTo(2);
    }

    @Test
    void twoWorkersClaimOnceHealthyLeaseStaysReadyAndExpiredLeaseReclaims() throws Exception {
        properties.setEnabled(true); assertThat(worker.purgeExpired()).isZero();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertThat(transactions.claim(first, "worker-one", Duration.ofMinutes(1))).isTrue();
        assertThat(transactions.claim(second, "worker-two", Duration.ofMinutes(1))).isFalse();
        assertThat(worker.ready()).isTrue();
        jdbc.update("UPDATE vm_meeting_record_retention_health SET active_lease_expires_at=clock_timestamp()-INTERVAL '1 second'");
        assertThat(worker.ready()).isFalse();
        assertThat(transactions.claim(second, "worker-two", Duration.ofMinutes(1))).isTrue();
        assertThatThrownBy(() -> transactions.purgeAndSucceed(first, "worker-one", 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transactions.fail(first, "worker-one")).isInstanceOf(IllegalStateException.class);
        assertThat(transactions.purgeAndSucceed(second, "worker-two", 10)).isZero();
        assertThat(worker.ready()).isTrue();
    }

    @Test
    void leaseExpiryDuringPurgeRollsBackAllDeletesAndEvidence() {
        authorize(); publishAudit(); properties.setEnabled(true);
        doAnswer(invocation -> {
            Object event = invocation.callRealMethod();
            jdbc.update("UPDATE vm_meeting_record_retention_health SET active_lease_expires_at=clock_timestamp()-INTERVAL '1 second'");
            return event;
        }).when(retentionAudit).purged(anyLong(), any(), anyString(), any(), any());
        assertThat(worker.purgeExpired()).isEqualTo(-1);
        assertThat(records.snapshot(1, record, false)).isNotNull();
        assertThat(count("vm_meeting_record_deletion_evidence")).isZero();
        assertThat(records.control(1, record, false).purgedAt()).isNull();
    }

    @Test
    void startupStaleHeartbeatAndFailureNeverReportReady() {
        properties.setEnabled(true); assertThat(worker.ready()).isFalse();
        assertThat(worker.purgeExpired()).isZero(); assertThat(worker.ready()).isTrue();
        jdbc.update("UPDATE vm_meeting_record_retention_health SET last_success_at=clock_timestamp()-INTERVAL '16 minutes'");
        assertThat(worker.ready()).isFalse();
    }

    @Test
    void chatPlaintextAndMissingEvidenceFailClosedAndIndependentEvidenceSurvives() {
        UUID participant = participant(), message = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_chat_messages(message_id,tenant_id,meeting_id,participant_id,sender_user_id,
                    sender_display_name,sender_role,created_sequence,last_sequence,message_text,retention_until)
                VALUES (?,1,?,?,3,'Private sender','ATTENDEE',1,1,'Private chat',clock_timestamp()-INTERVAL '1 day')
                """, message, record, participant);
        authorize(); publishAudit(); properties.setEnabled(true);
        assertBlocked("CHAT_DELETION_UNPROVEN");
        jdbc.update("UPDATE vm_meeting_chat_messages SET message_state='DELETED',message_text=NULL,deleted_at=clock_timestamp(),deleted_by=0 WHERE message_id=?", message);
        assertBlocked("CHAT_DELETION_UNPROVEN");
        jdbc.update("""
                INSERT INTO vm_meeting_chat_retention_evidence(deletion_id,execution_id,tenant_id,meeting_id,message_id,
                    deletion_reason,fence_token,worker_id,deleted_at)
                VALUES (?,?,1,?,?,'RETENTION_EXPIRED',?,'fixture-worker',clock_timestamp())
                """, UUID.randomUUID(), UUID.randomUUID(), record, message, UUID.randomUUID());
        assertThat(worker.purgeExpired()).isOne();
        assertThat(count("vm_meeting_chat_retention_evidence")).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vm_meeting_participants WHERE participant_id=?", Long.class, participant)).isZero();
    }

    @Test
    void futureChildRetentionBlocksAndOwnerOnlyReferenceDisappearsWithoutDeletingOriginalDocument() {
        UUID room = UUID.randomUUID();
        jdbc.update("INSERT INTO vm_personal_meeting_rooms(room_id,tenant_id,owner_user_id,name,opaque_alias) VALUES (?,1,303,'Private room',?)", room, "e".repeat(32));
        jdbc.update("INSERT INTO vm_personal_meeting_room_sessions(tenant_id,room_id,meeting_id,invitation_revision) VALUES (1,?,?,1)", room, record);
        jdbc.update("INSERT INTO vm_meeting_preparations(tenant_id,meeting_id) VALUES (1,?) ON CONFLICT DO NOTHING", record);
        jdbc.update("""
                INSERT INTO vm_meeting_preparation_materials(material_id,tenant_id,meeting_id,display_name,content_type,
                    reference_provider,opaque_reference,classification,retention_until,created_by,updated_by)
                VALUES (?,1,?,'Private doc','application/pdf','DWP_FILES','opaque-document-001','INTERNAL',
                    clock_timestamp()+INTERVAL '1 day',3,3)
                """, UUID.randomUUID(), record);
        authorize(); publishAudit(); properties.setEnabled(true);
        assertBlocked("CHILD_RETENTION_NOT_EXPIRED");
        jdbc.update("UPDATE vm_meeting_preparation_materials SET retention_until=clock_timestamp()-INTERVAL '1 day' WHERE meeting_id=?", record);
        assertThat(worker.purgeExpired()).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vm_personal_meeting_rooms WHERE room_id=?", Long.class, room)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vm_personal_meeting_room_sessions WHERE meeting_id=?", Long.class, record)).isZero();
        assertThat(jdbc.queryForObject("SELECT receipt_manifest::text FROM vm_meeting_record_deletion_evidence WHERE meeting_id=?", String.class, record))
                .doesNotContain("Private doc", "opaque-document-001", "Private room");
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECORDING", "TRANSCRIPT"})
    void succeededExternalDeletionReceiptIsArchivedBeforeCircularArtifactCascade(String type) {
        String prefix = type.toLowerCase(java.util.Locale.ROOT);
        UUID artifact = artifact(type, "DELETED"), deletion = externalReceipt(artifact, prefix);
        authorize(); publishAudit(); properties.setEnabled(true);
        assertThat(worker.purgeExpired()).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vm_meeting_" + prefix + "_deletion_commands WHERE deletion_command_id=?", Long.class, deletion)).isZero();
        assertThat(jdbc.queryForObject("SELECT receipt_manifest::text FROM vm_meeting_record_deletion_evidence WHERE meeting_id=?", String.class, record))
                .contains(deletion.toString(), artifact.toString(), "receipt-opaque-001").doesNotContain("e".repeat(64));
    }

    @Test
    void simultaneousWorkerClaimsCommitExactlyOneDurableFence() throws Exception {
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of("worker-a", "worker-b").stream().map(workerId -> executor.submit(() -> {
                ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException();
                return transactions.claim(UUID.randomUUID(), workerId, Duration.ofMinutes(1));
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(futures.getFirst().get(15, TimeUnit.SECONDS), futures.getLast().get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(health.read().activeFence()).isNotNull();
    }

    @Test
    void liveTransitionAfterApprovalStillPreventsPurge() {
        authorize(); publishAudit(); properties.setEnabled(true);
        jdbc.update("""
                UPDATE vm_meetings SET lifecycle_state='LIVE',provider='LIVEKIT',room_name='fixture-room',
                    started_at=clock_timestamp(),media_incarnation=?,media_access_state='ACTIVE' WHERE meeting_id=?
                """, UUID.randomUUID(), record);
        assertBlocked("RECORD_NOT_TERMINAL");
        assertThat(own(() -> controls.read(record)).reasons()).contains("MEDIA_NOT_CLOSED");
    }

    @Test
    void pollVoteNonCascadeFkIsRemovedOnlyWithinExactExpiredMeeting() {
        UUID participant = participant(), poll = UUID.randomUUID(), option = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_facilitation_states(tenant_id,meeting_id,retention_until,updated_by)
                VALUES (1,?,clock_timestamp()-INTERVAL '1 day',3)
                """, record);
        jdbc.update("""
                INSERT INTO vm_meeting_facilitation_polls(poll_id,tenant_id,meeting_id,creator_participant_id,
                    creator_user_id,poll_question,created_sequence,last_sequence,retention_until)
                VALUES (?,1,?, ?,3,'Private question',1,1,clock_timestamp()-INTERVAL '1 day')
                """, poll, record, participant);
        jdbc.update("""
                INSERT INTO vm_meeting_facilitation_poll_options(option_id,tenant_id,meeting_id,poll_id,position,option_label)
                VALUES (?,1,?,?,0,'Private option')
                """, option, record, poll);
        jdbc.update("""
                INSERT INTO vm_meeting_facilitation_poll_votes(tenant_id,meeting_id,poll_id,option_id,voter_participant_id,voter_user_id)
                VALUES (1,?,?,?,?,3)
                """, record, poll, option, participant);
        authorize(); publishAudit(); properties.setEnabled(true);
        UUID fence = UUID.randomUUID();
        assertThat(transactions.claim(fence, "fixture-worker", Duration.ofMinutes(1))).isTrue();
        assertThat(transactions.purgeAndSucceed(fence, "fixture-worker", 10)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vm_meeting_facilitation_poll_votes WHERE meeting_id=?", Long.class, record)).isZero();
        assertThat(jdbc.queryForObject("SELECT receipt_manifest::text FROM vm_meeting_record_deletion_evidence WHERE meeting_id=?", String.class, record))
                .doesNotContain("Private question", "Private option", "Private participant");
    }

    @Test
    void blockedOldestRecordDoesNotStarveLaterAuthorizedRecords() {
        authorize();
        jdbc.update("UPDATE vm_meetings SET provider='LIVEKIT' WHERE meeting_id=?", record);
        UUID blocked = record;
        record = expiredRecord(1);
        own(() -> controls.update(record, input(0, false, true), "authorize-second-001"));
        publishAudit(); properties.setEnabled(true); properties.setBatchSize(1);
        assertThat(worker.purgeExpired()).isZero();
        assertThat(worker.purgeExpired()).isOne();
        assertThat(records.snapshot(1, blocked, false)).isNotNull();
        assertThat(records.snapshot(1, record, false)).isNull();
        assertThat(worker.ready()).isFalse();
    }

    private UUID participant() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_participants(participant_id,tenant_id,meeting_id,user_id,email_address,
                    display_name,participant_role,created_by,updated_by)
                VALUES (?,1,?,3,'fixture@example.invalid','Private participant','ATTENDEE',3,3)
                """, id, record);
        return id;
    }

    private ControlInput input(long version, boolean hold, boolean authorize) {
        var snapshot = records.snapshot(1, record, false);
        return new ControlInput(snapshot == null ? 0L : snapshot.meetingVersion(),
                snapshot == null ? 0L : snapshot.policyVersion(), version, hold, authorize);
    }
    private void authorize() { own(() -> controls.update(record, input(0, false, true), "authorize-record-001")); }
    private void publishAudit() { jdbc.update("UPDATE sys_audit_outbox SET status='PUBLISHED', published_at=clock_timestamp()"); }
    private void assertBlocked(String reason) {
        assertThat(own(() -> controls.read(record)).reasons()).contains(reason);
        assertThat(worker.purgeExpired()).isZero();
        assertThat(records.snapshot(1, record, false)).isNotNull();
        assertThat(count("vm_meeting_record_deletion_evidence")).isZero();
        assertThat(worker.ready()).isFalse();
    }
    private UUID expiredRecord(long tenant) {
        UUID id = UUID.randomUUID();
        String code = id.toString().replace("-", "").substring(0, 12).toUpperCase().replace('0','G').replace('1','H');
        jdbc.update("""
                INSERT INTO vm_meetings(meeting_id,tenant_id,title,agenda,lifecycle_state,join_code,
                    organizer_user_id,organizer_name,created_by,updated_by,updated_at)
                VALUES (?,?,'Private title','secret-transcript','CANCELLED',?,3,'Private host',3,3,clock_timestamp()-INTERVAL '1200 days')
                """, id, tenant, code);
        return id;
    }
    private UUID artifact(String type, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO vm_meeting_artifacts(artifact_id,tenant_id,meeting_id,artifact_type,artifact_state) VALUES (?,1,?,?,?)", id, record, type, state);
        return id;
    }
    private UUID report(boolean hold) {
        UUID artifact = artifact("TRANSCRIPT", "DELETED"), notice = UUID.randomUUID(), run = UUID.randomUUID(), report = UUID.randomUUID();
        externalReceipt(artifact, "transcript");
        jdbc.update("""
                INSERT INTO vm_meeting_content_notices(notice_id,tenant_id,meeting_id,notice_revision,
                    recording_disclosed,transcription_disclosed,ai_summary_disclosed,published_by)
                VALUES (?,1,?,1,FALSE,TRUE,TRUE,3)
                """, notice, record);
        jdbc.update("""
                INSERT INTO vm_meeting_intelligence_runs(run_id,tenant_id,meeting_id,source_artifact_id,source_sha256,
                    content_notice_id,consent_snapshot_sha256,analysis_profile,output_language,processing_region,
                    execution_fence,lease_expires_at,run_state,provider_code,provider_model,prompt_version,schema_version,
                    idempotency_key,request_sha256,requested_at,requested_by,started_at,completed_at)
                VALUES (?,1,?,?,?, ?,?,'STANDARD_RECAP_V1','ko-KR','ap-northeast-2',?,clock_timestamp(),'SUCCEEDED',
                    'agent','model','v1','v1',?,?,clock_timestamp()-INTERVAL '1200 days',3,
                    clock_timestamp()-INTERVAL '1200 days',clock_timestamp()-INTERVAL '1199 days')
                """, run, record, artifact, "a".repeat(64), notice, "b".repeat(64), UUID.randomUUID(), "report-" + run, "c".repeat(64));
        jdbc.update("""
                INSERT INTO vm_meeting_intelligence_reports(report_id,tenant_id,meeting_id,run_id,report_state,audience,
                    encrypted_payload,payload_sha256,source_sha256,schema_version,retention_until,legal_hold,created_at,created_by,updated_by)
                VALUES (?,1,?,?,'DRAFT','PRIVATE_REVIEWERS','ciphertext',?,?,'v1',clock_timestamp()-INTERVAL '1 day',?,
                    clock_timestamp()-INTERVAL '1200 days',3,3)
                """, report, record, run, "d".repeat(64), "a".repeat(64), hold);
        return report;
    }
    private void deleteReport(UUID report) {
        jdbc.update("""
                UPDATE vm_meeting_intelligence_reports SET report_state='DELETED',encrypted_payload=NULL,payload_sha256=NULL,
                    deleted_at=clock_timestamp(),deleted_by=0 WHERE report_id=?
                """, report);
        jdbc.update("""
                INSERT INTO vm_meeting_intelligence_deletions(deletion_id,tenant_id,meeting_id,report_id,previous_report_state,
                    previous_payload_sha256,deletion_reason,fence_token,deleted_at,worker_id)
                VALUES (?,1,?,?,'DRAFT',?,'RETENTION_EXPIRED',?,clock_timestamp(),'fixture-worker')
                """, UUID.randomUUID(), record, report, "d".repeat(64), UUID.randomUUID());
    }
    private UUID externalReceipt(UUID artifact, String prefix) {
        UUID deletion = UUID.randomUUID();
        jdbc.update("UPDATE vm_meeting_artifacts SET retention_until=clock_timestamp()-INTERVAL '1 day' WHERE artifact_id=?", artifact);
        jdbc.update("INSERT INTO vm_meeting_" + prefix + "_deletion_commands" + """
                (deletion_command_id,tenant_id,meeting_id,artifact_id,
                    artifact_version,request_sha256,command_state,worker_id,provider_code,provider_deletion_id,requested_at,completed_at)
                VALUES (?,1,?,?,0,?,'SUCCEEDED','fixture-worker','MANAGED','receipt-opaque-001',
                    clock_timestamp()-INTERVAL '1 day',clock_timestamp())
                """, deletion, record, artifact, "e".repeat(64));
        jdbc.update("UPDATE vm_meeting_artifacts SET " + prefix + "_deleted_at=clock_timestamp(),"
                + prefix + "_deletion_command_id=?," + prefix + "_deletion_provider_code='MANAGED' WHERE artifact_id=?", deletion, artifact);
        return deletion;
    }
    private List<String> raceControls(String a, String b) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(a, b).stream().map(key -> executor.submit(() -> {
                ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException();
                try { own(() -> controls.update(record, input(0, false, true), key)); return "SAVED"; }
                catch (BaseException conflict) { return "CONFLICT"; }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(futures.getFirst().get(15, TimeUnit.SECONDS), futures.getLast().get(15, TimeUnit.SECONDS));
        }
    }
}

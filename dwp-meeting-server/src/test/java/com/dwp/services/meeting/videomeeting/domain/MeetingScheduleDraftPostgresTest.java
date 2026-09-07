package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.CommitScheduleDraftRequest;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.DraftAgendaItem;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.DraftRecurrence;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.DraftVersionRequest;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.SaveScheduleDraftRequest;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.TemplateScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

class MeetingScheduleDraftPostgresTest extends MeetingWorkspacePostgresFixture {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private MeetingScheduleDraftRepository draftRepository;
    private MeetingScheduleDraftRetentionRepository retentionRepository;
    private MeetingScheduleDraftRetentionTransactions retentionTransactions;
    private MeetingScheduleDraftRetentionProperties retentionProperties;
    private MeetingScheduleDraftRetentionService retention;
    private MeetingScheduleDraftService drafts;

    @Override
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    @BeforeEach
    void prepareDrafts() {
        draftRepository = new MeetingScheduleDraftRepository(jdbc, mapper);
        retentionRepository = new MeetingScheduleDraftRetentionRepository(jdbc);
        retentionTransactions = new MeetingScheduleDraftRetentionTransactions(retentionRepository);
        retentionProperties = new MeetingScheduleDraftRetentionProperties();
        retentionProperties.setPollDelay(Duration.ofMinutes(5));
        retentionProperties.setLeaseDuration(Duration.ofMinutes(1));
        retentionProperties.setWorkerId("schedule-draft-test");
        retention = new MeetingScheduleDraftRetentionService(
                retentionRepository, retentionTransactions, retentionProperties,
                Clock.systemUTC());
        bootstrapRetention();
        drafts = new MeetingScheduleDraftService(
                draftRepository, retention, templateRepository, meetings,
                meetingService, schedules, audit, Clock.systemUTC());
    }

    @Test
    void saveRestoreReplayAndCasPersistOnlyCurrentIdentitySnapshots() {
        OffsetDateTime beforeSave = OffsetDateTime.now(ZoneOffset.UTC);
        SaveScheduleDraftRequest request = valid(null, null, null);
        var saved = own(() -> drafts.save(request, "draft-save-one", "draft-save"));
        assertThat(saved.version()).isZero();
        assertThat(saved.retentionUntil())
                .isAfter(beforeSave.plusDays(6))
                .isBeforeOrEqualTo(beforeSave.plusDays(7).plusSeconds(5));
        assertThat(saved.participants()).singleElement()
                .satisfies(person -> {
                    assertThat(person.userId()).isEqualTo(4);
                    assertThat(person.displayName()).isNotBlank();
                });
        assertThat(saved.agendaItems()).singleElement()
                .satisfies(item -> assertThat(item.ownerUserId()).isEqualTo(4));
        assertThat(own(drafts::read).draft()).isEqualTo(saved);
        assertThat(own(() -> drafts.save(request, "draft-save-one", "draft-save")))
                .isEqualTo(saved);

        assertThatThrownBy(() -> own(() -> drafts.save(
                valid(null, "Different title", null), "draft-save-one", null)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> own(() -> drafts.save(
                valid(9L, null, null), "draft-stale-version", null)))
                .isInstanceOf(BaseException.class);

        String receipts = jdbc.queryForObject("""
                SELECT string_agg(row_to_json(command)::text, '')
                  FROM vm_meeting_schedule_draft_commands command
                """, String.class);
        assertThat(receipts).doesNotContain(
                request.title(), request.agenda(), "Participant Four", "example.test");
    }

    @Test
    void olderSaveReceiptCannotReplayAsNewerDraftContent() {
        SaveScheduleDraftRequest firstRequest = valid(null, "First title", null);
        var first = own(() -> drafts.save(firstRequest, "draft-save-first", null));
        var second = own(() -> drafts.save(
                valid(first.version(), "Second title", null), "draft-save-second", null));

        assertThat(second.version()).isGreaterThan(first.version());
        assertThatThrownBy(() -> own(() -> drafts.save(
                firstRequest, "draft-save-first", null)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(own(drafts::read).draft().title()).isEqualTo("Second title");
    }

    @Test
    void databaseRejectsDuplicateOwnerSlotAndCrossTenantDraftChildren() {
        var saved = own(() -> drafts.save(valid(null, "Private slot", null),
                "draft-constraint-save", null));

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO vm_meeting_schedule_drafts (
                    draft_id, tenant_id, owner_user_id, retention_until,
                    created_by, updated_by)
                VALUES (?, 1, 3, CURRENT_TIMESTAMP + INTERVAL '1 day', 3, 3)
                """, UUID.randomUUID())).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO vm_meeting_schedule_draft_participants (
                    tenant_id, draft_id, position, user_id)
                VALUES (2, ?, 1, 4)
                """, saved.draftId())).isInstanceOf(Exception.class);
    }

    @Test
    void revokedTemplateHidesContentAndOnlyBlindDiscardRemainsAvailable() {
        var template = own(() -> templates.create(
                input("Private source"), "draft-template-create", null, false));
        var saved = own(() -> drafts.save(valid(null, null, template.templateId()),
                "draft-template-save", null));
        own(() -> templates.delete(template.templateId(), template.version(),
                "draft-template-delete", null, false));

        var hidden = own(drafts::read);
        assertThat(hidden.discardOnly()).isTrue();
        assertThat(hidden.draft()).isNull();
        assertThat(hidden.draftId()).isEqualTo(saved.draftId());
        assertThatThrownBy(() -> own(() -> drafts.save(
                valid(saved.version(), "Cannot reveal", null),
                "draft-template-bypass", null)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.ENTITY_NOT_FOUND));

        var discarded = own(() -> drafts.discard(new DraftVersionRequest(saved.version()),
                "draft-template-discard", null));
        assertThat(discarded.discarded()).isTrue();
        assertThat(own(drafts::read).draft()).isNull();
    }

    @Test
    void commitDeletesDraftAtomicallyAndLostResponseReplayReturnsTheSameMeeting() {
        var saved = own(() -> drafts.save(valid(null, null, null),
                "draft-commit-save", null));
        var command = new CommitScheduleDraftRequest(saved.version(), null);
        var first = own(() -> drafts.commit(command, "draft-commit", "draft-commit"));
        assertThat(own(drafts::read).draft()).isNull();
        var replay = own(() -> drafts.commit(command, "draft-commit", "draft-commit"));
        assertThat(replay.meeting().meetingId()).isEqualTo(first.meeting().meetingId());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM vm_meetings WHERE meeting_id = ?
                """, Integer.class, first.meeting().meetingId())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM vm_meeting_schedule_draft_commands
                 WHERE operation = 'DRAFT_COMMIT' AND result_type = 'MEETING'
                """, Integer.class)).isOne();
    }

    @Test
    void commitAuditFailureRollsBackMeetingReceiptAndDraftDeletion() {
        var saved = own(() -> drafts.save(valid(null, "Atomic draft", null),
                "draft-audit-save", null));
        long before = count("vm_meetings");
        doThrow(new IllegalStateException("audit unavailable")).when(audit).workspaceChanged(
                any(), org.mockito.ArgumentMatchers.eq("meeting.schedule-draft.committed"),
                anyString(), anyString(), anyString(), anyMap());

        assertThatThrownBy(() -> own(() -> drafts.commit(
                new CommitScheduleDraftRequest(saved.version(), null),
                "draft-audit-commit", null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("vm_meetings")).isEqualTo(before);
        assertThat(own(drafts::read).draft().draftId()).isEqualTo(saved.draftId());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM vm_meeting_schedule_draft_commands
                 WHERE operation = 'DRAFT_COMMIT'
                """, Integer.class)).isZero();
    }

    @Test
    void expiredDraftAndReceiptArePhysicallyPurgedWithContentFreeEvidence() {
        var saved = own(() -> drafts.save(valid(null, "Expiring private title", null),
                "draft-expiry-save", null));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("UPDATE vm_meeting_schedule_drafts SET retention_until = ? WHERE draft_id = ?",
                now.minusMinutes(1), saved.draftId());
        jdbc.update("UPDATE vm_meeting_schedule_draft_commands SET retention_until = ?",
                now.minusMinutes(1));

        UUID fence = UUID.randomUUID();
        assertThat(Boolean.TRUE.equals(transaction.execute(status -> retentionTransactions.attempt(
                now, now.plusMinutes(1), fence, "schedule-draft-test")))).isTrue();
        var result = transaction.execute(status -> retentionTransactions.purgeAndSucceed(
                now, 100, UUID.randomUUID(), fence, "schedule-draft-test"));
        assertThat(result.deletedDraftCount()).isOne();
        assertThat(result.deletedReceiptCount()).isOne();
        assertThat(count("vm_meeting_schedule_drafts")).isZero();
        assertThat(count("vm_meeting_schedule_draft_commands")).isZero();
        String evidence = jdbc.queryForObject("""
                SELECT string_agg(row_to_json(item)::text, '')
                  FROM vm_meeting_schedule_draft_retention_evidence item
                """, String.class);
        assertThat(evidence).doesNotContain("Expiring private title", "draft-expiry-save");
        assertThat(retention.ready()).isTrue();
    }

    @Test
    void saveFailsClosedBeforeWritingWhenRetentionHeartbeatIsStale() {
        jdbc.update("""
                UPDATE vm_meeting_schedule_draft_retention_health
                   SET last_success_at = CURRENT_TIMESTAMP - INTERVAL '1 hour'
                 WHERE health_key = 'SCHEDULE_DRAFTS'
                """);
        assertThatThrownBy(() -> own(() -> drafts.save(valid(null, null, null),
                "draft-stale-retention", null)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
        assertThat(count("vm_meeting_schedule_drafts")).isZero();
    }

    @Test
    void concurrentRetentionWorkersHaveOneWinnerAndHealthyActiveLeaseStaysReady()
            throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> concurrentClaim(
                    ready, start, now, UUID.randomUUID(), "schedule-draft-a"));
            var second = workers.submit(() -> concurrentClaim(
                    ready, start, now, UUID.randomUUID(), "schedule-draft-b"));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(retention.ready()).isTrue();
    }

    @Test
    void expiredRetentionLeaseCanBeReclaimedAndStaleWorkerCannotPurgeOrComplete() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID staleFence = UUID.randomUUID();
        UUID winnerFence = UUID.randomUUID();
        assertThat(claim(now.minusMinutes(2), staleFence, "schedule-draft-stale")).isTrue();
        assertThat(retention.ready()).isFalse();
        assertThat(claim(now, winnerFence, "schedule-draft-winner")).isTrue();

        assertThatThrownBy(() -> transaction.execute(status ->
                retentionRepository.purgeExpired(now, 100, UUID.randomUUID(),
                        staleFence, "schedule-draft-stale")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                retentionRepository.markSuccess(now, staleFence,
                        "schedule-draft-stale", false)))
                .isInstanceOf(IllegalStateException.class);
        var health = retentionRepository.health().orElseThrow();
        assertThat(health.activeFence()).isEqualTo(winnerFence);
        assertThat(health.activeWorkerId()).isEqualTo("schedule-draft-winner");
        assertThat(health.lastFailureCode()).isEqualTo("RETENTION_LEASE_EXPIRED");
    }

    private void bootstrapRetention() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID fence = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            assertThat(retentionRepository.tryClaim(
                    now, now.plusMinutes(1), fence, "schedule-draft-test")).isTrue();
            var result = retentionRepository.purgeExpired(
                    now, 100, UUID.randomUUID(), fence, "schedule-draft-test");
            retentionRepository.markSuccess(
                    now, fence, "schedule-draft-test", result.overdueRemaining());
        });
    }

    private boolean concurrentClaim(
            CountDownLatch ready,
            CountDownLatch start,
            OffsetDateTime now,
            UUID fence,
            String workerId) throws InterruptedException {
        ready.countDown();
        start.await();
        return claim(now, fence, workerId);
    }

    private boolean claim(OffsetDateTime now, UUID fence, String workerId) {
        return Boolean.TRUE.equals(transaction.execute(status ->
                retentionRepository.tryClaim(
                        now, now.plusMinutes(1), fence, workerId)));
    }

    private SaveScheduleDraftRequest valid(
            Long expectedVersion,
            String title,
            UUID sourceTemplateId) {
        Long sourceVersion = sourceTemplateId == null ? null
                : templateRepository.find(1, sourceTemplateId, false).orElseThrow().version();
        return new SaveScheduleDraftRequest(
                expectedVersion,
                title == null ? "Quarterly decision" : title,
                "Private purpose 87421",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(5),
                45,
                "Asia/Seoul",
                "INVITED",
                true,
                false,
                List.of(4L),
                List.of(new DraftAgendaItem(
                        UUID.randomUUID(), "Decision", "Choose the safe option", 4L, 15)),
                new DraftRecurrence("NONE", 1, 4),
                sourceTemplateId,
                sourceVersion,
                "REVIEW");
    }
}

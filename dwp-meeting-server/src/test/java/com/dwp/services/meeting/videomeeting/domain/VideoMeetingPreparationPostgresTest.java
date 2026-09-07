package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingPreparationDtos.*;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AccessScope;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class VideoMeetingPreparationPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final long HOST = 3L;
    private static final long ATTENDEE = 4L;
    private static final long OTHER = 5L;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private VideoMeetingPreparationService preparation;
    private VideoMeetingService service;
    private VideoMeetingAuditRecorder audit;
    private ObjectMapper mapper;
    private MeetingPreparationMaterialRetentionService materialRetention;
    private MeetingPreparationMaterialRetentionTransactions materialRetentionTransactions;

    @BeforeEach
    void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        mapper = new ObjectMapper().findAndRegisterModules();
        var repository = new VideoMeetingRepository(jdbc, mapper);
        audit = mock(VideoMeetingAuditRecorder.class);
        service = new VideoMeetingService(repository, mock(MeetingMediaProvider.class),
                new MeetingJoinCodeGenerator(new SecureRandom(), 12), audit, Clock.systemUTC());
        materialRetentionTransactions = new MeetingPreparationMaterialRetentionTransactions(jdbc);
        materialRetention = new MeetingPreparationMaterialRetentionService(
                materialRetentionTransactions);
        // A successful bounded purge, not migration time, establishes retention readiness.
        materialRetention.purgeExpired();
        preparation = new VideoMeetingPreparationService(repository,
                new VideoMeetingPreparationRepository(jdbc), audit,
                new VideoMeetingScheduleRepository(jdbc, mapper), materialRetention);
    }

    @AfterEach
    void clear() { MeetingRequestContext.clear(); }

    @Test
    void creationCommitsOrderedAgendaAndInvitationResponsesTogether() {
        UUID meeting = create("agenda-create-001", List.of(item("Decision", ATTENDEE), item("Follow-up", null)));
        PreparationResponse response = as(HOST, () -> preparation.read(meeting));
        assertThat(response.agendaItems()).extracting(AgendaItemResponse::title)
                .containsExactly("Decision", "Follow-up");
        assertThat(response.agendaItems()).extracting(AgendaItemResponse::position).containsExactly(0, 1);
        assertThat(response.agendaVersion()).isOne();
        assertThat(response.invitationRevision()).isOne();
        assertThat(response.invitationCounts().accepted()).isOne();
        assertThat(response.invitationCounts().pending()).isOne();
        assertThat(response.canEditAgenda()).isTrue();
        assertThat(response.canRespond()).isFalse();
        assertThat(response.agendaItems().getFirst().ownerDisplayName()).isNotBlank();
    }

    @Test
    void invalidAgendaOwnerRollsBackMeetingParticipantsAndCreationAudit() {
        assertThatThrownBy(() -> create("agenda-invalid-owner", List.of(item("Private decision", OTHER))))
                .isInstanceOf(BaseException.class);
        assertThat(count("vm_meetings", "idempotency_key = 'agenda-invalid-owner'")).isZero();
        verify(audit, never()).meetingLifecycle(any(), any(), anyString(), anyString(), anyMap());
    }

    @Test
    void sameCreationKeyIncludesAgendaDigestAndDoesNotDuplicateItems() {
        var items = List.of(item("Decision", ATTENDEE));
        UUID first = create("agenda-repeat-001", items);
        assertThat(create("agenda-repeat-001", items)).isEqualTo(first);
        assertThat(count("vm_meeting_agenda_items", "meeting_id = '" + first + "'")).isOne();
        assertThatThrownBy(() -> create("agenda-repeat-001", List.of(item("Changed", ATTENDEE))))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void concurrentSameKeyCreationCommitsExactlyOneMeetingAndAgenda() throws Exception {
        var input = schedule(List.of(item("Concurrent", ATTENDEE)), null, null);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return create(input, "agenda-create-race"); });
            var second = executor.submit(() -> { start.await(); return create(input, "agenda-create-race"); });
            start.countDown();
            UUID meeting = first.get(20, TimeUnit.SECONDS);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(meeting);
            assertThat(count("vm_meetings", "idempotency_key = 'agenda-create-race'")).isOne();
            assertThat(count("vm_meeting_agenda_items", "meeting_id = '" + meeting + "'")).isOne();
        }
    }

    @Test
    void invitationAcceptanceNeverAdmitsParticipantAndOnlyExposesTheirOwnResponse() {
        UUID meeting = create("agenda-rsvp-001", List.of());
        PreparationResponse response = as(ATTENDEE, () -> transaction.execute(status -> preparation.respond(meeting,
                new InvitationResponseRequest(1L, 0L, "ACCEPTED"), "rsvp-accept-001", "rsvp")));
        assertThat(response.myResponse().response()).isEqualTo("ACCEPTED");
        assertThat(response.invitationResponses()).hasSize(1).allMatch(InvitationResponse::mine);
        assertThat(jdbc.queryForObject("""
                SELECT attendance_state FROM vm_meeting_participants
                 WHERE meeting_id = ? AND user_id = ?
                """, String.class, meeting, ATTENDEE)).isEqualTo("INVITED");
        assertThat(response.canEditAgenda()).isFalse();
    }

    @Test
    void invitationResponseUsesIdempotencyAndRejectsStaleVersions() {
        UUID meeting = create("agenda-rsvp-002", List.of());
        var request = new InvitationResponseRequest(1L, 0L, "TENTATIVE");
        as(ATTENDEE, () -> transaction.execute(status -> preparation.respond(meeting, request, "rsvp-repeat-001", null)));
        var replay = as(ATTENDEE, () -> transaction.execute(status ->
                preparation.respond(meeting, request, "rsvp-repeat-001", null)));
        assertThat(replay.myResponse().version()).isOne();
        assertThatThrownBy(() -> as(ATTENDEE, () -> transaction.execute(status -> preparation.respond(meeting,
                new InvitationResponseRequest(1L, 0L, "DECLINED"), "rsvp-stale-001", null))))
                .isInstanceOf(BaseException.class);
        assertThat(count("vm_meeting_preparation_commands", "meeting_id = '" + meeting + "'")).isOne();
    }

    @Test
    void personalPreparationIsSelfOnlyCasBoundAndClearsAcrossAgendaVersions() {
        UUID meeting = create("personal-preparation-001",
                List.of(item("Decision", ATTENDEE), item("Risks", null)));
        PreparationResponse initial = as(ATTENDEE, () -> preparation.read(meeting));
        UUID preparedId = initial.agendaItems().getFirst().itemId();
        assertThat(initial.myPreparation().preparedAgendaItemIds()).isEmpty();
        assertThat(initial.myPreparation().version()).isZero();
        assertThat(initial.canPrepare()).isTrue();

        var request = new UpdateMyPreparationRequest(
                initial.agendaVersion(), 0L, List.of(preparedId));
        PreparationResponse saved = as(ATTENDEE, () -> transaction.execute(status ->
                preparation.updateMyPreparation(meeting, request,
                        "personal-preparation-save", "personal-preparation")));
        assertThat(saved.myPreparation().version()).isOne();
        assertThat(saved.myPreparation().preparedAgendaItemIds()).containsExactly(preparedId);
        assertThat(as(HOST, () -> preparation.read(meeting))
                .myPreparation().preparedAgendaItemIds()).isEmpty();

        PreparationResponse replay = as(ATTENDEE, () -> transaction.execute(status ->
                preparation.updateMyPreparation(meeting, request,
                        "personal-preparation-save", "personal-preparation")));
        assertThat(replay.myPreparation().version()).isOne();
        assertThatThrownBy(() -> as(ATTENDEE, () -> transaction.execute(status ->
                preparation.updateMyPreparation(meeting,
                        new UpdateMyPreparationRequest(1L, 0L, List.of()),
                        "personal-preparation-save", null))))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        as(HOST, () -> transaction.execute(status -> preparation.replaceAgenda(meeting,
                new ReplaceAgendaRequest(1L, List.of(item("Changed agenda", ATTENDEE))),
                "personal-preparation-agenda-change", null)));
        PreparationResponse changed = as(ATTENDEE, () -> preparation.read(meeting));
        assertThat(changed.agendaVersion()).isEqualTo(2);
        assertThat(changed.myPreparation().agendaVersion()).isEqualTo(2);
        assertThat(changed.myPreparation().version()).isOne();
        assertThat(changed.myPreparation().preparedAgendaItemIds()).isEmpty();
        assertThatThrownBy(() -> as(ATTENDEE, () -> transaction.execute(status ->
                preparation.updateMyPreparation(meeting,
                        new UpdateMyPreparationRequest(1L, 1L, List.of()),
                        "personal-preparation-stale-agenda", null))))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void personalPreparationRejectsAgendaItemsFromAnotherMeeting() {
        UUID target = create("personal-preparation-target",
                List.of(item("Target agenda", ATTENDEE)));
        UUID other = create("personal-preparation-other",
                List.of(item("Other agenda", ATTENDEE)));
        PreparationResponse targetView = as(ATTENDEE, () -> preparation.read(target));
        UUID otherAgendaItem = as(ATTENDEE, () -> preparation.read(other))
                .agendaItems().getFirst().itemId();

        assertThatThrownBy(() -> as(ATTENDEE, () -> transaction.execute(status ->
                preparation.updateMyPreparation(target,
                        new UpdateMyPreparationRequest(
                                targetView.agendaVersion(), 0L, List.of(otherAgendaItem)),
                        "personal-preparation-cross-meeting", null))))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        assertThat(count("vm_meeting_personal_preparations",
                "meeting_id = '" + target + "'")).isZero();
    }

    @Test
    void inactiveOrDeniedParticipantCannotWritePersonalPreparation() {
        UUID meeting = create("personal-preparation-auth", List.of(item("Decision", ATTENDEE)));
        PreparationResponse current = as(ATTENDEE, () -> preparation.read(meeting));
        var request = new UpdateMyPreparationRequest(current.agendaVersion(), 0L, List.of());
        jdbc.update("UPDATE vm_people_snapshot SET lifecycle_state = 'INACTIVE' "
                + "WHERE tenant_id = 1 AND user_id = ?", ATTENDEE);
        assertThatThrownBy(() -> as(ATTENDEE, () -> transaction.execute(status ->
                preparation.updateMyPreparation(meeting, request,
                        "personal-preparation-inactive", null))))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        jdbc.update("UPDATE vm_people_snapshot SET lifecycle_state = 'ACTIVE' "
                + "WHERE tenant_id = 1 AND user_id = ?", ATTENDEE);
        jdbc.update("UPDATE vm_meeting_participants SET attendance_state = 'DENIED' "
                + "WHERE tenant_id = 1 AND meeting_id = ? AND user_id = ?", meeting, ATTENDEE);
        assertThatThrownBy(() -> as(ATTENDEE, () -> transaction.execute(status ->
                preparation.updateMyPreparation(meeting, request,
                        "personal-preparation-denied", null))))
                .isInstanceOf(BaseException.class);
        assertThat(count("vm_meeting_personal_preparations",
                "meeting_id = '" + meeting + "'")).isZero();
    }

    @Test
    void personalPreparationAuditFailureRollsBackStateAndReceipt() {
        UUID meeting = create("personal-preparation-audit", List.of(item("Decision", ATTENDEE)));
        PreparationResponse current = as(ATTENDEE, () -> preparation.read(meeting));
        doThrow(new IllegalStateException("audit unavailable")).when(audit).collaboration(
                any(), any(), eq("meeting.personal-preparation.updated"), anyString(),
                anyString(), anyString(), anyBoolean(), anyMap());

        assertThatThrownBy(() -> as(ATTENDEE, () -> transaction.execute(status ->
                preparation.updateMyPreparation(meeting,
                        new UpdateMyPreparationRequest(current.agendaVersion(), 0L,
                                List.of(current.agendaItems().getFirst().itemId())),
                        "personal-preparation-audit-fail", null))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("vm_meeting_personal_preparations",
                "meeting_id = '" + meeting + "'")).isZero();
        assertThat(count("vm_meeting_preparation_commands",
                "meeting_id = '" + meeting + "' AND operation = 'PREPARATION_CHECK'"))
                .isZero();
    }

    @Test
    void scheduleChangeRequiresReconfirmationWithoutChangingAdmission() {
        UUID meeting = create("agenda-reschedule-001", List.of());
        as(ATTENDEE, () -> transaction.execute(status -> preparation.respond(meeting,
                new InvitationResponseRequest(1L, 0L, "ACCEPTED"), "rsvp-before-change", null)));
        jdbc.update("""
                UPDATE vm_meetings SET scheduled_start_at = scheduled_start_at + INTERVAL '1 day',
                    scheduled_end_at = scheduled_end_at + INTERVAL '1 day', version = version + 1
                 WHERE meeting_id = ?
                """, meeting);
        var response = as(ATTENDEE, () -> preparation.read(meeting));
        assertThat(response.invitationRevision()).isEqualTo(2);
        assertThat(response.myResponse().response()).isEqualTo("RECONFIRM_REQUIRED");
        assertThat(response.myResponse().respondedAt()).isNull();
        assertThatThrownBy(() -> as(ATTENDEE, () -> transaction.execute(status -> preparation.respond(meeting,
                new InvitationResponseRequest(1L, response.myResponse().version(), "ACCEPTED"),
                "rsvp-old-revision", null)))).isInstanceOf(BaseException.class);
        var current = as(ATTENDEE, () -> transaction.execute(status -> preparation.respond(meeting,
                new InvitationResponseRequest(2L, response.myResponse().version(), "ACCEPTED"),
                "rsvp-new-revision", null)));
        assertThat(current.myResponse().response()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("""
                SELECT attendance_state FROM vm_meeting_participants WHERE meeting_id = ? AND user_id = ?
                """, String.class, meeting, ATTENDEE)).isEqualTo("INVITED");
    }

    @Test
    void nonHostCannotEditAndUninvitedAdminCannotReadOrRespond() {
        UUID meeting = create("agenda-auth-001", List.of());
        assertThatThrownBy(() -> as(ATTENDEE, () -> transaction.execute(status -> preparation.replaceAgenda(meeting,
                new ReplaceAgendaRequest(0L, List.of(item("Forbidden", null))), "agenda-forbidden", null))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> as(OTHER, () -> preparation.read(meeting))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> as(OTHER, () -> transaction.execute(status -> preparation.respond(meeting,
                new InvitationResponseRequest(1L, 0L, "ACCEPTED"), "rsvp-forbidden", null))))
                .isInstanceOf(BaseException.class);
        assertThat(count("vm_meeting_preparation_commands", "meeting_id = '" + meeting + "'")).isZero();
    }

    @Test
    void deniedAndCrossTenantViewersCannotReadPreparation() {
        UUID meeting = create("agenda-auth-002", List.of(item("Secret agenda", ATTENDEE)));
        jdbc.update("UPDATE vm_meeting_participants SET attendance_state = 'DENIED' WHERE meeting_id = ? AND user_id = ?",
                meeting, ATTENDEE);
        assertThatThrownBy(() -> as(ATTENDEE, () -> preparation.read(meeting))).isInstanceOf(BaseException.class);
        MeetingRequestContext.set(subject(2, HOST));
        assertThatThrownBy(() -> preparation.read(meeting)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO vm_meeting_agenda_items (
                    tenant_id, meeting_id, item_id, position, title, created_by, updated_by)
                VALUES (2, ?, ?, 0, 'cross-tenant', ?, ?)
                """, meeting, UUID.randomUUID(), HOST, HOST)).isInstanceOf(Exception.class);
    }

    @Test
    void walkInMembershipIsNotAnInvitationAndCannotSubmitRsvp() {
        UUID meeting = create("agenda-walk-in", List.of());
        jdbc.update("UPDATE vm_meetings SET access_scope = 'INTERNAL' WHERE meeting_id = ?", meeting);
        as(OTHER, () -> transaction.execute(status -> service.requestJoin(meeting,
                new VideoMeetingDtos.JoinRequestCommand("Walk-in"), "walk-in-join-001", null)));
        var view = as(OTHER, () -> preparation.read(meeting));
        assertThat(view.myResponse()).isNull();
        assertThat(view.canRespond()).isFalse();
        assertThatThrownBy(() -> as(OTHER, () -> transaction.execute(status -> preparation.respond(meeting,
                new InvitationResponseRequest(1L, 0L, "ACCEPTED"), "walk-in-rsvp-001", null))))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void agendaMutationAuditFailureRollsBackAgendaVersionAndReceipt() {
        UUID meeting = create("agenda-audit-001", List.of(item("Original", ATTENDEE)));
        doThrow(new IllegalStateException("audit unavailable")).when(audit).collaboration(
                any(), any(), eq("meeting.agenda.updated"), anyString(), anyString(), anyString(), anyBoolean(), anyMap());
        assertThatThrownBy(() -> as(HOST, () -> transaction.execute(status -> preparation.replaceAgenda(meeting,
                new ReplaceAgendaRequest(1L, List.of(item("Never commit", null))), "agenda-audit-failed", null))))
                .isInstanceOf(IllegalStateException.class);
        var current = as(HOST, () -> preparation.read(meeting));
        assertThat(current.agendaVersion()).isOne();
        assertThat(current.agendaItems().getFirst().title()).isEqualTo("Original");
        assertThat(count("vm_meeting_preparation_commands", "meeting_id = '" + meeting + "'")).isZero();
    }

    @Test
    void invitationAuditFailureRollsBackPersonalResponseAndReceipt() {
        UUID meeting = create("agenda-audit-002", List.of());
        doThrow(new IllegalStateException("audit unavailable")).when(audit).collaboration(
                any(), any(), eq("meeting.invitation.responded"), anyString(), anyString(), anyString(), anyBoolean(), anyMap());
        assertThatThrownBy(() -> as(ATTENDEE, () -> transaction.execute(status -> preparation.respond(meeting,
                new InvitationResponseRequest(1L, 0L, "ACCEPTED"), "rsvp-audit-failed", null))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(as(ATTENDEE, () -> preparation.read(meeting)).myResponse().response()).isEqualTo("NEEDS_RESPONSE");
        assertThat(count("vm_meeting_preparation_commands", "meeting_id = '" + meeting + "'")).isZero();
    }

    @Test
    void concurrentAgendaCommandsUseOneVersionAndNeverPersistTextInReceipts() throws Exception {
        UUID meeting = create("agenda-edit-race", List.of());
        var start = new CountDownLatch(1);
        var request = new ReplaceAgendaRequest(0L, List.of(item("Private body 97531", ATTENDEE)));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return as(HOST, () -> transaction.execute(status ->
                    preparation.replaceAgenda(meeting, request, "agenda-edit-repeat", null))); });
            var second = executor.submit(() -> { start.await(); return as(HOST, () -> transaction.execute(status ->
                    preparation.replaceAgenda(meeting, request, "agenda-edit-repeat", null))); });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).agendaVersion()).isOne();
            assertThat(second.get(20, TimeUnit.SECONDS).agendaVersion()).isOne();
        }
        assertThat(count("vm_meeting_preparation_commands", "meeting_id = '" + meeting + "'")).isOne();
        String receipts = jdbc.queryForObject("""
                SELECT row_to_json(receipt)::text FROM vm_meeting_preparation_commands receipt WHERE meeting_id = ?
                """, String.class, meeting);
        assertThat(receipts).doesNotContain("Private body", "97531", "displayName", "email");
        verify(audit).collaboration(any(), any(), eq("meeting.agenda.updated"), anyString(), anyString(),
                anyString(), eq(false), eq(java.util.Map.of("agendaVersion", 1L, "itemCount", 1)));
    }

    @Test
    void creationBindsAnAccessibleImmutableTemplateRevisionAndRejectsAnotherOwnersPrivateSource() {
        var templates = new MeetingTemplateRepository(jdbc, mapper);
        var input = new MeetingWorkspaceDtos.TemplateInput("Decision template", "Purpose", "DECISION", 30,
                List.of(new MeetingWorkspaceDtos.AgendaItem("Decision", "Goal", "Host", 30)));
        var template = transaction.execute(status -> templates.create(1, HOST,
                MeetingWorkspaceDtos.TemplateScope.PERSONAL, input));
        UUID meeting = create(schedule(List.of(item("Edited draft", ATTENDEE)), template.id(), template.version()),
                "agenda-template-source");
        assertThat(jdbc.queryForObject("""
                SELECT template_id FROM vm_meeting_template_sources WHERE tenant_id = 1 AND meeting_id = ?
                """, UUID.class, meeting)).isEqualTo(template.id());
        var foreign = transaction.execute(status -> templates.create(1, OTHER,
                MeetingWorkspaceDtos.TemplateScope.PERSONAL, input));
        assertThatThrownBy(() -> create(schedule(List.of(), foreign.id(), foreign.version()), "agenda-private-source"))
                .isInstanceOf(BaseException.class);
        assertThat(count("vm_meetings", "idempotency_key = 'agenda-private-source'")).isZero();
    }

    @Test
    void governedMaterialReferenceIsListedWithoutItsOpaqueReferenceUntilAccessIsRevalidated() {
        UUID meeting = create("material-register-001", List.of());
        RegisterMaterialRequest request = material(0L, "governed/Q3-plan", "v7");
        PreparationResponse registered = as(HOST, () -> transaction.execute(status ->
                preparation.registerMaterial(meeting, request, "material-add-001", "material-test")));

        assertThat(registered.materialsVersion()).isOne();
        assertThat(registered.materials()).singleElement().satisfies(material -> {
            assertThat(material.displayName()).isEqualTo("Q3 decision brief.pdf");
            assertThat(material.opaqueReference()).isEqualTo("governed/Q3-plan");
            assertThat(material.sourceVersion()).isEqualTo("v7");
            assertThat(material.classification()).isEqualTo("CONFIDENTIAL");
            assertThat(material.accessVerificationState()).isEqualTo("PENDING_REVALIDATION");
            assertThat(material.retentionUntil()).isAfter(OffsetDateTime.now().plusDays(360));
            assertThat(material.retentionUntil()).isBefore(OffsetDateTime.now().plusDays(370));
        });
        PreparationResponse attendee = as(ATTENDEE, () -> preparation.read(meeting));
        assertThat(attendee.canManageMaterials()).isFalse();
        assertThat(attendee.materials()).singleElement().satisfies(material -> {
            assertThat(material.displayName()).isEqualTo("Q3 decision brief.pdf");
            assertThat(material.opaqueReference()).isNull();
            assertThat(material.accessVerificationState()).isEqualTo("PENDING_REVALIDATION");
        });

        PreparationResponse replay = as(HOST, () -> transaction.execute(status ->
                preparation.registerMaterial(meeting, request, "material-add-001", "material-test")));
        assertThat(replay.materialsVersion()).isOne();
        assertThat(count("vm_meeting_preparation_materials", "meeting_id = '" + meeting + "'"))
                .isOne();
        assertThat(count("vm_meeting_invitation_outbox", "meeting_id = '" + meeting
                + "' AND event_type = 'PREPARATION_MATERIAL_ADDED'")).isOne();
        String intent = jdbc.queryForObject("""
                SELECT row_to_json(intent)::text FROM vm_meeting_invitation_outbox intent
                 WHERE meeting_id = ? AND event_type = 'PREPARATION_MATERIAL_ADDED'
                """, String.class, meeting);
        assertThat(intent).doesNotContain("email", "recipient", "title", "opaque", "payload");
    }

    @Test
    void materialRegistrationRejectsUrlsTokensDuplicatesStaleVersionsAndCrossTenantAccess() {
        UUID meeting = create("material-boundary-001", List.of());
        as(HOST, () -> transaction.execute(status -> preparation.registerMaterial(
                meeting, material(0L, "governed/reference-one", null),
                "material-boundary-add", "material-test")));

        assertThatThrownBy(() -> as(HOST, () -> transaction.execute(status ->
                preparation.registerMaterial(meeting, material(1L, "https://files.example/item?token=secret", null),
                        "material-url-denied", "material-test"))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> as(HOST, () -> transaction.execute(status ->
                preparation.registerMaterial(meeting, material(1L, "governed/reference-one", null),
                        "material-duplicate", "material-test"))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> as(HOST, () -> transaction.execute(status ->
                preparation.registerMaterial(meeting, material(0L, "governed/reference-two", null),
                        "material-stale", "material-test"))))
                .isInstanceOf(BaseException.class);
        MeetingRequestContext.set(subject(2, HOST));
        try {
            assertThatThrownBy(() -> preparation.read(meeting)).isInstanceOf(BaseException.class);
        } finally {
            MeetingRequestContext.clear();
        }
        assertThat(count("vm_meeting_preparation_materials", "meeting_id = '" + meeting + "'"))
                .isOne();
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_name = 'vm_meeting_preparation_materials'
                """, String.class)).doesNotContain(
                        "content", "file_bytes", "blob", "download_url", "access_token", "device_id");
    }

    @Test
    void materialRemovalIsSoftIdempotentAndAtomicallyAuditedWithDeliveryIntent() {
        UUID meeting = create("material-remove-001", List.of());
        PreparationResponse registered = as(HOST, () -> transaction.execute(status ->
                preparation.registerMaterial(meeting, material(0L, "governed/remove-me", "v1"),
                        "material-remove-add", "material-test")));
        MaterialResponse material = registered.materials().getFirst();
        RemoveMaterialRequest request = new RemoveMaterialRequest(
                registered.materialsVersion(), material.version());
        PreparationResponse removed = as(HOST, () -> transaction.execute(status ->
                preparation.removeMaterial(meeting, material.materialId(), request,
                        "material-remove-command", "material-test")));
        PreparationResponse replay = as(HOST, () -> transaction.execute(status ->
                preparation.removeMaterial(meeting, material.materialId(), request,
                        "material-remove-command", "material-test")));

        assertThat(removed.materials()).isEmpty();
        assertThat(replay.materialsVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM vm_meeting_preparation_materials
                 WHERE material_id = ?
                """, String.class, material.materialId())).isEqualTo("REMOVED");
        assertThat(count("vm_meeting_preparation_commands", "meeting_id = '" + meeting
                + "' AND operation = 'MATERIAL_REMOVE'")).isOne();
        assertThat(count("vm_meeting_invitation_outbox", "meeting_id = '" + meeting
                + "' AND event_type = 'PREPARATION_MATERIAL_REMOVED'")).isOne();
    }

    @Test
    void materialAuditFailureRollsBackMetadataVersionReceiptAndDeliveryIntent() {
        UUID meeting = create("material-audit-001", List.of());
        doThrow(new IllegalStateException("audit unavailable")).when(audit).collaboration(
                any(), any(), eq("meeting.preparation-material.registered"), anyString(),
                anyString(), anyString(), eq(false), anyMap());

        assertThatThrownBy(() -> as(HOST, () -> transaction.execute(status ->
                preparation.registerMaterial(meeting, material(0L, "governed/audit-fail", null),
                        "material-audit-failure", "material-test"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("vm_meeting_preparation_materials", "meeting_id = '" + meeting + "'"))
                .isZero();
        assertThat(count("vm_meeting_preparation_commands", "meeting_id = '" + meeting
                + "' AND operation = 'MATERIAL_REGISTER'")).isZero();
        assertThat(count("vm_meeting_invitation_outbox", "meeting_id = '" + meeting
                + "' AND event_type = 'PREPARATION_MATERIAL_ADDED'")).isZero();
        assertThat(as(HOST, () -> preparation.read(meeting)).materialsVersion()).isZero();
    }

    @Test
    void expiredMaterialMetadataIsImmediatelyHiddenThenPurgedWithPayloadFreeEvidence() {
        UUID meeting = create("material-retention-001", List.of());
        PreparationResponse registered = as(HOST, () -> transaction.execute(status ->
                preparation.registerMaterial(meeting, material(0L, "governed/expired", "v1"),
                        "material-expired-add", "material-test")));
        UUID materialId = registered.materials().getFirst().materialId();
        jdbc.update("""
                UPDATE vm_meeting_preparation_materials
                   SET retention_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                 WHERE material_id = ?
                """, materialId);

        assertThat(as(HOST, () -> preparation.read(meeting)).materials()).isEmpty();
        assertThat(as(ATTENDEE, () -> preparation.read(meeting)).materials()).isEmpty();
        assertThat(materialRetention.purgeExpired()).isOne();
        assertThat(count("vm_meeting_preparation_materials", "material_id = '" + materialId + "'"))
                .isZero();
        assertThat(jdbc.queryForMap("""
                SELECT outcome, deleted_count, error_code
                  FROM vm_meeting_material_retention_evidence
                 ORDER BY completed_at DESC, execution_id DESC LIMIT 1
                """)).containsEntry("outcome", "SUCCEEDED")
                .containsEntry("deleted_count", 1)
                .containsEntry("error_code", null);
    }

    @Test
    void retentionFailureEvidenceFailsClosedBeforeNewMaterialMetadataIsStored() {
        UUID meeting = create("material-retention-failure", List.of());
        materialRetentionTransactions.recordFailure(OffsetDateTime.now());
        assertThat(materialRetention.ready()).isFalse();

        assertThatThrownBy(() -> as(HOST, () -> transaction.execute(status ->
                preparation.registerMaterial(meeting, material(0L, "governed/blocked", null),
                        "material-retention-blocked", "material-test"))))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
        assertThat(count("vm_meeting_preparation_materials", "meeting_id = '" + meeting + "'"))
                .isZero();
        assertThat(count("vm_meeting_preparation_commands", "meeting_id = '" + meeting
                + "' AND operation = 'MATERIAL_REGISTER'")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM vm_meeting_material_retention_evidence
                 WHERE outcome = 'FAILED' AND error_code = 'PURGE_FAILED'
                """, Long.class)).isOne();
    }

    @Test
    void retentionReadinessRequiresAnActualSuccessfulWorkerRun() {
        jdbc.update("""
                UPDATE vm_meeting_material_retention_state
                   SET last_attempt_at = NULL, last_success_at = NULL,
                       last_failure_at = NULL, last_error_code = NULL
                 WHERE worker_key = 'PREPARATION_MATERIALS'
                """);
        assertThat(materialRetention.ready()).isFalse();
        assertThat(materialRetention.purgeExpired()).isZero();
        assertThat(materialRetention.ready()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM vm_meeting_material_retention_evidence
                 WHERE outcome = 'SUCCEEDED'
                """, Long.class)).isGreaterThanOrEqualTo(1L);

        jdbc.update("""
                UPDATE vm_meeting_material_retention_state
                   SET last_success_at = CURRENT_TIMESTAMP - INTERVAL '16 minutes'
                 WHERE worker_key = 'PREPARATION_MATERIALS'
                """);
        assertThat(materialRetention.ready()).isFalse();
        assertThat(materialRetention.purgeExpired()).isZero();
        assertThat(materialRetention.ready()).isTrue();
    }

    @Test
    void boundedPurgeKeepsReadinessClosedUntilTheOverdueBacklogIsEmpty() {
        UUID firstMeeting = create("material-backlog-first", List.of());
        UUID secondMeeting = create("material-backlog-second", List.of());
        UUID blockedMeeting = create("material-backlog-blocked", List.of());
        as(HOST, () -> transaction.execute(status -> preparation.registerMaterial(
                firstMeeting, material(0L, "governed/backlog-first", null),
                "material-backlog-first-add", "material-test")));
        as(HOST, () -> transaction.execute(status -> preparation.registerMaterial(
                secondMeeting, material(0L, "governed/backlog-second", null),
                "material-backlog-second-add", "material-test")));
        jdbc.update("""
                UPDATE vm_meeting_preparation_materials
                   SET retention_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                 WHERE meeting_id IN (?, ?)
                """, firstMeeting, secondMeeting);

        OffsetDateTime now = OffsetDateTime.now();
        assertThat(materialRetentionTransactions.purge(now, 1)).isOne();
        assertThat(materialRetention.ready()).isFalse();
        assertThatThrownBy(() -> as(HOST, () -> transaction.execute(status ->
                preparation.registerMaterial(
                        blockedMeeting, material(0L, "governed/backlog-blocked", null),
                        "material-backlog-blocked-add", "material-test"))))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
        assertThat(count("vm_meeting_preparation_materials",
                "meeting_id = '" + blockedMeeting + "'")).isZero();

        assertThat(materialRetentionTransactions.purge(now.plusSeconds(1), 1)).isOne();
        assertThat(materialRetention.ready()).isTrue();
    }

    private UUID create(String key, List<AgendaItemInput> items) { return create(schedule(items, null, null), key); }

    private UUID create(VideoMeetingDtos.ScheduleMeetingRequest request, String key) {
        return as(HOST, () -> transaction.execute(status -> service.schedule(request, key, "preparation-test").meeting().meetingId()));
    }

    private VideoMeetingDtos.ScheduleMeetingRequest schedule(List<AgendaItemInput> items, UUID template, Long revision) {
        return new VideoMeetingDtos.ScheduleMeetingRequest("Preparation meeting", null, "Legacy agenda",
                OffsetDateTime.parse("2099-09-04T14:00:00+09:00"), 45, "Asia/Seoul", AccessScope.INVITED,
                true, false, false, false, false, List.of(ATTENDEE), List.of(), items, template, revision);
    }

    private AgendaItemInput item(String title, Long owner) { return new AgendaItemInput(null, title, "Objective", owner, 15); }

    private RegisterMaterialRequest material(long expectedVersion, String reference, String sourceVersion) {
        return new RegisterMaterialRequest("Q3 decision brief.pdf", "application/pdf", "DWP_FILES",
                reference, sourceVersion, "CONFIDENTIAL", 1_024L, "a".repeat(64), expectedVersion);
    }

    private MeetingRequestContext.Subject subject(long tenant, long user) {
        return new MeetingRequestContext.Subject(user, tenant, null, "Private user", Set.of("TENANT_ADMIN"),
                Set.of("APP.MEETINGS:VIEW", "APP.MEETINGS:CREATE", "APP.MEETINGS:UPDATE", "ADMIN.MEETINGS:MANAGE"), Set.of());
    }

    private <T> T as(long user, Supplier<T> action) {
        MeetingRequestContext.set(subject(1, user));
        try { return action.get(); } finally { MeetingRequestContext.clear(); }
    }

    private long count(String table, String predicate) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + predicate, Long.class);
    }
}

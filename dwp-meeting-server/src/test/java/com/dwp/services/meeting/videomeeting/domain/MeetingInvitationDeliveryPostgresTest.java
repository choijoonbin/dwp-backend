package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryModels.Claim;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationNotificationGateway.Acceptance;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MeetingInvitationDeliveryPostgresTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-09-08T12:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private MeetingInvitationDeliveryProperties properties;
    private MeetingInvitationDeliveryTransactions transactions;
    private UUID eventId;
    private UUID meetingId;

    @BeforeEach
    void setup() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        properties = properties();
        MeetingInvitationDeliveryRepository repository =
                new MeetingInvitationDeliveryRepository(jdbc);
        transactions = transactional(new MeetingInvitationDeliveryTransactions(
                repository, properties, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)),
                new DataSourceTransactionManager(dataSource));
        seedEvent(true);
    }

    @Test
    void deliversOnlyAfterEveryCurrentActiveInternalRecipientIsMaterialized() {
        Claim first = transactions.claim();
        assertThat(first).isNotNull();
        assertThat(first.recipientUserId()).isEqualTo(101L);
        assertThat(targetCount()).isEqualTo(2);

        transactions.accept(first, acceptance(1));
        assertThat(parentState()).isEqualTo("PENDING");

        Claim second = transactions.claim();
        assertThat(second.recipientUserId()).isEqualTo(102L);
        transactions.accept(second, acceptance(2));

        assertThat(parentState()).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM vm_meeting_invitation_notification_deliveries
                 WHERE event_id = ? AND delivery_state = 'ACCEPTED'
                   AND recipient_count = 1 AND notification_id IS NOT NULL
                """, Integer.class, eventId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT delivered_at IS NOT NULL
                  FROM vm_meeting_invitation_outbox WHERE event_id = ?
                """, Boolean.class, eventId)).isTrue();
    }

    @Test
    void lostResponseReclaimsTheSameRecipientAndDeterministicIdentity() {
        Claim first = transactions.claim();
        jdbc.update("""
                UPDATE vm_meeting_invitation_outbox
                   SET lease_expires_at = ? WHERE event_id = ?
                """, NOW.minusSeconds(1), eventId);
        jdbc.update("""
                UPDATE vm_meeting_invitation_notification_deliveries
                   SET lease_expires_at = ?
                 WHERE event_id = ? AND recipient_user_id = ?
                """, NOW.minusSeconds(1), eventId, first.recipientUserId());

        Claim reclaimed = transactions.claim();

        assertThat(reclaimed.recipientUserId()).isEqualTo(first.recipientUserId());
        assertThat(reclaimed.sourceEventId()).isEqualTo(first.sourceEventId());
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
        assertThat(reclaimed.deliveryFence()).isNotEqualTo(first.deliveryFence());
        assertThatThrownBy(() -> transactions.accept(first, acceptance(3)))
                .isInstanceOf(IllegalStateException.class);
        transactions.accept(reclaimed, acceptance(4));
        assertThat(parentState()).isEqualTo("PENDING");
    }

    @Test
    void parentReclaimWaitsForTheStillValidRecipientLease() {
        jdbc.update("""
                UPDATE vm_people_snapshot SET lifecycle_state = 'INACTIVE'
                 WHERE tenant_id = 42 AND user_id = 102
                """);
        Claim first = transactions.claim();
        jdbc.update("""
                UPDATE vm_meeting_invitation_outbox
                   SET lease_expires_at = ? WHERE event_id = ?
                """, NOW.minusSeconds(1), eventId);

        assertThat(transactions.claim()).isNull();

        OffsetDateTime availableAt = jdbc.queryForObject("""
                SELECT available_at FROM vm_meeting_invitation_outbox WHERE event_id = ?
                """, OffsetDateTime.class, eventId);
        assertThat(availableAt).isEqualTo(first.deliveryLeaseExpiresAt());
        assertThat(jdbc.queryForObject("""
                SELECT delivery_fence FROM vm_meeting_invitation_notification_deliveries
                 WHERE event_id = ? AND recipient_user_id = ?
                """, UUID.class, eventId, first.recipientUserId()))
                .isEqualTo(first.deliveryFence());
    }

    @Test
    void retriesServerFailureWithinTheBoundAndStillProcessesRemainingRecipients() {
        properties.setMaximumAttempts(2);
        Claim first = transactions.claim();
        transactions.reject(first, "NOTIFICATION_UNAVAILABLE", true);
        assertThat(parentState()).isEqualTo("PENDING");
        assertThat(targetState(first.recipientUserId())).isEqualTo("PENDING");
        jdbc.update("""
                UPDATE vm_meeting_invitation_outbox SET available_at = ? WHERE event_id = ?
                """, NOW, eventId);
        jdbc.update("""
                UPDATE vm_meeting_invitation_notification_deliveries
                   SET available_at = ? WHERE event_id = ? AND recipient_user_id = ?
                """, NOW, eventId, first.recipientUserId());

        Claim second = transactions.claim();
        assertThat(second.sourceEventId()).isEqualTo(first.sourceEventId());
        transactions.reject(second, "NOTIFICATION_UNAVAILABLE", true);

        assertThat(parentState()).isEqualTo("PENDING");
        assertThat(targetState(first.recipientUserId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM vm_meeting_invitation_notification_deliveries
                 WHERE event_id = ? AND recipient_user_id = ?
                """, Integer.class, eventId, first.recipientUserId())).isEqualTo(2);

        Claim remaining = transactions.claim();
        assertThat(remaining.recipientUserId()).isEqualTo(102L);
        transactions.accept(remaining, acceptance(10));
        assertThat(targetState(remaining.recipientUserId())).isEqualTo("ACCEPTED");
        assertThat(parentState()).isEqualTo("FAILED");
    }

    @Test
    void contractRejectionDoesNotRetryOrStopTheRemainingRecipientFanout() {
        Claim claim = transactions.claim();
        transactions.reject(claim, "NOTIFICATION_REJECTED", false);
        assertThat(parentState()).isEqualTo("PENDING");
        assertThat(targetState(claim.recipientUserId())).isEqualTo("FAILED");

        assertThatThrownBy(() -> transactions.accept(claim,
                new Acceptance(UUID.randomUUID(), null, 0, false, "0")))
                .isInstanceOf(IllegalArgumentException.class);

        Claim remaining = transactions.claim();
        assertThat(remaining.recipientUserId()).isEqualTo(102L);
        transactions.accept(remaining, acceptance(11));
        assertThat(targetState(remaining.recipientUserId())).isEqualTo("ACCEPTED");
        assertThat(parentState()).isEqualTo("FAILED");
    }

    @Test
    void migrationIndexesTheRecipientIdentityForeignKey() {
        String definition = jdbc.queryForObject("""
                SELECT indexdef
                  FROM pg_indexes
                 WHERE schemaname = 'public'
                   AND tablename = 'vm_meeting_invitation_notification_deliveries'
                   AND indexname = 'ix_vm_meeting_invitation_recipient_identity'
                """, String.class);

        assertThat(definition).contains("(tenant_id, recipient_user_id)");
    }

    @Test
    void rosterIsReevaluatedAndLedgerContainsNoInvitationContent() {
        Claim first = transactions.claim();
        jdbc.update("""
                UPDATE vm_people_snapshot SET lifecycle_state = 'INACTIVE'
                 WHERE tenant_id = 42 AND user_id = 102
                """);
        transactions.accept(first, acceptance(5));

        assertThat(parentState()).isEqualTo("DELIVERED");
        assertThat(targetState(102L)).isEqualTo("CANCELLED");
        String columns = jdbc.queryForObject("""
                SELECT string_agg(column_name, ',')
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'vm_meeting_invitation_notification_deliveries'
                """, String.class);
        assertThat(columns).doesNotContain(
                "email", "title", "agenda", "message", "join_code", "token", "payload");
    }

    @Test
    void ownerReceiptIsSupersededWhenTheClaimedRecipientLosesEligibilityInFlight() {
        Claim first = transactions.claim();
        jdbc.update("""
                UPDATE vm_people_snapshot SET lifecycle_state = 'INACTIVE'
                 WHERE tenant_id = 42 AND user_id = ?
                """, first.recipientUserId());

        transactions.accept(first, acceptance(6));

        assertThat(targetState(first.recipientUserId())).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("""
                SELECT notification_id IS NOT NULL AND accepted_at IS NOT NULL
                  FROM vm_meeting_invitation_notification_deliveries
                 WHERE event_id = ? AND recipient_user_id = ?
                """, Boolean.class, eventId, first.recipientUserId())).isTrue();
        assertThat(parentState()).isEqualTo("PENDING");

        Claim remaining = transactions.claim();
        assertThat(remaining.recipientUserId()).isEqualTo(102L);
        transactions.accept(remaining, acceptance(7));
        assertThat(parentState()).isEqualTo("DELIVERED");
    }

    @Test
    void laterAggregateEventCannotPassItsPendingPredecessor() {
        UUID laterEventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_invitation_outbox (
                    event_id, tenant_id, meeting_id, event_type,
                    aggregate_version, invitation_revision, available_at, created_at)
                VALUES (?, 42, ?, 'MEETING_RESCHEDULED', 2, 2, ?, ?)
                """, laterEventId, meetingId, NOW, NOW);

        Claim firstRecipient = transactions.claim();
        assertThat(firstRecipient.event().eventId()).isEqualTo(eventId);
        assertThat(transactions.claim()).isNull();
        transactions.accept(firstRecipient, acceptance(8));

        Claim secondRecipient = transactions.claim();
        assertThat(secondRecipient.event().eventId()).isEqualTo(eventId);
        transactions.accept(secondRecipient, acceptance(9));

        Claim later = transactions.claim();
        assertThat(later.event().eventId()).isEqualTo(laterEventId);
        assertThat(parentState(eventId)).isEqualTo("DELIVERED");
        assertThat(parentState(laterEventId)).isEqualTo("PENDING");
    }

    private void seedEvent(boolean includeSecondActiveRecipient) {
        meetingId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_people_snapshot (
                    tenant_id, user_id, email_address, display_name, lifecycle_state)
                VALUES
                    (42, 101, 'organizer42@example.invalid', 'Organizer', 'ACTIVE'),
                    (42, 102, 'attendee42@example.invalid', 'Attendee', ?),
                    (42, 103, 'inactive42@example.invalid', 'Inactive', 'INACTIVE'),
                    (42, 104, 'denied42@example.invalid', 'Denied', 'ACTIVE')
                """, includeSecondActiveRecipient ? "ACTIVE" : "INACTIVE");
        jdbc.update("""
                INSERT INTO vm_tenant_policies (
                    tenant_id, guests_allowed, allow_join_before_host,
                    require_authenticated_internal_users, created_by, updated_by)
                VALUES (42, FALSE, FALSE, TRUE, 101, 101)
                """);
        jdbc.update("""
                INSERT INTO vm_meetings (
                    meeting_id, tenant_id, title, lifecycle_state, access_scope,
                    join_code, scheduled_start_at, scheduled_end_at,
                    organizer_user_id, organizer_name, created_by, updated_by)
                VALUES (?, 42, 'Private plan', 'SCHEDULED', 'INTERNAL',
                        'ABCDEFGHJKLM', ?, ?, 101, 'Organizer', 101, 101)
                """, meetingId, NOW.plusHours(1), NOW.plusHours(2));
        insertParticipant(101, "ORGANIZER", "INVITED", "organizer42@example.invalid");
        insertParticipant(102, "ATTENDEE", "INVITED", "attendee42@example.invalid");
        insertParticipant(103, "ATTENDEE", "INVITED", "inactive42@example.invalid");
        insertParticipant(104, "ATTENDEE", "DENIED", "denied42@example.invalid");
        jdbc.update("""
                INSERT INTO vm_meeting_participants (
                    participant_id, tenant_id, meeting_id, user_id, email_address,
                    display_name, participant_role, attendance_state, created_by, updated_by)
                VALUES (?, 42, ?, NULL, 'external42@example.invalid', 'External',
                        'ATTENDEE', 'INVITED', 101, 101)
                """, UUID.randomUUID(), meetingId);
        jdbc.update("""
                INSERT INTO vm_meeting_invitation_outbox (
                    event_id, tenant_id, meeting_id, event_type,
                    aggregate_version, invitation_revision, available_at, created_at)
                VALUES (?, 42, ?, 'MEETING_SCHEDULED', 1, 1, ?, ?)
                """, eventId, meetingId, NOW, NOW.minusMinutes(1));
    }

    private void insertParticipant(
            long userId, String role, String attendance, String email) {
        jdbc.update("""
                INSERT INTO vm_meeting_participants (
                    participant_id, tenant_id, meeting_id, user_id, email_address,
                    display_name, participant_role, attendance_state, created_by, updated_by)
                VALUES (?, 42, ?, ?, ?, 'Internal', ?, ?, 101, 101)
                """, UUID.randomUUID(), meetingId, userId, email, role, attendance);
    }

    private MeetingInvitationDeliveryProperties properties() {
        MeetingInvitationDeliveryProperties value =
                new MeetingInvitationDeliveryProperties();
        value.setEnabled(true);
        value.setRetryDelay(Duration.ofSeconds(1));
        value.setLeaseDuration(Duration.ofMinutes(1));
        value.setMaximumAttempts(3);
        return value;
    }

    private Acceptance acceptance(int suffix) {
        return new Acceptance(
                UUID.nameUUIDFromBytes(("intent-" + suffix).getBytes()),
                UUID.nameUUIDFromBytes(("notification-" + suffix).getBytes()),
                1, suffix % 2 == 0, Integer.toString(suffix));
    }

    private String parentState() {
        return parentState(eventId);
    }

    private String parentState(UUID selectedEventId) {
        return jdbc.queryForObject("""
                SELECT delivery_state FROM vm_meeting_invitation_outbox WHERE event_id = ?
                """, String.class, selectedEventId);
    }

    private String targetState(long recipientUserId) {
        return jdbc.queryForObject("""
                SELECT delivery_state
                  FROM vm_meeting_invitation_notification_deliveries
                 WHERE event_id = ? AND recipient_user_id = ?
                """, String.class, eventId, recipientUserId);
    }

    private int targetCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM vm_meeting_invitation_notification_deliveries
                 WHERE event_id = ? AND delivery_state <> 'CANCELLED'
                """, Integer.class, eventId);
    }

    @SuppressWarnings("deprecation")
    private MeetingInvitationDeliveryTransactions transactional(
            MeetingInvitationDeliveryTransactions target,
            DataSourceTransactionManager transactionManager) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (MeetingInvitationDeliveryTransactions) proxy.getProxy();
    }
}

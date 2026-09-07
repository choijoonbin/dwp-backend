package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.privacy.MessagingPrivacyDtos;
import com.dwp.services.messaging.privacy.MessagingPrivacyService;
import com.dwp.services.messaging.receipt.MessagingReceiptDtos;
import com.dwp.services.messaging.receipt.MessagingReceiptService;
import com.dwp.services.messaging.realtime.MessagingEventRecorder;
import com.dwp.services.messaging.realtime.MessagingRealtimeEvent;
import com.dwp.services.messaging.realtime.MessagingRealtimePublisher;
import com.dwp.services.messaging.realtime.MessagingRealtimeRepository;
import com.dwp.services.messaging.security.MessagingRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_DB_URL", matches = ".+")
class MessagingReadReceiptPostgresIntegrationTest {
    private static final long TENANT = 94_001;
    private static final long SENDER = 100;
    private static final long READER = 200;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext context;
    private static TransactionTemplate transaction;
    private MessagingPrivacyService privacy;
    private MessagingReceiptService receipts;
    private MessagingRealtimeRepository events;
    private MessagingQueryRepository queries;
    private MessagingCommandRepository commands;
    private UUID conversation;
    private UUID root;
    private UUID reply;
    private UUID laterRoot;

    @Configuration
    @EnableTransactionManagement
    static class Transactions { }

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(System.getenv("DWP_MESSAGING_INTEGRATION_DB_URL"),
                System.getenv().getOrDefault("DWP_MESSAGING_INTEGRATION_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault("DWP_MESSAGING_INTEGRATION_DB_PASSWORD", "postgres"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(DataSourceTransactionManager.class, () -> manager);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(MessagingRealtimePublisher.class, () -> mock(MessagingRealtimePublisher.class));
        context.register(MessagingRealtimeRepository.class, MessagingEventRecorder.class);
        context.scan("com.dwp.services.messaging.privacy", "com.dwp.services.messaging.receipt");
        context.refresh();
    }

    @AfterAll
    static void close() { if (context != null) context.close(); }

    @AfterEach
    void clearSubject() { MessagingRequestContext.clear(); }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM msg_conversations WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM msg_people_snapshot WHERE tenant_id IN (?, ?)", TENANT, TENANT + 1);
        jdbc.update("DELETE FROM msg_user_privacy_preferences WHERE tenant_id IN (?, ?)", TENANT, TENANT + 1);
        jdbc.update("DELETE FROM msg_realtime_events WHERE tenant_id IN (?, ?)", TENANT, TENANT + 1);
        jdbc.update("DELETE FROM msg_audit_events WHERE tenant_id IN (?, ?)", TENANT, TENANT + 1);
        conversation = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_conversations (conversation_id, tenant_id, conversation_key,
                    conversation_type, name, visibility, data_classification, lifecycle_state)
                VALUES (?, ?, ?, 'GROUP', 'Receipt tests', 'PRIVATE', 'INTERNAL', 'ACTIVE')
                """, conversation, TENANT, "receipts:" + conversation);
        member(SENDER, 1, "2026-01-01T00:00:00Z");
        member(READER, 1, "2026-01-01T00:00:00Z");
        member(300, 1, "2026-01-01T00:00:00Z");
        root = message(1, null, SENDER);
        reply = message(2, root, SENDER);
        laterRoot = message(3, null, SENDER);
        jdbc.update("UPDATE msg_conversations SET last_message_id = ? WHERE conversation_id = ?",
                laterRoot, conversation);
        privacy = context.getBean(MessagingPrivacyService.class);
        receipts = context.getBean(MessagingReceiptService.class);
        events = context.getBean(MessagingRealtimeRepository.class);
        queries = new MessagingQueryRepository(jdbc, new MessagingMessageQueryRepository(jdbc));
        commands = new MessagingCommandRepository(jdbc);
        as(SENDER);
    }

    @Test
    void observationsNotGlobalCursorDetermineReadIncludingUnopenedThreadReplies() {
        commands.markRead(TENANT, READER, conversation, laterRoot).orElseThrow();
        assertThat(status(reply, READER)).isEqualTo(MessagingReceiptDtos.Status.UNREAD);
        assertThat(status(root, READER)).isEqualTo(MessagingReceiptDtos.Status.UNREAD);
        observe(READER, List.of(root, laterRoot));
        assertThat(status(root, READER)).isEqualTo(MessagingReceiptDtos.Status.READ);
        assertThat(status(laterRoot, READER)).isEqualTo(MessagingReceiptDtos.Status.READ);
        assertThat(status(reply, READER)).isEqualTo(MessagingReceiptDtos.Status.UNREAD);
        observe(READER, List.of(reply));
        assertThat(status(reply, READER)).isEqualTo(MessagingReceiptDtos.Status.READ);
    }

    @Test
    void defaultAndOptOutFlipAreUnilateralRetainObservationsAndNeverChangeSelfCursor() {
        as(READER);
        assertThat(privacy.preference()).isEqualTo(new MessagingPrivacyDtos.PrivacyPreference(true, 0));
        assertThat(privacy.update(new MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest(false, 0L)))
                .isEqualTo(new MessagingPrivacyDtos.PrivacyPreference(false, 1));
        observe(READER, List.of(root));
        assertThat(status(root, READER)).isEqualTo(MessagingReceiptDtos.Status.UNAVAILABLE);
        assertThat(queries.conversation(TENANT, READER, conversation).orElseThrow().unreadCount()).isEqualTo(3);
        commands.markRead(TENANT, READER, conversation, laterRoot).orElseThrow();
        assertThat(queries.conversation(TENANT, READER, conversation).orElseThrow().unreadCount()).isZero();
        var self = queries.members(TENANT, conversation, READER).stream()
                .filter(member -> member.userId() == READER).findFirst().orElseThrow();
        assertThat(self.readReceiptVisibility()).isEqualTo("SHARED");
        assertThat(self.lastReadMessageId()).isEqualTo(laterRoot);
        assertThat(self.lastReadSequence()).isEqualTo(3);
        assertThat(self.lastReadAt()).isNotNull();
        for (var members : List.of(queries.members(TENANT, conversation),
                queries.members(TENANT, conversation, SENDER))) {
            var other = members.stream().filter(member -> member.userId() == READER).findFirst().orElseThrow();
            assertThat(other.readReceiptVisibility()).isEqualTo("PRIVATE");
            assertThat(other.lastReadMessageId()).isNull();
            assertThat(other.lastReadSequence()).isZero();
            assertThat(other.lastReadAt()).isNull();
        }
        as(READER);
        privacy.update(new MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest(true, 1L));
        assertThat(status(root, READER)).isEqualTo(MessagingReceiptDtos.Status.READ);
        assertThat(status(reply, READER)).isEqualTo(MessagingReceiptDtos.Status.UNREAD);
        as(SENDER);
        privacy.update(new MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest(false, 0L));
        assertThat(status(root, READER)).isEqualTo(MessagingReceiptDtos.Status.READ);
    }

    @Test
    void batchAndSingleAgreeAndExcludeInactiveRevokedPreJoinAndLaterFullHistoryMembers() {
        member(400, 4, "2026-01-01T00:00:00Z");
        member(500, 1, "2026-03-01T00:00:00Z");
        member(600, 1, "2026-01-01T00:00:00Z");
        jdbc.update("UPDATE msg_people_snapshot SET lifecycle_state = 'INACTIVE' WHERE tenant_id = ? AND user_id = 300", TENANT);
        jdbc.update("UPDATE msg_conversation_members SET lifecycle_state = 'REVOKED' WHERE tenant_id = ? AND user_id = 600", TENANT);
        observe(READER, List.of(root));
        as(SENDER);
        var batch = receipts.receipts(conversation, List.of(laterRoot, root));
        assertThat(batch).extracting(MessagingReceiptDtos.ReceiptSummary::messageId).containsExactly(laterRoot, root);
        assertThat(batch.getLast()).isEqualTo(receipts.receipt(conversation, root));
        assertThat(batch.getLast().recipients()).extracting(MessagingReceiptDtos.Recipient::userId).containsExactly(READER);
        assertThat(batch.getLast().readCount()).isEqualTo(1);
        assertThat(batch.getFirst().unreadCount()).isEqualTo(1);
    }

    @Test
    void revocationAndRejoinCannotResurrectOldReceiptEligibility() {
        observe(READER, List.of(root));
        jdbc.update("UPDATE msg_conversation_members SET lifecycle_state = 'REVOKED' WHERE tenant_id = ? AND user_id = ?", TENANT, READER);
        as(SENDER);
        assertThat(receipts.receipt(conversation, root).recipients()).extracting(MessagingReceiptDtos.Recipient::userId)
                .doesNotContain(READER);
        jdbc.update("""
                UPDATE msg_conversation_members SET lifecycle_state = 'ACTIVE', membership_started_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND user_id = ?
                """, TENANT, READER);
        assertThat(receipts.receipt(conversation, root).recipients()).extracting(MessagingReceiptDtos.Recipient::userId)
                .doesNotContain(READER);
    }

    @Test
    void onlyActiveAuthorCanGetReceiptsAndUnauthorizedIdsRejectWholeBatch() {
        as(READER);
        notFound(() -> receipts.receipt(conversation, root));
        jdbc.update("UPDATE msg_conversation_members SET member_role = 'OWNER' WHERE tenant_id = ? AND user_id = ?", TENANT, READER);
        notFound(() -> receipts.receipt(conversation, root));
        as(SENDER);
        notFound(() -> receipts.receipts(conversation, List.of(root, UUID.randomUUID())));
        notFound(() -> receipts.receipt(UUID.randomUUID(), root));
        MessagingRequestContext.set(subject(TENANT + 1, SENDER));
        notFound(() -> receipts.receipt(conversation, root));
        as(SENDER);
        jdbc.update("UPDATE msg_conversation_members SET history_start_sequence = 3, last_read_sequence = 2 WHERE tenant_id = ? AND user_id = ?", TENANT, SENDER);
        notFound(() -> receipts.receipt(conversation, root));
        jdbc.update("UPDATE msg_conversation_members SET lifecycle_state = 'REVOKED' WHERE tenant_id = ? AND user_id = ?", TENANT, SENDER);
        notFound(() -> receipts.receipt(conversation, laterRoot));
    }

    @Test
    void deletedSystemAndArchivedMessagesHaveNoPublicReceiptsOrObservations() {
        jdbc.update("UPDATE msg_messages SET deleted_at = CURRENT_TIMESTAMP WHERE message_id = ?", root);
        jdbc.update("UPDATE msg_messages SET message_kind = 'SYSTEM' WHERE message_id = ?", reply);
        notFound(() -> receipts.receipt(conversation, root));
        notFound(() -> receipts.receipt(conversation, reply));
        as(READER);
        notFound(() -> receipts.observe(conversation, new MessagingReceiptDtos.ObserveRequest(List.of(root))));
        notFound(() -> receipts.observe(conversation, new MessagingReceiptDtos.ObserveRequest(List.of(reply))));
        jdbc.update("UPDATE msg_conversations SET lifecycle_state = 'ARCHIVED' WHERE conversation_id = ?", conversation);
        notFound(() -> receipts.observe(conversation, new MessagingReceiptDtos.ObserveRequest(List.of(laterRoot))));
        as(SENDER);
        notFound(() -> receipts.receipt(conversation, laterRoot));
    }

    @Test
    void observationWritesAreIdempotentAllOrNothingAndLeaveUnreadCursorAndEventsAlone() {
        as(READER);
        notFound(() -> receipts.observe(conversation, new MessagingReceiptDtos.ObserveRequest(List.of(root, UUID.randomUUID()))));
        assertThat(observationCount()).isZero();
        observe(READER, List.of(root, reply));
        observe(READER, List.of(root, reply));
        assertThat(observationCount()).isEqualTo(2);
        assertThat(queries.members(TENANT, conversation, READER).stream()
                .filter(member -> member.userId() == READER).findFirst().orElseThrow().lastReadSequence()).isZero();
        assertThat(events.latestTenantSequence(TENANT)).isZero();
    }

    @Test
    void observationAuthorizationEnforcesHistoryTenantConversationAndActiveMember() {
        as(READER);
        jdbc.update("UPDATE msg_conversation_members SET history_start_sequence = 3, last_read_sequence = 2 WHERE tenant_id = ? AND user_id = ?", TENANT, READER);
        notFound(() -> receipts.observe(conversation, new MessagingReceiptDtos.ObserveRequest(List.of(root, laterRoot))));
        assertThat(observationCount()).isZero();
        notFound(() -> receipts.observe(UUID.randomUUID(), new MessagingReceiptDtos.ObserveRequest(List.of(laterRoot))));
        MessagingRequestContext.set(subject(TENANT + 1, READER));
        notFound(() -> receipts.observe(conversation, new MessagingReceiptDtos.ObserveRequest(List.of(laterRoot))));
        as(READER);
        jdbc.update("UPDATE msg_conversation_members SET lifecycle_state = 'REVOKED' WHERE tenant_id = ? AND user_id = ?", TENANT, READER);
        notFound(() -> receipts.observe(conversation, new MessagingReceiptDtos.ObserveRequest(List.of(laterRoot))));
        assertThat(observationCount()).isZero();
    }

    @Test
    void optimisticVersionsAreAtomicForFirstInsertAndConcurrentUpdates() throws Exception {
        racePreferenceWrites(0);
        as(READER);
        assertThat(privacy.preference().version()).isEqualTo(1);
        racePreferenceWrites(1);
        assertThat(privacy.preference().version()).isEqualTo(2);
        assertThatThrownBy(() -> privacy.update(new MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest(true, 0L)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        MessagingRequestContext.set(subject(TENANT + 1, READER));
        assertThat(privacy.preference()).isEqualTo(new MessagingPrivacyDtos.PrivacyPreference(true, 0));
        privacy.update(new MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest(false, 0L));
        as(READER);
        assertThat(privacy.preference().version()).isEqualTo(2);
    }

    @Test
    void privateCursorAndPrivacyEventsAreSelfOnlyInLiveChecksReplayAndHighWaterQueries() {
        as(READER);
        privacy.update(new MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest(false, 0L));
        var messaging = new MessagingService(queries, commands, new MessagingMessageQueryRepository(jdbc),
                mock(MessagingInteractionCommandRepository.class), context.getBean(MessagingEventRecorder.class));
        transaction.executeWithoutResult(ignored -> messaging.markRead(conversation, new MessagingDtos.ReadCursorRequest(laterRoot)));
        var selfEvents = events.eventsAfter(subject(TENANT, READER), 0, 100);
        assertThat(selfEvents).extracting(MessagingRealtimeEvent::eventType)
                .containsExactly("messaging.privacy-preferences.updated", "messaging.read-cursor.updated");
        assertThat(selfEvents).allSatisfy(event -> {
            assertThat(event.audienceUserId()).isEqualTo(READER);
            assertThat(events.canReceive(event, READER)).isTrue();
            assertThat(events.canReceive(event, SENDER)).isFalse();
        });
        assertThat(events.eventsAfter(subject(TENANT, SENDER), 0, 100)).isEmpty();
        assertThat(events.latestVisibleSequence(subject(TENANT, SENDER))).isZero();
        assertThat(events.eventsAfter(subject(TENANT + 1, READER), 0, 100)).isEmpty();
        assertThat(events.latestVisibleSequence(subject(TENANT, READER))).isEqualTo(selfEvents.getLast().sequence());
        assertThatThrownBy(() -> events.append(TENANT, null, conversation, root, READER,
                "messaging.read-cursor.updated", Map.of("messageSequence", 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> events.append(TENANT, SENDER, null, null, READER,
                "messaging.privacy-preferences.updated", Map.of()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void racePreferenceWrites(long version) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                as(READER);
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
                    privacy.update(new MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest(index == 0, version));
                    return "saved";
                } catch (BaseException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                    return "conflict";
                } finally { MessagingRequestContext.clear(); }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(futures.getFirst().get(10, TimeUnit.SECONDS), futures.getLast().get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("saved", "conflict");
        }
    }

    private long observationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM msg_message_read_observations WHERE tenant_id = ?", Long.class, TENANT);
    }

    private void observe(long userId, List<UUID> ids) {
        as(userId);
        assertThat(receipts.observe(conversation, new MessagingReceiptDtos.ObserveRequest(ids)).observedMessageIds()).isEqualTo(ids);
    }

    private MessagingReceiptDtos.Status status(UUID messageId, long recipient) {
        as(SENDER);
        return receipts.receipt(conversation, messageId).recipients().stream()
                .filter(row -> row.userId() == recipient).findFirst().orElseThrow().status();
    }

    private void notFound(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BaseException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND));
    }

    private void as(long userId) { MessagingRequestContext.set(subject(TENANT, userId)); }

    private MessagingRequestContext.Subject subject(long tenantId, long userId) {
        return new MessagingRequestContext.Subject(userId, tenantId, null, "Member", Set.of(),
                Set.of("APP.MESSAGING:VIEW"), Set.of());
    }

    private void member(long userId, long historyStart, String joinedAt) {
        UUID person = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_people_snapshot (tenant_id, user_id, person_public_id, email_address, display_name)
                VALUES (?, ?, ?, ?, ?)
                """, TENANT, userId, person, userId + "@receipts.test", "Member " + userId);
        jdbc.update("""
                INSERT INTO msg_conversation_members (tenant_id, conversation_id, user_id, person_public_id,
                    history_start_sequence, last_read_sequence, membership_started_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, TENANT, conversation, userId, person, historyStart, historyStart - 1, OffsetDateTime.parse(joinedAt));
    }

    private UUID message(long sequence, UUID parent, long sender) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_messages (message_id, tenant_id, conversation_id, sequence, sender_user_id,
                    sender_name, body, reply_to_message_id, created_at)
                VALUES (?, ?, ?, ?, ?, 'Sender', 'Observed message', ?, '2026-02-01T00:00:00Z')
                """, id, TENANT, conversation, sequence, sender, parent);
        return id;
    }
}

package com.dwp.services.notification.integration;

import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.services.notification.domain.DirectNotificationMaterializer;
import com.dwp.services.notification.domain.NotificationAttentionAdmissionRepository;
import com.dwp.services.notification.domain.NotificationAttentionAdmissionService;
import com.dwp.services.notification.domain.NotificationAttentionContextRepository;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceRepository;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceRuntime;
import com.dwp.services.notification.domain.NotificationDeliveryAdmissionRepository;
import com.dwp.services.notification.domain.NotificationDeliveryAdmissionService;
import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository;
import com.dwp.services.notification.domain.NotificationMaterializationRepository;
import com.dwp.services.notification.domain.NotificationMaterializationTransactions;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.domain.NotificationModels.InboxView;
import com.dwp.services.notification.domain.NotificationModels.MaterializationContext;
import com.dwp.services.notification.domain.NotificationModels.MaterializationContextKind;
import com.dwp.services.notification.domain.NotificationProducerOwnershipPolicy;
import com.dwp.services.notification.domain.NotificationQueryRepository;
import com.dwp.services.notification.domain.NotificationQueryRepository.InboxContextFilter;
import com.dwp.services.notification.domain.NotificationQueryRepository.InboxFilters;
import com.dwp.services.notification.domain.NotificationRecipientEntitlementAdmission;
import com.dwp.services.notification.domain.NotificationRuntimeAdmissionRepository;
import com.dwp.services.notification.domain.NotificationStructuredContexts;
import com.dwp.services.notification.operations.NotificationRetentionRepository;
import com.dwp.services.notification.operations.NotificationRetentionService;
import com.dwp.services.notification.realtime.NotificationChangePublisher;
import com.dwp.services.notification.realtime.NotificationRedisChannels;
import com.dwp.services.notification.realtime.NotificationRedisSignalCodec;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Opt-in disposable PostgreSQL verification of recipient context materialization and RLS. */
@EnabledIfEnvironmentVariable(named = "DWP_NOTIFICATION_CONTEXT_TEST_DB_URL", matches = ".+")
class NotificationStructuredContextMaterializationPostgresIntegrationTest {

    private static final String RUNTIME_USER = "ntf_context_test_runtime";
    private static final String RUNTIME_PASSWORD = "context-test-only";

    private JdbcTemplate admin;
    private JdbcTemplate runtime;
    private DataSourceTransactionManager transactions;
    private NotificationDatabaseScope scope;
    private DirectNotificationMaterializer materializer;
    private NotificationAttentionContextRepository contextRepository;
    private NotificationQueryRepository queryRepository;
    private long tenantId;

    @BeforeAll
    static void migrateDisposableDatabase() {
        DriverManagerDataSource source = adminSource();
        JdbcTemplate admin = new JdbcTemplate(source);
        assertThat(admin.queryForObject("SELECT current_database()", String.class))
                .startsWith("dwp_notification_context_test");
        admin.execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_roles WHERE rolname = 'ntf_context_test_runtime'
                    ) THEN
                        CREATE ROLE ntf_context_test_runtime LOGIN PASSWORD 'context-test-only'
                            NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
                    END IF;
                END $$
                """);
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .placeholders(Map.of("notificationRuntimeRole", RUNTIME_USER))
                .load()
                .migrate();
    }

    @BeforeEach
    void wireNativeMaterializationPath() {
        DriverManagerDataSource adminSource = adminSource();
        admin = new JdbcTemplate(adminSource);
        DriverManagerDataSource runtimeSource = new DriverManagerDataSource(
                required("DWP_NOTIFICATION_CONTEXT_TEST_DB_URL"),
                RUNTIME_USER,
                RUNTIME_PASSWORD);
        runtime = new JdbcTemplate(runtimeSource);
        transactions = new DataSourceTransactionManager(runtimeSource);
        scope = new NotificationDatabaseScope(runtime);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(runtimeSource);
        NotificationChangePublisher publisher = new NotificationChangePublisher(
                mock(StringRedisTemplate.class),
                new NotificationRedisChannels("notification.context.test", 8),
                new NotificationRedisSignalCodec(mapper),
                false);
        NotificationRetentionService retention = new NotificationRetentionService(
                scope,
                new NotificationRetentionRepository(named),
                publisher,
                Duration.ofDays(7),
                Duration.ofDays(7),
                100);
        NotificationAttentionAdmissionService attention =
                new NotificationAttentionAdmissionService(
                        new NotificationAttentionAdmissionRepository(named),
                        new NotificationEffectivePolicyRepository(named),
                        new NotificationAttentionGovernanceRuntime(
                                new NotificationAttentionGovernanceRepository(named, mapper),
                                500,
                                10));
        NotificationMaterializationRepository repository =
                new NotificationMaterializationRepository(
                        named,
                        mapper,
                        new NotificationDeliveryAdmissionService(
                                new NotificationDeliveryAdmissionRepository(named),
                                Duration.ofHours(1)),
                        new NotificationRuntimeAdmissionRepository(named),
                        attention);
        NotificationMaterializationTransactions nativeTransactions =
                new NotificationMaterializationTransactions(
                        transactions, scope, repository, retention, publisher, runtime);
        NotificationRecipientEntitlementAdmission entitlements =
                new NotificationRecipientEntitlementAdmission(
                        (requestedTenant, userId) -> Optional.of(
                                new NotificationRecipientEntitlementDirectory.Subject(
                                        requestedTenant,
                                        userId,
                                        "ACTIVE",
                                        "TENANT",
                                        List.of("APP.MESSAGING:VIEW", "APP.WORKPLACE:VIEW",
                                                "APP.MAIL:VIEW"))),
                        "approvals=APP.APPROVALS:VIEW,hcm=APP.HCM:VIEW,"
                                + "messaging=APP.MESSAGING:VIEW,space=APP.SPACES:VIEW,"
                                + "meetings=APP.MEETINGS:VIEW,workplace=APP.WORKPLACE:VIEW,"
                                + "mail=APP.MAIL:VIEW");
        materializer = new DirectNotificationMaterializer(
                nativeTransactions,
                new NotificationProducerOwnershipPolicy(
                        "dwp-messaging-server=messaging,"
                                + "dwp-platform-server=platform|workplace|mail"),
                entitlements,
                mapper);
        contextRepository = new NotificationAttentionContextRepository(named);
        queryRepository = new NotificationQueryRepository(named, mapper);
        tenantId = ThreadLocalRandom.current().nextLong(100_000L, 1_000_000_000_000L);
    }

    @Test
    void workplaceClosureDomainEventMaterializesAnInboxRowWithUserBookingLink() throws Exception {
        long recipient = 900030L;
        UUID booking = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var data = mapper.createObjectNode();
        var intent = data.putArray("notificationIntents").addObject()
                .put("typeKey", "WORKPLACE.FACILITY_CLOSURE_IMPACT")
                .put("threadKey", "workplace-booking:" + booking)
                .put("locale", "ko-KR")
                .put("reasonCode", "OWNER")
                .put("targetReference", "/workplace/reservations?booking=" + booking)
                .put("actionRequired", false);
        intent.putArray("recipientUserIds").add(recipient);
        intent.putArray("contexts").addObject()
                .put("kind", "WORK_ITEM")
                .put("key", "workplace-booking:" + booking)
                .put("matchable", true);
        intent.putObject("variables")
                .put("resourceName", "회의실 D-1208")
                .put("bookingId", booking.toString())
                .put("impactAction", "CANCEL")
                .put("startsAt", "2026-09-20T09:00:00+09:00")
                .put("endsAt", "2026-09-20T10:00:00+09:00")
                .put("commandId", UUID.randomUUID().toString())
                .put("closureId", UUID.randomUUID().toString());
        DomainEventEnvelope event = DomainEventEnvelope.create(
                "urn:dwp:platform:workplace",
                "workplace.facility-closure.executed.v1",
                1,
                tenantId,
                "FACILITY_CLOSURE_COMMAND",
                UUID.randomUUID().toString(),
                1,
                "corr-workplace-materialization",
                null,
                null,
                data);
        var translation = new NotificationDomainEventTranslator(
                mapper,
                "urn:dwp:platform:workplace=dwp-platform-server")
                .translate(mapper.writeValueAsString(event))
                .getFirst();

        var result = materializer.materialize(
                translation.actor(), translation.request(), translation.correlationId());

        assertThat(result.recipientCount()).isOne();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM ntf_user_notifications
                 WHERE tenant_id=? AND user_id=? AND notification_id=?
                """, Integer.class, tenantId, recipient, result.notificationId())).isOne();
        assertThat(admin.queryForMap("""
                SELECT target_ref, action_payload ->> 'route' AS route
                  FROM ntf_notifications
                 WHERE tenant_id=? AND notification_id=?
                """, tenantId, result.notificationId()))
                .containsEntry("target_ref", "/workplace/reservations?booking=" + booking)
                .containsEntry("route", "/workplace/reservations?booking=" + booking);
    }

    @Test
    void mailEventsMaterializeThroughSourceOwnershipAndViewEntitlementOnboarding()
            throws Exception {
        long recipient = 900031L;
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        UUID threadId = UUID.randomUUID();
        List<MailContractCase> cases = List.of(
                new MailContractCase(
                        "mail.message.received.v1",
                        "MAIL.NEW_MESSAGE",
                        "/mail/inbox?thread=" + threadId,
                        Map.of(
                                "threadId", threadId.toString(),
                                "messageId", UUID.randomUUID().toString()),
                        false),
                new MailContractCase(
                        "mail.shared-inbox.assigned.v1",
                        "MAIL.SHARED_ASSIGNMENT",
                        "/mail/shared?threadId=" + threadId,
                        Map.of(
                                "threadId", threadId.toString(),
                                "sharedInboxId", UUID.randomUUID().toString()),
                        true),
                new MailContractCase(
                        "mail.follow-up.due.v1",
                        "MAIL.FOLLOW_UP_DUE",
                        "/mail/follow-up?threadId=" + threadId,
                        Map.of(
                                "threadId", threadId.toString(),
                                "followUpId", UUID.randomUUID().toString(),
                                "dueAt", "2026-09-17T10:00:00Z"),
                        true));
        NotificationDomainEventTranslator translator = new NotificationDomainEventTranslator(
                mapper, "urn:dwp:platform:mail=dwp-platform-server");

        for (MailContractCase contract : cases) {
            var data = mapper.createObjectNode();
            var intent = data.putArray("notificationIntents").addObject()
                    .put("typeKey", contract.typeKey())
                    .put("threadKey", "mail-thread:" + threadId)
                    .put("locale", "ko-KR")
                    .put("reasonCode", "DIRECT")
                    .put("subjectReference", "mail-thread:" + threadId)
                    .put("targetReference", contract.route())
                    .put("actionRequired", contract.actionRequired());
            if (contract.typeKey().equals("MAIL.FOLLOW_UP_DUE")) {
                intent.put("dueAt", "2026-09-17T10:00:00Z");
            }
            intent.putArray("recipientUserIds").add(recipient);
            intent.putArray("contexts").addObject()
                    .put("kind", "THREAD")
                    .put("key", "mail-thread:" + threadId)
                    .put("matchable", true);
            var variables = intent.putObject("variables");
            contract.variables().forEach(variables::put);
            DomainEventEnvelope event = DomainEventEnvelope.create(
                    "urn:dwp:platform:mail",
                    contract.eventType(),
                    1,
                    tenantId,
                    "MAIL_TEST",
                    UUID.randomUUID().toString(),
                    1,
                    "corr-mail-materialization-" + contract.typeKey(),
                    null,
                    null,
                    data);
            var translation = translator.translate(mapper.writeValueAsString(event)).getFirst();

            var result = materializer.materialize(
                    translation.actor(), translation.request(), translation.correlationId());

            assertThat(result.recipientCount()).isOne();
            assertThat(admin.queryForMap("""
                    SELECT notification.target_ref,
                           notification.action_payload ->> 'route' AS route,
                           type.type_key
                      FROM ntf_notifications notification
                      JOIN ntf_notification_type_versions version
                        ON version.type_version_id = notification.type_version_id
                      JOIN ntf_notification_types type ON type.type_id = version.type_id
                     WHERE notification.tenant_id=? AND notification.notification_id=?
                    """, tenantId, result.notificationId()))
                    .containsEntry("target_ref", contract.route())
                    .containsEntry("route", contract.route())
                    .containsEntry("type_key", contract.typeKey());
        }
    }

    @Test
    void storesCanonicalContextsAndFiltersOnlyTheBoundRecipient() {
        long recipient = 900018L;
        DirectMaterializationRequest request = request(
                recipient,
                UUID.randomUUID(),
                List.of(
                        context(MaterializationContextKind.PROJECT, "project:renewal"),
                        context(MaterializationContextKind.TOPIC, "release.2026-q3")));

        var result = materializer.materialize(worker(), request, "context-materialization");

        assertThat(result.recipientCount()).isOne();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM ntf_recipient_notification_contexts
                 WHERE tenant_id=? AND user_id=? AND notification_id=?
                """, Integer.class, tenantId, recipient, result.notificationId()))
                .isEqualTo(4);
        NotificationRequestContext.Actor recipientActor = user(recipient);
        var topics = userTransaction(recipientActor, () -> contextRepository.discover(
                recipientActor, "TOPIC_TOKEN", "release", 20));
        assertThat(topics).singleElement().satisfies(option -> {
            assertThat(option.scopeKey()).isEqualTo("release.2026-q3");
            assertThat(option.contextKind()).isEqualTo("TOPIC");
        });
        InboxFilters projectFilter = new InboxFilters(
                null, null, null, null, null, List.of(),
                List.of(new InboxContextFilter("RESOURCE", "project:renewal")),
                null, null);
        assertThat(userTransaction(recipientActor, () -> queryRepository.inbox(
                recipientActor, InboxView.ALL, projectFilter, 20, null)))
                .singleElement()
                .satisfies(row -> assertThat(row.item().notificationId())
                        .isEqualTo(result.notificationId()));
        NotificationRequestContext.Actor otherUser = user(900019L);
        assertThat(userTransaction(otherUser, () -> queryRepository.inbox(
                otherUser, InboxView.ALL, projectFilter, 20, null))).isEmpty();
        assertThat(admin.queryForObject("""
                SELECT string_agg(context_key || ' ' || COALESCE(display_hint, ''), ' ')
                  FROM ntf_recipient_notification_contexts
                 WHERE tenant_id=? AND notification_id=?
                """, String.class, tenantId, result.notificationId()))
                .doesNotContain("Sender", "Secret message", "person@example.test");

        DirectMaterializationRequest collapsed = new DirectMaterializationRequest(
                UUID.randomUUID(),
                request.sourceEventType(),
                request.sourceSchemaVersion(),
                request.typeKey(),
                request.recipientUserIds(),
                request.threadKey(),
                request.locale(),
                request.reasonCode(),
                request.actorReference(),
                request.subjectReference(),
                request.targetReference(),
                request.occurredAt().plusSeconds(1),
                request.dueAt(),
                request.actionRequired(),
                List.of(
                        new MaterializationContext(
                                MaterializationContextKind.PROJECT,
                                "project:renewal",
                                "Untrusted rename",
                                true),
                        context(MaterializationContextKind.TOPIC, "release.2026-q3"),
                        context(MaterializationContextKind.WORK_ITEM, "work-item:42")),
                request.variables());
        var collapsedResult = materializer.materialize(
                worker(), collapsed, "context-collapse");
        assertThat(collapsedResult.notificationId()).isEqualTo(result.notificationId());
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM ntf_recipient_notification_contexts
                 WHERE tenant_id=? AND user_id=? AND notification_id=?
                """, Integer.class, tenantId, recipient, result.notificationId()))
                .isEqualTo(5);
        assertThat(admin.queryForObject("""
                SELECT display_hint FROM ntf_recipient_notification_contexts
                 WHERE tenant_id=? AND user_id=? AND notification_id=?
                   AND kind='PROJECT' AND context_key='project:renewal'
                """, String.class, tenantId, recipient, result.notificationId()))
                .isNull();

        var replay = materializer.materialize(worker(), collapsed, "context-collapse");
        assertThat(replay.duplicate()).isTrue();
        DirectMaterializationRequest tamperedReplay = new DirectMaterializationRequest(
                collapsed.sourceEventId(),
                collapsed.sourceEventType(),
                collapsed.sourceSchemaVersion(),
                collapsed.typeKey(),
                collapsed.recipientUserIds(),
                collapsed.threadKey(),
                collapsed.locale(),
                collapsed.reasonCode(),
                collapsed.actorReference(),
                collapsed.subjectReference(),
                collapsed.targetReference(),
                collapsed.occurredAt(),
                collapsed.dueAt(),
                collapsed.actionRequired(),
                List.of(context(MaterializationContextKind.TOPIC, "different-topic")),
                collapsed.variables());
        assertThatThrownBy(() -> materializer.materialize(
                worker(), tamperedReplay, "context-collapse"))
                .hasMessageContaining("reused with a different payload");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM ntf_recipient_notification_contexts
                 WHERE tenant_id=? AND user_id=? AND notification_id=?
                """, Integer.class, tenantId, recipient, result.notificationId()))
                .isEqualTo(5);
    }

    @Test
    void firstEventResourceMuteAndReplayLeaveNoRecipientContextProjection() {
        long recipient = 900020L;
        String project = "project:restricted";
        attentionRule(recipient, "RESOURCE", project, "MUTE");
        UUID sourceEventId = UUID.randomUUID();
        DirectMaterializationRequest request = request(
                recipient,
                sourceEventId,
                List.of(context(MaterializationContextKind.PROJECT, project)));

        var first = materializer.materialize(worker(), request, "first-resource-mute");
        var replay = materializer.materialize(worker(), request, "first-resource-mute");

        assertThat(first.notificationId()).isNull();
        assertThat(first.recipientCount()).isZero();
        assertThat(first.duplicate()).isFalse();
        assertThat(replay.duplicate()).isTrue();
        assertThat(tenantCount("ntf_notification_intents")).isEqualTo(1);
        assertThat(count("ntf_delivery_admission_receipts", recipient)).isEqualTo(1);
        assertThat(count("ntf_user_notifications", recipient)).isZero();
        assertThat(count("ntf_recipient_notification_contexts", recipient)).isZero();
        assertThat(count("ntf_user_counters", recipient)).isZero();
    }

    @Test
    void firstEventTopicMuteRequiresAndHonorsTheTenantAllowlist() {
        long deniedRecipient = 900021L;
        String topic = "security-alert";
        attentionRule(deniedRecipient, "TOPIC_TOKEN", topic, "MUTE");
        var withoutAllowlist = materializer.materialize(
                worker(),
                request(
                        deniedRecipient,
                        UUID.randomUUID(),
                        List.of(context(MaterializationContextKind.TOPIC, topic))),
                "topic-not-allowlisted");
        assertThat(withoutAllowlist.recipientCount()).isOne();

        long allowedTenant = tenantId + 1;
        tenantId = allowedTenant;
        long mutedRecipient = 900022L;
        publishGovernance(topic);
        attentionRule(mutedRecipient, "TOPIC_TOKEN", topic, "MUTE");
        var allowed = materializer.materialize(
                worker(),
                request(
                        mutedRecipient,
                        UUID.randomUUID(),
                        List.of(context(MaterializationContextKind.TOPIC, topic))),
                "topic-allowlisted");

        assertThat(allowed.notificationId()).isNull();
        assertThat(allowed.recipientCount()).isZero();
        assertThat(count("ntf_recipient_notification_contexts", mutedRecipient)).isZero();
    }

    private DirectMaterializationRequest request(
            long recipient,
            UUID sourceEventId,
            List<MaterializationContext> contexts) {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        return new DirectMaterializationRequest(
                sourceEventId,
                "messaging.message.sent.v1",
                1,
                "MESSAGING.DIRECT_MESSAGE",
                List.of(recipient),
                "messaging-conversation:" + conversationId,
                "ko-KR",
                "DIRECT",
                "user:10",
                "messaging-message:" + messageId,
                "/messages/direct?conversation=" + conversationId + "&message=" + messageId,
                Instant.parse("2026-09-17T01:00:00Z"),
                null,
                false,
                contexts,
                Map.of(
                        "senderName", "Sender",
                        "conversationName", "Conversation",
                        "conversationId", conversationId.toString(),
                        "messageId", messageId.toString(),
                        "messagePreview", "Secret message"));
    }

    private MaterializationContext context(
            MaterializationContextKind kind,
            String key) {
        return new MaterializationContext(kind, key, null, true);
    }

    private void attentionRule(
            long userId,
            String scopeKind,
            String scopeKey,
            String effect) {
        admin.update("""
                INSERT INTO ntf_user_attention_rules (
                    rule_id, tenant_id, user_id, scope_kind, scope_key,
                    scope_key_hash, effect, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'USER')
                """, UUID.randomUUID(), tenantId, userId, scopeKind, scopeKey,
                NotificationStructuredContexts.attentionScopeHash(
                        scopeKind, scopeKey), effect);
    }

    private void publishGovernance(String topic) {
        admin.update("""
                INSERT INTO ntf_attention_governance_revisions (
                    governance_id, tenant_id, state,
                    max_active_user_rules, max_vip_rules, max_follow_rules,
                    approved_topic_allowlist, mandatory_policy_precedence,
                    minimum_analytics_cohort, independent_reviewer_required,
                    revision_number, change_reason, created_by,
                    approved_by, approved_at, updated_by, decision_reason)
                VALUES (?, ?, 'PUBLISHED', 100, 20, 20, CAST(? AS jsonb), TRUE,
                        10, TRUE, 1, 'Approve exact topic attention governance',
                        1, 2, CURRENT_TIMESTAMP, 2,
                        'Independent reviewer approved this governance')
                """, UUID.randomUUID(), tenantId, "[\"#" + topic + "\"]");
    }

    private NotificationRequestContext.Actor worker() {
        return new NotificationRequestContext.Actor(
                tenantId, null, Set.of(), Set.of(), true, "dwp-messaging-server");
    }

    private NotificationRequestContext.Actor user(long userId) {
        return new NotificationRequestContext.Actor(
                tenantId, userId, Set.of(), Set.of(), false, "dwp-gateway");
    }

    private <T> T userTransaction(
            NotificationRequestContext.Actor actor,
            java.util.function.Supplier<T> query) {
        return new TransactionTemplate(transactions).execute(status -> {
            scope.applyUser(actor);
            return query.get();
        });
    }

    private long count(String table, long userId) {
        return admin.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE tenant_id=? AND user_id=?",
                Long.class,
                tenantId,
                userId);
    }

    private long tenantCount(String table) {
        return admin.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE tenant_id=?",
                Long.class,
                tenantId);
    }

    private static DriverManagerDataSource adminSource() {
        return new DriverManagerDataSource(
                required("DWP_NOTIFICATION_CONTEXT_TEST_DB_URL"),
                System.getenv().getOrDefault(
                        "DWP_NOTIFICATION_CONTEXT_TEST_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault(
                        "DWP_NOTIFICATION_CONTEXT_TEST_DB_PASSWORD", "postgres"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required.");
        }
        return value;
    }

    private record MailContractCase(
            String eventType,
            String typeKey,
            String route,
            Map<String, String> variables,
            boolean actionRequired) { }
}

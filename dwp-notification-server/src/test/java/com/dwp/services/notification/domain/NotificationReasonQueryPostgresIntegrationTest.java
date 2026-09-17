package com.dwp.services.notification.domain;

import com.dwp.services.notification.cursor.NotificationCursorCodec.InboxCursor;
import com.dwp.services.notification.domain.NotificationModels.InboxView;
import com.dwp.services.notification.domain.NotificationQueryRepository.InboxFilters;
import com.dwp.services.notification.domain.NotificationQueryRepository.InboxRow;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in disposable database only; applies real migrations and queries as the runtime API role. */
@EnabledIfEnvironmentVariable(named = "DWP_NOTIFICATION_INTEGRATION_DB_URL", matches = ".+")
class NotificationReasonQueryPostgresIntegrationTest {
    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(42, 900018L, Set.of(), Set.of(), false, "dwp-gateway");
    private static final List<String> REASONS = List.of(
            "DIRECT", "DIRECT_RECIPIENT", "MENTION", "MENTIONED", "ROLE", "ORGANIZATION", "ORG",
            "SUBSCRIPTION", "SUBSCRIBED", "MANDATORY_POLICY", "MANDATORY");
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactions;
    private static NotificationQueryRepository repository;
    private static NotificationDatabaseScope scope;

    private final Map<String, UUID> ids = new LinkedHashMap<>();
    private TransactionStatus transaction;
    private TypeFixture messaging;
    private TypeFixture approvals;

    @BeforeAll
    static void migrateDisposableDatabase() {
        var source = new DriverManagerDataSource(
                System.getenv("DWP_NOTIFICATION_INTEGRATION_DB_URL"),
                System.getenv().getOrDefault("DWP_NOTIFICATION_INTEGRATION_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault("DWP_NOTIFICATION_INTEGRATION_DB_PASSWORD", "postgres"));
        jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .as("Never migrate an application database for this test")
                .startsWith("dwp_notification_reason_test");
        jdbc.execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ntf_reason_test_runtime') THEN
                        CREATE ROLE ntf_reason_test_runtime NOLOGIN NOSUPERUSER NOCREATEDB
                            NOCREATEROLE NOREPLICATION NOBYPASSRLS;
                    END IF;
                END $$
                """);
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .placeholders(Map.of("notificationRuntimeRole", "ntf_reason_test_runtime"))
                .load().migrate();
        transactions = new DataSourceTransactionManager(source);
        repository = new NotificationQueryRepository(new NamedParameterJdbcTemplate(source), new ObjectMapper());
        scope = new NotificationDatabaseScope(jdbc);
    }

    @BeforeEach
    void seedRecipientProjections() {
        transaction = transactions.getTransaction(new DefaultTransactionDefinition());
        messaging = type("messaging");
        approvals = type("approvals");
        for (String reason : REASONS) ids.put(reason, notification(42, 900018, messaging, reason));
        notification(42, 900019, messaging, "SUBSCRIBED");
        notification(43, 900018, messaging, "SUBSCRIBED");
        notification(42, 900018, messaging, "DIRECT_EXTRA");
        notification(42, 900018, messaging, "SUBSCRIBED_EXTRA");
    }

    @AfterEach
    void rollbackFixture() {
        if (transaction != null) transactions.rollback(transaction);
    }

    @ParameterizedTest
    @CsvSource({
        "DIRECT,DIRECT,DIRECT_RECIPIENT", "DIRECT_RECIPIENT,DIRECT,DIRECT_RECIPIENT",
        "MENTION,MENTION,MENTIONED", "MENTIONED,MENTION,MENTIONED",
        "ORGANIZATION,ORGANIZATION,ORG", "ORG,ORGANIZATION,ORG",
        "SUBSCRIPTION,SUBSCRIPTION,SUBSCRIBED", "SUBSCRIBED,SUBSCRIPTION,SUBSCRIBED",
        "MANDATORY_POLICY,MANDATORY_POLICY,MANDATORY", "MANDATORY,MANDATORY_POLICY,MANDATORY",
        "ROLE,ROLE,ROLE"
    })
    void filtersBothSpellingsAndReturnsCanonicalDisplay(String filter, String canonical, String alias) {
        scope.applyUser(ACTOR);
        var rows = inbox(InboxView.ALL, filters(filter), 100, null);
        Set<UUID> expected = Set.copyOf(List.of(ids.get(canonical), ids.get(alias)));

        assertThat(rows).extracting(row -> row.item().notificationId()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.item().reason().kind()).isEqualTo(canonical);
            assertThat(row.item().reason().detail()).isIn(canonical, alias);
            assertThat(repository.detail(ACTOR, row.item().notificationId()).item().reason())
                    .isEqualTo(row.item().reason());
        });
    }

    @Test
    void includedTypesAreAnOrSetInsideTheExactTenantAndUserInbox() {
        scope.applyUser(ACTOR);
        var filters = new InboxFilters(
                null, null, null, null, null,
                List.of("ASSIGNED", "DIRECT"), List.of(), null, null);

        assertThat(inbox(InboxView.ALL, filters, 100, null))
                .extracting(row -> row.item().notificationId())
                .containsExactlyInAnyOrder(
                        ids.get("DIRECT"), ids.get("DIRECT_RECIPIENT"), ids.get("ROLE"));
    }

    @Test
    void mentionViewReasonFilterAndCounterAgreeIncludingLegacyCase() {
        UUID legacy = notification(42, 900018, messaging, "mentioned");
        UUID snoozed = notification(42, 900018, messaging, "MENTIONED");
        UUID done = notification(42, 900018, messaging, "MENTION");
        jdbc.update("UPDATE ntf_user_notifications SET snoozed_until = CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE notification_id = ?", snoozed);
        jdbc.update("UPDATE ntf_user_notifications SET inbox_state = 'DONE', completed_at = CURRENT_TIMESTAMP WHERE notification_id = ?", done);
        jdbc.update("UPDATE ntf_user_notifications SET read_at = CURRENT_TIMESTAMP WHERE notification_id = ?", legacy);
        scope.applyUser(ACTOR);

        var mentions = inbox(InboxView.MENTIONS, filters(null), 100, null);
        var filtered = inbox(InboxView.ALL, filters("mentioned"), 100, null);
        assertThat(mentions).extracting(row -> row.item().notificationId())
                .containsExactlyInAnyOrder(ids.get("MENTION"), ids.get("MENTIONED"), legacy);
        assertThat(filtered).isEqualTo(mentions);
        assertThat(repository.viewCounts(ACTOR).mentions()).isEqualTo(mentions.size());
        assertThat(mentions).allSatisfy(row -> assertThat(row.item().reason().kind()).isEqualTo("MENTION"));
    }

    @Test
    void reasonAliasesRemainAnIntersectionWithAppPriorityReadStateAndView() {
        notification(42, 900018, approvals, "SUBSCRIBED");
        UUID subscribed = ids.get("SUBSCRIBED");
        jdbc.update("UPDATE ntf_user_notifications SET effective_priority = 'HIGH', action_required = TRUE WHERE notification_id = ?", subscribed);
        jdbc.update("UPDATE ntf_user_notifications SET read_at = CURRENT_TIMESTAMP WHERE notification_id = ?", ids.get("SUBSCRIPTION"));
        scope.applyUser(ACTOR);
        var selected = new InboxFilters(
                null, "messaging", "HIGH", "UNREAD", "SUBSCRIPTION",
                List.of(), List.of(), null, null);

        assertThat(inbox(InboxView.PRIORITY, selected, 100, null))
                .extracting(row -> row.item().notificationId()).containsExactly(subscribed);
        assertThat(inbox(InboxView.MENTIONS, selected, 100, null)).isEmpty();
        assertThat(inbox(InboxView.ALL,
                new InboxFilters(
                        null, "messaging", null, "READ", "SUBSCRIBED",
                        List.of(), List.of(), null, null), 100, null))
                .extracting(row -> row.item().notificationId()).containsExactly(ids.get("SUBSCRIPTION"));
    }

    @Test
    void aliasExpansionPreservesKeysetPaginationWithoutDuplicatesOrSkips() {
        notification(42, 900018, messaging, "SUBSCRIBED");
        notification(42, 900018, messaging, "subscription");
        scope.applyUser(ACTOR);
        var all = inbox(InboxView.ALL, filters("SUBSCRIPTION"), 100, null);
        var first = inbox(InboxView.ALL, filters("SUBSCRIBED"), 2, null);
        var last = first.getLast().item();
        var second = inbox(InboxView.ALL, filters("SUBSCRIBED"), 2,
                new InboxCursor(last.lastActivityAt(), last.notificationId()));

        assertThat(all).hasSize(4);
        assertThat(Stream.concat(first.stream(), second.stream()).toList()).containsExactlyElementsOf(all);
    }

    @Test
    void unknownFiltersMatchOnlyTheirLiteralCodeWithoutInventingDirectMembership() {
        UUID unknown = notification(42, 900018, messaging, "OWNER");
        notification(42, 900018, messaging, "OWNER_EXTRA");
        scope.applyUser(ACTOR);

        assertThat(inbox(InboxView.ALL, filters("OWNER"), 100, null))
                .extracting(row -> row.item().notificationId()).containsExactly(unknown);
        assertThat(inbox(InboxView.ALL, filters("%"), 100, null)).isEmpty();
        assertThat(inbox(InboxView.ALL, filters("DIRECT' OR 1=1 --"), 100, null)).isEmpty();
        assertThat(inbox(InboxView.ALL, filters("DIRECT"), 100, null))
                .extracting(row -> row.item().notificationId())
                .containsExactlyInAnyOrder(ids.get("DIRECT"), ids.get("DIRECT_RECIPIENT"));
    }

    @Test
    void unknownRemainsVisibleWithNeutralReasonButOutsideEveryCanonicalReasonFilter() {
        String raw = "private-reason-42";
        UUID unknown = notification(42, 900018, messaging, raw);
        scope.applyUser(ACTOR);

        var item = inbox(InboxView.ALL, filters(null), 100, null).stream()
                .map(InboxRow::item).filter(row -> row.notificationId().equals(unknown)).findFirst().orElseThrow();
        assertThat(item.reason().kind()).isEqualTo("UNKNOWN");
        assertThat(item.reason().label()).isEqualTo("Reason unavailable");
        assertThat(item.reason().detail()).isNull();
        var detail = repository.detail(ACTOR, unknown);
        assertThat(detail.item().reason()).isEqualTo(item.reason());
        assertThat(detail.reasonExplanation())
                .isEqualTo("The reason for receiving this notification is unavailable.")
                .doesNotContain(raw, "directly");
        for (String filter : REASONS) {
            assertThat(inbox(InboxView.ALL, filters(filter), 100, null))
                    .extracting(row -> row.item().notificationId()).doesNotContain(unknown);
        }
        assertThat(jdbc.queryForObject("SELECT reason_code FROM ntf_user_notifications WHERE notification_id = ?",
                String.class, unknown)).isEqualTo(raw);
    }

    @Test
    void threadContextUsesExactRecipientOwnedMatching() {
        String threadKey = "conversation:" + UUID.randomUUID();
        UUID expected = notification(42, 900018, messaging, "DIRECT");
        UUID prefixOnly = notification(42, 900018, messaging, "DIRECT");
        UUID otherUser = notification(42, 900019, approvals, "DIRECT");
        UUID otherTenant = notification(43, 900018, messaging, "DIRECT");
        jdbc.update("UPDATE ntf_notifications SET thread_key = ? WHERE notification_id = ?",
                threadKey, expected);
        jdbc.update("UPDATE ntf_notifications SET thread_key = ? WHERE notification_id = ?",
                threadKey + "-child", prefixOnly);
        jdbc.update("UPDATE ntf_notifications SET thread_key = ? WHERE notification_id IN (?, ?)",
                threadKey, otherUser, otherTenant);
        scope.applyUser(ACTOR);

        assertThat(inbox(InboxView.ALL, context("THREAD", threadKey), 100, null))
                .extracting(row -> row.item().notificationId())
                .containsExactly(expected);
    }

    @Test
    void structuredTopicContextUsesHashAndExactKeyWithinRecipientBoundary() {
        String topic = "release.2026-q3";
        UUID expected = notification(42, 900018, messaging, "DIRECT");
        UUID prefixOnly = notification(42, 900018, messaging, "DIRECT");
        UUID otherUser = notification(42, 900019, messaging, "DIRECT");
        UUID otherTenant = notification(43, 900018, messaging, "DIRECT");
        context(42, 900018, expected, topic);
        context(42, 900018, prefixOnly, topic + "-hotfix");
        context(42, 900019, otherUser, topic);
        context(43, 900018, otherTenant, topic);
        scope.applyUser(ACTOR);

        assertThat(inbox(InboxView.ALL, context("TOPIC_TOKEN", topic), 100, null))
                .extracting(row -> row.item().notificationId())
                .containsExactly(expected);
    }

    @Test
    void multipleContextsUseOrWithinKindAndAndAcrossKinds() {
        UUID first = notification(42, 900018, messaging, "DIRECT");
        UUID second = notification(42, 900018, messaging, "DIRECT");
        UUID actorOnly = notification(42, 900018, messaging, "DIRECT");
        UUID resourceOnly = notification(42, 900018, messaging, "DIRECT");
        UUID otherUser = notification(42, 900019, messaging, "DIRECT");
        UUID otherTenant = notification(43, 900018, messaging, "DIRECT");
        jdbc.update("""
                UPDATE ntf_user_notifications
                   SET actor_ref = CASE
                         WHEN notification_id IN (?, ?, ?) THEN 'user:42'
                         WHEN notification_id = ? THEN 'user:84'
                         ELSE 'user:100'
                       END,
                       subject_ref = CASE
                         WHEN notification_id IN (?, ?, ?, ?) THEN 'project:renewal'
                         ELSE 'project:other'
                       END
                 WHERE notification_id IN (?, ?, ?, ?, ?, ?)
                """, first, actorOnly, otherUser, second,
                first, second, resourceOnly, otherTenant,
                first, second, actorOnly, resourceOnly, otherUser, otherTenant);
        scope.applyUser(ACTOR);

        var filters = new InboxFilters(
                null, null, null, null, null,
                List.of(),
                List.of(
                        new NotificationQueryRepository.InboxContextFilter("ACTOR", "user:42"),
                        new NotificationQueryRepository.InboxContextFilter("ACTOR", "user:84"),
                        new NotificationQueryRepository.InboxContextFilter(
                                "RESOURCE", "project:renewal")),
                null, null);

        assertThat(inbox(InboxView.ALL, filters, 100, null))
                .extracting(row -> row.item().notificationId())
                .containsExactlyInAnyOrder(first, second)
                .doesNotContain(actorOnly, resourceOnly, otherUser, otherTenant);
    }

    @Test
    void materializedPrioritizeFacetAndTotalStayInsideTheRecipientBoundary() {
        UUID expected = notification(42, 900018, messaging, "DIRECT");
        UUID ordinary = notification(42, 900018, messaging, "DIRECT");
        UUID otherUser = notification(42, 900019, messaging, "DIRECT");
        UUID otherTenant = notification(43, 900018, messaging, "DIRECT");
        jdbc.update("""
                UPDATE ntf_user_notifications
                   SET attention_rule_id = notification_id,
                       attention_scope_kind = 'ACTOR',
                       attention_effect = 'PRIORITIZE',
                       attention_rule_revision = 1,
                       attention_policy_source = 'USER'
                 WHERE notification_id IN (?, ?, ?)
                """, expected, otherUser, otherTenant);
        scope.applyUser(ACTOR);
        var filters = new InboxFilters(
                null, null, null, null, null, "PRIORITIZE",
                List.of(), List.of(), null, null);

        assertThat(inbox(InboxView.ALL, filters, 100, null))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.item().notificationId()).isEqualTo(expected);
                    assertThat(row.item().attentionEffect()).isEqualTo("PRIORITIZE");
                });
        assertThat(repository.inboxTotal(ACTOR, InboxView.ALL, filters)).isEqualTo(1L);
        assertThat(inbox(InboxView.ALL, filters(null), 100, null))
                .extracting(row -> row.item().notificationId())
                .contains(expected, ordinary)
                .doesNotContain(otherUser, otherTenant);
    }

    private List<InboxRow> inbox(InboxView view, InboxFilters filters, int limit, InboxCursor cursor) {
        return repository.inbox(ACTOR, view, filters, limit, cursor);
    }

    private InboxFilters filters(String reason) {
        return new InboxFilters(
                null, null, null, null, reason, List.of(), List.of(), null, null);
    }

    private InboxFilters context(String kind, String key) {
        return new InboxFilters(
                null, null, null, null, null,
                List.of(),
                List.of(new NotificationQueryRepository.InboxContextFilter(kind, key)), null, null);
    }

    private void context(long tenant, long user, UUID notificationId, String key) {
        jdbc.update("""
                INSERT INTO ntf_recipient_notification_contexts (
                    tenant_id, user_id, notification_id, kind,
                    context_key, context_key_hash, matchable)
                VALUES (?, ?, ?, 'TOPIC', ?, ?, TRUE)
                """, tenant, user, notificationId, key, NotificationAttentionScope.sha256(key));
    }

    private TypeFixture type(String appKey) {
        UUID typeId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        String key = "REASON_TEST." + typeId;
        jdbc.update("""
                INSERT INTO ntf_notification_types
                    (type_id, scope_type, scope_id, type_key, owner_app_key, owner_team, lifecycle_state)
                VALUES (?, 'PROVIDER', 'GLOBAL', ?, ?, 'test', 'ACTIVE')
                """, typeId, key, appKey);
        jdbc.update("""
                INSERT INTO ntf_notification_type_versions
                    (type_version_id, type_id, version, source_event_type, min_schema_version,
                     max_schema_version, priority, urgency, lifecycle_state)
                VALUES (?, ?, 1, 'test.reason', 1, 1, 'NORMAL', 'INFORMATIONAL', 'ACTIVE')
                """, versionId, typeId);
        jdbc.update("""
                INSERT INTO ntf_template_versions
                    (template_version_id, type_version_id, channel, locale, version, title_template,
                     preview_template, body_template, state, checksum)
                VALUES (?, ?, 'IN_APP', 'en', 1, 'Test', 'Test', 'Test', 'PUBLISHED', ?)
                """, templateId, versionId, "0".repeat(64));
        return new TypeFixture(key, versionId, templateId);
    }

    private UUID notification(long tenant, long user, TypeFixture type, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ntf_notifications
                    (notification_id, tenant_id, type_version_id, type_scope_tenant_id, type_key,
                     thread_key, safe_body, first_activity_at, last_activity_at)
                VALUES (?, ?, ?, 0, ?, ?, 'Test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, tenant, type.versionId(), type.key(), "test:" + id);
        jdbc.update("""
                INSERT INTO ntf_user_notifications
                    (tenant_id, user_id, notification_id, reason_code, effective_priority, locale,
                     in_app_template_version_id, template_scope_tenant_id, safe_title, safe_preview,
                     search_text, last_activity_at, change_version)
                VALUES (?, ?, ?, ?, 'NORMAL', 'en', ?, 0, 'Test', 'Test', 'Test', CURRENT_TIMESTAMP, 1)
                """, tenant, user, id, reason, type.templateId());
        return id;
    }

    private record TypeFixture(String key, UUID versionId, UUID templateId) { }

}

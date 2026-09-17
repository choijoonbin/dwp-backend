package com.dwp.services.platform.workplace.workplaceassistant;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceAssistantConcurrencyPostgresTest {
    private static final Instant FIXED = Instant.parse("2026-09-17T00:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
    private static final long ACTOR = 23_001L;
    private static final AtomicLong TENANTS = new AtomicLong(9_996_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static WorkplaceAssistantRequestService requests;
    private static CountingSuggestionProvider provider;

    @BeforeAll
    static void migrateAndBuildService() {
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
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(source);
        WorkplaceAssistantRepository repository = new WorkplaceAssistantRepository(named, mapper);
        WorkplaceAssistantAuditRepository audit = new WorkplaceAssistantAuditRepository(
                named, mapper);
        provider = new CountingSuggestionProvider();
        WorkplaceAssistantSupport support = new WorkplaceAssistantSupport(
                repository, List.of(provider), mapper,
                Clock.fixed(FIXED, ZoneOffset.UTC));
        requests = new WorkplaceAssistantRequestService(repository, audit,
                new WorkplaceAssistantRedactor(), mapper, support);
    }

    @Test
    void sameKeyConcurrentCreateInvokesProviderOnceAndReturnsExactReplay() throws Exception {
        long tenantId = tenant();
        CreateAssistantRequest request = request("Book one quiet desk");
        CountDownLatch start = new CountDownLatch(1);
        int before = provider.calls.get();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AssistantCommandResult> first = executor.submit(
                    () -> createAfter(start, tenantId, "same-key", request));
            Future<AssistantCommandResult> second = executor.submit(
                    () -> createAfter(start, tenantId, "same-key", request));
            start.countDown();
            AssistantCommandResult one = first.get();
            AssistantCommandResult two = second.get();

            assertThat(one.request().requestId()).isEqualTo(two.request().requestId());
            assertThat(List.of(one.receipt().replayed(), two.receipt().replayed()))
                    .containsExactlyInAnyOrder(false, true);
        }

        assertThat(provider.calls.get() - before).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_assistant_requests
                 WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
                """, Long.class, tenantId, ACTOR, "same-key")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_assistant_commands
                 WHERE tenant_id = ? AND actor_user_id = ?
                   AND command_type = 'CREATE_REQUEST' AND idempotency_key = ?
                """, Long.class, tenantId, ACTOR, "same-key")).isEqualTo(1L);
    }

    @Test
    void sameKeyConcurrentDifferentPayloadAllowsOneAndConflictsTheOther() throws Exception {
        long tenantId = tenant();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Outcome> first = executor.submit(() -> outcomeAfter(
                    start, tenantId, "conflict-key", request("Book a desk")));
            Future<Outcome> second = executor.submit(() -> outcomeAfter(
                    start, tenantId, "conflict-key", request("Book a room")));
            start.countDown();
            List<Outcome> outcomes = List.of(first.get(), second.get());

            assertThat(outcomes).filteredOn(Outcome::success).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> !outcome.success()).singleElement()
                    .extracting(Outcome::errorCode).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_assistant_requests
                 WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
                """, Long.class, tenantId, ACTOR, "conflict-key")).isEqualTo(1L);
    }

    private static AssistantCommandResult createAfter(
            CountDownLatch start, long tenantId, String key, CreateAssistantRequest request)
            throws Exception {
        start.await();
        return transaction.execute(status -> requests.create(
                tenantId, ACTOR, UUID.randomUUID(), "Trusted user", "group:employee", "en",
                key, "corr-" + key, request));
    }

    private static Outcome outcomeAfter(
            CountDownLatch start, long tenantId, String key, CreateAssistantRequest request)
            throws Exception {
        try {
            return new Outcome(true, null, createAfter(start, tenantId, key, request));
        } catch (BaseException exception) {
            return new Outcome(false, exception.getErrorCode(), null);
        }
    }

    private static long tenant() {
        long tenantId = TENANTS.incrementAndGet();
        jdbc.update("""
                INSERT INTO sys_service_tenants (
                    provider_tenant_id, tenant_id, tenant_key, display_name, lifecycle_state,
                    data_region, isolation_model, created_by, updated_by)
                VALUES (?, ?, ?, 'Assistant test', 'ACTIVE', 'kr', 'POOL', ?, ?)
                """, UUID.randomUUID(), tenantId, "assistant-" + tenantId, ACTOR, ACTOR);
        jdbc.update("""
                INSERT INTO wp_assistant_governance (
                    tenant_id, tenant_opt_in, kill_switch, model_provider_reference,
                    model_version, prompt_version, tool_version, retention_days,
                    feedback_use_enabled, redaction_state, version, updated_by)
                VALUES (?, TRUE, FALSE, 'TEST_PROVIDER', 'model-1', 'prompt-1', 'tool-1',
                        30, TRUE, 'READY', 1, ?)
                """, tenantId, ACTOR);
        return tenantId;
    }

    private static CreateAssistantRequest request(String text) {
        RequestedBookingItem item = new RequestedBookingItem(
                "item-one", ACTOR, UUID.randomUUID(), "Trusted user", null,
                ResourceType.DESK, null, null, null, NOW.plusDays(1),
                NOW.plusDays(1).plusHours(1), "Focus", true, false, List.of("quiet"));
        return new CreateAssistantRequest(text, List.of(item), true, false,
                "Create an explicit suggestion");
    }

    private static final class CountingSuggestionProvider
            implements WorkplaceAssistantSuggestionProvider {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean supports(String providerReference) {
            return "TEST_PROVIDER".equals(providerReference);
        }

        @Override
        public SuggestionResult suggest(SuggestionContext context) {
            calls.incrementAndGet();
            try {
                Thread.sleep(150);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new SuggestionResult(context.requestedItems().stream()
                    .map(item -> new SuggestedItem(item.clientItemKey(),
                            "Suggestion only; authority validation is required.",
                            List.of("user-constraints"), List.of()))
                    .toList(), List.of("Availability and policy are not yet validated."));
        }
    }

    private record Outcome(
            boolean success, ErrorCode errorCode, AssistantCommandResult result) { }
}

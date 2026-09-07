package com.dwp.services.platform.workhub.personal;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.dwp.services.platform.workhub.personal.PersonalWorkDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class PersonalWorkPostgresIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static PersonalWorkRepository repository;
    private final AccessContext owner = context(7L, 11L);
    private PlatformAuditService audit;
    private PersonalWorkService service;

    @BeforeAll
    static void migrate() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new PersonalWorkRepository(jdbc);
        jdbc.execute(new ClassPathResource("db/migration/V223__create_personal_work_runtime.sql")
                .getContentAsString(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE personal_work_timeline, personal_work_tasks, personal_work_day_plan_items, personal_work_day_plans, personal_work_command_receipts");
        audit = mock(PlatformAuditService.class);
        when(audit.successWithId(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> UUID.randomUUID());
        service = new PersonalWorkService(repository, new PersonalWorkAccess(), List.of(), audit,
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void lifecycleIsOptimisticScopedAndAuditedWithoutDeletingHistory() {
        Task created = create();
        assertCode(() -> tx(() -> service.get(context(8L, 11L), created.taskId())), ErrorCode.NOT_FOUND);
        assertCode(() -> tx(() -> service.get(context(7L, 12L), created.taskId())), ErrorCode.NOT_FOUND);
        Task running = change(created, Status.IN_PROGRESS);
        assertCode(() -> tx(() -> service.transition(owner, created.taskId(), UUID.randomUUID(), null,
                new StatusRequest(Status.COMPLETED, created.version()))), ErrorCode.RESOURCE_CONFLICT);
        Task waiting = change(running, Status.WAITING);
        Task completed = change(waiting, Status.COMPLETED);
        assertThat(completed.completedAt()).isNotNull();
        Task reopened = change(completed, Status.OPEN);
        assertThat(reopened.completedAt()).isNull();
        Task archived = change(reopened, Status.ARCHIVED);
        assertThat(tx(() -> service.list(owner, null, 0, 50)).items()).isEmpty();
        assertThat(tx(() -> service.list(owner, Status.ARCHIVED, 0, 50)).items()).hasSize(1);
        TimelinePage timeline = tx(() -> service.timeline(owner, archived.taskId(), 0, 100));
        assertThat(timeline.items()).hasSize(6);
        assertThat(timeline.items().stream().map(TimelineEvent::version)).containsExactly(5L, 4L, 3L, 2L, 1L, 0L);
        assertThat(timeline.items()).allMatch(item -> item.auditRecordId() != null);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM personal_work_timeline WHERE task_id = ?", archived.taskId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void sameCommandReplaysOriginalResponseAndDifferentPayloadConflicts() {
        UUID key = UUID.randomUUID();
        CreateTaskRequest request = new CreateTaskRequest("First", null, Priority.HIGH, null, null);
        Task first = tx(() -> service.create(owner, key, "correlation", request));
        Task changed = tx(() -> service.update(owner, first.taskId(), UUID.randomUUID(), null,
                new UpdateTaskRequest("Renamed", null, Priority.LOW, null, null, false, first.version())));
        Task replayed = tx(() -> service.create(owner, key, "different-correlation", request));
        assertThat(replayed.taskId()).isEqualTo(first.taskId());
        assertThat(replayed.title()).isEqualTo("First");
        assertThat(replayed.version()).isZero();
        assertThat(changed.title()).isEqualTo("Renamed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_work_tasks", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_work_timeline", Integer.class)).isEqualTo(2);
        assertCode(() -> tx(() -> service.create(owner, key, null,
                new CreateTaskRequest("Different", null, Priority.HIGH, null, null))), ErrorCode.RESOURCE_CONFLICT);
    }

    @Test
    void simultaneousSameCommandCreatesOneTaskAndOneTimelineEvent() throws Exception {
        UUID key = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Task> command = () -> {
                start.await(10, TimeUnit.SECONDS);
                return tx(() -> service.create(owner, key, null,
                        new CreateTaskRequest("Concurrent", null, Priority.NORMAL, null, null)));
            };
            var first = executor.submit(command);
            var second = executor.submit(command);
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).taskId()).isEqualTo(second.get(20, TimeUnit.SECONDS).taskId());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_work_tasks", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_work_timeline", Integer.class)).isOne();
    }

    @Test
    void planningAndReorderingNeverChangeTaskDatesLifecycleOrPriorDays() {
        Task first = create();
        Task second = create();
        LocalDate today = LocalDate.of(2026, 9, 4);
        DayPlan selected = tx(() -> service.replaceDayPlan(owner, today, UUID.randomUUID(), null,
                new ReplaceDayPlanRequest(List.of(reference(first), reference(second)), 0L)));
        assertThat(selected.version()).isEqualTo(1);
        DayPlan reordered = tx(() -> service.replaceDayPlan(owner, today, UUID.randomUUID(), null,
                new ReplaceDayPlanRequest(List.of(selected.items().get(1).selectionReference(), selected.items().get(0).selectionReference()), 1L)));
        assertThat(reordered.items().getFirst().source().reference()).isEqualTo(reference(second));
        assertThat(tx(() -> service.get(owner, first.taskId())).version()).isZero();
        assertThat(tx(() -> service.get(owner, first.taskId())).dueAt()).isEqualTo(first.dueAt());
        assertThat(tx(() -> service.dayPlan(owner, today.plusDays(1))).items()).isEmpty();
        assertThat(tx(() -> service.dayPlan(owner, today)).items()).hasSize(2);
        Task completed = change(first, Status.COMPLETED);
        assertThat(tx(() -> service.dayPlan(owner, today)).items()).hasSize(2);
        assertThat(completed.status()).isEqualTo(Status.COMPLETED);
        assertCode(() -> tx(() -> service.replaceDayPlan(owner, today, UUID.randomUUID(), null,
                new ReplaceDayPlanRequest(List.of(), 1L))), ErrorCode.RESOURCE_CONFLICT);
    }

    @Test
    void auditFailureRollsBackTaskMutationAndCommandReceipt() {
        when(audit.successWithId(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Audit unavailable"));
        assertThatThrownBy(this::create).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_work_tasks", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_work_command_receipts", Integer.class)).isZero();
    }

    @Test
    void crossOwnerNativeReferenceAndDuplicateSelectionAreRejectedAtomically() {
        Task task = create();
        assertCode(() -> tx(() -> service.replaceDayPlan(context(7L, 12L), LocalDate.now(), UUID.randomUUID(), null,
                new ReplaceDayPlanRequest(List.of(reference(task)), 0L))), ErrorCode.RESOURCE_NOT_AVAILABLE);
        assertCode(() -> tx(() -> service.replaceDayPlan(owner, LocalDate.now(), UUID.randomUUID(), null,
                new ReplaceDayPlanRequest(List.of(reference(task), reference(task)), 0L))), ErrorCode.INVALID_INPUT_VALUE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_work_day_plans", Integer.class)).isZero();
    }

    private Task create() {
        return tx(() -> service.create(owner, UUID.randomUUID(), null,
                new CreateTaskRequest("Owned task", "Private note", Priority.NORMAL,
                        OffsetDateTime.parse("2026-09-10T10:00:00Z"), null)));
    }

    private Task change(Task task, Status status) {
        return tx(() -> service.transition(owner, task.taskId(), UUID.randomUUID(), null,
                new StatusRequest(status, task.version())));
    }

    private SourceReference reference(Task task) { return new SourceReference("PERSONAL_TASK", task.taskId().toString(), null); }
    private static AccessContext context(Long tenant, Long user) {
        return new AccessContext(tenant, user, "APP.WORK:VIEW,APP.WORK:UPDATE", null, null, null);
    }
    private <T> T tx(Supplier<T> action) { return transactions.execute(status -> action.get()); }
    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}

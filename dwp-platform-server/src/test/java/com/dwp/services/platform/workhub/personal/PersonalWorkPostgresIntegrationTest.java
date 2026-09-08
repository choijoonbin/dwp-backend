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
        for (String migration : List.of("V223__create_personal_work_runtime.sql",
                "V228__extend_personal_work_checklists_sources_and_deletion.sql")) {
            jdbc.execute(new ClassPathResource("db/migration/" + migration).getContentAsString(StandardCharsets.UTF_8));
        }
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
    void preExtensionCommandReceiptStillReplaysAfterTheSchemaUpgrade() throws Exception {
        UUID key = UUID.randomUUID();
        CreateTaskRequest request = new CreateTaskRequest("Legacy command", null, Priority.NORMAL, null, null);
        Task first = tx(() -> service.create(owner, key, null, request));
        String legacyRequest = "{\"title\":\"Legacy command\",\"description\":null,\"priority\":\"NORMAL\",\"dueAt\":null,\"sourceReference\":null}";
        String fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(legacyRequest.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("UPDATE personal_work_command_receipts SET request_fingerprint = ? WHERE command_id = ?", fingerprint, key);
        assertThat(tx(() -> service.create(owner, key, null, request)).taskId()).isEqualTo(first.taskId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_work_tasks", Integer.class)).isOne();
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


    @Test
    void checklistAndMultipleSourcesRoundTripPreserveOrderAndRequireOwnedSources() {
        Task first = create();
        Task second = create();
        UUID itemId = UUID.randomUUID();
        Task task = tx(() -> service.create(owner, UUID.randomUUID(), null,
                new CreateTaskRequest("Checklist task", null, Priority.NORMAL, null, null,
                        List.of(new ChecklistItem(itemId, "  Check evidence  ", false)),
                        List.of(reference(first), reference(second)))));
        assertThat(task.checklist()).containsExactly(new ChecklistItem(itemId, "Check evidence", false));
        assertThat(task.sources()).extracting(SourceLink::reference).containsExactly(reference(first), reference(second));
        assertThat(task.source()).isEqualTo(task.sources().getFirst());
        Task updated = tx(() -> service.update(owner, task.taskId(), UUID.randomUUID(), null,
                new UpdateTaskRequest(task.title(), null, task.priority(), null, null, false, task.version(),
                        List.of(new ChecklistItem(itemId, "Check evidence", true)),
                        List.of(reference(second), reference(first)))));
        assertThat(updated.checklist().getFirst().completed()).isTrue();
        assertThat(updated.status()).isEqualTo(Status.OPEN);
        assertThat(updated.sources()).extracting(SourceLink::reference).containsExactly(reference(second), reference(first));
        Task preserved = tx(() -> service.update(owner, task.taskId(), UUID.randomUUID(), null,
                new UpdateTaskRequest("Legacy edit", null, task.priority(), null, null, false, updated.version())));
        assertThat(preserved.checklist()).isEqualTo(updated.checklist());
        assertThat(preserved.sources()).isEqualTo(updated.sources());
        assertCode(() -> tx(() -> service.update(owner, task.taskId(), UUID.randomUUID(), null,
                new UpdateTaskRequest("Stale edit", null, task.priority(), null, null, false, updated.version(),
                        List.of(), List.of()))), ErrorCode.RESOURCE_CONFLICT);
        Task cleared = tx(() -> service.update(owner, task.taskId(), UUID.randomUUID(), null,
                new UpdateTaskRequest(task.title(), null, task.priority(), null, null, false, preserved.version(),
                        List.of(), List.of())));
        assertThat(cleared.checklist()).isEmpty();
        assertThat(cleared.sources()).isEmpty();
        assertThat(cleared.source()).isNull();
        assertCode(() -> tx(() -> service.create(context(7L, 12L), UUID.randomUUID(), null,
                new CreateTaskRequest("Foreign reference", null, Priority.NORMAL, null, null, List.of(),
                        List.of(reference(first))))), ErrorCode.RESOURCE_NOT_AVAILABLE);
    }

    @Test
    void malformedChecklistAndDuplicateSourceReferencesAreRejectedBeforeWriting() {
        Task source = create();
        UUID itemId = UUID.randomUUID();
        assertCode(() -> tx(() -> service.create(owner, UUID.randomUUID(), null,
                new CreateTaskRequest("Duplicate checklist", null, Priority.NORMAL, null, null,
                        List.of(new ChecklistItem(itemId, "One", false), new ChecklistItem(itemId, "Two", true)),
                        List.of()))), ErrorCode.INVALID_INPUT_VALUE);
        assertCode(() -> tx(() -> service.create(owner, UUID.randomUUID(), null,
                new CreateTaskRequest("Duplicate sources", null, Priority.NORMAL, null, null, List.of(),
                        List.of(reference(source), reference(source))))), ErrorCode.INVALID_INPUT_VALUE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_work_tasks", Integer.class)).isOne();
    }

    @Test
    void softDeleteIsScopedIdempotentRemovesSelectionsAndPreservesHistory() {
        UUID createCommand = UUID.randomUUID();
        CreateTaskRequest createRequest = new CreateTaskRequest("Delete me", null, Priority.NORMAL, null, null);
        Task task = tx(() -> service.create(owner, createCommand, null, createRequest));
        Task other = create();
        LocalDate date = LocalDate.of(2026, 9, 7);
        DayPlan plan = tx(() -> service.replaceDayPlan(owner, date, UUID.randomUUID(), null,
                new ReplaceDayPlanRequest(List.of(reference(task), reference(other)), 0L)));
        assertCode(() -> tx(() -> service.delete(context(7L, 12L), task.taskId(), UUID.randomUUID(), null,
                new VersionRequest(task.version()))), ErrorCode.NOT_FOUND);
        assertCode(() -> tx(() -> service.delete(owner, task.taskId(), UUID.randomUUID(), null,
                new VersionRequest(task.version() + 1))), ErrorCode.RESOURCE_CONFLICT);
        UUID deleteCommand = UUID.randomUUID();
        DeleteResult deleted = tx(() -> service.delete(owner, task.taskId(), deleteCommand, null,
                new VersionRequest(task.version())));
        assertThat(deleted.deletedAt()).isNotNull();
        assertThat(deleted.version()).isEqualTo(task.version() + 1);
        assertThat(tx(() -> service.delete(owner, task.taskId(), deleteCommand, null,
                new VersionRequest(task.version())))).isEqualTo(deleted);
        assertCode(() -> tx(() -> service.get(owner, task.taskId())), ErrorCode.NOT_FOUND);
        assertCode(() -> tx(() -> service.create(owner, createCommand, null, createRequest)), ErrorCode.NOT_FOUND);
        assertCode(() -> tx(() -> service.transition(owner, task.taskId(), UUID.randomUUID(), null,
                new StatusRequest(Status.COMPLETED, deleted.version()))), ErrorCode.NOT_FOUND);
        assertThat(tx(() -> service.list(owner, null, 0, 50)).items()).extracting(Task::taskId).containsExactly(other.taskId());
        DayPlan remaining = tx(() -> service.dayPlan(owner, date));
        assertThat(remaining.version()).isEqualTo(plan.version() + 1);
        assertThat(remaining.items()).hasSize(1);
        assertThat(remaining.items().getFirst().position()).isZero();
        assertThat(remaining.items().getFirst().source().reference()).isEqualTo(reference(other));
        assertCode(() -> tx(() -> service.replaceDayPlan(owner, date, UUID.randomUUID(), null,
                new ReplaceDayPlanRequest(List.of(), plan.version()))), ErrorCode.RESOURCE_CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_work_tasks WHERE task_id = ?", Integer.class, task.taskId())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_work_timeline WHERE task_id = ?", Integer.class, task.taskId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT action FROM personal_work_timeline WHERE task_id = ? AND version = 1", String.class, task.taskId())).isEqualTo("DELETED");
    }

    @Test
    void deletionAuditFailureRollsBackTaskAndAffectedPlanTogether() {
        Task task = create();
        LocalDate date = LocalDate.of(2026, 9, 7);
        DayPlan plan = tx(() -> service.replaceDayPlan(owner, date, UUID.randomUUID(), null,
                new ReplaceDayPlanRequest(List.of(reference(task)), 0L)));
        when(audit.successWithId(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Audit unavailable"));
        assertThatThrownBy(() -> tx(() -> service.delete(owner, task.taskId(), UUID.randomUUID(), null,
                new VersionRequest(task.version())))).isInstanceOf(IllegalStateException.class);
        assertThat(tx(() -> service.get(owner, task.taskId())).version()).isEqualTo(task.version());
        assertThat(tx(() -> service.dayPlan(owner, date)).version()).isEqualTo(plan.version());
        assertThat(tx(() -> service.dayPlan(owner, date)).items()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_work_command_receipts WHERE operation = 'DELETE'", Integer.class)).isZero();
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

package com.dwp.services.platform.workhub.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.workhub.personal.PersonalWorkAccess;
import com.dwp.services.platform.workhub.personal.PersonalWorkRepository;
import com.dwp.services.platform.workhub.personal.PersonalWorkService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.dwp.services.platform.workhub.calendar.WorkCalendarDtos.*;
import static com.dwp.services.platform.workhub.personal.PersonalWorkDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class WorkCalendarPostgresIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static WorkCalendarRepository repository;
    private final AccessContext owner = context(7L, 11L);
    private PlatformAuditService audit;
    private PersonalWorkService personal;
    private WorkCalendarService service;

    @BeforeAll
    static void migrate() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new WorkCalendarRepository(jdbc);
        for (String migration : List.of("V223__create_personal_work_runtime.sql", "V224__create_work_calendar_references.sql",
                "V228__extend_personal_work_checklists_sources_and_deletion.sql")) {
            jdbc.execute(new ClassPathResource("db/migration/" + migration).getContentAsString(StandardCharsets.UTF_8));
        }
        // A Calendar-owned sentinel verifies that linking has no scheduling/lifecycle side effect.
        jdbc.execute("CREATE TABLE cal_events (event_id UUID PRIMARY KEY, starts_at TIMESTAMPTZ, ends_at TIMESTAMPTZ, status TEXT)");
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE personal_work_calendar_links, personal_work_timeline, personal_work_tasks, personal_work_day_plan_items, personal_work_day_plans, personal_work_command_receipts, cal_events");
        audit = mock(PlatformAuditService.class);
        when(audit.successWithId(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> UUID.randomUUID());
        personal = new PersonalWorkService(new PersonalWorkRepository(jdbc), new PersonalWorkAccess(),
                List.of(), audit, new ObjectMapper().findAndRegisterModules());
        service = new WorkCalendarService(repository, new PersonalWorkAccess(), personal, List.of(), audit);
    }

    @Test
    void tenantAndOwnerIsolationAppliesToLinkIdentityListAndRemoval() {
        Task task = task(owner);
        UUID linkId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Link link = put(owner, linkId, task, eventId);
        AccessContext otherUser = context(7L, 12L);
        AccessContext otherTenant = context(8L, 11L);
        assertThat(tx(() -> service.list(otherUser, 0, 100)).items()).isEmpty();
        assertThat(tx(() -> service.list(otherTenant, 0, 100)).items()).isEmpty();
        assertCode(() -> tx(() -> service.remove(otherUser, linkId, 0, null)), ErrorCode.NOT_FOUND);
        assertCode(() -> put(otherUser, UUID.randomUUID(), task, eventId), ErrorCode.NOT_FOUND);
        Task ownOtherTask = task(otherTenant);
        Link otherLink = put(otherTenant, linkId, ownOtherTask, eventId);
        assertThat(otherLink.linkId()).isEqualTo(link.linkId());
        assertThat(tx(() -> service.list(owner, 0, 100)).items()).containsExactly(link);
        assertThat(tx(() -> service.list(otherTenant, 0, 100)).items()).containsExactly(otherLink);
    }

    @Test
    void replayPayloadConflictAndTombstoneRetriesHaveStableSemantics() {
        Task task = task(owner);
        UUID linkId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Link first = put(owner, linkId, task, eventId);
        assertThat(first.work().obligationKey()).isNull();
        assertThat(put(owner, linkId, task, eventId)).isEqualTo(first);
        assertCode(() -> put(owner, linkId, task, UUID.randomUUID()), ErrorCode.RESOURCE_CONFLICT);
        assertCode(() -> tx(() -> service.remove(owner, linkId, 9, null)), ErrorCode.RESOURCE_CONFLICT);
        Link removed = tx(() -> service.remove(owner, linkId, 0, null));
        assertThat(removed.state()).isEqualTo("REMOVED");
        assertThat(removed.version()).isEqualTo(1);
        assertThat(tx(() -> service.remove(owner, linkId, 0, null))).isEqualTo(removed);
        assertThat(tx(() -> service.remove(owner, linkId, 1, null))).isEqualTo(removed);
        assertThat(put(owner, linkId, task, eventId)).isEqualTo(removed);
        assertThat(tx(() -> service.list(owner, 0, 100)).items()).isEmpty();
        verify(audit, times(2)).success(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void onlyOneActiveLinkCanOwnAnEventButADeletedLinkDoesNotPreventRelinking() {
        Task firstTask = task(owner);
        Task secondTask = task(owner);
        UUID eventId = UUID.randomUUID();
        Link first = put(owner, UUID.randomUUID(), firstTask, eventId);
        assertCode(() -> put(owner, UUID.randomUUID(), secondTask, eventId), ErrorCode.RESOURCE_CONFLICT);
        tx(() -> service.remove(owner, first.linkId(), 0, null));
        Link replacement = put(owner, UUID.randomUUID(), secondTask, eventId);
        assertThat(replacement.work().sourceReference()).isEqualTo(secondTask.taskId().toString());
        assertThat(tx(() -> service.list(owner, 0, 100)).items()).containsExactly(replacement);
    }

    @Test
    void concurrentLinkCreationCannotBypassActiveEventUniqueness() throws Exception {
        Task task = task(owner);
        UUID eventId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<String> command = () -> {
                start.await(10, TimeUnit.SECONDS);
                try { return put(owner, UUID.randomUUID(), task, eventId).state(); }
                catch (BaseException exception) { return exception.getErrorCode().name(); }
            };
            var first = executor.submit(command);
            var second = executor.submit(command);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("LINKED", "RESOURCE_CONFLICT");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_work_calendar_links", Integer.class)).isOne();
    }

    @Test
    void linkAndUnlinkDoNotChangeSourceTaskOrCalendarOwnedStateAndDoNotVerifyEventAccess() {
        Task task = task(owner);
        UUID eventId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-09-04T09:00:00Z");
        jdbc.update("INSERT INTO cal_events VALUES (?, ?, ?, 'CONFIRMED')", eventId, startsAt, startsAt.plusHours(1));
        var calendarBefore = jdbc.queryForMap("SELECT * FROM cal_events WHERE event_id = ?", eventId);
        Link link = put(owner, UUID.randomUUID(), task, eventId);
        assertThat(link.calendarAvailability()).isEqualTo("REFERENCE_ONLY");
        tx(() -> service.remove(owner, link.linkId(), 0, null));
        assertThat(tx(() -> personal.get(owner, task.taskId()))).isEqualTo(task);
        assertThat(jdbc.queryForMap("SELECT * FROM cal_events WHERE event_id = ?", eventId)).isEqualTo(calendarBefore);
        Link absentEventBookmark = put(owner, UUID.randomUUID(), task, UUID.randomUUID());
        assertThat(absentEventBookmark.calendarAvailability()).isEqualTo("REFERENCE_ONLY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cal_events", Integer.class)).isOne();
    }

    @Test
    void auditFailureRollsBackInsertionAndUnlink() {
        Task task = task(owner);
        Link link = put(owner, UUID.randomUUID(), task, UUID.randomUUID());
        doThrow(new IllegalStateException("Audit unavailable")).when(audit)
                .success(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any(), any());
        assertThatThrownBy(() -> put(owner, UUID.randomUUID(), task, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx(() -> service.remove(owner, link.linkId(), 0, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tx(() -> service.list(owner, 0, 100)).items()).containsExactly(link);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_work_calendar_links", Integer.class)).isOne();
    }

    private Task task(AccessContext context) {
        return tx(() -> personal.create(context, UUID.randomUUID(), null,
                new CreateTaskRequest("Owned task", null, Priority.NORMAL, OffsetDateTime.parse("2026-09-10T10:00:00Z"), null)));
    }
    private Link put(AccessContext context, UUID id, Task task, UUID eventId) {
        return tx(() -> service.put(context, id, new LinkRequest(
                new SourceReference("PERSONAL_TASK", task.taskId().toString(), null), eventId), null));
    }
    private static AccessContext context(Long tenant, Long user) {
        return new AccessContext(tenant, user, "APP.WORK:VIEW,APP.WORK:UPDATE,APP.CALENDAR:VIEW", null, null, null);
    }
    private <T> T tx(Supplier<T> action) { return transactions.execute(status -> action.get()); }
    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}

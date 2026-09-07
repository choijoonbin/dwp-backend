package com.dwp.services.platform.workhub.assignment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.workhub.personal.PersonalWorkAccess;
import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.AccessContext;
import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.Priority;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.dwp.services.platform.workhub.assignment.WorkAssignmentDtos.*;
import static com.dwp.services.platform.workhub.assignment.WorkAssignmentSourceAuthority.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class WorkAssignmentPostgresIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager transactionManager;
    private static WorkAssignmentRepository repository;
    private final AccessContext creator = context(7, 11);
    private final AccessContext assignee = context(7, 12);
    private final AccessContext nextAssignee = context(7, 13);
    private final AccessContext outsider = context(7, 14);
    private final OffsetDateTime dueAt = OffsetDateTime.parse("2026-09-10T10:00:00Z");
    private WorkAssignmentSourceAuthority authority;
    private PlatformAuditService audit;
    private WorkAssignmentService service;

    @BeforeAll
    static void migrate() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        repository = new WorkAssignmentRepository(jdbc);
        jdbc.execute(new ClassPathResource("db/migration/V226__create_work_assignments.sql")
                .getContentAsString(StandardCharsets.UTF_8));
        jdbc.execute("CREATE TABLE assignment_test_audit (event_id UUID PRIMARY KEY, action TEXT NOT NULL)");
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE work_assignment_events, work_assignment_command_receipts, work_assignments, assignment_test_audit");
        authority = mock(WorkAssignmentSourceAuthority.class);
        when(authority.confirmCreate(any(), any(), anyLong())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new ConfirmedTask(invocation.getArgument(1), invocation.getArgument(2), 12,
                    "Human-confirmed task", "Independent Work instructions", Priority.HIGH, dueAt);
        });
        when(authority.inspect(any(), any(), anyLong())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new Inspection(new SourceView(SourceAvailability.AVAILABLE, invocation.getArgument(1),
                    invocation.getArgument(2), "/meetings/confirmed"), true);
        });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(authority).requireReassignment(any(), any(), anyLong());
        audit = mock(PlatformAuditService.class);
        when(audit.successWithId(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    UUID id = UUID.randomUUID();
                    jdbc.update("INSERT INTO assignment_test_audit VALUES (?, ?)", id, invocation.getArgument(2, String.class));
                    return id;
                });
        service = service(repository);
    }

    @Test
    void acceptanceAndWorkProgressAreSeparateOptimisticAuditedTransitions() {
        Task created = create(source()).assignment();
        assertThat(created.assignmentState()).isEqualTo(AssignmentState.PENDING);
        assertThat(created.workState()).isEqualTo(WorkState.OPEN);
        assertThat(created.assignmentRevision()).isZero();
        assertThat(created.version()).isZero();
        assertThat(created.acceptedAt()).isNull();
        assertCode(() -> command(creator, created, Action.ACCEPT, null), ErrorCode.FORBIDDEN);
        assertCode(() -> command(assignee, created, Action.START, null), ErrorCode.RESOURCE_CONFLICT);
        Task accepted = command(assignee, created, Action.ACCEPT, null).assignment();
        assertThat(accepted.assignmentState()).isEqualTo(AssignmentState.ACCEPTED);
        assertThat(accepted.workState()).isEqualTo(WorkState.OPEN);
        assertThat(accepted.acceptedAt()).isNotNull();
        assertCode(() -> command(assignee, created, Action.START, null), ErrorCode.RESOURCE_CONFLICT);
        Task started = command(assignee, accepted, Action.START, null).assignment();
        Task waiting = command(assignee, started, Action.WAIT, null).assignment();
        Task resumed = command(assignee, waiting, Action.START, null).assignment();
        Task completed = command(assignee, resumed, Action.COMPLETE, null).assignment();
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.acceptedAt()).isEqualTo(accepted.acceptedAt());
        assertThat(completed.dueAt()).isEqualTo(dueAt);
        assertThat(completed.assignmentRevision()).isZero();
        assertThat(completed.capabilities()).isEqualTo(new Capabilities(false, false, false, false, false, false, false));
        assertCode(() -> command(creator, completed, Action.CANCEL, "NO_LONGER_NEEDED"), ErrorCode.RESOURCE_CONFLICT);
        assertCode(() -> reassign(completed, nextAssignee.userId()), ErrorCode.RESOURCE_CONFLICT);
        EventPage first = tx(() -> service.events(creator, created.assignmentId(), -1, 2));
        assertThat(first.items()).extracting(Event::version).containsExactly(0L, 1L);
        assertThat(first.nextAfterVersion()).isEqualTo(1);
        assertThat(first.hasMore()).isTrue();
        EventPage remaining = tx(() -> service.events(assignee, created.assignmentId(), first.nextAfterVersion(), 100));
        assertThat(remaining.items()).extracting(Event::action).containsExactly("START", "WAIT", "START", "COMPLETE");
        assertThat(remaining.hasMore()).isFalse();
        assertThat(count("work_assignment_events")).isEqualTo(6);
        assertThat(count("assignment_test_audit")).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_assignment_events e JOIN assignment_test_audit a ON a.event_id=e.audit_record_id", Integer.class)).isEqualTo(6);
    }

    @Test
    void creatorAndCurrentAssigneeAreTheOnlyReadersIncludingListsSourceAndReceipts() {
        SourceIdentity source = source();
        UUID commandId = UUID.randomUUID();
        Task created = tx(() -> service.create(creator, commandId, null, new CreateRequest(source, 3L))).assignment();
        assertThat(tx(() -> service.get(assignee, created.assignmentId())).title()).isEqualTo(created.title());
        assertThat(tx(() -> service.findBySource(creator, source)).assignmentId()).isEqualTo(created.assignmentId());
        assertCode(() -> tx(() -> service.get(outsider, created.assignmentId())), ErrorCode.NOT_FOUND);
        assertCode(() -> tx(() -> service.get(context(8, 11), created.assignmentId())), ErrorCode.NOT_FOUND);
        assertCode(() -> tx(() -> service.findBySource(outsider, source)), ErrorCode.NOT_FOUND);
        assertCode(() -> tx(() -> service.findBySource(creator,
                new SourceIdentity(source.sourceSystem(), source.meetingId(), UUID.randomUUID(), source.candidateId()))), ErrorCode.NOT_FOUND);
        assertCode(() -> tx(() -> service.receipt(assignee, commandId)), ErrorCode.NOT_FOUND);
        assertCode(() -> tx(() -> service.receipt(context(8, 11), commandId)), ErrorCode.NOT_FOUND);
        assertThat(tx(() -> service.list(creator, Scope.ASSIGNED_BY_ME, 0, 20)).totalElements()).isOne();
        assertThat(tx(() -> service.list(creator, Scope.ASSIGNED_TO_ME, 0, 20)).items()).isEmpty();
        assertThat(tx(() -> service.list(assignee, Scope.ASSIGNED_TO_ME, 0, 20)).items()).hasSize(1);
        assertThat(tx(() -> service.list(outsider, Scope.ASSIGNED_BY_ME, 0, 20)).items()).isEmpty();
        assertCode(() -> tx(() -> service.events(outsider, created.assignmentId(), -1, 20)), ErrorCode.NOT_FOUND);
    }

    @Test
    void aReceiptKeepsItsAppliedVersionWhileReplaysReturnCurrentAuthorizedTask() {
        SourceIdentity source = source();
        UUID commandId = UUID.randomUUID();
        CreateRequest request = new CreateRequest(source, 3L);
        MutationResult created = tx(() -> service.create(creator, commandId, "first-correlation", request));
        Task accepted = command(assignee, created.assignment(), Action.ACCEPT, null).assignment();
        MutationResult replay = tx(() -> service.create(creator, commandId, "retry-correlation", request));
        assertThat(replay.assignment().version()).isEqualTo(accepted.version());
        assertThat(replay.receipt().appliedVersion()).isZero();
        assertThat(replay.receipt().appliedAssignmentRevision()).isZero();
        assertThat(replay.receipt().appliedAt()).isEqualTo(created.receipt().appliedAt());
        assertThat(replay.receipt().replayed()).isTrue();
        assertThat(tx(() -> service.receipt(creator, commandId))).isEqualTo(replay);
        assertCode(() -> tx(() -> service.create(creator, commandId, null, new CreateRequest(source, 4L))), ErrorCode.RESOURCE_CONFLICT);
        assertThat(count("work_assignment_events")).isEqualTo(2);
        assertThat(count("work_assignment_command_receipts")).isEqualTo(2);
        verify(authority, times(1)).confirmCreate(any(), any(), anyLong());
    }

    @Test
    void reassignmentInvalidatesOldAcceptanceAndOldAssigneeCommandRecovery() {
        Task created = create(source()).assignment();
        UUID acceptId = UUID.randomUUID();
        VersionCommand accept = version(created, null);
        Task accepted = tx(() -> service.command(assignee, created.assignmentId(), acceptId, null, Action.ACCEPT, accept)).assignment();
        Task reassigned = reassign(accepted, nextAssignee.userId()).assignment();
        assertThat(reassigned.assigneeUserId()).isEqualTo(nextAssignee.userId());
        assertThat(reassigned.createdByUserId()).isEqualTo(creator.userId());
        assertThat(reassigned.assignedByUserId()).isEqualTo(creator.userId());
        assertThat(reassigned.assignmentRevision()).isEqualTo(1);
        assertThat(reassigned.assignmentState()).isEqualTo(AssignmentState.PENDING);
        assertThat(reassigned.workState()).isEqualTo(WorkState.OPEN);
        assertThat(reassigned.acceptedAt()).isNull();
        assertThat(reassigned.dueAt()).isEqualTo(created.dueAt());
        assertCode(() -> tx(() -> service.get(assignee, created.assignmentId())), ErrorCode.NOT_FOUND);
        assertCode(() -> tx(() -> service.receipt(assignee, acceptId)), ErrorCode.NOT_FOUND);
        assertCode(() -> tx(() -> service.command(assignee, created.assignmentId(), acceptId, null, Action.ACCEPT, accept)), ErrorCode.NOT_FOUND);
        assertCode(() -> tx(() -> service.events(assignee, created.assignmentId(), -1, 100)), ErrorCode.NOT_FOUND);
        assertCode(() -> tx(() -> service.command(nextAssignee, created.assignmentId(), UUID.randomUUID(), null,
                Action.ACCEPT, new VersionCommand(reassigned.version(), 0L, null))), ErrorCode.RESOURCE_CONFLICT);
        assertThat(command(nextAssignee, reassigned, Action.ACCEPT, null).assignment().assignmentState()).isEqualTo(AssignmentState.ACCEPTED);
        assertThat(tx(() -> service.list(assignee, Scope.ASSIGNED_TO_ME, 0, 20)).items()).isEmpty();
    }

    @Test
    void decliningRequiresAReasonAndOnlyCreatorCanReassignOrCancel() {
        Task created = create(source()).assignment();
        assertCode(() -> command(assignee, created, Action.DECLINE, null), ErrorCode.INVALID_INPUT_VALUE);
        Task declined = command(assignee, created, Action.DECLINE, "CAPACITY_CONFLICT").assignment();
        assertThat(declined.assignmentState()).isEqualTo(AssignmentState.DECLINED);
        assertThat(declined.workState()).isEqualTo(WorkState.OPEN);
        assertCode(() -> command(assignee, declined, Action.ACCEPT, null), ErrorCode.RESOURCE_CONFLICT);
        assertCode(() -> command(assignee, declined, Action.CANCEL, "NO_LONGER_NEEDED"), ErrorCode.FORBIDDEN);
        assertCode(() -> tx(() -> service.reassign(assignee, declined.assignmentId(), UUID.randomUUID(), null,
                new ReassignRequest(13L, declined.version(), declined.assignmentRevision(), "OWNER_CHANGED"))), ErrorCode.FORBIDDEN);
        assertCode(() -> reassign(declined, assignee.userId()), ErrorCode.RESOURCE_CONFLICT);
        Task reassigned = reassign(declined, nextAssignee.userId()).assignment();
        Task cancelled = command(creator, reassigned, Action.CANCEL, "NO_LONGER_NEEDED").assignment();
        assertThat(cancelled.workState()).isEqualTo(WorkState.CANCELLED);
        assertCode(() -> command(nextAssignee, cancelled, Action.ACCEPT, null), ErrorCode.RESOURCE_CONFLICT);
        assertCode(() -> reassign(cancelled, assignee.userId()), ErrorCode.RESOURCE_CONFLICT);
    }

    @Test
    void revokedSourceIsMinimizedWhileConfirmedWorkCanStillProgressAndNewAssignmentFailsClosed() {
        SourceIdentity source = source();
        Task created = create(source).assignment();
        when(authority.inspect(any(), any(), anyLong())).thenReturn(new Inspection(
                new SourceView(SourceAvailability.UNAVAILABLE, source, 3L, "/meetings/revoked"), true));
        doThrow(new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE)).when(authority).requireReassignment(any(), any(), anyLong());
        Task accepted = command(assignee, created, Action.ACCEPT, null).assignment();
        assertThat(accepted.source()).isEqualTo(new SourceView(SourceAvailability.UNAVAILABLE, null, null, null));
        assertThat(accepted.title()).isEqualTo(created.title());
        assertThat(accepted.description()).isEqualTo(created.description());
        assertThat(accepted.capabilities().canStart()).isTrue();
        assertThat(accepted.capabilities().canComplete()).isTrue();
        assertCode(() -> reassign(accepted, nextAssignee.userId()), ErrorCode.RESOURCE_NOT_AVAILABLE);
        assertThat(tx(() -> service.get(creator, created.assignmentId())).version()).isEqualTo(accepted.version());
        Task completed = command(assignee, accepted, Action.COMPLETE, null).assignment();
        assertThat(completed.workState()).isEqualTo(WorkState.COMPLETED);
        when(authority.confirmCreate(any(), any(), anyLong())).thenThrow(new BaseException(ErrorCode.FORBIDDEN));
        assertCode(() -> create(source()), ErrorCode.FORBIDDEN);
        assertThat(count("work_assignments")).isOne();
    }

    @Test
    void candidateCanOnlyBePromotedOnceEvenAfterCancellationOrReportRebinding() {
        SourceIdentity source = source();
        Task created = create(source).assignment();
        command(creator, created, Action.CANCEL, "NO_LONGER_NEEDED");
        assertCode(() -> create(source), ErrorCode.RESOURCE_CONFLICT);
        SourceIdentity rebound = new SourceIdentity(source.sourceSystem(), UUID.randomUUID(), UUID.randomUUID(), source.candidateId());
        assertCode(() -> create(rebound), ErrorCode.RESOURCE_CONFLICT);
        assertCode(() -> tx(() -> service.create(outsider, UUID.randomUUID(), null, new CreateRequest(source, 3L))), ErrorCode.RESOURCE_CONFLICT);
        Task otherTenant = tx(() -> service.create(context(8, 11), UUID.randomUUID(), null, new CreateRequest(source, 3L))).assignment();
        assertThat(otherTenant.assignmentId()).isNotEqualTo(created.assignmentId());
        assertThat(count("work_assignments")).isEqualTo(2);
    }

    @Test
    void concurrentSameCommandCreatesOneAssignmentAndOneEvidenceSet() throws Exception {
        SourceIdentity source = source();
        UUID commandId = UUID.randomUUID();
        List<Object> results = concurrent(
                () -> tx(() -> service.create(creator, commandId, null, new CreateRequest(source, 3L))),
                () -> tx(() -> service.create(creator, commandId, null, new CreateRequest(source, 3L))));
        assertThat(results).allMatch(MutationResult.class::isInstance);
        MutationResult first = (MutationResult) results.getFirst();
        MutationResult second = (MutationResult) results.getLast();
        assertThat(first.assignment().assignmentId()).isEqualTo(second.assignment().assignmentId());
        assertThat(first.receipt().replayed()).isNotEqualTo(second.receipt().replayed());
        assertThat(count("work_assignments")).isOne();
        assertThat(count("work_assignment_events")).isOne();
        assertThat(count("work_assignment_command_receipts")).isOne();
        assertThat(count("assignment_test_audit")).isOne();
    }

    @Test
    void concurrentDifferentCreatorsCannotPromoteTheSameCandidate() throws Exception {
        SourceIdentity source = source();
        List<Object> results = concurrent(
                () -> tx(() -> service.create(creator, UUID.randomUUID(), null, new CreateRequest(source, 3L))),
                () -> tx(() -> service.create(outsider, UUID.randomUUID(), null, new CreateRequest(source, 3L))));
        assertThat(results.stream().filter(MutationResult.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(ErrorCode.RESOURCE_CONFLICT::equals)).hasSize(1);
        assertThat(count("work_assignments")).isOne();
        assertThat(count("work_assignment_events")).isOne();
        assertThat(count("work_assignment_command_receipts")).isOne();
    }

    @Test
    void concurrentAcceptanceAndReassignmentCannotAcceptAReplacedAssignmentRevision() throws Exception {
        Task created = create(source()).assignment();
        List<Object> results = concurrent(
                () -> command(assignee, created, Action.ACCEPT, null),
                () -> reassign(created, nextAssignee.userId()));
        assertThat(results.stream().filter(MutationResult.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(value -> value == ErrorCode.NOT_FOUND || value == ErrorCode.RESOURCE_CONFLICT)).hasSize(1);
        Task current = tx(() -> service.get(creator, created.assignmentId()));
        assertThat(current.version()).isEqualTo(1);
        if (current.assigneeUserId() == nextAssignee.userId()) {
            assertThat(current.assignmentState()).isEqualTo(AssignmentState.PENDING);
            assertThat(current.assignmentRevision()).isEqualTo(1);
            assertThat(current.acceptedAt()).isNull();
        } else {
            assertThat(current.assigneeUserId()).isEqualTo(assignee.userId());
            assertThat(current.assignmentState()).isEqualTo(AssignmentState.ACCEPTED);
            assertThat(current.assignmentRevision()).isZero();
        }
        assertThat(count("work_assignment_events")).isEqualTo(2);
    }

    @Test
    void auditAndReceiptFailuresRollBackAssignmentEventsAndCommandEvidenceTogether() {
        doAnswer(invocation -> {
                    jdbc.update("INSERT INTO assignment_test_audit VALUES (?, ?)", UUID.randomUUID(), "failing-audit");
                    throw new IllegalStateException("Audit unavailable");
                }).when(audit).successWithId(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any(), any());
        assertThatThrownBy(() -> create(source())).isInstanceOf(IllegalStateException.class);
        assertThat(count("work_assignments")).isZero();
        assertThat(count("work_assignment_events")).isZero();
        assertThat(count("work_assignment_command_receipts")).isZero();
        assertThat(count("assignment_test_audit")).isZero();

        setUp();
        WorkAssignmentRepository failingRepository = spy(repository);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Receipt transaction failed");
        }).when(failingRepository).recordReceipt(any(), any(), anyString(), anyString(), anyString(), any());
        service = service(failingRepository);
        assertThatThrownBy(() -> create(source())).isInstanceOf(IllegalStateException.class);
        assertThat(count("work_assignments")).isZero();
        assertThat(count("work_assignment_events")).isZero();
        assertThat(count("work_assignment_command_receipts")).isZero();
        assertThat(count("assignment_test_audit")).isZero();
    }

    @Test
    void databaseRejectsSourceRebindingAndEvidenceMutationAndStoresNoReceiptContent() {
        Task created = create(source()).assignment();
        assertThatThrownBy(() -> jdbc.update("UPDATE work_assignments SET report_id = ? WHERE assignment_id = ?",
                UUID.randomUUID(), created.assignmentId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE work_assignments SET created_by_user_id = 99 WHERE assignment_id = ?",
                created.assignmentId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM work_assignment_events WHERE assignment_id = ?",
                created.assignmentId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE work_assignment_command_receipts SET applied_version = 999 WHERE assignment_id = ?",
                created.assignmentId())).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT row_to_json(r)::text FROM work_assignment_command_receipts r LIMIT 1", String.class))
                .doesNotContain(created.title(), created.description(), "sourceRoute", "meeting_id", "report_id");
    }

    @Test
    void sourceValidationThatExpiresWhileAcquiringLocksCannotWrite() {
        MutableClock clock = new MutableClock();
        WorkAssignmentRepository delayedRepository = spy(repository);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            clock.advanceSeconds(11);
            return null;
        }).when(delayedRepository).lockCandidate(any(), any());
        service = new WorkAssignmentService(delayedRepository, new PersonalWorkAccess(), authority, audit,
                new ObjectMapper().findAndRegisterModules(), transactionManager, clock);
        assertCode(() -> create(source()), ErrorCode.EXTERNAL_SERVICE_ERROR);
        assertThat(count("work_assignments")).isZero();
        assertThat(count("work_assignment_events")).isZero();
        assertThat(count("work_assignment_command_receipts")).isZero();
        assertThat(count("assignment_test_audit")).isZero();

        service = service(repository);
        Task created = create(source()).assignment();
        WorkAssignmentRepository delayedReassignment = spy(repository);
        doAnswer(invocation -> {
            Object row = invocation.callRealMethod();
            clock.advanceSeconds(11);
            return row;
        }).when(delayedReassignment).find(any(), eq(created.assignmentId()), eq(true));
        service = new WorkAssignmentService(delayedReassignment, new PersonalWorkAccess(), authority, audit,
                new ObjectMapper().findAndRegisterModules(), transactionManager, clock);
        assertCode(() -> reassign(created, nextAssignee.userId()), ErrorCode.EXTERNAL_SERVICE_ERROR);
        assertThat(service.get(creator, created.assignmentId()).version()).isZero();
        assertThat(count("work_assignment_events")).isOne();
        assertThat(count("work_assignment_command_receipts")).isOne();
    }

    @Test
    void reassignmentWhileSourceInspectionIsRunningRevokesTheWaitingView() {
        SourceIdentity source = source();
        Task created = create(source).assignment();
        AtomicBoolean reassigned = new AtomicBoolean();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (reassigned.compareAndSet(false, true)) reassign(created, nextAssignee.userId());
            return new Inspection(new SourceView(SourceAvailability.AVAILABLE, source, 3L, "/meetings/confirmed"), true);
        }).when(authority).inspect(eq(assignee), eq(source), eq(3L));
        assertCode(() -> service.get(assignee, created.assignmentId()), ErrorCode.NOT_FOUND);
        assertThat(service.get(creator, created.assignmentId()).assigneeUserId()).isEqualTo(nextAssignee.userId());
        assertThat(count("work_assignment_events")).isEqualTo(2);
    }

    @Test
    void lockWaitIsBoundedAndAConflictingCommandLeavesNoPartialEvidence() throws Exception {
        Task created = create(source()).assignment();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                repository.find(creator, created.assignmentId(), true).orElseThrow();
                locked.countDown();
                try { release.await(15, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                return null;
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                long started = System.nanoTime();
                assertCode(() -> command(assignee, created, Action.ACCEPT, null), ErrorCode.RESOURCE_CONFLICT);
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(4500L, 9000L);
            } finally { release.countDown(); }
            holder.get(5, TimeUnit.SECONDS);
        }
        assertThat(service.get(creator, created.assignmentId()).version()).isZero();
        assertThat(count("work_assignment_events")).isOne();
        assertThat(count("work_assignment_command_receipts")).isOne();
        assertThat(count("assignment_test_audit")).isOne();
    }

    private MutationResult create(SourceIdentity source) {
        return tx(() -> service.create(creator, UUID.randomUUID(), null, new CreateRequest(source, 3L)));
    }

    private MutationResult command(AccessContext actor, Task task, Action action, String reason) {
        return tx(() -> service.command(actor, task.assignmentId(), UUID.randomUUID(), null, action, version(task, reason)));
    }

    private MutationResult reassign(Task task, long assigneeUserId) {
        return tx(() -> service.reassign(creator, task.assignmentId(), UUID.randomUUID(), null,
                new ReassignRequest(assigneeUserId, task.version(), task.assignmentRevision(), "OWNER_CHANGED")));
    }

    private VersionCommand version(Task task, String reason) {
        return new VersionCommand(task.version(), task.assignmentRevision(), reason);
    }

    private SourceIdentity source() {
        return new SourceIdentity(SourceSystem.MEETING_FOLLOWUP, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private WorkAssignmentService service(WorkAssignmentRepository store) {
        return new WorkAssignmentService(store, new PersonalWorkAccess(), authority, audit,
                new ObjectMapper().findAndRegisterModules(), transactionManager);
    }

    private static AccessContext context(long tenant, long user) {
        return new AccessContext(tenant, user, "APP.WORK:VIEW,APP.WORK:UPDATE", null, null, null);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private <T> T tx(Supplier<T> command) { return command.get(); }

    private List<Object> concurrent(Supplier<Object> first, Supplier<Object> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> race(start, first));
            var b = executor.submit(() -> race(start, second));
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private Object race(CountDownLatch start, Supplier<Object> command) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try { return command.get(); }
        catch (BaseException exception) { return exception.getErrorCode(); }
    }

    private void assertCode(Runnable command, ErrorCode error) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(error));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-04T10:00:00Z");
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}

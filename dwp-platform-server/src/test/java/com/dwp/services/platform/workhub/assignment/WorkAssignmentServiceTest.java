package com.dwp.services.platform.workhub.assignment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.workhub.personal.PersonalWorkAccess;
import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.AccessContext;
import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.Priority;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.dwp.services.platform.workhub.assignment.WorkAssignmentDtos.*;
import static com.dwp.services.platform.workhub.assignment.WorkAssignmentRepository.*;
import static com.dwp.services.platform.workhub.assignment.WorkAssignmentSourceAuthority.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkAssignmentServiceTest {
    private final AccessContext creator = context(7L, 11L, "APP.WORK:VIEW,APP.WORK:UPDATE");
    private final UUID taskId = UUID.randomUUID();
    private final SourceIdentity source = new SourceIdentity(SourceSystem.MEETING_FOLLOWUP,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private WorkAssignmentRepository repository;
    private WorkAssignmentSourceAuthority authority;
    private PlatformAuditService audit;
    private WorkAssignmentService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkAssignmentRepository.class);
        authority = mock(WorkAssignmentSourceAuthority.class);
        audit = mock(PlatformAuditService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new WorkAssignmentService(repository, new PersonalWorkAccess(), authority,
                audit, new ObjectMapper().findAndRegisterModules(), transactionManager);
    }

    @Test
    void allPublicOperationsRequirePositiveIdentityAndExactWorkPermissions() {
        AccessContext readOnly = context(7L, 11L, "APP.WORK:VIEW");
        AccessContext updateOnly = context(7L, 11L, "APP.WORK:UPDATE");
        assertCode(() -> service.create(readOnly, UUID.randomUUID(), null, new CreateRequest(source, 0L)), ErrorCode.FORBIDDEN);
        assertCode(() -> service.get(updateOnly, taskId), ErrorCode.FORBIDDEN);
        assertCode(() -> service.events(context(0L, 11L, "APP.WORK:VIEW"), taskId, -1, 20), ErrorCode.TENANT_MISSING);
        assertCode(() -> service.receipt(context(7L, 0L, "APP.WORK:VIEW"), UUID.randomUUID()), ErrorCode.UNAUTHORIZED);
        assertCode(() -> service.list(context(7L, 11L, "APP.WORK:VIEWER"), Scope.ASSIGNED_TO_ME, 0, 20), ErrorCode.FORBIDDEN);
        verifyNoInteractions(repository, authority, audit);
    }

    @Test
    void callerCannotSupplyMalformedCommandsOrFreeTextReasons() {
        assertCode(() -> service.create(creator, UUID.randomUUID(), null, new CreateRequest(source, -1L)), ErrorCode.INVALID_INPUT_VALUE);
        assertCode(() -> service.command(creator, taskId, UUID.randomUUID(), null, Action.CANCEL,
                new VersionCommand(0L, 0L, null)), ErrorCode.INVALID_INPUT_VALUE);
        assertCode(() -> service.command(creator, taskId, UUID.randomUUID(), null, Action.DECLINE,
                new VersionCommand(0L, 0L, "Private meeting details")), ErrorCode.INVALID_INPUT_VALUE);
        assertCode(() -> service.command(creator, taskId, UUID.randomUUID(), null, Action.ACCEPT,
                new VersionCommand(0L, -1L, null)), ErrorCode.INVALID_INPUT_VALUE);
        assertCode(() -> service.reassign(creator, taskId, UUID.randomUUID(), null,
                new ReassignRequest(0L, 0L, 0L, "OWNER_CHANGED")), ErrorCode.INVALID_INPUT_VALUE);
        assertCode(() -> service.events(creator, taskId, -2, 20), ErrorCode.INVALID_INPUT_VALUE);
        verifyNoInteractions(repository, authority, audit);
    }

    @Test
    void failedOwnerConfirmationCannotCreateAWorkRecord() {
        when(authority.confirmCreate(creator, source, 0L)).thenThrow(new BaseException(ErrorCode.FORBIDDEN));
        assertCode(() -> service.create(creator, UUID.randomUUID(), null, new CreateRequest(source, 0L)), ErrorCode.FORBIDDEN);
        verify(repository, never()).insert(any(), any(), any());
        verify(repository, never()).candidateExists(any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void staleOrMismatchedOwnerConfirmationIsRejectedWithoutPersistence() {
        when(authority.confirmCreate(creator, source, 3L)).thenReturn(
                new ConfirmedTask(source, 4, 12, "Confirmed", null, Priority.NORMAL, null));
        assertCode(() -> service.create(creator, UUID.randomUUID(), null, new CreateRequest(source, 3L)), ErrorCode.RESOURCE_NOT_AVAILABLE);
        SourceIdentity other = new SourceIdentity(SourceSystem.MEETING_FOLLOWUP, source.meetingId(),
                UUID.randomUUID(), source.candidateId());
        when(authority.confirmCreate(creator, source, 3L)).thenReturn(
                new ConfirmedTask(other, 3, 12, "Confirmed", null, Priority.NORMAL, null));
        assertCode(() -> service.create(creator, UUID.randomUUID(), null, new CreateRequest(source, 3L)), ErrorCode.RESOURCE_NOT_AVAILABLE);
        when(authority.confirmCreate(creator, source, 3L)).thenReturn(
                new ConfirmedTask(source, 3, 12, "Confirmed", "x".repeat(4001), Priority.NORMAL, null));
        assertCode(() -> service.create(creator, UUID.randomUUID(), null, new CreateRequest(source, 3L)), ErrorCode.RESOURCE_NOT_AVAILABLE);
        verify(repository, never()).insert(any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void readOnlyViewerGetsNoMutationCapabilitiesDespiteOwnerEvidence() {
        AccessContext readOnly = context(7L, 11L, "APP.WORK:VIEW");
        when(repository.find(readOnly, taskId, false)).thenReturn(Optional.of(row()));
        when(authority.inspect(readOnly, source, 3L)).thenReturn(new Inspection(
                new SourceView(SourceAvailability.AVAILABLE, source, 3L, "/meetings/approved"), true));
        Task result = service.get(readOnly, taskId);
        assertThat(result.source().availability()).isEqualTo(SourceAvailability.AVAILABLE);
        assertThat(result.capabilities()).isEqualTo(new Capabilities(false, false, false, false, false, false, false));
    }

    @Test
    void sourceRevocationAndInvalidOwnerMetadataExposeNoSourceIdentifiersOrLink() {
        when(repository.find(creator, taskId, false)).thenReturn(Optional.of(row()));
        when(authority.inspect(creator, source, 3L)).thenReturn(new Inspection(
                new SourceView(SourceAvailability.UNAVAILABLE, source, 3L, "/meetings/private"), true));
        Task unavailable = service.get(creator, taskId);
        assertThat(unavailable.source()).isEqualTo(new SourceView(SourceAvailability.UNAVAILABLE, null, null, null));
        assertThat(unavailable.title()).isEqualTo("Human-confirmed Work title");
        assertThat(unavailable.description()).isEqualTo("Work instructions");
        assertThat(unavailable.capabilities().canCancel()).isTrue();
        assertThat(unavailable.capabilities().canReassign()).isFalse();
        when(authority.inspect(creator, source, 3L)).thenReturn(new Inspection(
                new SourceView(SourceAvailability.AVAILABLE, source, 3L, "https://untrusted.invalid/source"), true));
        assertThat(service.get(creator, taskId).source().availability()).isEqualTo(SourceAvailability.UNAVAILABLE);
        SourceIdentity other = new SourceIdentity(SourceSystem.MEETING_FOLLOWUP, source.meetingId(), source.reportId(), UUID.randomUUID());
        when(authority.inspect(creator, source, 3L)).thenReturn(new Inspection(
                new SourceView(SourceAvailability.AVAILABLE, other, 3L, "/meetings/other"), true));
        assertThat(service.get(creator, taskId).source().reference()).isNull();
    }

    @Test
    void replayChecksCurrentParticipantBeforeReturningAnyCommandEvidence() {
        UUID commandId = UUID.randomUUID();
        when(repository.receipt(creator, commandId)).thenReturn(Optional.of(new ReceiptRow(commandId,
                taskId, "ACCEPT", taskId.toString(), "0".repeat(64), 1, 0, OffsetDateTime.now())));
        when(repository.find(creator, taskId, true)).thenReturn(Optional.empty());
        assertCode(() -> service.command(creator, taskId, commandId, null, Action.ACCEPT,
                new VersionCommand(0L, 0L, null)), ErrorCode.NOT_FOUND);
        verifyNoInteractions(authority, audit);
    }

    @Test
    void maximumSizedWorkPageNeverWaitsForMeetingSourceInspection() {
        List<AssignmentRow> rows = IntStream.range(0, 100).mapToObj(index -> row(UUID.randomUUID())).toList();
        when(repository.count(creator, Scope.ASSIGNED_BY_ME)).thenReturn(100L);
        when(repository.list(creator, Scope.ASSIGNED_BY_ME, 0, 100)).thenReturn(rows);
        when(repository.find(eq(creator), any(), eq(false))).thenAnswer(invocation -> rows.stream()
                .filter(row -> row.assignmentId().equals(invocation.getArgument(1))).findFirst());
        TaskPage page = service.list(creator, Scope.ASSIGNED_BY_ME, 0, 100);
        assertThat(page.items()).hasSize(100);
        assertThat(page.totalElements()).isEqualTo(100);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.items()).allSatisfy(task -> {
            assertThat(task.source()).isEqualTo(new SourceView(SourceAvailability.NOT_REQUESTED, null, null, null));
            assertThat(task.title()).isEqualTo("Human-confirmed Work title");
            assertThat(task.capabilities().canCancel()).isTrue();
            assertThat(task.capabilities().canReassign()).isFalse();
        });
        verifyNoInteractions(authority, audit);
        verify(repository).list(creator, Scope.ASSIGNED_BY_ME, 0, 100);
    }

    private AssignmentRow row() { return row(taskId); }

    private AssignmentRow row(UUID id) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-04T10:00:00Z");
        return new AssignmentRow(id, 11, 11, 12, source, 3, "Human-confirmed Work title",
                "Work instructions", Priority.NORMAL, now.plusDays(3), AssignmentState.PENDING,
                WorkState.OPEN, 0, 0, now, now, null, null);
    }

    private AccessContext context(Long tenant, Long user, String permissions) {
        return new AccessContext(tenant, user, permissions, null, null, null);
    }

    private void assertCode(Runnable command, ErrorCode error) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(error));
    }
}

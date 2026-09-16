package com.dwp.services.platform.workhub.personal;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workhub.personal.PersonalWorkDtos.*;
import static com.dwp.services.platform.workhub.personal.PersonalWorkRepository.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersonalWorkServiceTest {
    private final AccessContext owner = new AccessContext(7L, 11L,
            "APP.WORK:VIEW,APP.WORK:UPDATE", null, null, "en");
    private final UUID taskId = UUID.randomUUID();
    private final SourceReference source = new SourceReference("APPROVAL_TASK", UUID.randomUUID().toString(), "REVIEW");
    private PersonalWorkRepository repository;
    private PersonalWorkSourceResolver resolver;
    private PlatformAuditService audit;
    private PersonalWorkService service;

    @BeforeEach
    void setUp() {
        repository = mock(PersonalWorkRepository.class);
        resolver = mock(PersonalWorkSourceResolver.class);
        audit = mock(PlatformAuditService.class);
        service = new PersonalWorkService(repository, new PersonalWorkAccess(), List.of(resolver),
                audit, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void rejectsMissingIdentityAndReadOnlyMutationBeforeQueryingStorage() {
        assertCode(() -> service.get(new AccessContext(null, 11L, owner.permissions(), null, null, null), taskId), ErrorCode.TENANT_MISSING);
        assertCode(() -> service.create(new AccessContext(7L, 11L, "APP.WORK:VIEW", null, null, null),
                UUID.randomUUID(), null, request(null)), ErrorCode.FORBIDDEN);
        assertCode(() -> service.get(new AccessContext(7L, -1L, owner.permissions(), null, null, null), taskId), ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsUnsupportedUnvalidatedUrlReferencesWithoutCreatingOrAuditing() {
        SourceReference arbitrary = new SourceReference("MAIL", "https://example.com/private", null);
        assertCode(() -> service.create(owner, UUID.randomUUID(), null, request(arbitrary)), ErrorCode.RESOURCE_NOT_AVAILABLE);
        verify(repository, never()).insert(any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void remoteBookmarksNeverMasqueradeAsVerifiedMetadata() {
        when(resolver.supports(source)).thenReturn(true);
        when(resolver.resolve(owner, source)).thenReturn(Optional.of(new ResolvedSource(source, null, null, null, null)));
        when(repository.insert(eq(owner), any(), any())).thenReturn(row(source, Status.OPEN, 0));
        when(audit.successWithId(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());
        Task result = service.create(owner, UUID.randomUUID(), null, request(source));
        assertThat(result.source().availability()).isEqualTo("REFERENCE_ONLY");
        assertThat(result.source().reference()).isEqualTo(source);
        assertThat(result.source().title()).isNull();
        assertThat(result.source().sourceRoute()).isNull();
        assertThat(result.source().status()).isNull();
        assertThat(result.source().dueAt()).isNull();
    }

    @Test
    void currentAclHidesAllMetadataAfterRevocation() {
        when(repository.find(owner, taskId)).thenReturn(Optional.of(row(source, Status.OPEN, 0)));
        when(resolver.supports(source)).thenReturn(true);
        when(resolver.resolve(owner, source)).thenReturn(Optional.empty());
        SourceLink result = service.get(owner, taskId).source();
        assertThat(result).isEqualTo(new SourceLink("UNAVAILABLE", null, null, null, null, null));
    }

    @Test
    void messagePreviewIsAuthoritativeAndCreateRevalidatesAfterRevocation() {
        SourceReference message = new SourceReference(
                "MESSAGING_MESSAGE", UUID.randomUUID().toString(), UUID.randomUUID().toString());
        OffsetDateTime receivedAt = OffsetDateTime.parse("2026-09-04T09:12:00+09:00");
        ResolvedSource resolved = new ResolvedSource(
                message, "공지 초안 정리", "/messages/inbox?conversation=exact", "AVAILABLE", null,
                "DWP Product Room", "김채원 책임", receivedAt, "공지 초안을 정리해 주세요",
                message.obligationKey(), 3L, receivedAt.plusMinutes(2));
        when(resolver.supports(message)).thenReturn(true);
        when(resolver.resolve(owner, message)).thenReturn(Optional.of(resolved), Optional.empty());

        SourceLink preview = service.preflightSource(owner, message);
        assertThat(preview.availability()).isEqualTo("AVAILABLE");
        assertThat(preview.channelName()).isEqualTo("DWP Product Room");
        assertThat(preview.senderName()).isEqualTo("김채원 책임");
        assertThat(preview.excerpt()).isEqualTo("공지 초안을 정리해 주세요");
        assertThat(preview.sourceMessageId()).isEqualTo(message.obligationKey());
        assertThat(preview.sourceVersion()).isEqualTo(3);

        assertCode(() -> service.create(owner, UUID.randomUUID(), null, request(message)),
                ErrorCode.RESOURCE_NOT_AVAILABLE);
        verify(repository, never()).insert(any(), any(), any());
    }

    @Test
    void updateCanKeepRevokedSourceWithoutRevalidatingOrLosingThePrivateLink() {
        when(repository.find(owner, taskId)).thenReturn(Optional.of(row(source, Status.OPEN, 2)));
        when(repository.update(eq(owner), eq(taskId), any())).thenReturn(row(source, Status.OPEN, 3));
        when(audit.successWithId(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());
        Task result = service.update(owner, taskId, UUID.randomUUID(), null,
                new UpdateTaskRequest("Edited", null, Priority.HIGH, null, null, false, 2L));
        ArgumentCaptor<UpdateTaskRequest> effective = ArgumentCaptor.forClass(UpdateTaskRequest.class);
        verify(repository).update(eq(owner), eq(taskId), effective.capture());
        assertThat(effective.getValue().sourceReference()).isEqualTo(source);
        assertThat(result.source().availability()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void staleVersionAndInvalidTransitionNeverWrite() {
        when(repository.find(owner, taskId)).thenReturn(Optional.of(row(null, Status.COMPLETED, 4)));
        assertCode(() -> service.transition(owner, taskId, UUID.randomUUID(), null,
                new StatusRequest(Status.OPEN, 3L)), ErrorCode.RESOURCE_CONFLICT);
        assertCode(() -> service.transition(owner, taskId, UUID.randomUUID(), null,
                new StatusRequest(Status.IN_PROGRESS, 4L)), ErrorCode.RESOURCE_CONFLICT);
        verify(repository, never()).transition(any(), any(), any(), anyLong());
    }

    @Test
    void planningKeepsInaccessibleSelectionWithoutTouchingTaskDatesOrStatus() {
        LocalDate date = LocalDate.of(2026, 9, 4);
        PlanRow before = new PlanRow(date, 1, List.of(source), OffsetDateTime.now());
        when(repository.plan(owner, date)).thenReturn(before);
        when(repository.replacePlan(owner, date, 1, List.of(source)))
                .thenReturn(new PlanRow(date, 2, List.of(source), OffsetDateTime.now()));
        DayPlan loaded = service.dayPlan(owner, date);
        assertThat(loaded.items().getFirst().source().availability()).isEqualTo("UNAVAILABLE");
        SourceReference token = loaded.items().getFirst().selectionReference();
        assertThat(token.sourceSystem()).isEqualTo("DAY_PLAN_SELECTION");
        assertThat(token.sourceReference()).doesNotContain(source.sourceReference());
        DayPlan result = service.replaceDayPlan(owner, date, UUID.randomUUID(), null,
                new ReplaceDayPlanRequest(List.of(token), 1L));
        assertThat(result.items()).hasSize(1);
        verify(repository, never()).update(any(), any(), any());
        verify(repository, never()).transition(any(), any(), any(), anyLong());
    }

    @Test
    void selectionTokenCannotBeReusedForAnotherDay() {
        LocalDate date = LocalDate.of(2026, 9, 4);
        when(repository.plan(owner, date)).thenReturn(new PlanRow(date, 1, List.of(source), OffsetDateTime.now()));
        SourceReference token = service.dayPlan(owner, date).items().getFirst().selectionReference();
        when(repository.plan(owner, date.plusDays(1))).thenReturn(new PlanRow(date.plusDays(1), 0, List.of(), null));
        assertCode(() -> service.replaceDayPlan(owner, date.plusDays(1), UUID.randomUUID(), null,
                new ReplaceDayPlanRequest(List.of(token), 0L)), ErrorCode.NOT_FOUND);
        verify(repository, never()).replacePlan(any(), any(), anyLong(), any());
    }

    @Test
    void nativeUuidAliasesCannotCreateMultipleIdentitiesForTheSameTask() {
        SourceReference shortened = new SourceReference("PERSONAL_TASK", "1-1-1-1-1", null);
        SourceReference upperCase = new SourceReference("PERSONAL_TASK", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", null);
        assertThat(service.resolveNative(owner, shortened)).isEmpty();
        assertThat(service.resolveNative(owner, upperCase)).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void nativeReferenceUsesCanonicalWorkDeepLinkAndRejectsObligationAliases() {
        when(repository.find(owner, taskId)).thenReturn(Optional.of(row(null, Status.OPEN, 0)));
        SourceReference nativeSource = new SourceReference("PERSONAL_TASK", taskId.toString(), null);
        ResolvedSource resolved = service.resolveNative(owner, nativeSource).orElseThrow();
        assertThat(resolved.sourceRoute()).isEqualTo("/work/queue?work=PERSONAL_TASK%3A" + taskId + "%3A");
        assertThat(service.resolveNative(owner, new SourceReference("PERSONAL_TASK", taskId.toString(), "alias"))).isEmpty();
        verify(repository, times(1)).find(owner, taskId);
    }

    private CreateTaskRequest request(SourceReference reference) {
        return new CreateTaskRequest("My task", null, Priority.NORMAL, null, reference);
    }

    private TaskRow row(SourceReference reference, Status status, long version) {
        OffsetDateTime now = OffsetDateTime.now();
        return new TaskRow(taskId, "My task", null, status, Priority.NORMAL, null, reference,
                version, now, now, status == Status.COMPLETED ? now : null);
    }

    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}

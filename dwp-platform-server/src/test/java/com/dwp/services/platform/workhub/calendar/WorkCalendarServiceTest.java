package com.dwp.services.platform.workhub.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.workhub.personal.PersonalWorkAccess;
import com.dwp.services.platform.workhub.personal.PersonalWorkService;
import com.dwp.services.platform.workhub.personal.PersonalWorkSourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workhub.calendar.WorkCalendarDtos.*;
import static com.dwp.services.platform.workhub.personal.PersonalWorkDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkCalendarServiceTest {
    private final AccessContext owner = new AccessContext(7L, 11L,
            "APP.WORK:VIEW,APP.WORK:UPDATE,APP.CALENDAR:VIEW", null, null, null);
    private final SourceReference work = new SourceReference("PERSONAL_TASK", UUID.randomUUID().toString(), null);
    private final UUID linkId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private WorkCalendarRepository repository;
    private PersonalWorkService personal;
    private PersonalWorkSourceResolver resolver;
    private PlatformAuditService audit;
    private WorkCalendarService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkCalendarRepository.class);
        personal = mock(PersonalWorkService.class);
        resolver = mock(PersonalWorkSourceResolver.class);
        audit = mock(PlatformAuditService.class);
        service = new WorkCalendarService(repository, new PersonalWorkAccess(), personal, List.of(resolver), audit);
    }

    @Test
    void writeRequiresWorkUpdateAndCalendarViewBeforeAnyStorageAccess() {
        AccessContext noCalendar = new AccessContext(7L, 11L, "APP.WORK:VIEW,APP.WORK:UPDATE", null, null, null);
        AccessContext readOnly = new AccessContext(7L, 11L, "APP.WORK:VIEW,APP.CALENDAR:VIEW", null, null, null);
        assertCode(() -> service.put(noCalendar, linkId, new LinkRequest(work, eventId), null), ErrorCode.FORBIDDEN);
        assertCode(() -> service.put(readOnly, linkId, new LinkRequest(work, eventId), null), ErrorCode.FORBIDDEN);
        verifyNoInteractions(repository, personal, resolver, audit);
    }

    @Test
    void sameLinkIdentityIsAnIdempotentReplayWithoutSourceMutationOrExtraAudit() {
        Link saved = link("LINKED", 0);
        when(repository.find(owner, linkId)).thenReturn(Optional.of(saved));
        assertThat(service.put(owner, linkId, new LinkRequest(work, eventId), null)).isEqualTo(saved);
        verify(repository, never()).insert(any(), any(), any());
        verifyNoInteractions(personal, resolver, audit);
    }

    @Test
    void replayingRemovedLinkNeverResurrectsItAndChangedPayloadConflicts() {
        Link removed = link("REMOVED", 1);
        when(repository.find(owner, linkId)).thenReturn(Optional.of(removed));
        assertThat(service.put(owner, linkId, new LinkRequest(work, eventId), null).state()).isEqualTo("REMOVED");
        assertCode(() -> service.put(owner, linkId, new LinkRequest(work, UUID.randomUUID()), null), ErrorCode.RESOURCE_CONFLICT);
        verify(repository, never()).insert(any(), any(), any());
    }

    @Test
    void inaccessibleNativeTaskCannotBeLinkedAndDoesNotGenerateAnAudit() {
        when(personal.resolveNative(owner, work)).thenReturn(Optional.empty());
        assertCode(() -> service.put(owner, linkId, new LinkRequest(work, eventId), null), ErrorCode.NOT_FOUND);
        verify(repository, never()).insert(any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void unlinkCanCleanPrivateBookmarkAfterCalendarPermissionWasRevoked() {
        AccessContext noCalendar = new AccessContext(7L, 11L, "APP.WORK:VIEW,APP.WORK:UPDATE", null, null, null);
        when(repository.find(noCalendar, linkId)).thenReturn(Optional.of(link("REMOVED", 1)));
        assertThat(service.remove(noCalendar, linkId, 0, null).state()).isEqualTo("REMOVED");
        verify(repository, never()).remove(any(), any(), anyLong());
        verifyNoInteractions(personal, resolver, audit);
    }

    @Test
    void linkCreationDoesNotInvokeTaskMutationAndCalendarAccessRemainsUnverified() {
        when(repository.find(owner, linkId)).thenReturn(Optional.empty()).thenReturn(Optional.of(link("LINKED", 0)));
        when(personal.resolveNative(owner, work)).thenReturn(Optional.of(new ResolvedSource(work, "Task", "/work/queue", "OPEN", null)));
        Link linked = service.put(owner, linkId, new LinkRequest(work, eventId), null);
        assertThat(linked.calendarAvailability()).isEqualTo("REFERENCE_ONLY");
        verify(personal).resolveNative(owner, work);
        verifyNoMoreInteractions(personal);
        verify(audit).success(eq(7L), eq(11L), eq("workspace.calendar-link.created"),
                eq("WORK_CALENDAR_LINK"), eq(linkId.toString()), isNull(), isNull(), eq(linked));
    }

    private Link link(String state, long version) {
        OffsetDateTime now = OffsetDateTime.now();
        return new Link(linkId, work, eventId, state, version, now, now, "REFERENCE_ONLY");
    }

    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}

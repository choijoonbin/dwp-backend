package com.dwp.services.platform.mail;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailOrganizationTypes.LifecycleAction;
import static com.dwp.services.platform.mail.MailTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MailLifecycleServiceTest {

    @Mock
    private MailLifecycleRepository lifecycle;
    @Mock
    private MailQueryRepository queries;
    @Mock
    private MailCommandRepository evidence;

    private MailLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new MailLifecycleService(lifecycle, queries, evidence);
    }

    @Test
    void trashPreservesThePreviousFolderAndReturnsTheCommittedState() {
        UUID threadId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID inboxId = UUID.randomUUID();
        UUID trashId = UUID.randomUUID();
        var before = new MailLifecycleRepository.LifecycleThread(
                threadId, accountId, inboxId, "INBOX", null, "OPEN", 3L,
                true, false, false, false);
        var target = new MailLifecycleRepository.FolderTarget(
                trashId, accountId, "TRASH", "Trash");
        when(lifecycle.visibleThread(1L, 7L, threadId)).thenReturn(Optional.of(before));
        when(lifecycle.systemTarget(1L, 7L, accountId, "TRASH")).thenReturn(Optional.of(target));
        when(lifecycle.move(1L, 7L, before, target, "TRASHED", inboxId, 3L)).thenReturn(1);
        when(queries.thread(1L, 7L, threadId)).thenReturn(Optional.of(
                thread(threadId, accountId, "TRASH", WorkflowState.TRASHED, 4L)));

        MailOrganizationDtos.LifecycleResult result = service.apply(
                1L, 7L, threadId, "corr-trash",
                new MailOrganizationDtos.LifecycleRequest(LifecycleAction.TRASH, null, 3L));

        assertThat(result.deleted()).isFalse();
        assertThat(result.thread().folderType()).isEqualTo("TRASH");
        verify(evidence).audit(
                eq(1L), eq(7L), eq("mail.thread.trash"), eq("MAIL_THREAD"),
                eq(threadId.toString()), eq("corr-trash"), anyMap(), anyMap());
    }

    @Test
    void staleVersionFailsBeforeAnyMailboxWrite() {
        UUID threadId = UUID.randomUUID();
        var before = new MailLifecycleRepository.LifecycleThread(
                threadId, UUID.randomUUID(), UUID.randomUUID(),
                "INBOX", null, "OPEN", 5L, true, false, false, false);
        when(lifecycle.visibleThread(1L, 7L, threadId)).thenReturn(Optional.of(before));

        assertThatThrownBy(() -> service.apply(
                1L, 7L, threadId, "corr-stale",
                new MailOrganizationDtos.LifecycleRequest(LifecycleAction.ARCHIVE, null, 4L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("changed");

        verify(lifecycle, never()).move(
                eq(1L), eq(7L), eq(before),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void permanentDeleteIsBlockedWhileRetentionIsActive() {
        UUID threadId = UUID.randomUUID();
        var before = new MailLifecycleRepository.LifecycleThread(
                threadId, UUID.randomUUID(), UUID.randomUUID(),
                "TRASH", null, "TRASHED", 2L, false, false, false, false);
        when(lifecycle.visibleThread(1L, 7L, threadId)).thenReturn(Optional.of(before));

        assertThatThrownBy(() -> service.apply(
                1L, 7L, threadId, "corr-delete",
                new MailOrganizationDtos.LifecycleRequest(
                        LifecycleAction.DELETE_FOREVER, null, 2L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("RETENTION_PERIOD_ACTIVE");

        verify(lifecycle, never()).deleteForever(1L, 7L, before, 2L);
    }

    @Test
    void previewResolvesTheGovernedMoveTargetWithoutWriting() {
        UUID threadId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        var before = new MailLifecycleRepository.LifecycleThread(
                threadId, accountId, UUID.randomUUID(),
                "INBOX", null, "OPEN", 4L, false, false, false, false);
        var target = new MailLifecycleRepository.FolderTarget(
                targetId, accountId, "CUSTOM", "Customer follow-up");
        when(lifecycle.visibleThread(1L, 7L, threadId)).thenReturn(Optional.of(before));
        when(lifecycle.target(1L, 7L, accountId, targetId)).thenReturn(Optional.of(target));

        MailOrganizationDtos.LifecyclePreview preview = service.preview(
                1L, 7L, threadId,
                new MailOrganizationDtos.LifecycleRequest(
                        LifecycleAction.MOVE, targetId, 4L));

        assertThat(preview.allowed()).isTrue();
        assertThat(preview.targetFolderId()).isEqualTo(targetId);
        assertThat(preview.targetFolderName()).isEqualTo("Customer follow-up");
        assertThat(preview.affectedCount()).isOne();
        verify(lifecycle, never()).move(
                eq(1L), eq(7L), eq(before), eq(target),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void permanentDeleteExecutesOnlyAfterEveryGatePasses() {
        UUID threadId = UUID.randomUUID();
        var before = new MailLifecycleRepository.LifecycleThread(
                threadId, UUID.randomUUID(), UUID.randomUUID(),
                "TRASH", null, "TRASHED", 8L,
                true, false, false, false);
        when(lifecycle.visibleThread(1L, 7L, threadId)).thenReturn(Optional.of(before));
        when(lifecycle.deleteForever(1L, 7L, before, 8L)).thenReturn(1);

        MailOrganizationDtos.LifecycleResult result = service.apply(
                1L, 7L, threadId, "corr-delete",
                new MailOrganizationDtos.LifecycleRequest(
                        LifecycleAction.DELETE_FOREVER, null, 8L));

        assertThat(result.deleted()).isTrue();
        assertThat(result.thread()).isNull();
        verify(evidence).audit(
                eq(1L), eq(7L), eq("mail.thread.delete.forever"), eq("MAIL_THREAD"),
                eq(threadId.toString()), eq("corr-delete"), anyMap(), anyMap());
    }

    private MailDtos.ThreadSummary thread(
            UUID threadId,
            UUID accountId,
            String folderType,
            WorkflowState workflowState,
            long version) {
        return new MailDtos.ThreadSummary(
                threadId, accountId, "Mina Kim", folderType,
                null, null, "Launch review", "Please review",
                List.of(new MailDtos.Participant("Alex Park", "alex@example.com")),
                OffsetDateTime.now(), false, false, Importance.HIGH,
                TriageLane.PRIORITY, workflowState, null,
                null, null, false, true,
                Classification.CONFIDENTIAL, 2, version);
    }
}

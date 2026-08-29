package com.dwp.services.platform.mail;

import com.dwp.core.exception.BaseException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailDraftCommandReceiptRepository.CommandType.CREATE;
import static com.dwp.services.platform.mail.MailDraftCommandReceiptRepository.CommandType.SAVE;
import static com.dwp.services.platform.mail.MailTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MailDraftServiceTest {

    private final MailService mail = mock(MailService.class);
    private final MailDraftRepository drafts = mock(MailDraftRepository.class);
    private final MailDraftCommandReceiptRepository receipts =
            mock(MailDraftCommandReceiptRepository.class);
    private final MailCommandRepository evidence = mock(MailCommandRepository.class);
    private final MailDraftService service = new MailDraftService(mail, drafts, receipts, evidence);
    private final MailDraftCommandFingerprint fingerprints = new MailDraftCommandFingerprint();

    @Test
    void draftContractAllowsPartialContentWithoutWeakeningSendValidation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var draft = new MailDtos.DraftSaveRequest(
                    null, null, "회의 준비", null, UUID.randomUUID(), null);
            var send = new MailDtos.ComposeRequest(
                    "", null, "", "", DeliveryMode.SEND, UUID.randomUUID());

            assertThat(validator.validate(draft)).isEmpty();
            assertThat(validator.validate(send)).hasSize(3);
        }
    }

    @Test
    void completelyEmptyDraftIsRejectedBeforePersistence() {
        var request = new MailDtos.DraftSaveRequest(
                "  ", "Recipient name only", null, "\n", UUID.randomUUID(), null);

        assertThatThrownBy(() -> service.create(1L, 7L, "corr-empty", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("recipient, subject, or message body");

        verifyNoInteractions(mail, drafts, evidence);
    }

    @Test
    void partialDraftCreationIsIdempotentAndRecordsEvidenceOnce() {
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        var request = new MailDtos.DraftSaveRequest(
                null, null, "회의 준비", null, idempotencyKey, null);
        MailDtos.ThreadDetail detail = detail(threadId, 0L);
        String fingerprint = fingerprints.create(request);
        when(receipts.reserve(1L, 7L, CREATE, idempotencyKey, fingerprint))
                .thenReturn(new MailDraftCommandReceiptRepository.Receipt(
                        fingerprint, null, null, "IN_PROGRESS", true))
                .thenReturn(new MailDraftCommandReceiptRepository.Receipt(
                        fingerprint, threadId, 0L, "COMPLETED", false));
        when(drafts.create(1L, 7L, request))
                .thenReturn(new MailDraftRepository.CreateResult(threadId, true));
        when(mail.thread(1L, 7L, threadId)).thenReturn(detail);

        assertThat(service.create(1L, 7L, "corr-create", request)).isEqualTo(detail);
        assertThat(service.create(1L, 7L, "corr-replay", request)).isEqualTo(detail);

        verify(evidence).audit(
                eq(1L), eq(7L), eq("mail.draft.saved"), eq("MAIL_THREAD"),
                eq(threadId.toString()), eq("corr-create"), anyMap(), anyMap());
        verify(evidence).domainEvent(
                eq(1L), eq("MAIL_THREAD"), eq(threadId),
                eq("mail.draft.saved"), anyMap(), eq("corr-create"));
        verify(evidence, never()).audit(
                eq(1L), eq(7L), eq("mail.draft.saved"), eq("MAIL_THREAD"),
                eq(threadId.toString()), eq("corr-replay"), anyMap(), anyMap());
        verify(receipts).complete(
                1L, 7L, CREATE, idempotencyKey, fingerprint, threadId, 0L);
    }

    @Test
    void repeatedDraftSaveReturnsTheCurrentProjectionWithoutAnotherMutation() {
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        var request = new MailDtos.DraftSaveRequest(
                null, null, null, "부분 본문", idempotencyKey, 3L);
        MailDtos.ThreadDetail current = detail(threadId, 4L);
        String fingerprint = fingerprints.save(threadId, request);
        when(mail.thread(1L, 7L, threadId)).thenReturn(current);
        when(receipts.reserve(1L, 7L, SAVE, idempotencyKey, fingerprint))
                .thenReturn(new MailDraftCommandReceiptRepository.Receipt(
                        fingerprint, threadId, 4L, "COMPLETED", false));

        assertThat(service.save(
                1L, 7L, threadId, "corr-replay", request)).isEqualTo(current);

        verify(drafts, never()).save(1L, 7L, threadId, request);
        verifyNoInteractions(evidence);
    }

    @Test
    void staleDraftVersionFailsUnlessTheIdempotencyKeyWasAlreadyApplied() {
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        var request = new MailDtos.DraftSaveRequest(
                "recipient@sk.com", null, null, null, idempotencyKey, 2L);
        when(mail.thread(1L, 7L, threadId)).thenReturn(detail(threadId, 3L));
        String fingerprint = fingerprints.save(threadId, request);
        when(receipts.reserve(1L, 7L, SAVE, idempotencyKey, fingerprint))
                .thenReturn(new MailDraftCommandReceiptRepository.Receipt(
                        fingerprint, null, null, "IN_PROGRESS", true));
        when(drafts.save(1L, 7L, threadId, request)).thenReturn(0);

        assertThatThrownBy(() -> service.save(
                1L, 7L, threadId, "corr-stale", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("changed");

        verifyNoInteractions(evidence);
    }

    @Test
    void idempotencyKeyCannotReplayAChangedDraftPayload() {
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        var request = new MailDtos.DraftSaveRequest(
                null, null, null, "changed body", idempotencyKey, 3L);
        String fingerprint = fingerprints.save(threadId, request);
        when(mail.thread(1L, 7L, threadId)).thenReturn(detail(threadId, 3L));
        when(receipts.reserve(1L, 7L, SAVE, idempotencyKey, fingerprint))
                .thenReturn(new MailDraftCommandReceiptRepository.Receipt(
                        "0".repeat(64), threadId, 4L, "COMPLETED", false));

        assertThatThrownBy(() -> service.save(
                1L, 7L, threadId, "corr-drift", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different draft command");

        verify(drafts, never()).save(1L, 7L, threadId, request);
        verifyNoInteractions(evidence);
    }

    private MailDtos.ThreadDetail detail(UUID threadId, long version) {
        return new MailDtos.ThreadDetail(
                new MailDtos.ThreadSummary(
                        threadId, UUID.randomUUID(), "내 메일", "DRAFTS", null, null,
                        "회의 준비", "부분 본문", List.of(), OffsetDateTime.now(),
                        false, false, Importance.NORMAL, TriageLane.UPDATES,
                        WorkflowState.DRAFT, null, null, null,
                        false, false, Classification.INTERNAL, 1, version),
                List.of(), List.of(), List.of(), List.of());
    }
}

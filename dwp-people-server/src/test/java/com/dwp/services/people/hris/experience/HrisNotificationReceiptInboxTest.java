package com.dwp.services.people.hris.experience;

import com.dwp.platform.contracts.hris.generated.HrisNotificationReceiptV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HrisNotificationReceiptInboxTest {

    private static final long TENANT = 77L;
    private static final long RECIPIENT = 23L;
    private static final UUID INTENT = UUID.fromString(
            "00000000-0000-4000-8000-000000000081");
    private static final UUID SOURCE = UUID.fromString(
            "00000000-0000-4000-8000-000000000082");
    private static final UUID CORRELATION = UUID.fromString(
            "00000000-0000-4000-8000-000000000083");
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    private final HrisExperienceNotificationRepository repository =
            mock(HrisExperienceNotificationRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HrisNotificationReceiptInbox inbox =
            new HrisNotificationReceiptInbox(true, repository, mapper);

    @Test
    void retryableFailedDoesNotCloseStream() {
        var receipt = failed(true);
        arrange(receipt);

        assertThat(inbox.accept(receipt))
                .isEqualTo(HrisNotificationReceiptInbox.Admission.APPENDED);
        verify(repository).appendReceipt(eq(receipt), any(), isNull());
    }

    @Test
    void nonRetryableFailedClosesStreamAsFailed() {
        var receipt = failed(false);
        arrange(receipt);

        assertThat(inbox.accept(receipt))
                .isEqualTo(HrisNotificationReceiptInbox.Admission.APPENDED_TERMINAL);
        verify(repository).appendReceipt(eq(receipt), any(), eq("FAILED"));
    }

    @Test
    void concurrentExactReplayIsDuplicateAfterStreamLock() {
        var receipt = failed(false);
        String digest = HrisExperienceDigests.canonicalSha256(mapper, receipt);
        when(repository.receiptDigest(TENANT, receipt.receiptId()))
                .thenReturn(Optional.empty(), Optional.of(digest));
        when(repository.handoff(TENANT, INTENT)).thenReturn(Optional.of(
                new HrisExperienceNotificationRepository.Handoff(
                        INTENT, SOURCE, RECIPIENT, "READY_LOCAL_ONLY", 1L,
                        CORRELATION)));
        when(repository.lockStream(TENANT, INTENT, RECIPIENT)).thenReturn(
                new HrisExperienceNotificationRepository.StreamState(1L, "FAILED"));

        assertThat(inbox.accept(receipt))
                .isEqualTo(HrisNotificationReceiptInbox.Admission.DUPLICATE);
        verify(repository, never()).appendReceipt(any(), any(), any());
    }

    @Test
    void exactFirstReplayReturnsDuplicateWithoutAdvancingStream() {
        var receipt = failed(false);
        String digest = HrisExperienceDigests.canonicalSha256(mapper, receipt);
        when(repository.receiptDigest(TENANT, receipt.receiptId()))
                .thenReturn(Optional.of(digest));

        assertThat(inbox.accept(receipt))
                .isEqualTo(HrisNotificationReceiptInbox.Admission.DUPLICATE);
        verify(repository, never()).lockStream(TENANT, INTENT, RECIPIENT);
    }

    @Test
    void rejectsReceiptWhoseTemplateVersionDiffersFromAdmittedIntent() {
        var receipt = new HrisNotificationReceiptV1(
                UUID.fromString("00000000-0000-4000-8000-000000000084"),
                INTENT, SOURCE, TENANT, RECIPIENT, 1L,
                "FAILED", "EMAIL", "DELIVERY_FAILED", null,
                UUID.fromString("00000000-0000-4000-8000-000000000085"),
                1L, 2L, "a".repeat(64), null, 1, NOW,
                "PROVIDER_UNAVAILABLE", false, null, CORRELATION);
        when(repository.receiptDigest(TENANT, receipt.receiptId()))
                .thenReturn(Optional.empty());
        when(repository.handoff(TENANT, INTENT)).thenReturn(Optional.of(
                new HrisExperienceNotificationRepository.Handoff(
                        INTENT, SOURCE, RECIPIENT, "READY_LOCAL_ONLY", 1L,
                        CORRELATION)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inbox.accept(receipt))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);
        verify(repository, never()).lockStream(TENANT, INTENT, RECIPIENT);
    }

    private void arrange(HrisNotificationReceiptV1 receipt) {
        when(repository.receiptDigest(TENANT, receipt.receiptId()))
                .thenReturn(Optional.empty());
        when(repository.handoff(TENANT, INTENT)).thenReturn(Optional.of(
                new HrisExperienceNotificationRepository.Handoff(
                        INTENT, SOURCE, RECIPIENT, "READY_LOCAL_ONLY", 1L,
                        CORRELATION)));
        when(repository.lockStream(TENANT, INTENT, RECIPIENT)).thenReturn(
                new HrisExperienceNotificationRepository.StreamState(0L, null));
        when(repository.sequenceDigest(TENANT, INTENT, RECIPIENT, 1L))
                .thenReturn(Optional.empty());
    }

    private HrisNotificationReceiptV1 failed(boolean retryable) {
        return new HrisNotificationReceiptV1(
                UUID.fromString("00000000-0000-4000-8000-000000000084"),
                INTENT, SOURCE, TENANT, RECIPIENT, 1L,
                "FAILED", "EMAIL", "DELIVERY_FAILED", null,
                UUID.fromString("00000000-0000-4000-8000-000000000085"),
                1L, 1L, "a".repeat(64), null, 1, NOW,
                "PROVIDER_UNAVAILABLE", retryable,
                retryable ? NOW.plusSeconds(60) : null, CORRELATION);
    }
}

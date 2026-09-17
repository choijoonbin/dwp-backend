package com.dwp.services.platform.mail;

import com.dwp.core.exception.BaseException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailAddressBookCommandReceiptRepository.CommandType.GROUP_MESSAGE_SEND;
import static com.dwp.services.platform.mail.MailTypes.Classification.INTERNAL;
import static com.dwp.services.platform.mail.MailTypes.Importance.NORMAL;
import static com.dwp.services.platform.mail.MailTypes.TriageLane.UPDATES;
import static com.dwp.services.platform.mail.MailTypes.WorkflowState.OPEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MailAddressBookServiceTest {

    private final MailAddressBookRepository addressBook = mock(MailAddressBookRepository.class);
    private final MailAddressBookCommandReceiptRepository receipts =
            mock(MailAddressBookCommandReceiptRepository.class);
    private final MailGroupComposeRepository groupCompose = mock(MailGroupComposeRepository.class);
    private final MailService mail = mock(MailService.class);
    private final MailCommandRepository evidence = mock(MailCommandRepository.class);
    private final MailAddressBookService service = new MailAddressBookService(
            addressBook, receipts, groupCompose, mail, evidence);
    private final MailAddressBookCommandFingerprint fingerprints =
            new MailAddressBookCommandFingerprint();

    @Test
    void requestContractsRejectMalformedContactsAndOversizedGroups() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var invalidContact = new MailAddressBookDtos.ContactCreateRequest(
                    "", "not-an-email", null, null, "script", "MANUAL",
                    null, false, null);
            var oversizedGroup = new MailAddressBookDtos.GroupMembersReplaceRequest(
                    java.util.stream.IntStream.range(0, 101)
                            .mapToObj(ignored -> UUID.randomUUID())
                            .toList(),
                    UUID.randomUUID(),
                    0L);

            assertThat(validator.validate(invalidContact)).hasSize(4);
            assertThat(validator.validate(oversizedGroup)).hasSize(1);
        }
    }

    @Test
    void directoryProvenanceMustMatchTheSourceKind() {
        var request = new MailAddressBookDtos.ContactCreateRequest(
                "Kim", "kim@example.com", null, null, null,
                "DIRECTORY", null, false, UUID.randomUUID());

        assertThatThrownBy(() -> service.createContact(1L, 7L, "corr", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("trusted People import");

        verifyNoInteractions(addressBook, receipts, groupCompose, mail, evidence);
    }

    @Test
    void contactChangesAdvanceEveryContainingGroupVersion() {
        UUID contactId = UUID.randomUUID();
        var before = contact(contactId, "before@example.com", 3L);
        var after = contact(contactId, "after@example.com", 4L);
        var request = new MailAddressBookDtos.ContactUpdateRequest(
                "Kim", "after@example.com", null, null, null, true, 3L);
        when(addressBook.contact(1L, 7L, contactId))
                .thenReturn(java.util.Optional.of(before))
                .thenReturn(java.util.Optional.of(after));
        when(addressBook.updateContact(1L, 7L, contactId, request)).thenReturn(1);

        assertThat(service.updateContact(1L, 7L, contactId, "corr", request))
                .isEqualTo(after);

        verify(addressBook).advanceContainingGroupVersions(1L, 7L, contactId);
        verify(evidence).audit(
                eq(1L), eq(7L), eq("mail.contact.updated"), eq("MAIL_CONTACT"),
                eq(contactId.toString()), eq("corr"), anyMap(), anyMap());
    }

    @Test
    void groupSendLocksTheReviewedVersionAndReplaysWithoutAnotherDelivery() {
        UUID groupId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        var request = new MailAddressBookDtos.GroupMessageRequest(
                "Decision", "Please review", INTERNAL, key, 5L);
        String fingerprint = fingerprints.groupMessage(groupId, request);
        var recipient = new MailAddressBookRepository.Recipient(
                UUID.randomUUID(), "Kim", "kim@example.com");
        var detail = detail(threadId);
        var sendReceipt = new MailAddressBookDtos.GroupSendReceipt(
                UUID.randomUUID(), groupId, 5L,
                MailAddressBookDtos.GroupRecipientMode.TO,
                1, threadId, OffsetDateTime.now(), "ACCEPTED");
        when(receipts.reserve(1L, 7L, GROUP_MESSAGE_SEND, key, fingerprint))
                .thenReturn(new MailAddressBookCommandReceiptRepository.Receipt(
                        fingerprint, null, null, "IN_PROGRESS", true))
                .thenReturn(new MailAddressBookCommandReceiptRepository.Receipt(
                        fingerprint, threadId, 0L, "COMPLETED", false));
        when(addressBook.lockGroup(1L, 7L, groupId, 5L)).thenReturn(true);
        when(addressBook.recipients(1L, 7L, groupId)).thenReturn(List.of(recipient));
        when(groupCompose.compose(
                1L, 7L, groupId, request, List.of(recipient), "corr-first"))
                .thenReturn(new MailGroupComposeRepository.ComposeResult(
                        threadId, 0L, "a".repeat(64), 1, UUID.randomUUID(), sendReceipt));
        when(mail.thread(1L, 7L, threadId)).thenReturn(detail);
        when(groupCompose.receipt(1L, 7L, groupId, threadId))
                .thenReturn(java.util.Optional.of(sendReceipt));

        assertThat(service.sendGroupMessage(
                1L, 7L, groupId, "corr-first", request))
                .satisfies(result -> {
                    assertThat(result.thread()).isEqualTo(detail);
                    assertThat(result.receipt()).isEqualTo(sendReceipt);
                });
        assertThat(service.sendGroupMessage(
                1L, 7L, groupId, "corr-replay", request))
                .satisfies(result -> {
                    assertThat(result.thread()).isEqualTo(detail);
                    assertThat(result.receipt()).isEqualTo(sendReceipt);
                });

        verify(groupCompose).compose(
                1L, 7L, groupId, request, List.of(recipient), "corr-first");
        verify(receipts).complete(
                1L, 7L, GROUP_MESSAGE_SEND, key, fingerprint, threadId, 0L);
        verify(evidence).domainEvent(
                eq(1L), eq("MAIL_THREAD"), eq(threadId),
                eq("mail.contact.group.message.queued"), anyMap(), eq("corr-first"));
        verify(evidence, never()).audit(
                eq(1L), eq(7L), eq("mail.contact.group.message.queued"),
                eq("MAIL_THREAD"), eq(threadId.toString()), eq("corr-replay"),
                anyMap(), anyMap());
    }

    @Test
    void groupBccFailsClosedBeforeACommandOrDeliveryIsCreated() {
        var request = new MailAddressBookDtos.GroupMessageRequest(
                "Private update", "Body", INTERNAL,
                MailAddressBookDtos.GroupRecipientMode.BCC,
                UUID.randomUUID(), 3L);

        assertThatThrownBy(() -> service.sendGroupMessage(
                1L, 7L, UUID.randomUUID(), "corr-bcc", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("Group BCC delivery is unavailable");

        verifyNoInteractions(addressBook, receipts, groupCompose, mail, evidence);
    }

    private MailAddressBookDtos.Contact contact(UUID contactId, String email, long version) {
        return new MailAddressBookDtos.Contact(
                contactId, "Kim", email, null, null, null,
                "MANUAL", null, true, version, OffsetDateTime.now());
    }

    private MailDtos.ThreadDetail detail(UUID threadId) {
        return new MailDtos.ThreadDetail(
                new MailDtos.ThreadSummary(
                        threadId, UUID.randomUUID(), "Mail", "SENT", null, null,
                        "Decision", "Please review", List.of(), OffsetDateTime.now(),
                        false, false, NORMAL, UPDATES, OPEN, null, null, null,
                        false, false, INTERNAL, 1, 0L),
                List.of(), List.of(), List.of(), List.of());
    }
}

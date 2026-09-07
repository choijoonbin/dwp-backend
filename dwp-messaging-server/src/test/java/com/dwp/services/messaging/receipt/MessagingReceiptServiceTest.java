package com.dwp.services.messaging.receipt;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MessagingReceiptServiceTest {
    private final MessagingReceiptRepository repository = mock(MessagingReceiptRepository.class);
    private final MessagingReceiptService service = new MessagingReceiptService(repository);
    private final UUID conversation = UUID.randomUUID();

    @BeforeEach
    void context() {
        MessagingRequestContext.set(new MessagingRequestContext.Subject(
                100, 7, null, "Sender", Set.of(), Set.of("APP.MESSAGING:VIEW"), Set.of()));
    }

    @AfterEach
    void clear() { MessagingRequestContext.clear(); }

    @Test
    void batchPreservesOrderCountsAndUsesOneScopedQuery() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var recipients = List.of(recipient(200, MessagingReceiptDtos.Status.READ),
                recipient(300, MessagingReceiptDtos.Status.UNREAD),
                recipient(400, MessagingReceiptDtos.Status.UNAVAILABLE));
        when(repository.receipts(7, 100, conversation, List.of(first, second)))
                .thenReturn(Map.of(second, List.of(), first, recipients));
        var result = service.receipts(conversation, List.of(first, second));
        assertThat(result).extracting(MessagingReceiptDtos.ReceiptSummary::messageId).containsExactly(first, second);
        assertThat(result.getFirst()).isEqualTo(new MessagingReceiptDtos.ReceiptSummary(first, recipients, 1, 1, 1));
        assertThat(result.getLast()).isEqualTo(new MessagingReceiptDtos.ReceiptSummary(second, List.of(), 0, 0, 0));
        verify(repository).receipts(7, 100, conversation, List.of(first, second));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void mixedAuthorizedAndHiddenIdsRejectWholeBatchWithoutPartialResponse() {
        UUID visible = UUID.randomUUID();
        UUID hidden = UUID.randomUUID();
        when(repository.receipts(7, 100, conversation, List.of(visible, hidden)))
                .thenReturn(Map.of(visible, List.of()));
        assertThatThrownBy(() -> service.receipts(conversation, List.of(visible, hidden)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND));
    }

    @Test
    void singleEndpointDelegatesTheSameAuthorQuery() {
        UUID id = UUID.randomUUID();
        when(repository.receipts(7, 100, conversation, List.of(id))).thenReturn(Map.of(id, List.of()));
        assertThat(service.receipt(conversation, id).messageId()).isEqualTo(id);
        verify(repository).receipts(7, 100, conversation, List.of(id));
    }

    @Test
    void observationsReturnInputOrderAndDoNotWriteTheUnreadCursor() {
        var ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(repository.observe(7, 100, conversation, ids)).thenReturn(ids.reversed());
        assertThat(service.observe(conversation, new MessagingReceiptDtos.ObserveRequest(ids)).observedMessageIds())
                .isEqualTo(ids);
        verify(repository).observe(7, 100, conversation, ids);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsUnobservableMessages() {
        var ids = List.of(UUID.randomUUID());
        when(repository.observe(7, 100, conversation, ids)).thenReturn(List.of());
        assertThatThrownBy(() -> service.observe(conversation, new MessagingReceiptDtos.ObserveRequest(ids)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND));
    }

    @Test
    void rejectsEmptyOversizedDuplicateAndNullIdentifiersBeforeQuery() {
        UUID id = UUID.randomUUID();
        List<List<UUID>> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(List.of());
        invalid.add(List.of(id, id));
        invalid.add(java.util.Arrays.asList(id, null));
        invalid.add(Stream.generate(UUID::randomUUID).limit(51).toList());
        for (List<UUID> ids : invalid) {
            assertThatThrownBy(() -> service.receipts(conversation, ids)).isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> service.observe(conversation, new MessagingReceiptDtos.ObserveRequest(ids)))
                    .isInstanceOf(BaseException.class);
        }
        verifyNoInteractions(repository);
    }

    private MessagingReceiptDtos.Recipient recipient(long userId, MessagingReceiptDtos.Status status) {
        return new MessagingReceiptDtos.Recipient(userId, null, "Recipient", status);
    }
}

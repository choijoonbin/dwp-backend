package com.dwp.services.messaging.receipt;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class MessagingReceiptService {
    private final MessagingReceiptRepository repository;

    MessagingReceiptService(MessagingReceiptRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public MessagingReceiptDtos.ReceiptSummary receipt(UUID conversationId, UUID messageId) {
        return receipts(conversationId, List.of(messageId)).getFirst();
    }

    @Transactional(readOnly = true)
    public List<MessagingReceiptDtos.ReceiptSummary> receipts(UUID conversationId, List<UUID> messageIds) {
        validate(messageIds);
        var subject = MessagingRequestContext.get();
        var receipts = repository.receipts(subject.tenantId(), subject.userId(), conversationId, messageIds);
        if (!receipts.keySet().containsAll(messageIds)) throw notFound();
        return messageIds.stream().map(id -> MessagingReceiptDtos.ReceiptSummary.of(id, receipts.get(id)))
                .toList();
    }

    @Transactional
    public MessagingReceiptDtos.ObservationResponse observe(UUID conversationId, MessagingReceiptDtos.ObserveRequest request) {
        List<UUID> messageIds = request == null ? null : request.messageIds();
        validate(messageIds);
        var subject = MessagingRequestContext.get();
        if (repository.observe(subject.tenantId(), subject.userId(), conversationId, messageIds).size()
                != messageIds.size()) throw notFound();
        // Deliberately no public event: the existing SSE envelope identifies its actor.
        return new MessagingReceiptDtos.ObservationResponse(List.copyOf(messageIds));
    }

    private void validate(List<UUID> messageIds) {
        if (messageIds == null || messageIds.isEmpty() || messageIds.size() > 50
                || messageIds.stream().anyMatch(id -> id == null)
                || new HashSet<>(messageIds).size() != messageIds.size()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Provide between 1 and 50 unique message identifiers.");
        }
    }

    private BaseException notFound() {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND, "The requested receipts were not found.");
    }
}

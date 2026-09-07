package com.dwp.services.messaging.receipt;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class MessagingReceiptDtos {
    private MessagingReceiptDtos() { }

    public enum Status { READ, UNREAD, UNAVAILABLE }

    public record Recipient(long userId, UUID personPublicId, String displayName, Status status) { }

    public record ObserveRequest(@NotNull @Size(min = 1, max = 50) List<@NotNull UUID> messageIds) { }

    public record ObservationResponse(List<UUID> observedMessageIds) { }

    public record ReceiptSummary(UUID messageId, List<Recipient> recipients,
                                 long readCount, long unreadCount, long unavailableCount) {
        static ReceiptSummary of(UUID messageId, List<Recipient> recipients) {
            return new ReceiptSummary(messageId, List.copyOf(recipients),
                    count(recipients, Status.READ), count(recipients, Status.UNREAD),
                    count(recipients, Status.UNAVAILABLE));
        }

        private static long count(List<Recipient> recipients, Status status) {
            return recipients.stream().filter(recipient -> recipient.status() == status).count();
        }
    }
}

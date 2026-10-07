package com.dwp.services.notification.hriscontracts;

import com.dwp.platform.contracts.hris.generated.HrisNotificationIntentV1;
import com.dwp.platform.contracts.hris.generated.HrisNotificationReceiptV1;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Privacy-minimized admission boundary for HRIS notification intent and receipt contracts. */
public final class HrisNotificationContractAdapter {

    private HrisNotificationContractAdapter() {
    }

    public static Admission admit(HrisNotificationIntentV1 intent) {
        Objects.requireNonNull(intent, "intent must not be null");
        Set<Long> exclusions = new HashSet<>(intent.excludedUserIds());
        List<Long> effectiveRecipients = intent.recipientUserIds().stream()
                .filter(recipient -> !exclusions.contains(recipient))
                .toList();
        if (effectiveRecipients.isEmpty()) {
            throw new IllegalArgumentException("notification intent has no effective recipient");
        }
        return new Admission(intent, effectiveRecipients, idempotencyBindingDigest(intent));
    }

    public static HrisNotificationReceiptV1 bindReceipt(
            Admission admission,
            HrisNotificationReceiptV1 receipt) {
        Objects.requireNonNull(admission, "admission must not be null");
        Objects.requireNonNull(receipt, "receipt must not be null");
        HrisNotificationIntentV1 intent = admission.intent();
        if (!receipt.intentId().equals(intent.intentId())
                || !receipt.sourceEventId().equals(intent.sourceEventId())
                || receipt.tenantId() != intent.tenantId()
                || !receipt.correlationId().equals(intent.correlationId())
                || receipt.templateVersion() != intent.templateVersion()
                || !admission.effectiveRecipientUserIds().contains(receipt.recipientUserId())) {
            throw new IllegalArgumentException(
                    "notification receipt does not belong to the admitted intent and recipient");
        }
        if (receipt.occurredAt().isBefore(intent.requestedAt())) {
            throw new IllegalArgumentException(
                    "notification receipt predates the admitted intent");
        }
        return receipt;
    }

    public record Admission(
            HrisNotificationIntentV1 intent,
            List<Long> effectiveRecipientUserIds,
            String idempotencyBindingDigest) {

        public Admission {
            Objects.requireNonNull(intent, "intent must not be null");
            Objects.requireNonNull(
                    effectiveRecipientUserIds,
                    "effectiveRecipientUserIds must not be null");
            effectiveRecipientUserIds = List.copyOf(effectiveRecipientUserIds);
            Set<Long> exclusions = new HashSet<>(intent.excludedUserIds());
            List<Long> expected = intent.recipientUserIds().stream()
                    .filter(recipient -> !exclusions.contains(recipient))
                    .toList();
            if (effectiveRecipientUserIds.isEmpty() || !effectiveRecipientUserIds.equals(expected)) {
                throw new IllegalArgumentException(
                        "effectiveRecipientUserIds must exactly match admitted recipients");
            }
            if (!HrisNotificationContractAdapter.idempotencyBindingDigest(intent)
                    .equals(idempotencyBindingDigest)) {
                throw new IllegalArgumentException(
                        "idempotencyBindingDigest must bind the admitted intent exactly");
            }
        }
    }

    private static String idempotencyBindingDigest(HrisNotificationIntentV1 intent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "HrisNotificationIntent.idempotency.v1");
            update(digest, intent.intentId().toString());
            update(digest, intent.sourceEventId().toString());
            update(digest, Long.toString(intent.tenantId()));
            update(digest, intent.producerAppKey());
            update(digest, intent.typeKey());
            update(digest, intent.purposeCode());
            update(digest, intent.recipientUserIds());
            update(digest, intent.excludedUserIds());
            updateNullable(digest, intent.threadRef());
            update(digest, intent.locale());
            update(digest, intent.reasonCode());
            updateNullable(digest, intent.actorRef());
            update(digest, intent.subjectRef());
            updateNullable(digest, intent.targetRef());
            update(digest, intent.templateKey());
            update(digest, Long.toString(intent.templateVersion()));
            update(digest, intent.templateModelDigest());
            update(digest, intent.classification());
            update(digest, Boolean.toString(intent.actionRequired()));
            updateNullable(digest, intent.dueAt() == null ? null : intent.dueAt().toString());
            update(digest, intent.requestedAt().toString());
            update(digest, intent.idempotencyKey());
            update(digest, intent.correlationId().toString());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = Objects.requireNonNull(value, "digest field must not be null")
                .getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void update(MessageDigest digest, List<Long> values) {
        update(digest, Integer.toString(values.size()));
        values.forEach(value -> update(digest, Long.toString(value)));
    }

    private static void updateNullable(MessageDigest digest, String value) {
        digest.update((byte) (value == null ? 0 : 1));
        if (value != null) {
            update(digest, value);
        }
    }
}

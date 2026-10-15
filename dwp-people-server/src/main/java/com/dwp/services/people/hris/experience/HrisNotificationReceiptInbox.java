package com.dwp.services.people.hris.experience;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.HrisNotificationReceiptV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** Ordered, idempotent and fail-closed PDX-013 owner inbox. */
@Service
public class HrisNotificationReceiptInbox {

    private static final Set<String> TERMINAL = Set.of(
            "SUPPRESSED", "DELIVERED", "DEAD_LETTERED", "CANCELED");

    private final boolean enabled;
    private final HrisExperienceNotificationRepository repository;
    private final ObjectMapper mapper;

    @Autowired
    HrisNotificationReceiptInbox(
            @Value("${dwp.hris.experience.enabled:false}") boolean enabled,
            HrisExperienceNotificationRepository repository,
            ObjectMapper mapper) {
        this.enabled = enabled;
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public Admission accept(HrisNotificationReceiptV1 receipt) {
        if (!enabled) {
            throw new IllegalStateException("HRIS experience receipt inbox is disabled.");
        }
        String digest = HrisExperienceDigests.canonicalSha256(mapper, receipt);
        var existing = repository.receiptDigest(
                receipt.tenantId(), receipt.receiptId());
        if (existing.isPresent()) {
            if (existing.get().equals(digest)) return Admission.DUPLICATE;
            throw conflict("Notification receipt id was reused with different evidence.");
        }
        var handoff = repository.handoff(receipt.tenantId(), receipt.intentId())
                .orElseThrow(() -> conflict("Notification intent is not locally admitted."));
        if (!"READY_LOCAL_ONLY".equals(handoff.decision())
                || handoff.recipientUserId() == null
                || handoff.recipientUserId() != receipt.recipientUserId()
                || !handoff.sourceEventId().equals(receipt.sourceEventId())
                || !handoff.correlationId().equals(receipt.correlationId())) {
            throw conflict("Notification receipt does not match the admitted intent.");
        }
        var stream = repository.lockStream(
                receipt.tenantId(), receipt.intentId(), receipt.recipientUserId());
        existing = repository.receiptDigest(receipt.tenantId(), receipt.receiptId());
        if (existing.isPresent()) {
            if (existing.get().equals(digest)) return Admission.DUPLICATE;
            throw conflict("Notification receipt id was reused with different evidence.");
        }
        var sequence = repository.sequenceDigest(
                receipt.tenantId(), receipt.intentId(), receipt.recipientUserId(),
                receipt.receiptSequence());
        if (sequence.isPresent()) {
            throw conflict("Notification receipt sequence is already occupied.");
        }
        if (stream.terminalState() != null) {
            throw conflict("Notification receipt stream is terminal.");
        }
        if (receipt.receiptSequence() != stream.lastSequence() + 1) {
            throw conflict("Notification receipt sequence is not monotonic.");
        }
        String terminal = terminal(receipt);
        repository.appendReceipt(receipt, digest, terminal);
        return terminal == null ? Admission.APPENDED : Admission.APPENDED_TERMINAL;
    }

    private String terminal(HrisNotificationReceiptV1 receipt) {
        if (TERMINAL.contains(receipt.state())) return receipt.state();
        if ("FAILED".equals(receipt.state()) && !receipt.retryable()) return "FAILED";
        return null;
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    public enum Admission {
        APPENDED,
        APPENDED_TERMINAL,
        DUPLICATE
    }
}

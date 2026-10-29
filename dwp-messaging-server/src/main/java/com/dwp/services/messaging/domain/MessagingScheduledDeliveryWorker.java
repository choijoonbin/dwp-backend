package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
class MessagingScheduledDeliveryWorker {

    private static final Logger log =
            LoggerFactory.getLogger(MessagingScheduledDeliveryWorker.class);
    private static final Set<ErrorCode> TRANSIENT_ERRORS = Set.of(
            ErrorCode.INTERNAL_SERVER_ERROR,
            ErrorCode.EXTERNAL_SERVICE_ERROR,
            ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
            ErrorCode.SERVICE_RATE_LIMITED);

    private final MessagingContinuityRepository continuity;
    private final MessagingScheduledDeliveryDispatcher dispatcher;
    private final int batchSize;
    private final int maximumAttempts;
    private final boolean enabled;
    private final UUID workerId;
    private final Counter deliveredCounter;
    private final Counter retryCounter;
    private final Counter failedCounter;

    @Autowired
    MessagingScheduledDeliveryWorker(
            MessagingContinuityRepository continuity,
            MessagingScheduledDeliveryDispatcher dispatcher,
            @Value("${dwp.messaging.scheduled-delivery.batch-size:20}") int batchSize,
            @Value("${dwp.messaging.scheduled-delivery.maximum-attempts:5}")
            int maximumAttempts,
            @Value("${dwp.messaging.scheduled-delivery.enabled:true}") boolean enabled,
            MeterRegistry meters) {
        this(continuity, dispatcher, batchSize, maximumAttempts,
                enabled, meters, UUID.randomUUID());
    }

    MessagingScheduledDeliveryWorker(
            MessagingContinuityRepository continuity,
            MessagingScheduledDeliveryDispatcher dispatcher,
            int batchSize,
            int maximumAttempts) {
        this(continuity, dispatcher, batchSize, maximumAttempts,
                true, new SimpleMeterRegistry(), UUID.randomUUID());
    }

    MessagingScheduledDeliveryWorker(
            MessagingContinuityRepository continuity,
            MessagingScheduledDeliveryDispatcher dispatcher,
            int batchSize,
            int maximumAttempts,
            boolean enabled,
            MeterRegistry meters,
            UUID workerId) {
        this.continuity = continuity;
        this.dispatcher = dispatcher;
        this.batchSize = Math.max(1, Math.min(100, batchSize));
        this.maximumAttempts = Math.max(1, Math.min(20, maximumAttempts));
        this.enabled = enabled;
        this.workerId = workerId;
        this.deliveredCounter = Counter.builder("dwp.messaging.scheduled.delivered")
                .description("Scheduled messages delivered by the durable worker")
                .register(meters);
        this.retryCounter = Counter.builder("dwp.messaging.scheduled.retried")
                .description("Scheduled delivery attempts returned to the bounded retry queue")
                .register(meters);
        this.failedCounter = Counter.builder("dwp.messaging.scheduled.failed")
                .description("Scheduled messages moved to terminal failure")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${dwp.messaging.scheduled-delivery.poll-ms:5000}")
    public void dispatchDue() {
        if (!enabled) return;
        continuity.recordWorkerStarted(workerId);
        int claimed = 0;
        int delivered = 0;
        int retried = 0;
        int failed = 0;
        try {
            var due = continuity.claimDue(batchSize);
            claimed = due.size();
            for (var scheduled : due) {
                DeliveryOutcome outcome = deliver(scheduled);
                if (outcome == DeliveryOutcome.DELIVERED) delivered++;
                if (outcome == DeliveryOutcome.RETRIED) retried++;
                if (outcome == DeliveryOutcome.FAILED) failed++;
            }
            continuity.recordWorkerCompleted(
                    workerId, claimed, delivered, retried, failed, null);
        } catch (RuntimeException exception) {
            continuity.recordWorkerCompleted(
                    workerId, claimed, delivered, retried, failed,
                    safeCode(exception.getClass().getSimpleName()));
            throw exception;
        }
    }

    private DeliveryOutcome deliver(MessagingContinuityRepository.ScheduledRecord scheduled) {
        UUID id = scheduled.response().scheduledMessageId();
        try {
            UUID messageId = dispatcher.dispatch(scheduled);
            if (continuity.markSent(id, messageId) != 1) {
                log.warn("Scheduled message {} was delivered but its state was not PROCESSING", id);
            }
            deliveredCounter.increment();
            return DeliveryOutcome.DELIVERED;
        } catch (BaseException exception) {
            return failOrRetry(
                    scheduled,
                    exception.getErrorCode().getCode(),
                    safeDetail(exception.getMessage()),
                    TRANSIENT_ERRORS.contains(exception.getErrorCode()));
        } catch (RuntimeException exception) {
            log.warn("Scheduled message {} delivery failed transiently", id, exception);
            return failOrRetry(scheduled, "SCHEDULED_DELIVERY_TRANSIENT",
                    "Scheduled delivery is temporarily unavailable.", true);
        }
    }

    private DeliveryOutcome failOrRetry(
            MessagingContinuityRepository.ScheduledRecord scheduled,
            String code,
            String detail,
            boolean transientFailure) {
        int attempt = scheduled.response().attemptCount();
        if (transientFailure && attempt < maximumAttempts) {
            long delaySeconds = Math.min(300L, 5L << Math.min(10, attempt - 1));
            continuity.markRetry(
                    scheduled.response().scheduledMessageId(), code, detail, delaySeconds);
            retryCounter.increment();
            return DeliveryOutcome.RETRIED;
        }
        continuity.markFailed(scheduled.response().scheduledMessageId(), code, detail);
        failedCounter.increment();
        return DeliveryOutcome.FAILED;
    }

    private String safeDetail(String value) {
        if (value == null || value.isBlank()) return "Scheduled delivery was rejected.";
        String normalized = value.replace('\n', ' ').replace('\r', ' ').strip();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private String safeCode(String value) {
        String normalized = value == null ? "SCHEDULED_WORKER_FAILED"
                : value.replaceAll("[^A-Za-z0-9]", "_").toUpperCase(java.util.Locale.ROOT);
        return normalized.substring(0, Math.min(80, normalized.length()));
    }

    private enum DeliveryOutcome {
        DELIVERED,
        RETRIED,
        FAILED
    }
}

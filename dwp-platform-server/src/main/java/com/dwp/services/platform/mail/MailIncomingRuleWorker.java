package com.dwp.services.platform.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class MailIncomingRuleWorker {

    private static final Logger log = LoggerFactory.getLogger(MailIncomingRuleWorker.class);

    private final MailIncomingRuleRepository runs;
    private final MailIncomingRuleTransactions transactions;
    private final boolean enabled;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maximumAttempts;
    private final String workerId;

    MailIncomingRuleWorker(
            MailIncomingRuleRepository runs,
            MailIncomingRuleTransactions transactions,
            @Value("${dwp.platform.mail.incoming-rules.enabled:true}") boolean enabled,
            @Value("${dwp.platform.mail.incoming-rules.batch-size:50}") int batchSize,
            @Value("${dwp.platform.mail.incoming-rules.lease-seconds:30}") int leaseSeconds,
            @Value("${dwp.platform.mail.incoming-rules.maximum-attempts:5}") int maximumAttempts,
            @Value("${dwp.platform.mail.incoming-rules.worker-id:${HOSTNAME:local}}")
            String workerName) {
        this.runs = runs;
        this.transactions = transactions;
        this.enabled = enabled;
        this.batchSize = positive(batchSize, "batchSize");
        this.leaseSeconds = positive(leaseSeconds, "leaseSeconds");
        this.maximumAttempts = positive(maximumAttempts, "maximumAttempts");
        this.workerId = workerName + ':' + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${dwp.platform.mail.incoming-rules.poll-delay-ms:1000}")
    void executePending() {
        if (!enabled) return;
        try {
            runs.releaseExpiredLeases(maximumAttempts);
            for (MailIncomingRuleRepository.IncomingRun run
                    : runs.claim(workerId, batchSize, leaseSeconds)) {
                try {
                    transactions.execute(run, workerId);
                } catch (RuntimeException failure) {
                    String code = sanitize(failure);
                    try {
                        transactions.fail(run, workerId, maximumAttempts, code);
                    } catch (RuntimeException persistenceFailure) {
                        log.error("Mail incoming rule failure receipt could not be persisted runId={}",
                                run.runId(), persistenceFailure);
                    }
                    log.warn("Mail incoming rule failed runId={} attempt={}",
                            run.runId(), run.attemptCount(), failure);
                }
            }
        } catch (RuntimeException pollingFailure) {
            log.error("Mail incoming rule polling failed", pollingFailure);
        }
    }

    private String sanitize(RuntimeException failure) {
        String value = failure.getMessage();
        if (value == null || !value.matches("[A-Z0-9_.-]{1,120}")) {
            value = failure.getClass().getSimpleName().toUpperCase();
        }
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}

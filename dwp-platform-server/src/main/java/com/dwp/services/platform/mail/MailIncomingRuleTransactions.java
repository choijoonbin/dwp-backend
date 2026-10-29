package com.dwp.services.platform.mail;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class MailIncomingRuleTransactions {

    private final MailIncomingRuleRepository runs;
    private final MailOrganizationCommandRepository commands;
    private final MailRuleEvaluator evaluator;
    private final MailMetrics metrics;

    MailIncomingRuleTransactions(
            MailIncomingRuleRepository runs,
            MailOrganizationCommandRepository commands,
            MailRuleEvaluator evaluator,
            MailMetrics metrics) {
        this.runs = runs;
        this.commands = commands;
        this.evaluator = evaluator;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void execute(MailIncomingRuleRepository.IncomingRun run, String workerId) {
        MailOrganizationQueryRepository.RuleCandidate candidate = runs.candidate(run)
                .orElseThrow(() -> new IncomingRuleFailure("INCOMING_RULE_CANDIDATE_UNAVAILABLE"));
        boolean matched = evaluator.matches(run.snapshot(), candidate);
        boolean changed = false;
        if (matched) {
            MailOrganizationCommandRepository.RuleApplication application =
                    commands.applyRuleActions(
                            run.tenantId(), run.ownerUserId(), run.accountId(),
                            run.threadId(), candidate.version(), run.actions());
            if (!application.eligible()) {
                throw new IncomingRuleFailure("INCOMING_RULE_THREAD_VERSION_CONFLICT");
            }
            changed = application.changed();
        }
        if (runs.complete(run, workerId, matched, changed) != 1) {
            throw new IncomingRuleFailure("INCOMING_RULE_LEASE_LOST");
        }
        if (matched && run.stopProcessing()) runs.skipRemaining(run);
        metrics.incomingRule(matched ? (changed ? "changed" : "matched") : "unmatched");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void fail(
            MailIncomingRuleRepository.IncomingRun run,
            String workerId,
            int maximumAttempts,
            String errorCode) {
        runs.fail(run, workerId, maximumAttempts, errorCode);
        metrics.incomingRule(run.attemptCount() >= maximumAttempts ? "failed" : "retry");
    }

    static final class IncomingRuleFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        IncomingRuleFailure(String code) {
            super(code);
        }
    }
}

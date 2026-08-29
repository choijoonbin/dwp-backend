package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class MailRuleBackfillTransactions {

    private record Snapshot(
            List<MailOrganizationDtos.RuleSummary> rules,
            List<MailOrganizationQueryRepository.RuleCandidate> candidates,
            String fingerprint,
            int matchedThreadCount,
            int plannedApplicationCount,
            boolean truncated) {
    }

    private final MailOrganizationQueryRepository queries;
    private final MailOrganizationCommandRepository commands;
    private final MailRuleBackfillRepository backfills;
    private final MailRuleEvaluator evaluator;
    private final MailRuleBackfillFingerprint fingerprints;
    private final MailCommandRepository evidence;

    MailRuleBackfillTransactions(
            MailOrganizationQueryRepository queries,
            MailOrganizationCommandRepository commands,
            MailRuleBackfillRepository backfills,
            MailRuleEvaluator evaluator,
            MailRuleBackfillFingerprint fingerprints,
            MailCommandRepository evidence) {
        this.queries = queries;
        this.commands = commands;
        this.backfills = backfills;
        this.evaluator = evaluator;
        this.fingerprints = fingerprints;
        this.evidence = evidence;
    }

    @Transactional(readOnly = true)
    MailRuleBackfillDtos.Preview preview(Long tenantId, Long userId, UUID accountId) {
        Snapshot snapshot = snapshot(tenantId, userId, accountId);
        return new MailRuleBackfillDtos.Preview(
                accountId,
                snapshot.fingerprint(),
                snapshot.rules().size(),
                snapshot.candidates().size(),
                snapshot.matchedThreadCount(),
                snapshot.plannedApplicationCount(),
                snapshot.truncated(),
                OffsetDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    MailRuleBackfillRepository.Claim claim(
            Long tenantId,
            Long userId,
            UUID accountId,
            MailRuleBackfillDtos.Request request) {
        requireOwnedAccount(tenantId, userId, accountId);
        return backfills.claim(
                tenantId,
                userId,
                accountId,
                request.requestId(),
                fingerprints.request(accountId, request.previewFingerprint()),
                request.previewFingerprint());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    MailRuleBackfillDtos.Result execute(
            Long tenantId,
            Long userId,
            String correlationId,
            MailRuleBackfillRepository.Claim claim,
            MailRuleBackfillDtos.Request request) {
        backfills.requireActiveLease(tenantId, userId, claim);
        backfills.requireActivePersonalAccount(tenantId, userId, claim.accountId());
        Snapshot snapshot = snapshot(tenantId, userId, claim.accountId());
        if (snapshot.truncated()) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The backfill preview is truncated. Narrow the mailbox scope before execution.");
        }
        if (snapshot.rules().isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Enable at least one rule first.");
        }
        if (!fingerprints.matches(request.previewFingerprint(), snapshot.fingerprint())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The mailbox or rules changed. Refresh the preview and try again.");
        }

        Map<UUID, Integer> matchedByRule = new LinkedHashMap<>();
        Map<UUID, Integer> changedByRule = new LinkedHashMap<>();
        int matchedThreads = 0;
        int applications = 0;
        int changed = 0;

        for (MailOrganizationQueryRepository.RuleCandidate candidate : snapshot.candidates()) {
            long threadVersion = candidate.version();
            boolean threadMatched = false;
            for (MailOrganizationDtos.RuleSummary rule : snapshot.rules()) {
                if (!evaluator.matches(rule, candidate)) continue;
                threadMatched = true;
                applications++;
                matchedByRule.merge(rule.ruleId(), 1, Integer::sum);
                MailOrganizationCommandRepository.RuleApplication application =
                        commands.applyRuleActions(
                        tenantId,
                        userId,
                        claim.accountId(),
                        candidate.threadId(),
                        threadVersion,
                        rule.actions());
                if (!application.eligible()) {
                    throw new BaseException(
                            ErrorCode.RESOURCE_CONFLICT,
                            "A mailbox item changed while the backfill was running.");
                }
                backfills.recordApplication(
                        claim,
                        tenantId,
                        candidate.threadId(),
                        rule.ruleId(),
                        rule.version(),
                        threadVersion,
                        application.changed());
                if (application.changed()) {
                    threadVersion++;
                    changed++;
                    changedByRule.merge(rule.ruleId(), 1, Integer::sum);
                }
                if (rule.stopProcessing()) break;
            }
            if (threadMatched) matchedThreads++;
        }

        for (MailOrganizationDtos.RuleSummary rule : snapshot.rules()) {
            backfills.recordRuleRun(
                    tenantId,
                    userId,
                    rule,
                    snapshot.candidates().size(),
                    matchedByRule.getOrDefault(rule.ruleId(), 0),
                    changedByRule.getOrDefault(rule.ruleId(), 0));
        }

        MailRuleBackfillDtos.Result result = backfills.complete(
                tenantId,
                userId,
                claim,
                snapshot.candidates().size(),
                matchedThreads,
                applications,
                changed);
        Map<String, Object> after = Map.of(
                "requestId", claim.requestId(),
                "previewFingerprint", request.previewFingerprint(),
                "scannedCount", result.scannedCount(),
                "matchedThreadCount", result.matchedThreadCount(),
                "applicationCount", result.applicationCount(),
                "changedCount", result.changedCount());
        evidence.audit(
                tenantId,
                userId,
                "mail.rules.backfilled",
                "MAIL_ACCOUNT",
                claim.accountId().toString(),
                correlationId,
                Map.of(),
                after);
        evidence.domainEvent(
                tenantId,
                "MAIL_ACCOUNT",
                claim.accountId(),
                "mail.rules.backfilled",
                after,
                correlationId);
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void fail(
            Long tenantId,
            Long userId,
            MailRuleBackfillRepository.Claim claim,
            String errorCode) {
        backfills.fail(tenantId, userId, claim, errorCode);
    }

    private Snapshot snapshot(Long tenantId, Long userId, UUID accountId) {
        requireOwnedAccount(tenantId, userId, accountId);
        List<MailOrganizationDtos.RuleSummary> rules = queries.rules(tenantId, userId).stream()
                .filter(rule -> rule.accountId().equals(accountId) && rule.enabled())
                .sorted(Comparator
                        .comparingInt(MailOrganizationDtos.RuleSummary::priority)
                        .thenComparing(MailOrganizationDtos.RuleSummary::ruleId))
                .toList();
        List<MailOrganizationQueryRepository.RuleCandidate> discovered =
                queries.candidates(tenantId, userId, accountId);
        boolean truncated = discovered.size() > 500;
        List<MailOrganizationQueryRepository.RuleCandidate> candidates = truncated
                ? List.copyOf(discovered.subList(0, 500))
                : discovered;
        int matchedThreads = 0;
        int applications = 0;
        for (MailOrganizationQueryRepository.RuleCandidate candidate : candidates) {
            boolean matched = false;
            for (MailOrganizationDtos.RuleSummary rule : rules) {
                if (!evaluator.matches(rule, candidate)) continue;
                matched = true;
                applications++;
                if (rule.stopProcessing()) break;
            }
            if (matched) matchedThreads++;
        }
        return new Snapshot(
                rules,
                candidates,
                fingerprints.preview(accountId, rules, candidates),
                matchedThreads,
                applications,
                truncated);
    }

    private void requireOwnedAccount(Long tenantId, Long userId, UUID accountId) {
        if (!queries.ownsAccount(tenantId, userId, accountId)) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
    }
}

package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailOrganizationTypes.*;

@Service
public class MailOrganizationService {

    private final MailQueryRepository mailQueries;
    private final MailOrganizationQueryRepository queries;
    private final MailOrganizationCommandRepository commands;
    private final MailCommandRepository evidence;
    private final MailRuleEvaluator evaluator;

    public MailOrganizationService(
            MailQueryRepository mailQueries,
            MailOrganizationQueryRepository queries,
            MailOrganizationCommandRepository commands,
            MailCommandRepository evidence,
            MailRuleEvaluator evaluator) {
        this.mailQueries = mailQueries;
        this.queries = queries;
        this.commands = commands;
        this.evidence = evidence;
        this.evaluator = evaluator;
    }

    @Transactional(readOnly = true)
    public MailOrganizationDtos.OrganizationResponse organization(Long tenantId, Long userId) {
        List<MailDtos.AccountSummary> accounts = mailQueries.accounts(tenantId, userId);
        if (accounts.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_STATE, "No governed mail account is assigned.");
        }
        return new MailOrganizationDtos.OrganizationResponse(
                accounts,
                queries.folders(tenantId, userId),
                queries.rules(tenantId, userId),
                queries.recentRuns(tenantId, userId),
                OffsetDateTime.now());
    }

    @Transactional
    public MailOrganizationDtos.FolderSummary createFolder(
            Long tenantId,
            Long userId,
            String correlationId,
            MailOrganizationDtos.FolderCreateRequest request) {
        requireOwnedAccount(tenantId, userId, request.accountId());
        validateParent(tenantId, userId, request.accountId(), request.parentFolderId(), null);
        try {
            UUID folderId = commands.createFolder(tenantId, userId, request, 1000);
            MailOrganizationDtos.FolderSummary created = folder(tenantId, userId, folderId);
            record(tenantId, userId, correlationId, "mail.folder.created", "MAIL_FOLDER",
                    folderId, Map.of(), folderState(created));
            return created;
        } catch (DataIntegrityViolationException exception) {
            throw conflict("A folder with the same name already exists.");
        }
    }

    @Transactional
    public MailOrganizationDtos.FolderSummary updateFolder(
            Long tenantId,
            Long userId,
            UUID folderId,
            String correlationId,
            MailOrganizationDtos.FolderUpdateRequest request) {
        MailOrganizationDtos.FolderSummary before = folder(tenantId, userId, folderId);
        requireCustom(before);
        validateParent(tenantId, userId, before.accountId(), request.parentFolderId(), folderId);
        try {
            if (commands.updateFolder(tenantId, userId, folderId, request) == 0) {
                throw conflict("The folder changed. Refresh and try again.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("A folder with the same name already exists.");
        }
        MailOrganizationDtos.FolderSummary after = folder(tenantId, userId, folderId);
        record(tenantId, userId, correlationId, "mail.folder.updated", "MAIL_FOLDER",
                folderId, folderState(before), folderState(after));
        return after;
    }

    @Transactional
    public void archiveFolder(
            Long tenantId,
            Long userId,
            UUID folderId,
            String correlationId,
            MailOrganizationDtos.VersionRequest request) {
        MailOrganizationDtos.FolderSummary before = folder(tenantId, userId, folderId);
        requireCustom(before);
        if (queries.hasActiveChildren(tenantId, folderId)) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE, "Move or archive child folders first.");
        }
        if (queries.isReferencedByActiveRule(tenantId, folderId)) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE, "Disable rules that use this folder first.");
        }
        if (commands.archiveFolder(
                tenantId, userId, folderId, before.accountId(), request.version()) == 0) {
            throw conflict("The folder changed. Refresh and try again.");
        }
        record(tenantId, userId, correlationId, "mail.folder.archived", "MAIL_FOLDER",
                folderId, folderState(before), Map.of("lifecycleState", "ARCHIVED"));
    }

    @Transactional
    public MailOrganizationDtos.RuleSummary createRule(
            Long tenantId,
            Long userId,
            String correlationId,
            MailOrganizationDtos.RuleCreateRequest request) {
        requireOwnedAccount(tenantId, userId, request.accountId());
        validateRule(tenantId, userId, request.accountId(), request.conditions(), request.actions());
        try {
            UUID ruleId = commands.createRule(tenantId, userId, request);
            MailOrganizationDtos.RuleSummary created = rule(tenantId, userId, ruleId);
            record(tenantId, userId, correlationId, "mail.rule.created", "MAIL_RULE",
                    ruleId, Map.of(), ruleState(created));
            return created;
        } catch (DataIntegrityViolationException exception) {
            throw conflict("A rule with the same name already exists.");
        }
    }

    @Transactional
    public MailOrganizationDtos.RuleSummary updateRule(
            Long tenantId,
            Long userId,
            UUID ruleId,
            String correlationId,
            MailOrganizationDtos.RuleUpdateRequest request) {
        MailOrganizationDtos.RuleSummary before = rule(tenantId, userId, ruleId);
        validateRule(tenantId, userId, before.accountId(), request.conditions(), request.actions());
        try {
            if (commands.updateRule(tenantId, userId, ruleId, request) == 0) {
                throw conflict("The rule changed. Refresh and try again.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("A rule with the same name already exists.");
        }
        MailOrganizationDtos.RuleSummary after = rule(tenantId, userId, ruleId);
        record(tenantId, userId, correlationId, "mail.rule.updated", "MAIL_RULE",
                ruleId, ruleState(before), ruleState(after));
        return after;
    }

    @Transactional
    public void archiveRule(
            Long tenantId,
            Long userId,
            UUID ruleId,
            String correlationId,
            MailOrganizationDtos.VersionRequest request) {
        MailOrganizationDtos.RuleSummary before = rule(tenantId, userId, ruleId);
        if (commands.archiveRule(tenantId, userId, ruleId, request.version()) == 0) {
            throw conflict("The rule changed. Refresh and try again.");
        }
        record(tenantId, userId, correlationId, "mail.rule.archived", "MAIL_RULE",
                ruleId, ruleState(before), Map.of("lifecycleState", "ARCHIVED"));
    }

    @Transactional
    public MailOrganizationDtos.RuleRunSummary runRule(
            Long tenantId, Long userId, UUID ruleId, String correlationId) {
        MailOrganizationDtos.RuleSummary rule = rule(tenantId, userId, ruleId);
        if (!rule.enabled()) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Enable the rule before running it.");
        }
        UUID runId = commands.startRuleRun(tenantId, userId, ruleId);
        List<MailOrganizationQueryRepository.RuleCandidate> candidates =
                queries.candidates(tenantId, userId, rule.accountId());
        List<MailOrganizationQueryRepository.RuleCandidate> matches = candidates.stream()
                .filter(candidate -> evaluator.matches(rule, candidate))
                .toList();
        int changed = matches.stream()
                .mapToInt(candidate -> commands.applyRuleActions(
                        tenantId, userId, rule.accountId(), candidate.threadId(), rule.actions()))
                .sum();
        commands.completeRuleRun(
                tenantId, userId, ruleId, runId, candidates.size(), matches.size(), changed);
        record(tenantId, userId, correlationId, "mail.rule.executed", "MAIL_RULE",
                ruleId, Map.of("version", rule.version()), Map.of(
                        "runId", runId,
                        "scannedCount", candidates.size(),
                        "matchedCount", matches.size(),
                        "changedCount", changed));
        return queries.recentRuns(tenantId, userId).stream()
                .filter(run -> run.runId().equals(runId))
                .findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void validateRule(
            Long tenantId,
            Long userId,
            UUID accountId,
            List<MailOrganizationDtos.RuleCondition> conditions,
            List<MailOrganizationDtos.RuleAction> actions) {
        try {
            conditions.forEach(evaluator::validate);
            for (MailOrganizationDtos.RuleAction action : actions) {
                if (action.type() == RuleActionType.MOVE_TO_FOLDER) {
                    if (action.folderId() == null) {
                        throw new IllegalArgumentException("Move actions require a folder.");
                    }
                    MailOrganizationDtos.FolderSummary target =
                            folder(tenantId, userId, action.folderId());
                    if (!target.accountId().equals(accountId)
                            || !List.of("INBOX", "ARCHIVE", "CUSTOM").contains(target.folderType())) {
                        throw new IllegalArgumentException("The rule folder is not a safe target.");
                    }
                } else if (action.folderId() != null) {
                    throw new IllegalArgumentException("Only move actions accept a folder.");
                }
                if (action.type() == RuleActionType.SET_IMPORTANCE && action.importance() == null) {
                    throw new IllegalArgumentException("Importance actions require a value.");
                }
            }
        } catch (IllegalArgumentException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, exception.getMessage());
        }
    }

    private void validateParent(
            Long tenantId, Long userId, UUID accountId, UUID parentId, UUID folderId) {
        if (parentId == null) return;
        if (parentId.equals(folderId)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "A folder cannot contain itself.");
        }
        MailOrganizationDtos.FolderSummary parent = folder(tenantId, userId, parentId);
        if (!parent.accountId().equals(accountId) || !"CUSTOM".equals(parent.folderType())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE, "The parent must be a custom folder in this account.");
        }
    }

    private void requireOwnedAccount(Long tenantId, Long userId, UUID accountId) {
        if (!queries.ownsAccount(tenantId, userId, accountId)) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
    }

    private void requireCustom(MailOrganizationDtos.FolderSummary folder) {
        if (!"CUSTOM".equals(folder.folderType())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "System folders cannot be changed.");
        }
    }

    private MailOrganizationDtos.FolderSummary folder(Long tenantId, Long userId, UUID folderId) {
        return queries.folder(tenantId, userId, folderId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private MailOrganizationDtos.RuleSummary rule(Long tenantId, Long userId, UUID ruleId) {
        return queries.rule(tenantId, userId, ruleId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private Map<String, Object> folderState(MailOrganizationDtos.FolderSummary folder) {
        return Map.of(
                "displayName", folder.displayName(),
                "folderType", folder.folderType(),
                "color", folder.color().name(),
                "version", folder.version());
    }

    private Map<String, Object> ruleState(MailOrganizationDtos.RuleSummary rule) {
        return Map.of(
                "displayName", rule.displayName(),
                "priority", rule.priority(),
                "enabled", rule.enabled(),
                "synchronizationState", rule.synchronizationState().name(),
                "version", rule.version());
    }

    private void record(
            Long tenantId,
            Long userId,
            String correlationId,
            String eventType,
            String targetType,
            UUID targetId,
            Map<String, Object> before,
            Map<String, Object> after) {
        evidence.audit(
                tenantId, userId, eventType, targetType, targetId.toString(),
                correlationId, before, after);
        evidence.domainEvent(
                tenantId, targetType, targetId, eventType,
                Map.of("targetId", targetId, "actorUserId", userId), correlationId);
    }
}

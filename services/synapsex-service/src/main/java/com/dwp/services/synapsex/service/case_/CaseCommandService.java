package com.dwp.services.synapsex.service.case_;

import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.entity.AgentCaseStatus;
import com.dwp.services.synapsex.entity.CaseComment;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import com.dwp.services.synapsex.repository.CaseCommentRepository;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2 Cases 명령 서비스 (상태 변경, 할당, 코멘트 등)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseCommandService {

    private static final List<String> ALLOWED_STATUSES = List.of(
            "OPEN", "TRIAGED", "IN_PROGRESS", "RESOLVED", "DISMISSED", "CLOSED", "IN_REVIEW", "APPROVED", "REJECTED", "ACTIONED");

    private final AgentCaseRepository agentCaseRepository;
    private final CaseCommentRepository caseCommentRepository;
    private final AuditWriter auditWriter;

    @Transactional
    public AgentCase updateCaseStatus(Long tenantId, Long caseId, String newStatus,
                                      Long actorUserId, String ipAddress, String userAgent, String gatewayRequestId) {
        AgentCase case_ = agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        AgentCaseStatus statusEnum = AgentCaseStatus.fromString(newStatus);
        if (statusEnum == null || !ALLOWED_STATUSES.contains(newStatus)) {
            throw new IllegalArgumentException("Invalid status: " + newStatus);
        }

        String oldStatus = case_.getStatus() != null ? case_.getStatus().name() : null;
        case_.setStatus(statusEnum);
        agentCaseRepository.save(case_);

        auditWriter.logCaseStatusChange(tenantId, caseId, oldStatus, newStatus,
                actorUserId, ipAddress, userAgent, gatewayRequestId);

        return case_;
    }

    @Transactional
    public AgentCase assignCase(Long tenantId, Long caseId, Long assigneeUserId,
                                Long actorUserId, String ipAddress, String userAgent, String gatewayRequestId) {
        AgentCase case_ = agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        Long oldAssignee = case_.getAssigneeUserId();
        case_.setAssigneeUserId(assigneeUserId);
        agentCaseRepository.save(case_);

        Map<String, Object> diff = new HashMap<>();
        diff.put("assigneeUserId", Map.of("before", oldAssignee != null ? oldAssignee : "null", "after", assigneeUserId));
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_CASE, AuditEventConstants.TYPE_ASSIGN,
                "AGENT_CASE", String.valueOf(caseId),
                AuditEventConstants.ACTOR_HUMAN, actorUserId, null, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, diff, null, Map.of("caseId", caseId),
                ipAddress, userAgent, gatewayRequestId, null, null);

        return case_;
    }

    @Transactional
    public CaseComment addComment(Long tenantId, Long caseId, String commentText,
                                  Long actorUserId, String actorAgentId,
                                  String ipAddress, String userAgent, String gatewayRequestId) {
        AgentCase case_ = agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        CaseComment comment = CaseComment.builder()
                .tenantId(tenantId)
                .caseId(caseId)
                .authorUserId(actorUserId)
                .authorAgentId(actorAgentId)
                .commentText(commentText)
                .build();
        comment = caseCommentRepository.save(comment);

        Map<String, Object> afterMap = new HashMap<>();
        afterMap.put("commentId", comment.getCommentId());
        afterMap.put("commentText", commentText.substring(0, Math.min(200, commentText.length())));
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_CASE, AuditEventConstants.TYPE_COMMENT_CREATE,
                "CASE_COMMENT", String.valueOf(comment.getCommentId()),
                actorAgentId != null ? AuditEventConstants.ACTOR_AGENT : AuditEventConstants.ACTOR_HUMAN,
                actorUserId, actorAgentId, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, afterMap, null, null, Map.of("caseId", caseId),
                ipAddress, userAgent, gatewayRequestId, null, null);

        return comment;
    }
}

package com.dwp.services.synapsex.service.case_;

import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Phase 2 Cases 명령 서비스 (상태 변경 등)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseCommandService {

    private static final List<String> ALLOWED_STATUSES = List.of("TRIAGED", "IN_PROGRESS", "RESOLVED", "DISMISSED", "OPEN", "CLOSED");

    private final AgentCaseRepository agentCaseRepository;
    private final AuditWriter auditWriter;

    @Transactional
    public AgentCase updateCaseStatus(Long tenantId, Long caseId, String newStatus,
                                      Long actorUserId, String ipAddress, String userAgent, String gatewayRequestId) {
        AgentCase case_ = agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        if (!ALLOWED_STATUSES.contains(newStatus)) {
            throw new IllegalArgumentException("Invalid status: " + newStatus);
        }

        String oldStatus = case_.getStatus();
        case_.setStatus(newStatus);
        agentCaseRepository.save(case_);

        auditWriter.logCaseStatusChange(tenantId, caseId, oldStatus, newStatus,
                actorUserId, ipAddress, userAgent, gatewayRequestId);

        return case_;
    }
}

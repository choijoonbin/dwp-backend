package com.dwp.services.synapsex.service.audit;

import com.dwp.services.synapsex.client.AuthServerAuditLogClient;
import com.dwp.services.synapsex.dto.auth.InternalAuditLogRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthAuditLogBridgeService {

    private final AuthServerAuditLogClient authServerAuditLogClient;

    public void logUserExplanationSubmitted(Long tenantId, Long actorUserId, Long caseId, String evidenceAttachmentId, int explanationLength) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("caseId", caseId);
            metadata.put("evidenceAttachmentId", evidenceAttachmentId);
            metadata.put("explanationLength", explanationLength);
            authServerAuditLogClient.recordAuditLog(InternalAuditLogRequest.builder()
                    .tenantId(tenantId)
                    .actorUserId(actorUserId)
                    .action("USER_EXPLANATION_SUBMITTED")
                    .resourceType("AGENT_CASE")
                    .resourceId(caseId)
                    .metadata(metadata)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to write com_audit_logs bridge event: tenantId={}, caseId={}, error={}",
                    tenantId, caseId, e.getMessage());
        }
    }
}

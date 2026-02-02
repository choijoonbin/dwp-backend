package com.dwp.services.synapsex.service.admin;

import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.dwp.services.synapsex.dto.admin.ThresholdDto;
import com.dwp.services.synapsex.dto.admin.UpsertThresholdRequest;
import com.dwp.services.synapsex.entity.RuleThreshold;
import com.dwp.services.synapsex.repository.ConfigProfileRepository;
import com.dwp.services.synapsex.repository.RuleThresholdRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ThresholdCommandService {

    private final RuleThresholdRepository ruleThresholdRepository;
    private final ConfigProfileRepository configProfileRepository;
    private final AuditWriter auditWriter;

    @Transactional
    public ThresholdDto upsert(Long tenantId, Long actorUserId, UpsertThresholdRequest req) {
        if (!configProfileRepository.findByTenantIdAndProfileId(tenantId, req.getProfileId()).isPresent()) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "프로파일을 찾을 수 없습니다.");
        }
        Instant now = Instant.now();
        RuleThreshold e;
        if (req.getThresholdId() != null) {
            e = ruleThresholdRepository.findById(req.getThresholdId())
                    .filter(x -> x.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "한도 정책을 찾을 수 없습니다."));
        } else {
            e = new RuleThreshold();
            e.setTenantId(tenantId);
            e.setCreatedAt(now);
            e.setCreatedBy(actorUserId);
        }
        e.setProfileId(req.getProfileId());
        e.setPolicyDocId(req.getPolicyDocId());
        e.setDimension(req.getDimension());
        e.setDimensionKey(req.getDimensionKey());
        e.setWaers(req.getWaers() != null ? req.getWaers() : "KRW");
        e.setThresholdAmount(req.getThresholdAmount());
        e.setRequireEvidence(Boolean.TRUE.equals(req.getRequireEvidence()));
        e.setEvidenceTypes(req.getEvidenceTypes());
        e.setSeverityOnBreach(req.getSeverityOnBreach() != null ? req.getSeverityOnBreach() : "MEDIUM");
        e.setActionOnBreach(req.getActionOnBreach() != null ? req.getActionOnBreach() : "FLAG_FOR_REVIEW");
        e.setUpdatedAt(now);
        e.setUpdatedBy(actorUserId);
        e = ruleThresholdRepository.save(e);
        auditWriter.logAdminChange(tenantId, actorUserId, AuditEventConstants.TYPE_BULK_UPDATE, "rule_threshold", String.valueOf(e.getThresholdId()), null, null, null, null, null);
        return ThresholdDto.from(e);
    }

    @Transactional
    public void delete(Long tenantId, Long thresholdId) {
        RuleThreshold e = ruleThresholdRepository.findById(thresholdId)
                .filter(x -> x.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "한도 정책을 찾을 수 없습니다."));
        auditWriter.logAdminChange(tenantId, null, AuditEventConstants.TYPE_DELETE, "rule_threshold", String.valueOf(thresholdId), null, null, null, null, null);
        ruleThresholdRepository.delete(e);
    }
}

package com.dwp.services.synapsex.service.admin;

import com.dwp.services.synapsex.dto.admin.BulkPiiPolicyRequest;
import com.dwp.services.synapsex.dto.admin.PiiPolicyDto;
import com.dwp.services.synapsex.entity.PolicyPiiField;
import com.dwp.services.synapsex.repository.ConfigProfileRepository;
import com.dwp.services.synapsex.repository.PolicyPiiFieldRepository;
import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PiiPolicyCommandService {

    private static final List<String> VALID_HANDLING = List.of("ALLOW", "MASK", "HASH_ONLY", "ENCRYPT", "FORBID");

    private final PolicyPiiFieldRepository policyPiiFieldRepository;
    private final ConfigProfileRepository configProfileRepository;
    private final AuditWriter auditWriter;

    @Transactional
    public List<PiiPolicyDto> bulkSave(Long tenantId, Long actorUserId, BulkPiiPolicyRequest req) {
        if (configProfileRepository.findByTenantIdAndProfileId(tenantId, req.getProfileId()).isEmpty()) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "프로파일을 찾을 수 없습니다.");
        }
        Instant now = Instant.now();
        List<PolicyPiiField> existing = policyPiiFieldRepository.findByTenantIdAndProfileIdOrderByFieldName(tenantId, req.getProfileId());

        // before state for audit
        Map<String, Object> beforeJson = buildPoliciesAuditJson(existing);

        // Upsert per (tenant_id, profile_id, field_key)
        Map<String, PolicyPiiField> existingByField = existing.stream()
                .collect(Collectors.toMap(PolicyPiiField::getFieldName, e -> e, (a, b) -> a));

        if (req.getItems() != null && !req.getItems().isEmpty()) {
            for (BulkPiiPolicyRequest.PiiPolicyItem item : req.getItems()) {
                if (item.getFieldKey() == null || item.getFieldKey().isBlank()) continue;
                String handling = item.getHandling() != null ? item.getHandling().toUpperCase() : "ALLOW";
                if (!VALID_HANDLING.contains(handling)) {
                    throw new BaseException(ErrorCode.INVALID_CODE, "handling은 ALLOW, MASK, HASH_ONLY, ENCRYPT, FORBID 중 하나여야 합니다: " + item.getHandling());
                }
                PolicyPiiField e = existingByField.get(item.getFieldKey());
                if (e != null) {
                    e.setHandling(handling);
                    e.setMaskRule(item.getMaskRule());
                    e.setHashRule(item.getHashRule());
                    e.setEncryptRule(item.getEncryptRule());
                    e.setNote(item.getNote());
                    e.setUpdatedAt(now);
                    e.setUpdatedBy(actorUserId);
                    policyPiiFieldRepository.save(e);
                } else {
                    PolicyPiiField newEntity = PolicyPiiField.builder()
                            .tenantId(tenantId)
                            .profileId(req.getProfileId())
                            .fieldName(item.getFieldKey())
                            .handling(handling)
                            .maskRule(item.getMaskRule())
                            .hashRule(item.getHashRule())
                            .encryptRule(item.getEncryptRule())
                            .note(item.getNote())
                            .createdAt(now)
                            .createdBy(actorUserId)
                            .updatedAt(now)
                            .updatedBy(actorUserId)
                            .build();
                    policyPiiFieldRepository.save(newEntity);
                }
            }
        }

        // Delete fields no longer in request
        if (req.getItems() != null && !req.getItems().isEmpty()) {
            List<String> requestedKeys = req.getItems().stream()
                    .map(BulkPiiPolicyRequest.PiiPolicyItem::getFieldKey)
                    .filter(k -> k != null && !k.isBlank())
                    .collect(Collectors.toList());
            for (PolicyPiiField e : existing) {
                if (!requestedKeys.contains(e.getFieldName())) {
                    policyPiiFieldRepository.delete(e);
                }
            }
        } else {
            for (PolicyPiiField e : existing) {
                policyPiiFieldRepository.delete(e);
            }
        }

        List<PolicyPiiField> afterList = policyPiiFieldRepository.findByTenantIdAndProfileIdOrderByFieldName(tenantId, req.getProfileId());
        Map<String, Object> afterJson = buildPoliciesAuditJson(afterList);
        Map<String, Object> diffJson = buildDiffJson(beforeJson, afterJson);

        auditWriter.logPiiPolicyBulkChange(tenantId, actorUserId, String.valueOf(req.getProfileId()), beforeJson, afterJson, diffJson);
        return afterList.stream().map(PiiPolicyDto::from).collect(Collectors.toList());
    }

    private Map<String, Object> buildPoliciesAuditJson(List<PolicyPiiField> list) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (PolicyPiiField e : list) {
            Map<String, Object> m = new HashMap<>();
            m.put("fieldKey", e.getFieldName());
            m.put("handling", e.getHandling());
            m.put("maskRule", e.getMaskRule());
            m.put("hashRule", e.getHashRule());
            m.put("encryptRule", e.getEncryptRule());
            m.put("updatedAt", e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null);
            items.add(m);
        }
        return Map.of("items", items, "count", items.size());
    }

    private Map<String, Object> buildDiffJson(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> diff = new HashMap<>();
        diff.put("beforeCount", before.get("count"));
        diff.put("afterCount", after.get("count"));
        return diff;
    }
}

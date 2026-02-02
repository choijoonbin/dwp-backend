package com.dwp.services.synapsex.service.admin;

import com.dwp.services.synapsex.dto.admin.DataProtectionDto;
import com.dwp.services.synapsex.dto.admin.UpdateDataProtectionRequest;
import com.dwp.services.synapsex.entity.PolicyDataProtection;
import com.dwp.services.synapsex.repository.ConfigProfileRepository;
import com.dwp.services.synapsex.repository.PolicyDataProtectionRepository;
import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 데이터 보호 정책 명령 서비스.
 */
@Service
@RequiredArgsConstructor
public class DataProtectionCommandService {

    private static final int MIN_RETENTION = 1;
    private static final int MAX_RETENTION = 20;
    private static final String[] VALID_KEY_PROVIDERS = {"KMS_MOCK", "KMS", "HSM"};
    private static final String[] VALID_EXPORT_MODES = {"ZIP", "CSV"};
    private static final String VALID_KMS_MODE = "KMS_MANAGED_KEYS";

    private final PolicyDataProtectionRepository policyDataProtectionRepository;
    private final ConfigProfileRepository configProfileRepository;
    private final AuditWriter auditWriter;

    @Transactional
    public DataProtectionDto upsert(Long tenantId, Long actorUserId, UpdateDataProtectionRequest req) {
        if (configProfileRepository.findByTenantIdAndProfileId(tenantId, req.getProfileId()).isEmpty()) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "프로파일을 찾을 수 없습니다.");
        }
        validate(req);

        PolicyDataProtection existing = policyDataProtectionRepository.findByTenantIdAndProfileId(tenantId, req.getProfileId()).orElse(null);
        Map<String, Object> beforeJson = toAuditJson(existing);

        Instant now = Instant.now();
        PolicyDataProtection entity;
        if (existing != null) {
            applyUpdates(existing, req, now);
            entity = policyDataProtectionRepository.save(existing);
        } else {
            entity = PolicyDataProtection.builder()
                    .tenantId(tenantId)
                    .profileId(req.getProfileId())
                    .atRestEncryptionEnabled(req.getAtRestEncryptionEnabled() != null ? req.getAtRestEncryptionEnabled() : false)
                    .keyProvider(req.getKeyProvider() != null ? req.getKeyProvider() : "KMS_MOCK")
                    .kmsMode(req.getKmsMode() != null ? req.getKmsMode() : VALID_KMS_MODE)
                    .auditRetentionYears(req.getAuditRetentionYears() != null ? req.getAuditRetentionYears() : 7)
                    .exportRequiresApproval(req.getExportRequiresApproval() != null ? req.getExportRequiresApproval() : true)
                    .exportMode(req.getExportMode() != null ? req.getExportMode() : "ZIP")
                    .updatedAt(now)
                    .build();
            entity = policyDataProtectionRepository.save(entity);
        }

        Map<String, Object> afterJson = toAuditJson(entity);
        Map<String, Object> diffJson = buildDiff(beforeJson, afterJson);
        auditWriter.logDataProtectionUpdate(tenantId, actorUserId, String.valueOf(req.getProfileId()), beforeJson, afterJson, diffJson);

        return DataProtectionDto.from(entity);
    }

    private void validate(UpdateDataProtectionRequest req) {
        if (req.getAuditRetentionYears() != null && (req.getAuditRetentionYears() < MIN_RETENTION || req.getAuditRetentionYears() > MAX_RETENTION)) {
            throw new BaseException(ErrorCode.INVALID_CODE, "auditRetentionYears는 1~20 사이여야 합니다.");
        }
        if (req.getKeyProvider() != null && !java.util.Arrays.asList(VALID_KEY_PROVIDERS).contains(req.getKeyProvider())) {
            throw new BaseException(ErrorCode.INVALID_CODE, "keyProvider는 KMS_MOCK, KMS, HSM 중 하나여야 합니다.");
        }
        if (req.getExportMode() != null && !java.util.Arrays.asList(VALID_EXPORT_MODES).contains(req.getExportMode())) {
            throw new BaseException(ErrorCode.INVALID_CODE, "exportMode는 ZIP 또는 CSV여야 합니다.");
        }
    }

    private void applyUpdates(PolicyDataProtection e, UpdateDataProtectionRequest req, Instant now) {
        if (req.getAtRestEncryptionEnabled() != null) e.setAtRestEncryptionEnabled(req.getAtRestEncryptionEnabled());
        if (req.getKeyProvider() != null) e.setKeyProvider(req.getKeyProvider());
        if (req.getAuditRetentionYears() != null) e.setAuditRetentionYears(req.getAuditRetentionYears());
        if (req.getExportRequiresApproval() != null) e.setExportRequiresApproval(req.getExportRequiresApproval());
        if (req.getExportMode() != null) e.setExportMode(req.getExportMode());
        e.setUpdatedAt(now);
    }

    private Map<String, Object> toAuditJson(PolicyDataProtection e) {
        if (e == null) return Map.of();
        Map<String, Object> m = new HashMap<>();
        m.put("protectionId", e.getProtectionId());
        m.put("tenantId", e.getTenantId());
        m.put("profileId", e.getProfileId());
        m.put("atRestEncryptionEnabled", e.getAtRestEncryptionEnabled());
        m.put("keyProvider", e.getKeyProvider());
        m.put("kmsMode", e.getKmsMode());
        m.put("auditRetentionYears", e.getAuditRetentionYears());
        m.put("exportRequiresApproval", e.getExportRequiresApproval());
        m.put("exportMode", e.getExportMode());
        m.put("updatedAt", e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> buildDiff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> diff = new HashMap<>();
        for (String key : after.keySet()) {
            Object b = before.get(key);
            Object a = after.get(key);
            if (b == null && a == null) continue;
            if (b != null && b.equals(a)) continue;
            diff.put(key, Map.of("before", b != null ? b : "null", "after", a != null ? a : "null"));
        }
        return diff;
    }
}

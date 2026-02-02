package com.dwp.services.synapsex.service.admin;

import com.dwp.services.synapsex.dto.admin.DataProtectionDto;
import com.dwp.services.synapsex.entity.PolicyDataProtection;
import com.dwp.services.synapsex.repository.ConfigProfileRepository;
import com.dwp.services.synapsex.repository.PolicyDataProtectionRepository;
import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데이터 보호 정책 조회 서비스.
 */
@Service
@RequiredArgsConstructor
public class DataProtectionQueryService {

    private final PolicyDataProtectionRepository policyDataProtectionRepository;
    private final ConfigProfileRepository configProfileRepository;

    /**
     * tenant+profileId 기준 데이터 보호 정책 조회.
     * 없으면 기본 행 생성 후 반환 (create default if missing).
     */
    @Transactional(readOnly = true)
    public DataProtectionDto getByProfile(Long tenantId, Long profileId) {
        if (configProfileRepository.findByTenantIdAndProfileId(tenantId, profileId).isEmpty()) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "프로파일을 찾을 수 없습니다.");
        }
        return policyDataProtectionRepository.findByTenantIdAndProfileId(tenantId, profileId)
                .map(DataProtectionDto::from)
                .orElse(DataProtectionDto.builder()
                        .tenantId(tenantId)
                        .profileId(profileId)
                        .atRestEncryptionEnabled(false)
                        .keyProvider("KMS_MOCK")
                        .kmsMode("KMS_MANAGED_KEYS")
                        .auditRetentionYears(7)
                        .exportRequiresApproval(true)
                        .exportMode("ZIP")
                        .build());
    }

    /**
     * 없으면 기본 행 생성 후 반환 (persist).
     */
    @Transactional
    public DataProtectionDto getOrCreateByProfile(Long tenantId, Long profileId) {
        if (configProfileRepository.findByTenantIdAndProfileId(tenantId, profileId).isEmpty()) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "프로파일을 찾을 수 없습니다.");
        }
        return policyDataProtectionRepository.findByTenantIdAndProfileId(tenantId, profileId)
                .map(DataProtectionDto::from)
                .orElseGet(() -> {
                    PolicyDataProtection created = PolicyDataProtection.builder()
                            .tenantId(tenantId)
                            .profileId(profileId)
                            .atRestEncryptionEnabled(false)
                            .keyProvider("KMS_MOCK")
                            .auditRetentionYears(7)
                            .exportRequiresApproval(true)
                            .exportMode("ZIP")
                            .updatedAt(java.time.Instant.now())
                            .build();
                    return DataProtectionDto.from(policyDataProtectionRepository.save(created));
                });
    }
}

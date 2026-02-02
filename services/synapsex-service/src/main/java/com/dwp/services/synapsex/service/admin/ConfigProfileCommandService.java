package com.dwp.services.synapsex.service.admin;

import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.dto.admin.ConfigProfileDto;
import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.dwp.services.synapsex.dto.admin.CreateProfileRequest;
import com.dwp.services.synapsex.dto.admin.UpdateProfileRequest;
import com.dwp.services.synapsex.entity.ConfigProfile;
import com.dwp.services.synapsex.repository.ConfigProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConfigProfileCommandService {

    private final ConfigProfileRepository configProfileRepository;
    private final ConfigProfileQueryService configProfileQueryService;
    private final com.dwp.services.synapsex.repository.RuleThresholdRepository ruleThresholdRepository;
    private final com.dwp.services.synapsex.repository.PolicyPiiFieldRepository policyPiiFieldRepository;
    private final AuditWriter auditWriter;

    @Transactional
    public ConfigProfileDto create(Long tenantId, Long actorUserId, CreateProfileRequest req) {
        if (configProfileRepository.existsByTenantIdAndProfileName(tenantId, req.getProfileName())) {
            throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 프로파일명입니다.");
        }
        Instant now = Instant.now();
        ConfigProfile e = ConfigProfile.builder()
                .tenantId(tenantId)
                .profileName(req.getProfileName())
                .description(req.getDescription())
                .isDefault(Boolean.TRUE.equals(req.getIsDefault()))
                .createdAt(now)
                .createdBy(actorUserId)
                .updatedAt(now)
                .updatedBy(actorUserId)
                .build();
        if (e.getIsDefault()) {
            unsetDefaultForTenant(tenantId, null);
        }
        e = configProfileRepository.save(e);
        auditWriter.logAdminChange(tenantId, actorUserId, AuditEventConstants.TYPE_CREATE, "config_profile", String.valueOf(e.getProfileId()), null, null, null, null, null);
        return ConfigProfileDto.from(e);
    }

    @Transactional
    public ConfigProfileDto update(Long tenantId, Long actorUserId, Long profileId, UpdateProfileRequest req) {
        ConfigProfile e = configProfileQueryService.getEntity(tenantId, profileId);
        if (e == null) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "프로파일을 찾을 수 없습니다.");
        }
        if (req.getProfileName() != null && !req.getProfileName().isBlank()) {
            if (!req.getProfileName().equals(e.getProfileName()) &&
                    configProfileRepository.existsByTenantIdAndProfileName(tenantId, req.getProfileName())) {
                throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 프로파일명입니다.");
            }
            e.setProfileName(req.getProfileName());
        }
        if (req.getDescription() != null) e.setDescription(req.getDescription());
        if (req.getIsDefault() != null) {
            e.setIsDefault(req.getIsDefault());
            if (req.getIsDefault()) unsetDefaultForTenant(tenantId, e.getProfileId());
        }
        e.setUpdatedBy(actorUserId);
        e = configProfileRepository.save(e);
        auditWriter.logAdminChange(tenantId, actorUserId, AuditEventConstants.TYPE_UPDATE, "config_profile", String.valueOf(profileId), null, null, null, null, null);
        return ConfigProfileDto.from(e);
    }

    @Transactional
    public void setDefault(Long tenantId, Long actorUserId, Long profileId) {
        ConfigProfile e = configProfileQueryService.getEntity(tenantId, profileId);
        if (e == null) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "프로파일을 찾을 수 없습니다.");
        }
        unsetDefaultForTenant(tenantId, profileId);
        e.setIsDefault(true);
        configProfileRepository.save(e);
        auditWriter.logAdminChange(tenantId, actorUserId, AuditEventConstants.TYPE_SET_DEFAULT, "config_profile", String.valueOf(profileId), null, null, null, null, null);
    }

    @Transactional
    public void delete(Long tenantId, Long profileId) {
        ConfigProfile e = configProfileQueryService.getEntity(tenantId, profileId);
        if (e == null) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "프로파일을 찾을 수 없습니다.");
        }
        if (Boolean.TRUE.equals(e.getIsDefault())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "기본 프로파일은 삭제할 수 없습니다. 다른 프로파일을 기본으로 지정한 후 삭제하세요.");
        }
        long thresholdCount = ruleThresholdRepository.countByTenantIdAndProfileId(tenantId, profileId);
        long piiCount = policyPiiFieldRepository.countByTenantIdAndProfileId(tenantId, profileId);
        if (thresholdCount > 0 || piiCount > 0) {
            throw new BaseException(ErrorCode.RESOURCE_HAS_CHILDREN, "한도 정책 또는 PII 정책이 참조 중입니다. 먼저 제거하세요.");
        }
        auditWriter.logAdminChange(tenantId, null, AuditEventConstants.TYPE_DELETE, "config_profile", String.valueOf(profileId), null, null, null, null, null);
        configProfileRepository.delete(e);
    }

    private void unsetDefaultForTenant(Long tenantId, Long excludeProfileId) {
        List<ConfigProfile> list = configProfileRepository.findByTenantIdOrderByProfileIdAsc(tenantId);
        for (ConfigProfile p : list) {
            if (excludeProfileId != null && p.getProfileId().equals(excludeProfileId)) continue;
            if (Boolean.TRUE.equals(p.getIsDefault())) {
                p.setIsDefault(false);
                configProfileRepository.save(p);
            }
        }
    }
}

package com.dwp.services.synapsex.service.admin;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.dto.admin.UpdateGovernanceConfigRequest;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.dwp.services.synapsex.entity.ConfigKv;
import com.dwp.services.synapsex.repository.AppCodeRepository;
import com.dwp.services.synapsex.repository.ConfigKvRepository;
import com.dwp.services.synapsex.repository.ConfigProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 거버넌스 설정 값 업데이트 (config_kv).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceConfigCommandService {

    private static final List<String> GOVERNANCE_GROUP_KEYS = List.of(
            "SECURITY_ACCESS_MODEL",
            "SECURITY_SOD_MODE",
            "UX_SAVED_VIEWS_SCOPE",
            "PII_HANDLING"
    );

    private final ConfigProfileRepository configProfileRepository;
    private final ConfigKvRepository configKvRepository;
    private final AppCodeRepository appCodeRepository;

    /**
     * config_key의 현재값 업데이트. 기본 프로파일 기준.
     * value는 해당 그룹의 app_codes.code 중 하나여야 함.
     */
    @Transactional
    public void updateConfigValue(Long tenantId, Long actorUserId, String configKey, UpdateGovernanceConfigRequest request) {
        if (!GOVERNANCE_GROUP_KEYS.contains(configKey)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "지원하지 않는 configKey: " + configKey);
        }

        Long profileId = configProfileRepository.findByTenantIdAndIsDefaultTrue(tenantId)
                .map(p -> p.getProfileId())
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "기본 프로파일이 없습니다."));

        String value = request.getValue() != null ? request.getValue().trim().toUpperCase() : "";
        boolean valid = appCodeRepository.findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(configKey).stream()
                .anyMatch(c -> value.equals(c.getCode()));
        if (!valid) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "유효하지 않은 코드 값: " + value + " (configKey=" + configKey + ")");
        }

        Instant now = Instant.now();

        ConfigKv kv = configKvRepository.findByTenantIdAndProfileIdAndConfigKey(tenantId, profileId, configKey)
                .orElse(ConfigKv.builder()
                        .tenantId(tenantId)
                        .profileId(profileId)
                        .configKey(configKey)
                        .configValue(JsonNodeFactory.instance.textNode(value))
                        .createdAt(now)
                        .createdBy(actorUserId)
                        .updatedAt(now)
                        .updatedBy(actorUserId)
                        .build());

        kv.setConfigValue(JsonNodeFactory.instance.textNode(value));
        kv.setUpdatedAt(now);
        kv.setUpdatedBy(actorUserId);
        if (kv.getCreatedAt() == null) {
            kv.setCreatedAt(now);
            kv.setCreatedBy(actorUserId);
        }
        configKvRepository.save(kv);
        log.info("Governance config updated: tenantId={}, configKey={}, value={}", tenantId, configKey, value);
    }
}

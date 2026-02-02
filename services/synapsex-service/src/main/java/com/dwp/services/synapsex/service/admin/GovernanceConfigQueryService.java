package com.dwp.services.synapsex.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.dwp.services.synapsex.dto.admin.GovernanceConfigItemDto;
import com.dwp.services.synapsex.entity.AppCode;
import com.dwp.services.synapsex.entity.AppCodeGroup;
import com.dwp.services.synapsex.entity.ConfigKv;
import com.dwp.services.synapsex.repository.AppCodeGroupRepository;
import com.dwp.services.synapsex.repository.AppCodeRepository;
import com.dwp.services.synapsex.repository.ConfigKvRepository;
import com.dwp.services.synapsex.repository.ConfigProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 거버넌스 설정 조회 (코드 그룹 + 현재값 + 선택지).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GovernanceConfigQueryService {

    private static final List<String> GOVERNANCE_GROUP_KEYS = List.of(
            "SECURITY_ACCESS_MODEL",
            "SECURITY_SOD_MODE",
            "UX_SAVED_VIEWS_SCOPE",
            "PII_HANDLING"
    );

    private final ConfigProfileRepository configProfileRepository;
    private final ConfigKvRepository configKvRepository;
    private final AppCodeGroupRepository appCodeGroupRepository;
    private final AppCodeRepository appCodeRepository;

    /**
     * 거버넌스 설정 목록 (현재값 + 선택지). 기본 프로파일 기준.
     */
    public List<GovernanceConfigItemDto> listGovernanceConfig(Long tenantId) {
        Long profileId = configProfileRepository.findByTenantIdAndIsDefaultTrue(tenantId)
                .map(p -> p.getProfileId())
                .orElse(null);
        if (profileId == null) {
            log.warn("No default profile for tenantId={}", tenantId);
            return List.of();
        }

        List<GovernanceConfigItemDto> result = new ArrayList<>();
        for (String groupKey : GOVERNANCE_GROUP_KEYS) {
            Optional<AppCodeGroup> groupOpt = appCodeGroupRepository.findByGroupKey(groupKey);
            if (groupOpt.isEmpty()) continue;

            AppCodeGroup group = groupOpt.get();
            String currentValue = configKvRepository.findByTenantIdAndProfileIdAndConfigKey(tenantId, profileId, groupKey)
                    .map(ConfigKv::getConfigValue)
                    .map(this::jsonNodeToValue)
                    .orElse(null);

            List<AppCode> codes = appCodeRepository.findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(groupKey);
            List<GovernanceConfigItemDto.CodeOptionDto> options = codes.stream()
                    .map(c -> GovernanceConfigItemDto.CodeOptionDto.builder()
                            .code(c.getCode())
                            .name(c.getName())
                            .build())
                    .collect(Collectors.toList());

            result.add(GovernanceConfigItemDto.builder()
                    .configKey(groupKey)
                    .groupName(group.getGroupName())
                    .currentValue(currentValue)
                    .options(options)
                    .build());
        }
        return result;
    }

    /** JsonNode → 문자열 값 (텍스트 노드면 asText(), 아니면 null) */
    private String jsonNodeToValue(JsonNode node) {
        if (node == null) return null;
        return node.isTextual() ? node.asText() : (node.isNull() ? null : node.toString());
    }
}

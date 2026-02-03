package com.dwp.services.synapsex.scope;

import com.dwp.services.synapsex.repository.AppCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Drill-down Enum 검증: app_codes SoT 기반.
 * 요청 파라미터(status, severity 등)가 유효한 코드인지 검증.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DrillDownCodeResolver {

    private final AppCodeRepository appCodeRepository;

    public static final String GROUP_CASE_STATUS = "CASE_STATUS";
    public static final String GROUP_ACTION_STATUS = "ACTION_STATUS";
    public static final String GROUP_SEVERITY = "SEVERITY";
    public static final String GROUP_DRIVER_TYPE = "DRIVER_TYPE";
    public static final String GROUP_SLA_RISK = "SLA_RISK";
    public static final String GROUP_ANOMALY_STATUS = "ANOMALY_STATUS";

    /**
     * groupKey에 해당하는 활성 코드 Set 반환.
     */
    public Set<String> getValidCodes(String groupKey) {
        return appCodeRepository.findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(groupKey).stream()
                .map(c -> c.getCode().toUpperCase())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * values 중 groupKey 범위 내 유효한 것만 필터.
     * app_code에 없으면 무시 (빈 리스트 반환 가능).
     */
    public List<String> filterValid(String groupKey, List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        Set<String> valid = getValidCodes(groupKey);
        if (valid.isEmpty()) return values; // 코드 없으면 요청값 그대로 (fallback)
        return values.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> valid.contains(s.toUpperCase()))
                .toList();
    }

    /**
     * 단일 값이 유효한지.
     */
    public boolean isValid(String groupKey, String value) {
        if (value == null || value.isBlank()) return false;
        return getValidCodes(groupKey).contains(value.toUpperCase());
    }
}

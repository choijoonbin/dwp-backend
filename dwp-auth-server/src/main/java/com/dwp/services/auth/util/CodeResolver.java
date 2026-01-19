package com.dwp.services.auth.util;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.Code;
import com.dwp.services.auth.repository.CodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 코드 해석 및 검증 유틸리티
 * 
 * 하드코딩된 문자열 비교를 제거하고 코드 테이블 기반 검증을 제공합니다.
 * 
 * 사용 예시:
 * - CodeResolver.validate("RESOURCE_TYPE", "MENU") -> true/false
 * - CodeResolver.require("RESOURCE_TYPE", "MENU") -> throws if invalid
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeResolver {
    
    private final CodeRepository codeRepository;
    
    // 간단한 인메모리 캐시 (그룹별 활성 코드 Set)
    private final Map<String, Set<String>> codeCache = new ConcurrentHashMap<>();
    
    /**
     * 코드 유효성 검증
     * 
     * @param groupKey 그룹 키 (예: RESOURCE_TYPE)
     * @param code 코드 값 (예: MENU)
     * @return 유효하면 true, 아니면 false
     */
    public boolean validate(String groupKey, String code) {
        if (groupKey == null || code == null) {
            return false;
        }
        
        // 캐시에서 확인
        Set<String> codes = codeCache.computeIfAbsent(groupKey, key -> {
            List<Code> codeList = codeRepository.findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(key);
            return codeList.stream()
                    .map(Code::getCode)
                    .collect(Collectors.toSet());
        });
        
        boolean isValid = codes.contains(code);
        
        if (!isValid) {
            log.debug("Invalid code: groupKey={}, code={}", groupKey, code);
        }
        
        return isValid;
    }
    
    /**
     * 코드 필수 검증 (유효하지 않으면 예외 발생)
     * 
     * @param groupKey 그룹 키
     * @param code 코드 값
     * @throws BaseException 코드가 유효하지 않은 경우
     */
    public void require(String groupKey, String code) {
        if (!validate(groupKey, code)) {
            throw new BaseException(ErrorCode.INVALID_CODE, 
                String.format("Invalid code: groupKey=%s, code=%s", groupKey, code));
        }
    }
    
    /**
     * 그룹의 활성 코드 목록 조회
     * 
     * @param groupKey 그룹 키
     * @return 코드 값 목록
     */
    public List<String> getCodes(String groupKey) {
        return codeRepository.findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(groupKey)
                .stream()
                .map(Code::getCode)
                .collect(Collectors.toList());
    }
    
    /**
     * 캐시 초기화 (코드 변경 시 호출)
     */
    public void clearCache() {
        codeCache.clear();
        log.debug("Code cache cleared");
    }
    
    /**
     * 특정 그룹의 캐시만 초기화
     */
    public void clearCache(String groupKey) {
        codeCache.remove(groupKey);
        log.debug("Code cache cleared for groupKey: {}", groupKey);
    }
}

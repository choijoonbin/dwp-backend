package com.dwp.services.auth.util;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 공통 Validation Helper
 * 
 * Controller에서 반복되는 검증 로직을 공통화합니다.
 */
@Slf4j
@Component
public class ValidationHelper {
    
    private final CodeResolver codeResolver;
    
    public ValidationHelper(CodeResolver codeResolver) {
        this.codeResolver = codeResolver;
    }
    
    /**
     * tenantId 검증 (null 체크)
     * 
     * @param tenantId 테넌트 ID
     * @throws BaseException tenantId가 null인 경우
     */
    public void requireTenantId(Long tenantId) {
        if (tenantId == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "X-Tenant-ID 헤더가 필요합니다");
        }
    }
    
    /**
     * Authorization 헤더 검증
     * 
     * @param authorization Authorization 헤더 값
     * @throws BaseException Authorization 헤더가 없거나 Bearer 형식이 아닌 경우
     */
    public void requireAuthHeader(String authorization) {
        if (authorization == null || authorization.isEmpty()) {
            throw new BaseException(ErrorCode.UNAUTHORIZED, "Authorization 헤더가 필요합니다");
        }
        if (!authorization.startsWith("Bearer ")) {
            throw new BaseException(ErrorCode.UNAUTHORIZED, "Authorization 헤더는 Bearer 형식이어야 합니다");
        }
    }
    
    /**
     * 코드 값 검증 (CodeResolver 사용)
     * 
     * @param groupKey 코드 그룹 키 (예: "SUBJECT_TYPE", "LOGIN_TYPE")
     * @param codeValue 코드 값
     * @throws BaseException 코드가 존재하지 않는 경우
     */
    public void validateCode(String groupKey, String codeValue) {
        if (codeValue == null || codeValue.isEmpty()) {
            return; // null/empty는 허용 (선택적 필드)
        }
        codeResolver.require(groupKey, codeValue);
    }
    
    /**
     * 액션 정규화 (대문자 변환, 공백 제거)
     * 
     * @param action 액션 문자열
     * @return 정규화된 액션 문자열 (대문자)
     */
    public String normalizeAction(String action) {
        if (action == null || action.isEmpty()) {
            return action;
        }
        return action.trim().toUpperCase();
    }
    
    /**
     * userId 검증 (null 체크)
     * 
     * @param userId 사용자 ID
     * @throws BaseException userId가 null인 경우
     */
    public void requireUserId(Long userId) {
        if (userId == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "사용자 ID가 필요합니다");
        }
    }
    
    /**
     * HttpServletRequest에서 tenantId 추출 및 검증
     * 
     * @param request HttpServletRequest
     * @return tenantId
     * @throws BaseException tenantId가 없거나 유효하지 않은 경우
     */
    public Long extractTenantId(HttpServletRequest request) {
        String tenantIdHeader = request.getHeader("X-Tenant-ID");
        if (tenantIdHeader == null || tenantIdHeader.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "X-Tenant-ID 헤더가 필요합니다");
        }
        try {
            Long tenantId = Long.parseLong(tenantIdHeader);
            requireTenantId(tenantId);
            return tenantId;
        } catch (NumberFormatException e) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "X-Tenant-ID는 숫자여야 합니다");
        }
    }
    
    /**
     * HttpServletRequest에서 userId 추출 및 검증
     * 
     * @param request HttpServletRequest
     * @return userId
     * @throws BaseException userId가 없거나 유효하지 않은 경우
     */
    public Long extractUserId(HttpServletRequest request) {
        String userIdHeader = request.getHeader("X-User-ID");
        if (userIdHeader == null || userIdHeader.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "X-User-ID 헤더가 필요합니다");
        }
        try {
            Long userId = Long.parseLong(userIdHeader);
            requireUserId(userId);
            return userId;
        } catch (NumberFormatException e) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "X-User-ID는 숫자여야 합니다");
        }
    }
}

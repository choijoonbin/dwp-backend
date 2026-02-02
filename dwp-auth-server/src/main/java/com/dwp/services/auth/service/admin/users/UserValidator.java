package com.dwp.services.auth.service.admin.users;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.CreateUserRequest;
import com.dwp.services.auth.dto.admin.UpdateUserRequest;
import com.dwp.services.auth.repository.DepartmentRepository;
import com.dwp.services.auth.repository.UserAccountRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.dwp.services.auth.util.CodeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 사용자 검증 컴포넌트
 * 
 * tenantId, 이메일, 부서, principal 등 검증 규칙을 담당합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserValidator {
    
    private final UserRepository userRepository;
    private final UserAccountRepository userAccountRepository;
    private final DepartmentRepository departmentRepository;
    private final CodeResolver codeResolver;
    
    /**
     * tenantId 검증
     * 
     * @param tenantId 테넌트 ID
     * @throws BaseException tenantId가 null인 경우
     */
    public void validateTenantId(Long tenantId) {
        if (tenantId == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "X-Tenant-ID 헤더가 필요합니다");
        }
    }
    
    /**
     * 이메일 중복 체크 (생성 시)
     * 
     * @param tenantId 테넌트 ID
     * @param email 이메일
     * @throws BaseException 이미 존재하는 이메일인 경우
     */
    public void validateEmailNotDuplicate(Long tenantId, String email) {
        if (email != null && !email.isEmpty()) {
            userRepository.findByTenantIdAndEmail(tenantId, email)
                    .ifPresent(u -> {
                        throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 이메일입니다.");
                    });
        }
    }
    
    /**
     * 이메일 중복 체크 (수정 시, 본인 제외)
     * 
     * @param tenantId 테넌트 ID
     * @param userId 사용자 ID (본인)
     * @param email 이메일
     * @throws BaseException 이미 존재하는 이메일인 경우
     */
    public void validateEmailNotDuplicate(Long tenantId, Long userId, String email) {
        if (email != null && !email.isEmpty()) {
            userRepository.findByTenantIdAndEmail(tenantId, email)
                    .filter(u -> !u.getUserId().equals(userId))
                    .ifPresent(u -> {
                        throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 이메일입니다.");
                    });
        }
    }
    
    /**
     * 부서 존재 확인
     * 
     * @param tenantId 테넌트 ID
     * @param departmentId 부서 ID
     * @throws BaseException 부서를 찾을 수 없는 경우
     */
    public void validateDepartmentExists(Long tenantId, Long departmentId) {
        if (departmentId != null) {
            departmentRepository.findByTenantIdAndDepartmentId(tenantId, departmentId)
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부서를 찾을 수 없습니다."));
        }
    }
    
    /**
     * principal 중복 체크 (LOCAL 계정)
     * 
     * @param tenantId 테넌트 ID
     * @param principal principal (username)
     * @throws BaseException 이미 존재하는 principal인 경우
     */
    public void validatePrincipalNotDuplicate(Long tenantId, String principal) {
        String providerType = "LOCAL";
        codeResolver.require("IDP_PROVIDER_TYPE", providerType);
        
        userAccountRepository.findByTenantIdAndProviderTypeAndProviderIdAndPrincipal(
                tenantId, providerType, "local", principal)
                .ifPresent(acc -> {
                    throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 principal입니다.");
                });
    }
    
    /**
     * 사용자 생성 요청 검증
     * 
     * @param tenantId 테넌트 ID
     * @param request 생성 요청
     */
    public void validateCreateRequest(Long tenantId, CreateUserRequest request) {
        validateTenantId(tenantId);
        validateEmailNotDuplicate(tenantId, request.getEmail());
        validateDepartmentExists(tenantId, request.getDepartmentId());
        
        var effectiveLocal = request.getEffectiveLocalAccount();
        if (effectiveLocal != null) {
            validatePrincipalNotDuplicate(tenantId, effectiveLocal.getPrincipal());
        }
        if (Boolean.TRUE.equals(request.getCreateLocalAccount()) && effectiveLocal == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "로컬 계정 생성 시 principal과 password는 필수입니다.");
        }
    }
    
    /**
     * 사용자 수정 요청 검증
     * 
     * @param tenantId 테넌트 ID
     * @param userId 사용자 ID
     * @param request 수정 요청
     */
    public void validateUpdateRequest(Long tenantId, Long userId, UpdateUserRequest request) {
        validateTenantId(tenantId);
        validateEmailNotDuplicate(tenantId, userId, request.getEmail());
        validateDepartmentExists(tenantId, request.getDepartmentId());
    }
}

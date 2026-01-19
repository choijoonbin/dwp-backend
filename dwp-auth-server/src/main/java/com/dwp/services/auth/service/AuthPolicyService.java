package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AuthPolicyResponse;
import com.dwp.services.auth.entity.AuthPolicy;
import com.dwp.services.auth.repository.AuthPolicyRepository;
import com.dwp.services.auth.util.CodeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 인증 정책 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class AuthPolicyService {
    
    private final AuthPolicyRepository authPolicyRepository;
    private final CodeResolver codeResolver;
    
    /**
     * 테넌트별 인증 정책 조회
     * 
     * @param tenantId 테넌트 ID
     * @return 인증 정책 응답 (없으면 기본 정책 반환)
     */
    @Transactional(readOnly = true)
    public AuthPolicyResponse getAuthPolicy(Long tenantId) {
        AuthPolicy policy = authPolicyRepository.findByTenantId(tenantId)
                .orElseGet(() -> {
                    log.debug("Auth policy not found for tenantId={}, returning default policy", tenantId);
                    return createDefaultPolicy(tenantId);
                });
        
        return toAuthPolicyResponse(policy);
    }
    
    /**
     * 기본 인증 정책 생성 (LOCAL만 허용)
     */
    private AuthPolicy createDefaultPolicy(Long tenantId) {
        return AuthPolicy.builder()
                .tenantId(tenantId)
                .defaultLoginType("LOCAL")
                .allowedLoginTypes("LOCAL")
                .localLoginEnabled(true)
                .ssoLoginEnabled(false)
                .requireMfa(false)
                .build();
    }
    
    /**
     * AuthPolicy를 AuthPolicyResponse로 변환
     */
    private AuthPolicyResponse toAuthPolicyResponse(AuthPolicy policy) {
        // allowedLoginTypes CSV를 List로 변환
        List<String> allowedLoginTypes = Arrays.stream(policy.getAllowedLoginTypes().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        
        // LOGIN_TYPE 코드 검증
        String defaultLoginType = policy.getDefaultLoginType();
        codeResolver.require("LOGIN_TYPE", defaultLoginType);
        
        for (String loginType : allowedLoginTypes) {
            codeResolver.require("LOGIN_TYPE", loginType);
        }
        
        return AuthPolicyResponse.builder()
                .tenantId(policy.getTenantId())
                .defaultLoginType(defaultLoginType)
                .allowedLoginTypes(allowedLoginTypes)
                .localLoginEnabled(policy.getLocalLoginEnabled())
                .ssoLoginEnabled(policy.getSsoLoginEnabled())
                .ssoProviderKey(policy.getSsoProviderKey())
                .requireMfa(policy.getRequireMfa())
                .build();
    }
}

package com.dwp.services.auth.service.admin.users;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.ResetPasswordRequest;
import com.dwp.services.auth.dto.admin.ResetPasswordResponse;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.entity.UserAccount;
import com.dwp.services.auth.repository.UserAccountRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.util.CodeResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 사용자 비밀번호 관리 서비스 (CQRS: Command 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
@SuppressWarnings("null")
public class UserPasswordService {
    
    private final UserRepository userRepository;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final CodeResolver codeResolver;
    private final AuditLogService auditLogService;
    
    /**
     * 비밀번호 재설정
     */
    public ResetPasswordResponse resetPassword(Long tenantId, Long actorUserId, Long userId,
                                               ResetPasswordRequest request, HttpServletRequest httpRequest) {
        User user = userRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        
        // LOCAL 계정 찾기
        String providerType = "LOCAL";
        codeResolver.require("IDP_PROVIDER_TYPE", providerType);
        
        UserAccount account = userAccountRepository.findByTenantIdAndProviderTypeAndProviderIdAndPrincipal(
                        tenantId, providerType, "local", user.getEmail())
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "LOCAL 계정을 찾을 수 없습니다."));
        
        String newPassword = request.getNewPassword() != null ? request.getNewPassword() : generateTempPassword();
        String passwordHash = passwordEncoder.encode(newPassword);
        
        // 계정 상태 검증
        String accountStatus = "ACTIVE";
        codeResolver.require("USER_STATUS", accountStatus);
        
        account.setPasswordHash(passwordHash);
        account.setStatus(accountStatus);
        userAccountRepository.save(account);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "USER_RESET_PASSWORD", "USER", userId,
                null, null, httpRequest);
        
        ResetPasswordResponse response = ResetPasswordResponse.builder().build();
        if (request.getNewPassword() == null) {
            response.setTempPassword(newPassword);
        }
        return response;
    }
    
    /**
     * 임시 비밀번호 생성
     */
    private String generateTempPassword() {
        return UUID.randomUUID().toString().substring(0, 12);
    }
}

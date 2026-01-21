package com.dwp.services.auth.service.admin.users;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.*;
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

/**
 * 사용자 변경 서비스 (CQRS: Command 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
@SuppressWarnings("null")
public class UserCommandService {
    
    private final UserRepository userRepository;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final CodeResolver codeResolver;
    private final AuditLogService auditLogService;
    private final UserValidator userValidator;
    private final UserMapper userMapper;
    private final UserQueryService userQueryService;
    
    /**
     * 사용자 생성
     * 
     * PR-02B: 중복 검증(409) - email/principal 중복 시 DUPLICATE_ENTITY (CONFLICT) 반환
     */
    public UserDetail createUser(Long tenantId, Long actorUserId, CreateUserRequest request,
                                  HttpServletRequest httpRequest) {
        // PR-02B: 중복 검증 (email/principal) - 409 CONFLICT 반환
        // UserValidator.validateCreateRequest()에서 DUPLICATE_ENTITY 예외 발생
        userValidator.validateCreateRequest(tenantId, request);
        
        // 사용자 상태 처리 (기본값: ACTIVE)
        String userStatus = request.getStatus() != null ? request.getStatus() : "ACTIVE";
        codeResolver.require("USER_STATUS", userStatus);
        
        // 사용자 생성
        User user = User.builder()
                .tenantId(tenantId)
                .displayName(request.getUserName())
                .email(request.getEmail())
                .primaryDepartmentId(request.getDepartmentId())
                .status(userStatus)
                .build();
        user = userRepository.save(user);
        
        // LOCAL 계정 생성 (옵션)
        if (request.getLocalAccount() != null) {
            String providerType = "LOCAL";
            codeResolver.require("IDP_PROVIDER_TYPE", providerType);
            
            String password = request.getLocalAccount().getPassword();
            String passwordHash = passwordEncoder.encode(password);
            
            // 계정 상태 검증
            String accountStatus = "ACTIVE";
            codeResolver.require("USER_STATUS", accountStatus);
            
            UserAccount account = UserAccount.builder()
                    .tenantId(tenantId)
                    .userId(user.getUserId())
                    .providerType(providerType)
                    .providerId("local")
                    .principal(request.getLocalAccount().getPrincipal())
                    .passwordHash(passwordHash)
                    .status(accountStatus)
                    .build();
            userAccountRepository.save(account);
        }
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "USER_CREATE", "USER", user.getUserId(),
                null, user, httpRequest);
        
        return userQueryService.getUserDetail(tenantId, user.getUserId());
    }
    
    /**
     * 사용자 수정
     * 
     * PR-02C: principal(계정 username) 수정은 운영 위험이므로 이번 PR에서는 금지
     * - userName/email/status/department 변경 가능
     * - principal 수정은 별도 API로 분리 필요 (향후 구현)
     */
    public UserDetail updateUser(Long tenantId, Long actorUserId, Long userId, UpdateUserRequest request,
                                 HttpServletRequest httpRequest) {
        User user = userRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        
        User before = userMapper.copyUser(user);
        
        // 검증
        userValidator.validateUpdateRequest(tenantId, userId, request);
        
        // PR-02C: principal 수정 금지 (UpdateUserRequest에 principal 필드가 없으므로 자동으로 금지됨)
        // 향후 principal 수정이 필요하면 별도 API로 분리해야 함
        
        // 수정
        if (request.getUserName() != null) {
            user.setDisplayName(request.getUserName());
        }
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        if (request.getDepartmentId() != null) {
            user.setPrimaryDepartmentId(request.getDepartmentId());
        }
        if (request.getStatus() != null) {
            codeResolver.require("USER_STATUS", request.getStatus());
            user.setStatus(request.getStatus());
        }
        
        userRepository.save(user);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "USER_UPDATE", "USER", userId,
                before, user, httpRequest);
        
        return userQueryService.getUserDetail(tenantId, userId);
    }
    
    /**
     * 사용자 상태 변경
     */
    public UserDetail updateUserStatus(Long tenantId, Long actorUserId, Long userId,
                                       UpdateUserStatusRequest request, HttpServletRequest httpRequest) {
        User user = userRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        
        User before = userMapper.copyUser(user);
        codeResolver.require("USER_STATUS", request.getStatus());
        user.setStatus(request.getStatus());
        userRepository.save(user);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "USER_STATUS_UPDATE", "USER", userId,
                before, user, httpRequest);
        
        return userQueryService.getUserDetail(tenantId, userId);
    }
    
    /**
     * 사용자 삭제 (soft delete, idempotent)
     * 
     * PR-02D: 이미 삭제된 경우(idempotent) 200 OK 반환
     */
    public void deleteUser(Long tenantId, Long actorUserId, Long userId, HttpServletRequest httpRequest) {
        User user = userRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        
        // PR-02D: 이미 삭제된 경우(idempotent) - INACTIVE 상태면 그대로 반환
        String inactiveStatus = codeResolver.getCodes("USER_STATUS").stream()
                .filter("INACTIVE"::equals)
                .findFirst()
                .orElse("INACTIVE");
        
        if (inactiveStatus.equals(user.getStatus())) {
            log.debug("User already deleted (idempotent): tenantId={}, userId={}", tenantId, userId);
            return; // 이미 삭제됨 - idempotent 처리
        }
        
        User before = userMapper.copyUser(user);
        
        // Soft delete (status = INACTIVE)
        codeResolver.require("USER_STATUS", inactiveStatus);
        user.setStatus(inactiveStatus);
        userRepository.save(user);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "USER_DELETE", "USER", userId,
                before, user, httpRequest);
    }
}

package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.*;
import com.dwp.services.auth.entity.*;
import com.dwp.services.auth.repository.*;
import com.dwp.services.auth.util.CodeResolver;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 인증 서비스
 * 
 * DB 기반 LOCAL 인증 및 JWT 토큰 발급을 담당합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class AuthService {
    
    private final UserRepository userRepository;
    private final UserAccountRepository userAccountRepository;
    private final TenantRepository tenantRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final ResourceRepository resourceRepository;
    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;
    private final LoginHistoryRepository loginHistoryRepository;
    private final PasswordEncoder passwordEncoder;
    private final MenuRepository menuRepository;
    private final MenuService menuService;
    private final CodeResolver codeResolver;
    private final AuthPolicyService authPolicyService;
    
    @Value("${jwt.secret:your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256}")
    private String jwtSecret;
    
    @Value("${jwt.expiration-seconds:3600}")
    private Long tokenExpirationSeconds;
    
    private SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * 로그인 및 JWT 토큰 발급 (DB 기반 LOCAL 인증)
     * 
     * @param request 로그인 요청 (username, password, tenantId)
     * @return 로그인 응답 (JWT 토큰 포함)
     * @throws BaseException 인증 실패 시
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        final String username = request.getUsername();
        final String password = request.getPassword();
        final String tenantIdStr = request.getTenantId();
        
        // tenantId 파싱 (숫자 또는 code)
        Long parsedTenantId;
        try {
            parsedTenantId = Long.parseLong(tenantIdStr);
        } catch (NumberFormatException e) {
            // 'default'는 개발 환경에서 'dev'로 매핑
            String tenantCode = "default".equals(tenantIdStr) ? "dev" : tenantIdStr;
            Tenant tenant = tenantRepository.findByCode(tenantCode)
                .orElseThrow(() -> new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS, 
                    String.format("Invalid tenant: %s (mapped to: %s)", tenantIdStr, tenantCode)));
            parsedTenantId = tenant.getTenantId();
        }
        final Long tenantId = parsedTenantId;
        
        try {
            log.debug("Login attempt: tenantId={}, username={}", tenantId, username);
            
            // PR-10A: 정책 기반 로그인 흐름 최종 확정
            // 0. Auth Policy 확인 (LOCAL 로그인 허용 여부 체크)
            AuthPolicyResponse policy = authPolicyService.getAuthPolicy(tenantId);
            if (!policy.getLocalLoginEnabled() || !policy.getAllowedLoginTypes().contains("LOCAL")) {
                log.warn("Local login not allowed: tenantId={}, policy={}", tenantId, policy);
                recordLoginFailure(tenantId, null, username, "LOCAL_LOGIN_DISABLED", "LOCAL");
                throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS, 
                        "Local login is not allowed. Please use SSO login.");
            }
            
            // 1. UserAccount 조회
            String localProviderType = "LOCAL";
            codeResolver.require("IDP_PROVIDER_TYPE", localProviderType);
            UserAccount account = userAccountRepository
                .findByTenantIdAndProviderTypeAndProviderIdAndPrincipal(
                    tenantId, localProviderType, "local", username
                )
                .orElseThrow(() -> {
                    log.warn("UserAccount not found: tenantId={}, username={}", tenantId, username);
                    recordLoginFailure(tenantId, null, username, "USER_NOT_FOUND", "LOCAL");
                    return new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid username or password");
                });
            
            // 2. 계정 상태 확인
            if (!"ACTIVE".equals(account.getStatus())) {
                log.warn("Account is not active: status={}, username={}", account.getStatus(), username);
                recordLoginFailure(tenantId, account.getUserId(), username, "USER_LOCKED", "LOCAL");
                throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Account is locked");
            }
            
            // 3. 비밀번호 검증 (BCrypt)
            if (account.getPasswordHash() == null || 
                !passwordEncoder.matches(password, account.getPasswordHash())) {
                log.warn("Password mismatch for user: {}", username);
                recordLoginFailure(tenantId, account.getUserId(), username, "INVALID_PASSWORD", "LOCAL");
                throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid username or password");
            }
            
            // 4. User 조회
            User user = userRepository.findById(account.getUserId())
                .orElseThrow(() -> {
                    log.error("User profile missing for account: userId={}", account.getUserId());
                    recordLoginFailure(tenantId, account.getUserId(), username, "USER_NOT_FOUND", "LOCAL");
                    return new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS, "User not found");
                });
            
            // 5. User 상태 확인
            if (!"ACTIVE".equals(user.getStatus())) {
                log.warn("User is not active: status={}, userId={}", user.getStatus(), user.getUserId());
                recordLoginFailure(tenantId, user.getUserId(), username, "USER_LOCKED", "LOCAL");
                throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS, "User is not active");
            }
            
            // 6. JWT 토큰 발급
            String accessToken = generateJwtToken(user.getUserId().toString(), tenantId.toString());
            
            // 7. PR-10E: 로그인 성공 기록 (provider_type 기록)
            recordLoginSuccess(tenantId, user.getUserId(), username, "LOCAL", "local", null);
            
            log.info("Login successful: userId={}, tenantId={}, username={}", 
                    user.getUserId(), tenantId, username);
            
            List<PermissionDTO> permissions = getMyPermissions(user.getUserId(), tenantId);
            List<MenuNode> menus = menuService.getMenuTree(user.getUserId(), tenantId).getMenus();
            
            return LoginResponse.builder()
                    .accessToken(accessToken)
                    .tokenType("Bearer")
                    .expiresIn(tokenExpirationSeconds)
                    .userId(user.getUserId().toString())
                    .tenantId(tenantId.toString())
                    .permissions(permissions)
                    .menus(menus)
                    .build();
                    
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected login error: username={}, tenantId={}", username, tenantId, e);
            recordLoginFailure(tenantId, null, username, "SYSTEM_ERROR");
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Login failed");
        }
    }
    
    /**
     * 내 정보 조회
     */
    @Transactional(readOnly = true)
    public MeResponse getMe(Long userId, Long tenantId) {
        User user = userRepository.findByUserIdAndTenantId(userId, tenantId)
            .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND, "User not found"));
        
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND, "Tenant not found"));
        
        List<Long> roleIds = roleMemberRepository.findRoleIdsByTenantIdAndUserId(tenantId, userId);
        List<String> roleCodes = roleRepository.findByRoleIdIn(roleIds).stream()
            .map(Role::getCode)
            .collect(Collectors.toList());
        
        return MeResponse.builder()
                .userId(user.getUserId())
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .tenantId(tenant.getTenantId())
                .tenantCode(tenant.getCode())
                .roles(roleCodes)
                .build();
    }
    
    /**
     * 내 권한 목록 조회
     */
    @Transactional(readOnly = true)
    public List<PermissionDTO> getMyPermissions(Long userId, Long tenantId) {
        List<Long> roleIds = roleMemberRepository.findRoleIdsByTenantIdAndUserId(tenantId, userId);
        
        if (roleIds.isEmpty()) return List.of();
        
        List<RolePermission> rolePermissions = rolePermissionRepository.findByTenantIdAndRoleIdIn(tenantId, roleIds);
        if (rolePermissions.isEmpty()) return List.of();
        
        List<Long> resourceIds = rolePermissions.stream().map(RolePermission::getResourceId).distinct().toList();
        List<Long> permissionIds = rolePermissions.stream().map(RolePermission::getPermissionId).distinct().toList();
        
        List<Resource> resources = resourceRepository.findByResourceIdIn(resourceIds);
        List<Permission> permissions = permissionRepository.findByPermissionIdIn(permissionIds);
        
        return rolePermissions.stream()
            .map(rp -> {
                Resource resource = resources.stream().filter(r -> r.getResourceId().equals(rp.getResourceId())).findFirst().orElse(null);
                Permission permission = permissions.stream().filter(p -> p.getPermissionId().equals(rp.getPermissionId())).findFirst().orElse(null);
                
                if (resource == null || permission == null) return null;
                
                // MENU 타입인 경우 sys_menus에서 menu_name을 가져옴 (호환성 개선)
                String resourceName = resource.getName();
                String menuTypeCode = "MENU";
                if (codeResolver.validate("RESOURCE_TYPE", resource.getType()) && 
                    menuTypeCode.equals(resource.getType())) {
                    resourceName = menuRepository.findByTenantIdAndMenuKey(tenantId, resource.getKey())
                            .map(menu -> menu.getMenuName())
                            .orElse(resource.getName());
                }
                
                // 확장 필드 추가 (BE P1-5)
                return PermissionDTO.builder()
                        .resourceType(resource.getType())  // 기존 필드 (하위 호환)
                        .resourceKey(resource.getKey())
                        .resourceName(resourceName)
                        .permissionCode(permission.getCode())
                        .permissionName(permission.getName())
                        .effect(rp.getEffect())
                        .resourceCategory(resource.getResourceCategory() != null ? resource.getResourceCategory() : resource.getType())  // 기본값: type과 동일
                        .resourceKind(resource.getResourceKind() != null ? resource.getResourceKind() : "PAGE")  // 기본값: PAGE
                        .eventKey(resource.getEventKey())
                        .trackingEnabled(resource.getTrackingEnabled() != null ? resource.getTrackingEnabled() : true)  // 기본값: true
                        .eventActions(resource.getEventActions())  // JSON 문자열
                        .meta(parseMetadataJson(resource.getMetadataJson()))  // 기존 metadata_json 파싱
                        .build();
            })
            .filter(dto -> dto != null)
            .collect(Collectors.toList());
    }
    
    /**
     * metadata_json 문자열을 Object로 파싱 (BE P1-5)
     */
    private Object parseMetadataJson(String metadataJson) {
        if (metadataJson == null || metadataJson.trim().isEmpty()) {
            return null;
        }
        try {
            // 간단한 JSON 파싱 (Jackson ObjectMapper 사용 가능)
            // 현재는 문자열 그대로 반환 (향후 필요시 ObjectMapper로 파싱)
            return metadataJson;
        } catch (Exception e) {
            log.warn("Failed to parse metadata_json: {}", metadataJson, e);
            return metadataJson;
        }
    }
    
    private String generateJwtToken(String userId, String tenantId) {
        Instant now = Instant.now();
        
        return Jwts.builder()
                .subject(userId)
                .claim("tenant_id", tenantId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(tokenExpirationSeconds, ChronoUnit.SECONDS)))
                .signWith(getSecretKey(), Jwts.SIG.HS256)
                .compact();
    }
    
    /**
     * PR-10E: 로그인 성공 기록 (provider_type 기록)
     */
    private void recordLoginSuccess(Long tenantId, Long userId, String principal, 
                                   String providerType, String providerId,
                                   jakarta.servlet.http.HttpServletRequest request) {
        try {
            codeResolver.require("IDP_PROVIDER_TYPE", providerType);
            LoginHistory history = LoginHistory.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .providerType(providerType)
                    .providerId(providerId)
                    .principal(principal)
                    .success(true)
                    .ipAddress(request != null ? getClientIp(request) : null)
                    .userAgent(request != null ? request.getHeader("User-Agent") : null)
                    .build();
            loginHistoryRepository.save(history);
        } catch (Exception e) {
            log.error("Failed to record login success", e);
        }
    }
    
    /**
     * PR-10E: 로그인 실패 기록 (provider_type 및 실패 사유 표준화)
     */
    private void recordLoginFailure(Long tenantId, Long userId, String username, String reason) {
        recordLoginFailure(tenantId, userId, username, reason, "LOCAL");
    }
    
    /**
     * PR-10E: 로그인 실패 기록 (provider_type 및 실패 사유 표준화, 오버로드)
     */
    private void recordLoginFailure(Long tenantId, Long userId, String principal, 
                                   String reason, String providerType) {
        try {
            codeResolver.require("IDP_PROVIDER_TYPE", providerType);
            LoginHistory history = LoginHistory.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .providerType(providerType)
                    .providerId(providerType.equals("LOCAL") ? "local" : "sso")
                    .principal(principal)
                    .success(false)
                    .failureReason(reason) // PR-10E: 실패 사유 표준화
                    .build();
            loginHistoryRepository.save(history);
        } catch (Exception e) {
            log.error("Failed to record login failure", e);
        }
    }
    
    /**
     * PR-10D: SSO 로그인 및 JWT 토큰 발급 (LOCAL과 동일한 JWT 모델)
     * 
     * @param tenantId 테넌트 ID
     * @param providerKey Provider Key (예: AZURE_AD)
     * @param userInfo OIDC 사용자 정보
     * @param request HTTP 요청 (IP, User-Agent 추출용)
     * @return 로그인 응답 (JWT 토큰 포함)
     */
    @Transactional
    public LoginResponse loginWithSso(Long tenantId, String providerKey, 
                                     com.dwp.services.auth.service.sso.OidcUserInfo userInfo,
                                     jakarta.servlet.http.HttpServletRequest request) {
        try {
            log.debug("SSO login attempt: tenantId={}, providerKey={}, email={}", 
                    tenantId, providerKey, userInfo.getEmail());
            
            // PR-10A: 정책 체크 (SSO 로그인 허용 여부)
            AuthPolicyResponse policy = authPolicyService.getAuthPolicy(tenantId);
            if (!policy.getSsoLoginEnabled() || !policy.getAllowedLoginTypes().contains("SSO")) {
                log.warn("SSO login not allowed: tenantId={}, policy={}", tenantId, policy);
                recordLoginFailure(tenantId, null, userInfo.getEmail(), "SSO_LOGIN_DISABLED", "SSO");
                throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS, 
                        "SSO login is not allowed. Please use local login.");
            }
            
            // 사용자 계정 찾기 또는 생성
            String ssoProviderType = "SSO";
            codeResolver.require("IDP_PROVIDER_TYPE", ssoProviderType);
            String principal = userInfo.getEmail() != null ? userInfo.getEmail() : userInfo.getSub();
            
            UserAccount account = userAccountRepository
                    .findByTenantIdAndProviderTypeAndProviderIdAndPrincipal(
                            tenantId, ssoProviderType, providerKey, principal)
                    .orElseThrow(() -> {
                        log.warn("SSO UserAccount not found: tenantId={}, providerKey={}, principal={}", 
                                tenantId, providerKey, principal);
                        recordLoginFailure(tenantId, null, principal, "USER_NOT_FOUND", ssoProviderType);
                        return new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS, 
                                "SSO user not found. Please contact administrator.");
                    });
            
            // 계정 상태 확인
            if (!"ACTIVE".equals(account.getStatus())) {
                log.warn("SSO Account is not active: status={}, principal={}", account.getStatus(), principal);
                recordLoginFailure(tenantId, account.getUserId(), principal, "USER_LOCKED", ssoProviderType);
                throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Account is locked");
            }
            
            // User 조회
            User user = userRepository.findById(account.getUserId())
                    .orElseThrow(() -> {
                        log.error("User profile missing for SSO account: userId={}", account.getUserId());
                        recordLoginFailure(tenantId, account.getUserId(), principal, "USER_NOT_FOUND", ssoProviderType);
                        return new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS, "User not found");
                    });
            
            // User 상태 확인
            if (!"ACTIVE".equals(user.getStatus())) {
                log.warn("User is not active: status={}, userId={}", user.getStatus(), user.getUserId());
                recordLoginFailure(tenantId, user.getUserId(), principal, "USER_LOCKED", ssoProviderType);
                throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS, "User is not active");
            }
            
            // PR-10D: JWT 토큰 발급 (LOCAL과 동일한 모델)
            String accessToken = generateJwtToken(user.getUserId().toString(), tenantId.toString());
            
            // PR-10E: 로그인 성공 기록 (provider_type 기록)
            recordLoginSuccess(tenantId, user.getUserId(), principal, ssoProviderType, providerKey, request);
            
            log.info("SSO login successful: userId={}, tenantId={}, providerKey={}, principal={}", 
                    user.getUserId(), tenantId, providerKey, principal);
            
            List<PermissionDTO> permissions = getMyPermissions(user.getUserId(), tenantId);
            List<MenuNode> menus = menuService.getMenuTree(user.getUserId(), tenantId).getMenus();
            
            return LoginResponse.builder()
                    .accessToken(accessToken)
                    .tokenType("Bearer")
                    .expiresIn(tokenExpirationSeconds)
                    .userId(user.getUserId().toString())
                    .tenantId(tenantId.toString())
                    .permissions(permissions)
                    .menus(menus)
                    .build();
                    
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected SSO login error: tenantId={}, providerKey={}", tenantId, providerKey, e);
            recordLoginFailure(tenantId, null, userInfo.getEmail(), "SYSTEM_ERROR", "SSO");
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "SSO login failed");
        }
    }
    
    private String getClientIp(jakarta.servlet.http.HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isEmpty()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

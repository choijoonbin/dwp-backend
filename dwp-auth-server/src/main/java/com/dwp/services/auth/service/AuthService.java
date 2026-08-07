package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AuthPolicyResponse;
import com.dwp.services.auth.dto.LoginRequest;
import com.dwp.services.auth.dto.LoginResponse;
import com.dwp.services.auth.dto.MeResponse;
import com.dwp.services.auth.dto.OidcUserInfo;
import com.dwp.services.auth.dto.PermissionDTO;
import com.dwp.services.auth.entity.LoginHistory;
import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.RolePermission;
import com.dwp.services.auth.entity.Tenant;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.entity.UserAccount;
import com.dwp.services.auth.repository.LoginHistoryRepository;
import com.dwp.services.auth.repository.PermissionRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RolePermissionRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.TenantRepository;
import com.dwp.services.auth.repository.UserAccountRepository;
import com.dwp.services.auth.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final UserAccountRepository userAccountRepository;
    private final TenantRepository tenantRepository;
    private final RoleRepository roleRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final ResourceRepository resourceRepository;
    private final PermissionRepository permissionRepository;
    private final LoginHistoryRepository loginHistoryRepository;
    private final AuthPolicyService authPolicyService;
    private final PasswordEncoder passwordEncoder;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration-seconds:28800}")
    private Long tokenExpirationSeconds;

    public AuthService(
            UserRepository userRepository,
            UserAccountRepository userAccountRepository,
            TenantRepository tenantRepository,
            RoleRepository roleRepository,
            RoleMemberRepository roleMemberRepository,
            RolePermissionRepository rolePermissionRepository,
            ResourceRepository resourceRepository,
            PermissionRepository permissionRepository,
            LoginHistoryRepository loginHistoryRepository,
            AuthPolicyService authPolicyService,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userAccountRepository = userAccountRepository;
        this.tenantRepository = tenantRepository;
        this.roleRepository = roleRepository;
        this.roleMemberRepository = roleMemberRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.resourceRepository = resourceRepository;
        this.permissionRepository = permissionRepository;
        this.loginHistoryRepository = loginHistoryRepository;
        this.authPolicyService = authPolicyService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public LoginResponse login(LoginRequest request, HttpServletRequest servletRequest) {
        Long tenantId = resolveTenantId(request.getTenantId());
        AuthPolicyResponse policy = authPolicyService.getPolicy(tenantId);
        if (!Boolean.TRUE.equals(policy.getLocalLoginEnabled())
                || !policy.getAllowedLoginTypes().contains("LOCAL")) {
            recordLogin(tenantId, null, "LOCAL", "local", request.getUsername(), false,
                    "LOCAL_LOGIN_DISABLED", servletRequest);
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        UserAccount account = userAccountRepository
                .findByTenantIdAndProviderTypeAndProviderIdAndPrincipal(
                        tenantId, "LOCAL", "local", request.getUsername())
                .orElseThrow(() -> {
                    recordLogin(tenantId, null, "LOCAL", "local", request.getUsername(), false,
                            "USER_NOT_FOUND", servletRequest);
                    return new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
                });

        validateAccount(account, request.getUsername(), servletRequest);
        if (account.getPasswordHash() == null
                || !passwordEncoder.matches(request.getPassword(), account.getPasswordHash())) {
            recordLogin(tenantId, account.getUserId(), "LOCAL", "local", request.getUsername(),
                    false, "INVALID_PASSWORD", servletRequest);
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        User user = requireActiveUser(account.getUserId(), tenantId);
        recordLogin(tenantId, user.getUserId(), "LOCAL", "local", request.getUsername(), true,
                null, servletRequest);
        return createLoginResponse(user, tenantId);
    }

    @Transactional
    public LoginResponse loginWithOidc(
            Long tenantId,
            String providerKey,
            OidcUserInfo userInfo,
            HttpServletRequest servletRequest) {
        AuthPolicyResponse policy = authPolicyService.getPolicy(tenantId);
        if (!Boolean.TRUE.equals(policy.getSsoLoginEnabled())
                || !policy.getAllowedLoginTypes().contains("SSO")) {
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        String principal = userInfo.principal();
        if (principal == null || principal.isBlank()) {
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        UserAccount account = userAccountRepository
                .findByTenantIdAndProviderTypeAndProviderIdAndPrincipal(
                        tenantId, "OIDC", providerKey, principal)
                .orElseThrow(() -> new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS));

        validateAccount(account, principal, servletRequest);
        User user = requireActiveUser(account.getUserId(), tenantId);
        recordLogin(tenantId, user.getUserId(), "OIDC", providerKey, principal, true,
                null, servletRequest);
        return createLoginResponse(user, tenantId);
    }

    @Transactional(readOnly = true)
    public MeResponse getMe(Long userId, Long tenantId) {
        User user = userRepository.findByUserIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));

        return MeResponse.builder()
                .userId(user.getUserId())
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .tenantId(tenant.getTenantId())
                .tenantCode(tenant.getCode())
                .roles(getRoleCodes(userId, tenantId))
                .build();
    }

    @Transactional(readOnly = true)
    public List<PermissionDTO> getPermissions(Long userId, Long tenantId) {
        List<Long> roleIds = roleMemberRepository.findRoleIds(tenantId, userId);
        if (roleIds.isEmpty()) return List.of();

        List<RolePermission> assignments =
                rolePermissionRepository.findByTenantIdAndRoleIdInAndEffect(
                        tenantId, roleIds, "ALLOW");
        if (assignments.isEmpty()) return List.of();

        Map<Long, Resource> resources = resourceRepository
                .findAllById(assignments.stream().map(RolePermission::getResourceId).toList())
                .stream()
                .filter(resource -> Boolean.TRUE.equals(resource.getEnabled()))
                .collect(Collectors.toMap(Resource::getResourceId, Function.identity()));
        Map<Long, Permission> permissions = permissionRepository
                .findAllById(assignments.stream().map(RolePermission::getPermissionId).toList())
                .stream()
                .collect(Collectors.toMap(Permission::getPermissionId, Function.identity()));

        return assignments.stream()
                .map(assignment -> toPermission(
                        assignment,
                        resources.get(assignment.getResourceId()),
                        permissions.get(assignment.getPermissionId())))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private PermissionDTO toPermission(
            RolePermission assignment,
            Resource resource,
            Permission permission) {
        if (resource == null || permission == null) return null;
        return PermissionDTO.builder()
                .resourceType(resource.getType())
                .resourceKey(resource.getKey())
                .resourceName(resource.getName())
                .permissionCode(permission.getCode())
                .permissionName(permission.getName())
                .effect(assignment.getEffect())
                .build();
    }

    private LoginResponse createLoginResponse(User user, Long tenantId) {
        String token = createToken(user.getUserId(), tenantId, getRoleCodes(user.getUserId(), tenantId));
        return LoginResponse.builder()
                .accessToken(token)
                .expiresIn(tokenExpirationSeconds)
                .userId(String.valueOf(user.getUserId()))
                .tenantId(String.valueOf(tenantId))
                .permissions(getPermissions(user.getUserId(), tenantId))
                .build();
    }

    private String createToken(Long userId, Long tenantId, List<String> roles) {
        Instant now = Instant.now();
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("tenant_id", String.valueOf(tenantId))
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(tokenExpirationSeconds, ChronoUnit.SECONDS)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    private List<String> getRoleCodes(Long userId, Long tenantId) {
        List<Long> roleIds = roleMemberRepository.findRoleIds(tenantId, userId);
        if (roleIds.isEmpty()) return List.of();
        return roleRepository.findByRoleIdIn(roleIds).stream()
                .filter(role -> "ACTIVE".equals(role.getStatus()))
                .map(Role::getCode)
                .toList();
    }

    private Long resolveTenantId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return tenantRepository.findByCode(value)
                    .map(Tenant::getTenantId)
                    .orElseThrow(() -> new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        }
    }

    private User requireActiveUser(Long userId, Long tenantId) {
        User user = userRepository.findByUserIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        return user;
    }

    private void validateAccount(
            UserAccount account,
            String principal,
            HttpServletRequest request) {
        if (!"ACTIVE".equals(account.getStatus())) {
            recordLogin(account.getTenantId(), account.getUserId(), account.getProviderType(),
                    account.getProviderId(), principal, false, "ACCOUNT_LOCKED", request);
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
    }

    private void recordLogin(
            Long tenantId,
            Long userId,
            String providerType,
            String providerId,
            String principal,
            boolean success,
            String failureReason,
            HttpServletRequest request) {
        LoginHistory history = LoginHistory.builder()
                .tenantId(tenantId)
                .userId(userId)
                .providerType(providerType)
                .providerId(providerId)
                .principal(principal)
                .success(success)
                .failureReason(failureReason)
                .ipAddress(clientIp(request))
                .userAgent(request == null ? null : request.getHeader("User-Agent"))
                .build();
        loginHistoryRepository.save(history);
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}

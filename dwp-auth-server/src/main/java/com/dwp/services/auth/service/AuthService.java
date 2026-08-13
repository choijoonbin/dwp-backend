package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.identity.EmailAddressNormalizer;
import com.dwp.services.auth.dto.AuthPolicyResponse;
import com.dwp.services.auth.dto.LoginRequest;
import com.dwp.services.auth.dto.LoginResponse;
import com.dwp.services.auth.dto.MeResponse;
import com.dwp.services.auth.dto.GroupMembershipDTO;
import com.dwp.services.auth.dto.OidcUserInfo;
import com.dwp.services.auth.dto.PermissionDTO;
import com.dwp.services.auth.dto.UpdatePreferredLocaleRequest;
import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.RolePermission;
import com.dwp.services.auth.entity.Tenant;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.entity.UserAccount;
import com.dwp.services.auth.repository.PermissionRepository;
import com.dwp.services.auth.repository.PrincipalResourceGrantRepository;
import com.dwp.services.auth.repository.DirectoryGroupMemberRepository;
import com.dwp.services.auth.repository.DirectoryGroupRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RolePermissionRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.TenantRepository;
import com.dwp.services.auth.repository.UserAccountRepository;
import com.dwp.services.auth.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.IllformedLocaleException;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$ms19wna8hc6sLRzidr3VKOtpJ6Pbq/kT6MIpizN79m93qnPyi5hD.";

    private final UserRepository userRepository;
    private final UserAccountRepository userAccountRepository;
    private final TenantRepository tenantRepository;
    private final RoleRepository roleRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final DirectoryGroupRepository groupRepository;
    private final DirectoryGroupMemberRepository groupMemberRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final ResourceRepository resourceRepository;
    private final PermissionRepository permissionRepository;
    private final PrincipalResourceGrantRepository principalResourceGrantRepository;
    private final AuthSessionService authSessionService;
    private final AuthPolicyService authPolicyService;
    private final IdentityAccountService identityAccountService;
    private final LoginAttemptService loginAttemptService;
    private final AppGovernanceService appGovernanceService;
    private final PasswordEncoder passwordEncoder;

    public AuthService(
            UserRepository userRepository,
            UserAccountRepository userAccountRepository,
            TenantRepository tenantRepository,
            RoleRepository roleRepository,
            RoleMemberRepository roleMemberRepository,
            DirectoryGroupRepository groupRepository,
            DirectoryGroupMemberRepository groupMemberRepository,
            RolePermissionRepository rolePermissionRepository,
            ResourceRepository resourceRepository,
            PermissionRepository permissionRepository,
            PrincipalResourceGrantRepository principalResourceGrantRepository,
            AuthSessionService authSessionService,
            AuthPolicyService authPolicyService,
            IdentityAccountService identityAccountService,
            LoginAttemptService loginAttemptService,
            AppGovernanceService appGovernanceService,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userAccountRepository = userAccountRepository;
        this.tenantRepository = tenantRepository;
        this.roleRepository = roleRepository;
        this.roleMemberRepository = roleMemberRepository;
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.resourceRepository = resourceRepository;
        this.permissionRepository = permissionRepository;
        this.principalResourceGrantRepository = principalResourceGrantRepository;
        this.authSessionService = authSessionService;
        this.authPolicyService = authPolicyService;
        this.identityAccountService = identityAccountService;
        this.loginAttemptService = loginAttemptService;
        this.appGovernanceService = appGovernanceService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AuthenticatedSession login(LoginRequest request, HttpServletRequest servletRequest) {
        Long tenantId = resolveTenantId(request.getTenantId());
        String email;
        try {
            email = EmailAddressNormalizer.requireValid(request.getEmail());
        } catch (IllegalArgumentException exception) {
            passwordEncoder.matches(request.getPassword(), DUMMY_PASSWORD_HASH);
            loginAttemptService.failure(
                    null, tenantId, "LOCAL", "local", EmailAddressNormalizer.normalize(request.getEmail()),
                    "INVALID_EMAIL", servletRequest);
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        AuthPolicyResponse policy = authPolicyService.getPolicy(tenantId);
        if (!Boolean.TRUE.equals(policy.getLocalLoginEnabled())
                || !policy.getAllowedLoginTypes().contains("LOCAL")) {
            passwordEncoder.matches(request.getPassword(), DUMMY_PASSWORD_HASH);
            loginAttemptService.failure(
                    null, tenantId, "LOCAL", "local", email,
                    "LOCAL_LOGIN_DISABLED", servletRequest);
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        UserAccount account = userAccountRepository
                .findLocalForAuthentication(tenantId, email)
                .orElse(null);
        String passwordHash = account == null || account.getPasswordHash() == null
                ? DUMMY_PASSWORD_HASH
                : account.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), passwordHash);
        if (account == null || !passwordMatches) {
            loginAttemptService.failure(
                    account, tenantId, "LOCAL", "local", email,
                    localFailureReason(account), servletRequest);
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        if (!accountActive(account)) {
            loginAttemptService.failure(
                    account, tenantId, "LOCAL", "local", email,
                    temporarilyLocked(account) ? "RATE_LIMITED" : "ACCOUNT_UNAVAILABLE",
                    servletRequest);
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        User user = activeUser(account.getUserId(), tenantId);
        if (user == null) {
            loginAttemptService.failure(
                    account, tenantId, "LOCAL", "local", email,
                    "USER_UNAVAILABLE", servletRequest);
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        loginAttemptService.success(account, email, servletRequest);
        return createAuthenticatedSession(user, tenantId, servletRequest);
    }

    @Transactional
    public AuthenticatedSession loginWithOidc(
            Long tenantId,
            String providerKey,
            OidcUserInfo userInfo,
            HttpServletRequest servletRequest) {
        requireActiveTenant(tenantId);
        AuthPolicyResponse policy = authPolicyService.getPolicy(tenantId);
        if (!Boolean.TRUE.equals(policy.getSsoLoginEnabled())
                || !policy.getAllowedLoginTypes().contains("SSO")) {
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        String issuer = userInfo.issuer();
        String subject = userInfo.subject();
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        UserAccount account = userAccountRepository
                .findByTenantIdAndProviderTypeAndIssuerUriAndPrincipal(
                        tenantId, "OIDC", issuer, subject)
                .orElse(null);
        if (account == null) {
            User linkedUser = verifiedOidcUser(tenantId, userInfo);
            account = identityAccountService.linkOidcAccount(
                    linkedUser, providerKey, issuer, subject);
        }
        if (!accountActive(account)) {
            loginAttemptService.failure(
                    account, tenantId, "OIDC", providerKey, subject,
                    "ACCOUNT_UNAVAILABLE", servletRequest);
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        User user = activeUser(account.getUserId(), tenantId);
        if (user == null) {
            loginAttemptService.failure(
                    account, tenantId, "OIDC", providerKey, subject,
                    "USER_UNAVAILABLE", servletRequest);
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        loginAttemptService.success(account, subject, servletRequest);
        return createAuthenticatedSession(user, tenantId, servletRequest);
    }

    @Transactional(readOnly = true)
    public MeResponse getMe(Long userId, Long tenantId) {
        return getMe(userId, tenantId, null);
    }

    @Transactional(readOnly = true)
    public MeResponse getMe(Long userId, Long tenantId, String permissionPrefix) {
        User user = userRepository.findByUserIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));

        List<PermissionDTO> permissions = permissionPrefix == null || permissionPrefix.isBlank()
                ? List.of()
                : getPermissions(userId, tenantId).stream()
                        .filter(permission -> permission.getResourceKey() != null
                                && permission.getResourceKey().startsWith(permissionPrefix))
                        .toList();
        return toMeResponse(user, tenant, permissions);
    }

    @Transactional
    public MeResponse updatePreferredLocale(
            Long userId,
            Long tenantId,
            UpdatePreferredLocaleRequest request) {
        User user = userRepository.findByUserIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));

        user.setPreferredLocale(canonicalLocale(request.locale()));
        userRepository.save(user);
        return toMeResponse(user, tenant, List.of());
    }

    private MeResponse toMeResponse(
            User user, Tenant tenant, List<PermissionDTO> permissions) {
        return MeResponse.builder()
                .userId(user.getUserId())
                .personPublicId(user.getPersonPublicId())
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .jobTitle(user.getJobTitle())
                .preferredLocale(user.getPreferredLocale())
                .tenantDefaultLocale(tenant.getDefaultLocale())
                .tenantId(tenant.getTenantId())
                .tenantCode(tenant.getCode())
                .tenantName(tenant.getName())
                .roles(getRoleCodes(user.getUserId(), tenant.getTenantId()))
                .groups(getGroupMemberships(user.getUserId(), tenant.getTenantId()))
                .permissions(permissions)
                .resourceRoles(appGovernanceService.resourceRoles(
                        tenant.getTenantId(), user.getUserId()))
                .build();
    }

    private List<GroupMembershipDTO> getGroupMemberships(Long userId, Long tenantId) {
        List<Long> groupIds = groupMemberRepository.findByTenantIdAndUserId(tenantId, userId)
                .stream()
                .map(member -> member.getGroupId())
                .distinct()
                .toList();
        if (groupIds.isEmpty()) return List.of();
        return groupRepository.findByTenantIdAndGroupIdInAndStatus(tenantId, groupIds, "ACTIVE")
                .stream()
                .filter(group -> group.getPublicId() != null)
                .map(group -> new GroupMembershipDTO(group.getPublicId(), group.getDisplayName()))
                .sorted(java.util.Comparator.comparing(
                        GroupMembershipDTO::displayName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private String canonicalLocale(String locale) {
        try {
            Locale parsed = new Locale.Builder().setLanguageTag(locale.trim()).build();
            String canonical = parsed.toLanguageTag();
            if (canonical.isBlank() || "und".equalsIgnoreCase(canonical)) {
                throw new IllformedLocaleException("Locale must identify a language");
            }
            return canonical;
        } catch (IllformedLocaleException exception) {
            throw new BaseException(ErrorCode.INVALID_FORMAT);
        }
    }

    @Transactional(readOnly = true)
    public List<PermissionDTO> getPermissions(Long userId, Long tenantId) {
        List<Long> roleIds = roleMemberRepository.findRoleIds(tenantId, userId);
        List<RolePermission> assignments = roleIds.isEmpty()
                ? List.of()
                : rolePermissionRepository.findByTenantIdAndRoleIdInAndEffect(
                        tenantId, roleIds, "ALLOW");

        Map<Long, Resource> resources = resourceRepository
                .findAllById(assignments.stream().map(RolePermission::getResourceId).toList())
                .stream()
                .filter(resource -> Boolean.TRUE.equals(resource.getEnabled()))
                .collect(Collectors.toMap(Resource::getResourceId, Function.identity()));
        Map<Long, Permission> permissions = permissionRepository
                .findAllById(assignments.stream().map(RolePermission::getPermissionId).toList())
                .stream()
                .collect(Collectors.toMap(Permission::getPermissionId, Function.identity()));

        LinkedHashMap<String, PermissionDTO> effective = new LinkedHashMap<>();
        assignments.stream()
                .map(assignment -> toPermission(
                        assignment,
                        resources.get(assignment.getResourceId()),
                        permissions.get(assignment.getPermissionId())))
                .filter(java.util.Objects::nonNull)
                .forEach(permission -> effective.putIfAbsent(permissionKey(permission), permission));
        principalResourceGrantRepository.findEffective(tenantId, userId).stream()
                .map(this::toPermission)
                .forEach(permission -> effective.putIfAbsent(permissionKey(permission), permission));
        return List.copyOf(effective.values());
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

    private PermissionDTO toPermission(
            PrincipalResourceGrantRepository.EffectiveGrant grant) {
        return PermissionDTO.builder()
                .resourceType(grant.resourceType())
                .resourceKey(grant.resourceKey())
                .resourceName(grant.resourceName())
                .permissionCode(grant.permissionCode())
                .permissionName(grant.permissionName())
                .effect(grant.effect())
                .build();
    }

    private String permissionKey(PermissionDTO permission) {
        return permission.getResourceKey() + ":" + permission.getPermissionCode()
                + ":" + permission.getEffect();
    }

    @Transactional
    public void revokeSession(String tokenId) {
        authSessionService.revokeCurrent(tokenId);
    }

    private AuthenticatedSession createAuthenticatedSession(
            User user,
            Long tenantId,
            HttpServletRequest servletRequest) {
        List<String> roles = getRoleCodes(user.getUserId(), tenantId);
        AuthSessionService.IssuedSession session = authSessionService.create(
                user.getUserId(), tenantId, roles, servletRequest);

        LoginResponse response = LoginResponse.builder()
                .expiresIn(session.expiresIn())
                .userId(String.valueOf(user.getUserId()))
                .tenantId(String.valueOf(tenantId))
                .permissions(getPermissions(user.getUserId(), tenantId))
                .build();
        return new AuthenticatedSession(session.accessToken(), response);
    }

    @Transactional(readOnly = true)
    public List<String> getRoleCodes(Long userId, Long tenantId) {
        List<Long> roleIds = roleMemberRepository.findRoleIds(tenantId, userId);
        if (roleIds.isEmpty()) return List.of();
        return roleRepository.findByRoleIdIn(roleIds).stream()
                .filter(role -> "ACTIVE".equals(role.getStatus()))
                .map(Role::getCode)
                .toList();
    }

    private Long resolveTenantId(String value) {
        Long tenantId;
        try {
            tenantId = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            tenantId = tenantRepository.findByCode(value)
                    .map(Tenant::getTenantId)
                    .orElseThrow(() -> new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        }
        requireActiveTenant(tenantId);
        return tenantId;
    }

    private Tenant requireActiveTenant(Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        if (!"ACTIVE".equals(tenant.getStatus())) {
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        return tenant;
    }

    private User activeUser(Long userId, Long tenantId) {
        return userRepository.findByUserIdAndTenantId(userId, tenantId)
                .filter(user -> "ACTIVE".equals(user.getStatus()))
                .orElse(null);
    }

    private User verifiedOidcUser(Long tenantId, OidcUserInfo userInfo) {
        if (!userInfo.emailVerified()) {
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        String email;
        try {
            email = EmailAddressNormalizer.requireValid(userInfo.email());
        } catch (IllegalArgumentException exception) {
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        return userRepository.findByTenantIdAndEmailNormalized(tenantId, email)
                .filter(user -> "ACTIVE".equals(user.getStatus()))
                .orElseThrow(() -> new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS));
    }

    private boolean accountActive(UserAccount account) {
        return "ACTIVE".equals(account.getStatus()) && !temporarilyLocked(account);
    }

    private boolean temporarilyLocked(UserAccount account) {
        return account.getLockedUntil() != null && account.getLockedUntil().isAfter(Instant.now());
    }

    private String localFailureReason(UserAccount account) {
        if (account == null) return "USER_NOT_FOUND";
        if (temporarilyLocked(account)) return "RATE_LIMITED";
        if (!"ACTIVE".equals(account.getStatus()) || account.getPasswordHash() == null) {
            return "ACCOUNT_UNAVAILABLE";
        }
        return "INVALID_PASSWORD";
    }
}

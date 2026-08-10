package com.dwp.services.auth.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthTenantProvisioningService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public AuthTenantProvisioningService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AuthTenantProvisioningDtos.ProvisionTenantResponse provision(
            AuthTenantProvisioningDtos.ProvisionTenantRequest request) {
        TenantRecord existing = tenantByProviderId(request.providerTenantId());
        if (existing != null) {
            if (!existing.tenantKey().equals(request.tenantKey())) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The provider tenant is already mapped to a different tenant key.");
            }
            return ensureTenantFoundation(existing, request);
        }
        if (tenantByKey(request.tenantKey()) != null) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The tenant key already exists.");
        }
        try {
            Long tenantId = jdbc.queryForObject("""
                    INSERT INTO com_tenants (
                        public_id, code, name, status, default_locale, time_zone,
                        data_region, isolation_model)
                    VALUES (?, ?, ?, 'PROVISIONING', ?, ?, ?, ?)
                    RETURNING tenant_id
                    """, Long.class,
                    request.providerTenantId(), request.tenantKey(), request.displayName(),
                    request.defaultLocale(), request.timeZone(), request.dataRegion(), request.isolationModel());
            if (tenantId == null) throw new IllegalStateException("Auth tenant insert returned no identifier.");
            return ensureTenantFoundation(
                    new TenantRecord(tenantId, request.providerTenantId(), request.tenantKey(), "PROVISIONING"),
                    request);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The auth tenant already exists.", exception);
        }
    }

    @Transactional
    public AuthTenantProvisioningDtos.ProvisionTenantResponse updateLifecycle(
            UUID providerTenantId,
            AuthTenantProvisioningDtos.UpdateLifecycleRequest request) {
        TenantRecord tenant = requireTenant(providerTenantId);
        jdbc.update("""
                UPDATE com_tenants
                   SET status = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE tenant_id = ?
                """, request.lifecycleState(), tenant.tenantId());
        if ("SUSPENDED".equals(request.lifecycleState()) || "RETIRED".equals(request.lifecycleState())) {
            jdbc.update("""
                    UPDATE sys_auth_sessions
                       SET lifecycle_state = 'REVOKED', revoked_at = CURRENT_TIMESTAMP,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
                    """, tenant.tenantId());
        }
        AdministratorRecord administrator = primaryAdministrator(tenant.tenantId());
        return new AuthTenantProvisioningDtos.ProvisionTenantResponse(
                tenant.providerTenantId(), tenant.tenantId(),
                administrator == null ? null : administrator.userId(),
                administrator == null ? null : administrator.principal(),
                request.lifecycleState(), 1);
    }

    @Transactional
    public AuthTenantProvisioningDtos.ProvisionTenantResponse replaceEntitlements(
            UUID providerTenantId,
            AuthTenantProvisioningDtos.ReplaceEntitlementsRequest request) {
        TenantRecord tenant = requireTenant(providerTenantId);
        Long roleId = jdbc.queryForObject("""
                SELECT role_id FROM com_roles WHERE tenant_id = ? AND code = 'TENANT_ADMIN'
                """, Long.class, tenant.tenantId());
        syncResources(tenant.tenantId(), roleId, request.entitlementKeys());
        AdministratorRecord administrator = primaryAdministrator(tenant.tenantId());
        return new AuthTenantProvisioningDtos.ProvisionTenantResponse(
                tenant.providerTenantId(), tenant.tenantId(),
                administrator == null ? null : administrator.userId(),
                administrator == null ? null : administrator.principal(),
                tenant.lifecycleState(), 1);
    }

    @Transactional
    public AuthTenantProvisioningDtos.InvitationResponse issueInvitation(
            UUID providerTenantId,
            AuthTenantProvisioningDtos.IssueInvitationRequest request) {
        TenantRecord tenant = requireTenant(providerTenantId);
        AdministratorRecord administrator = administrator(tenant.tenantId(), request.administratorUserId());
        if (administrator == null) throw new BaseException(ErrorCode.NOT_FOUND);
        jdbc.update("""
                UPDATE sys_account_activation_tokens
                   SET lifecycle_state = 'REVOKED', revoked_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND user_account_id = ? AND lifecycle_state = 'ACTIVE'
                """, tenant.tenantId(), administrator.accountId());
        String token = randomToken();
        String tokenHash = sha256(token);
        Instant expiresAt = Instant.now().plus(request.expiresInMinutes(), ChronoUnit.MINUTES);
        jdbc.update("""
                INSERT INTO sys_account_activation_tokens (
                    tenant_id, user_id, user_account_id, token_hash, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, tenant.tenantId(), administrator.userId(), administrator.accountId(),
                tokenHash, Timestamp.from(expiresAt));
        jdbc.update("""
                UPDATE com_users SET status = 'INVITED', updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND user_id = ? AND status <> 'ACTIVE'
                """, tenant.tenantId(), administrator.userId());
        jdbc.update("""
                UPDATE com_user_accounts SET status = 'INVITED', updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND user_account_id = ? AND status <> 'ACTIVE'
                """, tenant.tenantId(), administrator.accountId());
        return new AuthTenantProvisioningDtos.InvitationResponse(
                tenant.tenantId(), administrator.userId(), administrator.principal(), token, expiresAt);
    }

    @Transactional
    public AuthTenantProvisioningDtos.ActivationSummary activation(String token) {
        ActivationRecord record = requireActivation(token);
        return new AuthTenantProvisioningDtos.ActivationSummary(
                record.tenantId(), record.tenantKey(), record.tenantName(),
                record.userId(), record.displayName(), record.email(), record.principal(), record.expiresAt());
    }

    @Transactional
    public AuthTenantProvisioningDtos.ActivateAccountResponse activate(
            String token,
            AuthTenantProvisioningDtos.ActivateAccountRequest request) {
        validatePassword(request.password());
        ActivationRecord record = requireActivation(token);
        jdbc.update("""
                UPDATE com_user_accounts
                   SET password_hash = ?, status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND user_account_id = ?
                """, passwordEncoder.encode(request.password()), record.tenantId(), record.accountId());
        jdbc.update("""
                UPDATE com_users SET status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND user_id = ?
                """, record.tenantId(), record.userId());
        jdbc.update("""
                UPDATE sys_account_activation_tokens
                   SET lifecycle_state = 'USED', used_at = CURRENT_TIMESTAMP
                 WHERE activation_token_id = ? AND lifecycle_state = 'ACTIVE'
                """, record.activationTokenId());
        return new AuthTenantProvisioningDtos.ActivateAccountResponse(
                record.tenantId(), record.tenantKey(), record.principal(), "ACTIVE");
    }

    private AuthTenantProvisioningDtos.ProvisionTenantResponse ensureTenantFoundation(
            TenantRecord tenant,
            AuthTenantProvisioningDtos.ProvisionTenantRequest request) {
        jdbc.update("""
                INSERT INTO sys_auth_policies (
                    tenant_id, default_login_type, allowed_login_types,
                    local_login_enabled, sso_login_enabled, require_mfa, token_ttl_sec)
                VALUES (?, 'LOCAL', 'LOCAL', TRUE, FALSE, FALSE, 28800)
                ON CONFLICT (tenant_id) DO NOTHING
                """, tenant.tenantId());
        jdbc.update("""
                INSERT INTO com_roles (
                    tenant_id, code, name, description, role_type,
                    privileged, assignable_to_groups)
                VALUES (?, 'TENANT_ADMIN', 'Tenant administrator',
                        'Administrator for a single tenant', 'SYSTEM', TRUE, FALSE)
                ON CONFLICT (tenant_id, code) DO UPDATE
                SET status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
                """, tenant.tenantId());
        Long roleId = jdbc.queryForObject("""
                SELECT role_id FROM com_roles WHERE tenant_id = ? AND code = 'TENANT_ADMIN'
                """, Long.class, tenant.tenantId());
        Long userId = ensureAdministrator(tenant.tenantId(), request);
        jdbc.update("""
                INSERT INTO com_role_members (tenant_id, role_id, user_id)
                VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING
                """, tenant.tenantId(), roleId, userId);
        syncResources(tenant.tenantId(), roleId, request.entitlementKeys());
        AdministratorRecord administrator = administrator(tenant.tenantId(), userId);
        return new AuthTenantProvisioningDtos.ProvisionTenantResponse(
                tenant.providerTenantId(), tenant.tenantId(), userId,
                administrator == null ? request.administratorPrincipal() : administrator.principal(),
                tenant.lifecycleState(), 1);
    }

    private void syncResources(Long tenantId, Long roleId, List<String> entitlementKeys) {
        Map<String, String> resources = entitledResources(entitlementKeys);
        jdbc.update("""
                UPDATE com_resources
                   SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND type = 'APP'
                """, tenantId);
        resources.forEach((key, name) -> jdbc.update("""
                INSERT INTO com_resources (tenant_id, type, key, name, enabled)
                VALUES (?, 'APP', ?, ?, TRUE)
                ON CONFLICT (tenant_id, type, key) DO UPDATE
                SET name = EXCLUDED.name, enabled = TRUE, updated_at = CURRENT_TIMESTAMP
                """, tenantId, key, name));
        jdbc.update("""
                INSERT INTO com_resources (tenant_id, type, key, name, enabled)
                VALUES (?, 'ADMIN', 'ADMIN.API_MONITORING', 'API monitoring', TRUE)
                ON CONFLICT (tenant_id, type, key) DO UPDATE
                SET name = EXCLUDED.name, enabled = TRUE, updated_at = CURRENT_TIMESTAMP
                """, tenantId);
        jdbc.update("""
                INSERT INTO com_role_permissions (
                    tenant_id, role_id, resource_id, permission_id, effect)
                SELECT ?, ?, resource.resource_id, permission.permission_id, 'ALLOW'
                  FROM com_resources resource
                  CROSS JOIN com_permissions permission
                 WHERE resource.tenant_id = ?
                   AND resource.enabled = TRUE
                   AND permission.code IN ('VIEW', 'CREATE', 'UPDATE', 'DELETE', 'MANAGE')
                ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE
                SET effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP
                """, tenantId, roleId, tenantId);
    }

    private Long ensureAdministrator(
            Long tenantId,
            AuthTenantProvisioningDtos.ProvisionTenantRequest request) {
        List<Long> existing = jdbc.query("""
                SELECT user_id FROM com_user_accounts
                 WHERE tenant_id = ? AND provider_type = 'LOCAL'
                   AND provider_id = 'local' AND principal = ?
                """, (result, ignored) -> result.getLong(1), tenantId, request.administratorPrincipal());
        if (!existing.isEmpty()) return existing.get(0);
        Long userId = jdbc.queryForObject("""
                INSERT INTO com_users (
                    tenant_id, display_name, email, status, job_title, preferred_locale)
                VALUES (?, ?, ?, 'INVITED', 'Tenant administrator', ?)
                RETURNING user_id
                """, Long.class, tenantId, request.administratorDisplayName(),
                request.administratorEmail(), request.defaultLocale());
        if (userId == null) throw new IllegalStateException("Administrator insert returned no identifier.");
        jdbc.update("""
                INSERT INTO com_user_accounts (
                    tenant_id, user_id, provider_type, provider_id, principal,
                    password_hash, status)
                VALUES (?, ?, 'LOCAL', 'local', ?, NULL, 'INVITED')
                """, tenantId, userId, request.administratorPrincipal());
        return userId;
    }

    private Map<String, String> entitledResources(List<String> entitlementKeys) {
        Map<String, String> resources = new LinkedHashMap<>();
        resources.put("APP.ADMINISTRATION", "Administration");
        if (entitlementKeys.contains("core.workspace")) {
            resources.put("APP.WORK", "Work");
            resources.put("APP.ACTIVITY", "Activity");
            resources.put("APP.APPS", "Apps");
        }
        if (entitlementKeys.contains("ai.agent-runtime")) resources.put("APP.ASK", "Ask DWP");
        if (entitlementKeys.contains("core.people")) {
            resources.put("APP.PEOPLE_DIRECTORY", "People directory");
            resources.put("APP.EMPLOYEE_SERVICES", "Employee services");
        }
        return resources;
    }

    private TenantRecord requireTenant(UUID providerTenantId) {
        TenantRecord tenant = tenantByProviderId(providerTenantId);
        if (tenant == null) throw new BaseException(ErrorCode.NOT_FOUND);
        return tenant;
    }

    private TenantRecord tenantByProviderId(UUID providerTenantId) {
        return jdbc.query("""
                SELECT tenant_id, public_id, code, status FROM com_tenants WHERE public_id = ?
                """, this::tenant, providerTenantId).stream().findFirst().orElse(null);
    }

    private TenantRecord tenantByKey(String tenantKey) {
        return jdbc.query("""
                SELECT tenant_id, public_id, code, status FROM com_tenants WHERE code = ?
                """, this::tenant, tenantKey).stream().findFirst().orElse(null);
    }

    private AdministratorRecord primaryAdministrator(Long tenantId) {
        return jdbc.query("""
                SELECT account.user_account_id, account.user_id, account.principal
                  FROM com_user_accounts account
                  JOIN com_role_members member
                    ON member.tenant_id = account.tenant_id AND member.user_id = account.user_id
                  JOIN com_roles role
                    ON role.tenant_id = member.tenant_id AND role.role_id = member.role_id
                 WHERE account.tenant_id = ? AND role.code = 'TENANT_ADMIN'
                 ORDER BY account.user_account_id
                 LIMIT 1
                """, this::administrator, tenantId).stream().findFirst().orElse(null);
    }

    private AdministratorRecord administrator(Long tenantId, Long userId) {
        return jdbc.query("""
                SELECT user_account_id, user_id, principal
                  FROM com_user_accounts
                 WHERE tenant_id = ? AND user_id = ?
                   AND provider_type = 'LOCAL' AND provider_id = 'local'
                """, this::administrator, tenantId, userId).stream().findFirst().orElse(null);
    }

    private ActivationRecord requireActivation(String token) {
        String hash = sha256(token == null ? "" : token.trim());
        jdbc.update("""
                UPDATE sys_account_activation_tokens
                   SET lifecycle_state = 'EXPIRED'
                 WHERE lifecycle_state = 'ACTIVE' AND expires_at <= CURRENT_TIMESTAMP
                """);
        return jdbc.query("""
                SELECT activation.activation_token_id,
                       activation.tenant_id,
                       tenant.code AS tenant_key,
                       tenant.name AS tenant_name,
                       activation.user_id,
                       activation.user_account_id,
                       user_record.display_name,
                       user_record.email,
                       account.principal,
                       activation.expires_at
                  FROM sys_account_activation_tokens activation
                  JOIN com_tenants tenant ON tenant.tenant_id = activation.tenant_id
                  JOIN com_users user_record ON user_record.user_id = activation.user_id
                  JOIN com_user_accounts account
                    ON account.user_account_id = activation.user_account_id
                 WHERE activation.token_hash = ?
                   AND activation.lifecycle_state = 'ACTIVE'
                   AND activation.expires_at > CURRENT_TIMESTAMP
                   AND tenant.status = 'ACTIVE'
                """, (RowMapper<ActivationRecord>) this::activation, hash).stream().findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.TOKEN_INVALID));
    }

    private void validatePassword(String password) {
        boolean valid = password.chars().anyMatch(Character::isUpperCase)
                && password.chars().anyMatch(Character::isLowerCase)
                && password.chars().anyMatch(Character::isDigit)
                && password.chars().anyMatch(value -> !Character.isLetterOrDigit(value));
        if (!valid) {
            throw new BaseException(
                    ErrorCode.VALIDATION_ERROR,
                    "The password must contain upper-case, lower-case, numeric and special characters.");
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private TenantRecord tenant(ResultSet result, int ignored) throws SQLException {
        return new TenantRecord(
                result.getLong("tenant_id"),
                result.getObject("public_id", UUID.class),
                result.getString("code"),
                result.getString("status"));
    }

    private AdministratorRecord administrator(ResultSet result, int ignored) throws SQLException {
        return new AdministratorRecord(
                result.getLong("user_account_id"),
                result.getLong("user_id"),
                result.getString("principal"));
    }

    private ActivationRecord activation(ResultSet result, int ignored) throws SQLException {
        return new ActivationRecord(
                result.getObject("activation_token_id", UUID.class),
                result.getLong("tenant_id"),
                result.getString("tenant_key"),
                result.getString("tenant_name"),
                result.getLong("user_id"),
                result.getLong("user_account_id"),
                result.getString("display_name"),
                result.getString("email"),
                result.getString("principal"),
                result.getTimestamp("expires_at").toInstant());
    }

    private record TenantRecord(
            Long tenantId,
            UUID providerTenantId,
            String tenantKey,
            String lifecycleState) {
    }

    private record AdministratorRecord(Long accountId, Long userId, String principal) {
    }

    private record ActivationRecord(
            UUID activationTokenId,
            Long tenantId,
            String tenantKey,
            String tenantName,
            Long userId,
            Long accountId,
            String displayName,
            String email,
            String principal,
            Instant expiresAt) {
    }
}

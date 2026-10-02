package com.dwp.services.auth.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.identity.EmailAddressNormalizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Activates only the invited administrator created by the normal tenant
 * provisioning service. This is a disposable-local test feed, not an
 * invitation or customer credential-delivery implementation.
 */
@Service
@ConditionalOnProperty(
        name = "dwp.synthetic-identity-bootstrap.enabled",
        havingValue = "true")
public class LocalSyntheticIdentityBootstrapService {

    private static final String SYNTHETIC_EMAIL_SUFFIX = "@dwp.test";
    private static final UUID URL_NAMESPACE =
            UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    private static final List<String> TENANT_A_ROLES =
            List.of("HR_ADMIN", "PAYROLL_ADMIN");
    private static final Set<String> FOUNDATION_ROLES =
            Set.of("TENANT_ADMIN", "WORKSPACE_MEMBER");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String expectedRunId;

    public LocalSyntheticIdentityBootstrapService(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            @org.springframework.beans.factory.annotation.Value(
                    "${dwp.synthetic-identity-bootstrap.run-id:}")
                    String expectedRunId) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.expectedRunId = expectedRunId == null ? "" : expectedRunId;
        if (!this.expectedRunId.matches("w1-[0-9]{8}t[0-9]{6}z-[0-9a-f]{8}")) {
            throw new IllegalStateException(
                    "Local synthetic identity bootstrap requires an exact W1 run binding.");
        }
    }

    @Transactional
    public LocalSyntheticIdentityBootstrapDtos.ActivateResponse activate(
            LocalSyntheticIdentityBootstrapDtos.ActivateRequest request) {
        if (!expectedRunId.equals(request.runId())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Synthetic identity request is not bound to this runtime.");
        }
        String email = EmailAddressNormalizer.requireValid(request.administratorEmail());
        if (!email.endsWith(SYNTHETIC_EMAIL_SUFFIX)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Synthetic identity email must use the @dwp.test suffix.");
        }
        String lane = requireRunBoundIdentity(request, email);
        List<String> roleCodes = requireExactRoleCodes(lane, request.roleCodes());
        validatePassword(request.password());

        List<AccountRecord> matches = jdbc.query("""
                SELECT tenant.tenant_id, tenant.public_id,
                       tenant.status AS tenant_status,
                       user_record.user_id, user_record.status AS user_status,
                       account.user_account_id, account.status AS account_status,
                       user_record.email_normalized, user_record.person_public_id
                  FROM com_tenants tenant
                  JOIN com_users user_record
                    ON user_record.tenant_id = tenant.tenant_id
                  JOIN com_user_accounts account
                    ON account.tenant_id = user_record.tenant_id
                   AND account.user_id = user_record.user_id
                 WHERE tenant.public_id = ?
                   AND user_record.user_id = ?
                   AND user_record.email_normalized = ?
                   AND account.provider_type = 'LOCAL'
                   AND account.provider_id = 'local'
                   AND account.principal = ?
                """, (result, ignored) -> new AccountRecord(
                        result.getLong("tenant_id"),
                        result.getObject("public_id", UUID.class),
                        result.getString("tenant_status"),
                        result.getLong("user_id"),
                        result.getString("user_status"),
                        result.getLong("user_account_id"),
                        result.getString("account_status"),
                        result.getString("email_normalized"),
                        result.getObject("person_public_id", UUID.class)),
                request.providerTenantId(), request.administratorUserId(), email, email);
        if (matches.size() != 1) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        AccountRecord account = matches.getFirst();
        if (!"ACTIVE".equals(account.tenantStatus())
                || !"INVITED".equals(account.userStatus())
                || !"INVITED".equals(account.accountStatus())
                || account.personPublicId() != null) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Synthetic activation requires one ACTIVE tenant and one INVITED local account.");
        }

        requireRoleAssignmentPolicy(account.tenantId(), account.userId(), roleCodes);
        for (String roleCode : roleCodes) {
            int memberships = jdbc.update("""
                    INSERT INTO com_role_members (
                        tenant_id, role_id, user_id, created_by, updated_by)
                    SELECT role.tenant_id, role.role_id, ?, ?, ?
                      FROM com_roles role
                      JOIN sys_builtin_role_catalog catalog
                        ON catalog.role_code = role.code
                       AND catalog.lifecycle_state = 'ACTIVE'
                     WHERE role.tenant_id = ?
                       AND role.code = ?
                       AND role.status = 'ACTIVE'
                       AND role.role_type = 'SYSTEM'
                       AND role.builtin_role_code = role.code
                    ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING
                    """, account.userId(), account.userId(), account.userId(),
                    account.tenantId(), roleCode);
            if (memberships != 1) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "Synthetic delegated role membership lost its one-shot fence.");
            }
        }

        int accountUpdates = jdbc.update("""
                UPDATE com_user_accounts
                   SET password_hash = ?, status = 'ACTIVE',
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND user_account_id = ?
                   AND user_id = ? AND principal = ?
                   AND provider_type = 'LOCAL' AND provider_id = 'local'
                   AND status = 'INVITED' AND password_hash IS NULL
                """, passwordEncoder.encode(request.password()), account.tenantId(),
                account.accountId(), account.userId(), email);
        int userUpdates = jdbc.update("""
                UPDATE com_users
                   SET status = 'ACTIVE', person_public_id = ?,
                       access_revision = access_revision + 1,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND user_id = ?
                   AND email_normalized = ? AND status = 'INVITED'
                   AND person_public_id IS NULL
                """, request.personPublicId(), account.tenantId(), account.userId(), email);
        if (accountUpdates != 1 || userUpdates != 1) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Synthetic account activation lost its invited-state fence.");
        }

        String receipt = sha256(String.join("|",
                expectedRunId,
                account.providerTenantId().toString(),
                Long.toString(account.tenantId()),
                Long.toString(account.userId()),
                request.personPublicId().toString(),
                email,
                String.join(",", roleCodes),
                "ACTIVE"));
        return new LocalSyntheticIdentityBootstrapDtos.ActivateResponse(
                expectedRunId,
                account.providerTenantId(),
                account.tenantId(),
                account.userId(),
                request.personPublicId(),
                email,
                "ACTIVE",
                roleCodes,
                receipt);
    }

    private void requireRoleAssignmentPolicy(
            Long tenantId,
            Long userId,
            List<String> requestedRoleCodes) {
        List<String> currentRoleCodes = jdbc.queryForList("""
                SELECT role.code
                  FROM com_role_members membership
                  JOIN com_roles role
                    ON role.tenant_id = membership.tenant_id
                   AND role.role_id = membership.role_id
                 WHERE membership.tenant_id = ?
                   AND membership.user_id = ?
                   AND role.status = 'ACTIVE'
                 ORDER BY role.code
                """, String.class, tenantId, userId);
        if (!new LinkedHashSet<>(currentRoleCodes).equals(FOUNDATION_ROLES)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Synthetic activation requires only the provisioned foundation roles.");
        }
        if (requestedRoleCodes.isEmpty()) {
            return;
        }

        List<String> assignable = jdbc.queryForList("""
                SELECT DISTINCT policy.target_role_code
                  FROM sys_role_assignment_policies policy
                 WHERE policy.assignment_mode IN ('DIRECT', 'APPROVAL')
                   AND policy.lifecycle_state = 'ACTIVE'
                   AND policy.target_role_code IN ('HR_ADMIN', 'PAYROLL_ADMIN')
                   AND policy.grantor_role_code IN (
                       SELECT role.code
                         FROM com_role_members membership
                         JOIN com_roles role
                           ON role.tenant_id = membership.tenant_id
                          AND role.role_id = membership.role_id
                        WHERE membership.tenant_id = ?
                          AND membership.user_id = ?
                          AND role.status = 'ACTIVE')
                 ORDER BY policy.target_role_code
                """, String.class, tenantId, userId);
        if (!assignable.equals(requestedRoleCodes)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Synthetic delegated roles are not allowed by the active assignment policy.");
        }

        List<String> conflicts = jdbc.queryForList("""
                WITH prospective(role_code) AS (
                    SELECT role.code
                      FROM com_role_members membership
                      JOIN com_roles role
                        ON role.tenant_id = membership.tenant_id
                       AND role.role_id = membership.role_id
                     WHERE membership.tenant_id = ?
                       AND membership.user_id = ?
                       AND role.status = 'ACTIVE'
                    UNION ALL VALUES ('HR_ADMIN'), ('PAYROLL_ADMIN')
                )
                SELECT policy.reason_code
                  FROM sys_role_conflict_policies policy
                 WHERE policy.lifecycle_state = 'ACTIVE'
                   AND policy.left_role_code IN (SELECT role_code FROM prospective)
                   AND policy.right_role_code IN (SELECT role_code FROM prospective)
                 ORDER BY policy.left_role_code, policy.right_role_code
                """, String.class, tenantId, userId);
        if (!conflicts.isEmpty()) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Synthetic delegated roles violate an active separation-of-duties policy.");
        }
    }

    private static void validatePassword(String password) {
        boolean valid = password.chars().anyMatch(Character::isUpperCase)
                && password.chars().anyMatch(Character::isLowerCase)
                && password.chars().anyMatch(Character::isDigit)
                && password.chars().anyMatch(value -> !Character.isLetterOrDigit(value));
        if (!valid) {
            throw new BaseException(
                    ErrorCode.VALIDATION_ERROR,
                    "The synthetic password must contain upper-case, lower-case, numeric and special characters.");
        }
    }

    private String requireRunBoundIdentity(
            LocalSyntheticIdentityBootstrapDtos.ActivateRequest request,
            String email) {
        String suffix = expectedRunId.substring(expectedRunId.lastIndexOf('-') + 1);
        String lane;
        if (email.equals("hris-w1-a-" + suffix + SYNTHETIC_EMAIL_SUFFIX)) {
            lane = "a";
        } else if (email.equals("hris-w1-b-" + suffix + SYNTHETIC_EMAIL_SUFFIX)) {
            lane = "b";
        } else {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Synthetic identity email is not bound to this runtime.");
        }
        UUID expectedTenant = uuidV5("dwp:" + expectedRunId + ":tenant-" + lane);
        if (!expectedTenant.equals(request.providerTenantId())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Synthetic identity identifiers are not bound to this runtime.");
        }
        return lane;
    }

    private static List<String> requireExactRoleCodes(
            String lane,
            List<String> requestedRoleCodes) {
        List<String> canonical = requestedRoleCodes == null
                ? List.of()
                : requestedRoleCodes.stream().sorted().toList();
        if (new LinkedHashSet<>(canonical).size() != canonical.size()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Synthetic delegated role codes must be unique.");
        }
        List<String> expected = "a".equals(lane) ? TENANT_A_ROLES : List.of();
        if (!canonical.equals(expected)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Synthetic delegated roles do not match the exact tenant lane policy.");
        }
        return canonical;
    }

    private static UUID uuidV5(String name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            ByteBuffer namespace = ByteBuffer.allocate(16)
                    .putLong(URL_NAMESPACE.getMostSignificantBits())
                    .putLong(URL_NAMESPACE.getLeastSignificantBits());
            digest.update(namespace.array());
            byte[] bytes = digest.digest(name.getBytes(StandardCharsets.UTF_8));
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            ByteBuffer value = ByteBuffer.wrap(bytes, 0, 16);
            return new UUID(value.getLong(), value.getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record AccountRecord(
            Long tenantId,
            UUID providerTenantId,
            String tenantStatus,
            Long userId,
            String userStatus,
            Long accountId,
            String accountStatus,
            String email,
            UUID personPublicId) {
    }
}

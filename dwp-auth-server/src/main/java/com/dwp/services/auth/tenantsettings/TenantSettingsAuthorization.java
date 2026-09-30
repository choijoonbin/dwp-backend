package com.dwp.services.auth.tenantsettings;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.security.TenantPermissionAuthorization;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exact persisted permission boundary for tenant settings reads and commands. */
final class TenantSettingsAuthorization {

    static final String POLICY_RESOURCE = TenantPermissionAuthorization.IDENTITY_PROVISIONING;
    static final String DIRECTORY_RESOURCE = TenantPermissionAuthorization.IDENTITY_DIRECTORY;

    private final TenantPermissionAuthorization delegate;
    private final boolean testAllowAll;

    TenantSettingsAuthorization(JdbcTemplate jdbc) {
        this.delegate = new TenantPermissionAuthorization(jdbc);
        this.testAllowAll = false;
    }

    private TenantSettingsAuthorization() {
        this.delegate = null;
        this.testAllowAll = true;
    }

    static TenantSettingsAuthorization testAllowAll() {
        return new TenantSettingsAuthorization();
    }

    void require(Long tenantId, Long actorId, String resourceKey, String permissionCode) {
        if (!can(tenantId, actorId, resourceKey, permissionCode)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    boolean can(Long tenantId, Long actorId, String resourceKey, String permissionCode) {
        if (testAllowAll) return true;
        return delegate.can(tenantId, actorId, resourceKey, permissionCode);
    }
}

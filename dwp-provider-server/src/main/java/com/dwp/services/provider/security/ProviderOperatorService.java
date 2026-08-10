package com.dwp.services.provider.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProviderOperatorService {

    private final JdbcTemplate jdbc;

    public ProviderOperatorService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isActive(Long authTenantId, Long authUserId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_operators
                 WHERE auth_tenant_id = ?
                   AND auth_user_id = ?
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class, authTenantId, authUserId);
        return count != null && count > 0;
    }
}

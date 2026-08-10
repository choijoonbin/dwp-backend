package com.dwp.services.provider.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class ProviderOperatorService {

    private final JdbcTemplate jdbc;

    public ProviderOperatorService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProviderRequestContext.Actor> activeOperator(Long authTenantId, Long authUserId) {
        List<OperatorRow> rows = jdbc.query("""
                SELECT operator.provider_operator_id,
                       operator.display_name,
                       assignment.role_code,
                       permission.permission_code
                  FROM prv_operators operator
                  JOIN prv_operator_role_assignments assignment
                    ON assignment.provider_operator_id = operator.provider_operator_id
                   AND assignment.lifecycle_state = 'ACTIVE'
                   AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
                   AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
                  JOIN prv_operator_roles role
                    ON role.role_code = assignment.role_code
                   AND role.lifecycle_state = 'ACTIVE'
                  LEFT JOIN prv_operator_role_permissions permission
                    ON permission.role_code = role.role_code
                 WHERE operator.auth_tenant_id = ?
                   AND operator.auth_user_id = ?
                   AND operator.lifecycle_state = 'ACTIVE'
                 ORDER BY assignment.role_code, permission.permission_code
                """, this::row, authTenantId, authUserId);
        if (rows.isEmpty()) return Optional.empty();
        Set<String> roles = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        rows.forEach(row -> {
            roles.add(row.roleCode());
            if (row.permissionCode() != null) permissions.add(row.permissionCode());
        });
        OperatorRow first = rows.get(0);
        return Optional.of(new ProviderRequestContext.Actor(
                first.operatorId(), authUserId, authTenantId, first.displayName(), roles, permissions));
    }

    private OperatorRow row(ResultSet result, int ignored) throws SQLException {
        return new OperatorRow(
                result.getLong("provider_operator_id"),
                result.getString("display_name"),
                result.getString("role_code"),
                result.getString("permission_code"));
    }

    private record OperatorRow(
            Long operatorId,
            String displayName,
            String roleCode,
            String permissionCode) {
    }
}

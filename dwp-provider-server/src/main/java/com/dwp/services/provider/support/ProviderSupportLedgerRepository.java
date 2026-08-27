package com.dwp.services.provider.support;

import com.dwp.services.provider.security.ProviderRequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class ProviderSupportLedgerRepository {

    private final JdbcTemplate jdbc;

    public ProviderSupportLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProviderSupportDtos.AccessRequestLedgerItem> accessRequests(
            UUID tenantId,
            ProviderRequestContext.Actor actor) {
        String tenantPredicate = tenantId == null
                ? "TRUE"
                : "request.provider_tenant_id = ?";
        List<Object> arguments = new ArrayList<>();
        arguments.add(actor.operatorId());
        if (tenantId != null) arguments.add(tenantId);
        arguments.add(actor.roles().contains("PROVIDER_ADMIN"));
        arguments.add(actor.operatorId());
        arguments.add(actor.permissions().contains("SUPPORT_ACCESS_REVIEW"));
        arguments.add(actor.permissions().contains("SUPPORT_POST_REVIEW"));
        return jdbc.query("""
                SELECT request.support_access_request_id,
                       request.provider_tenant_id,
                       tenant.tenant_key,
                       tenant.display_name AS tenant_name,
                       request.requester_operator_id = ? AS requester_owned,
                       requester.display_name AS requester_name,
                       request.lifecycle_state,
                       request.access_mode,
                       request.justification,
                       request.duration_minutes,
                       request.approval_reference,
                       request.customer_approval_required,
                       request.risk_tier,
                       request.created_at AS requested_at,
                       request.decision_due_at,
                       session.support_session_id,
                       request.activated_at,
                       request.completed_at,
                       request.post_review_state,
                       request.version,
                       ARRAY(
                           SELECT scope.scope_code
                             FROM prv_support_access_request_scopes scope
                            WHERE scope.support_access_request_id =
                                  request.support_access_request_id
                            ORDER BY scope.scope_code
                       ) AS scopes
                  FROM prv_support_access_requests request
                  JOIN prv_tenants tenant
                    ON tenant.provider_tenant_id = request.provider_tenant_id
                  JOIN prv_operators requester
                    ON requester.provider_operator_id = request.requester_operator_id
                  LEFT JOIN prv_support_sessions session
                    ON session.support_access_request_id = request.support_access_request_id
                 WHERE %s
                   AND (
                       ?
                       OR request.requester_operator_id = ?
                       OR (? AND request.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED'))
                       OR (? AND request.lifecycle_state IN ('COMPLETED', 'REVIEWED'))
                   )
                 ORDER BY request.created_at DESC
                 LIMIT 300
                """.formatted(tenantPredicate), this::accessRequest, arguments.toArray());
    }

    public List<ProviderSupportDtos.SessionLedgerItem> sessions(
            UUID tenantId,
            ProviderRequestContext.Actor actor) {
        String tenantPredicate = tenantId == null
                ? "TRUE"
                : "session.provider_tenant_id = ?";
        List<Object> arguments = new ArrayList<>();
        arguments.add(actor.operatorId());
        if (tenantId != null) arguments.add(tenantId);
        arguments.add(actor.roles().contains("PROVIDER_ADMIN"));
        arguments.add(actor.operatorId());
        arguments.add(actor.permissions().contains("SUPPORT_POST_REVIEW"));
        return jdbc.query("""
                SELECT session.support_session_id,
                       session.support_access_request_id,
                       session.provider_tenant_id,
                       tenant.tenant_key,
                       tenant.display_name AS tenant_name,
                       session.provider_operator_id = ? AS operator_owned,
                       operator.display_name AS operator_name,
                       session.lifecycle_state,
                       session.access_mode,
                       session.risk_tier,
                       session.started_at,
                       session.expires_at,
                       session.last_used_at,
                       session.revoked_at,
                       session.version,
                       ARRAY(
                           SELECT scope.scope_code
                             FROM prv_support_session_scopes scope
                            WHERE scope.support_session_id = session.support_session_id
                            ORDER BY scope.scope_code
                       ) AS scopes
                  FROM prv_support_sessions session
                  JOIN prv_tenants tenant
                    ON tenant.provider_tenant_id = session.provider_tenant_id
                  JOIN prv_operators operator
                    ON operator.provider_operator_id = session.provider_operator_id
                 WHERE %s
                   AND (
                       ?
                       OR session.provider_operator_id = ?
                       OR (? AND session.lifecycle_state <> 'ACTIVE')
                   )
                 ORDER BY session.created_at DESC
                 LIMIT 200
                """.formatted(tenantPredicate), this::session, arguments.toArray());
    }

    private ProviderSupportDtos.AccessRequestLedgerItem accessRequest(
            ResultSet result,
            int ignored) throws SQLException {
        return new ProviderSupportDtos.AccessRequestLedgerItem(
                result.getObject("support_access_request_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class),
                result.getString("tenant_key"),
                result.getString("tenant_name"),
                result.getBoolean("requester_owned"),
                result.getString("requester_name"),
                result.getString("lifecycle_state"),
                result.getString("access_mode"),
                result.getString("justification"),
                List.of((String[]) result.getArray("scopes").getArray()),
                result.getInt("duration_minutes"),
                result.getString("approval_reference"),
                result.getBoolean("customer_approval_required"),
                result.getString("risk_tier"),
                instant(result, "requested_at"),
                instant(result, "decision_due_at"),
                result.getObject("support_session_id", UUID.class),
                instant(result, "activated_at"),
                instant(result, "completed_at"),
                result.getString("post_review_state"),
                result.getLong("version"));
    }

    private ProviderSupportDtos.SessionLedgerItem session(
            ResultSet result,
            int ignored) throws SQLException {
        return new ProviderSupportDtos.SessionLedgerItem(
                result.getObject("support_session_id", UUID.class),
                result.getObject("support_access_request_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class),
                result.getString("tenant_key"),
                result.getString("tenant_name"),
                result.getBoolean("operator_owned"),
                result.getString("operator_name"),
                result.getString("lifecycle_state"),
                List.of((String[]) result.getArray("scopes").getArray()),
                result.getString("access_mode"),
                result.getString("risk_tier"),
                instant(result, "started_at"),
                instant(result, "expires_at"),
                instant(result, "last_used_at"),
                instant(result, "revoked_at"),
                result.getLong("version"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}

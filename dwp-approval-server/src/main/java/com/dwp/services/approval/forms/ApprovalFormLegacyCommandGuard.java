package com.dwp.services.approval.forms;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class ApprovalFormLegacyCommandGuard {
    private ApprovalFormLegacyCommandGuard() { }

    /** Requires the caller's mutation transaction; refresh the RC snapshot after acquiring the form lock. */
    public static void requireUnmanaged(NamedParameterJdbcTemplate jdbc, Actor actor, UUID formId) {
        if (actor == null || actor.tenantId() == null || actor.userId() == null || formId == null) throw unavailable();
        String resourceSet = ApprovalManagementScopeContext.current().orElseThrow(ApprovalFormLegacyCommandGuard::unavailable)
                .resourceSetKey();
        String sql = """
                SELECT workspace.form_id IS NOT NULL AS managed
                  FROM apr_forms form LEFT JOIN apr_form_workspaces workspace
                    ON workspace.tenant_id=form.tenant_id AND workspace.form_id=form.form_id
                 WHERE form.tenant_id=:tenant AND form.form_id=:form AND form.management_resource_set_key=:scope
                """;
        var params = new MapSqlParameterSource().addValue("tenant", actor.tenantId()).addValue("form", formId).addValue("scope", resourceSet);
        var locked = jdbc.query(sql + " FOR UPDATE OF form", params, (row, number) -> row.getBoolean("managed"));
        if (locked.size() != 1) throw new BaseException(ErrorCode.NOT_FOUND);
        // A waiter can retain a pre-adoption LEFT JOIN result when the locked form tuple did not change.
        var managed = jdbc.query(sql, params, (row, number) -> row.getBoolean("managed"));
        if (managed.size() != 1) throw new BaseException(ErrorCode.NOT_FOUND);
        if (managed.getFirst()) throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                "Managed form drafts and publication require the reviewed workspace command contract.");
    }

    private static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE); }
}

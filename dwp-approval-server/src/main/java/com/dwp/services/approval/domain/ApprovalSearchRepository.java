package com.dwp.services.approval.domain;

import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.documentretention.ApprovalRetentionLiveGuard;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class ApprovalSearchRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ApprovalSearchRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void requireTenant(ApprovalRequestContext.Actor actor) {
        var zones = jdbc.query("SELECT default_time_zone FROM apr_tenants "
                + "WHERE tenant_id=:tenantId AND lifecycle_state='ACTIVE'", params(actor, null), (rs, row) -> rs.getString(1));
        if (zones.isEmpty()) throw new BaseException(ErrorCode.FORBIDDEN, "The Approval tenant is inactive.");
        try { java.time.ZoneId.of(zones.getFirst()); }
        catch (java.time.DateTimeException exception) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "The Approval tenant time zone is invalid.");
        }
    }

    public List<Delegator> delegators(ApprovalRequestContext.Actor actor) {
        return jdbc.query("""
                SELECT DISTINCT delegator_user_id FROM apr_delegations
                 WHERE tenant_id = :tenantId AND delegate_user_id = :userId AND lifecycle_state = 'ACTIVE'
                   AND CURRENT_TIMESTAMP BETWEEN starts_at AND ends_at
                """, params(actor, null), (rs, row) -> new Delegator(rs.getLong(1)));
    }

    public ApprovalWorkDtos.Page<ApprovalDtos.TaskSummary> tasks(ApprovalRequestContext.Actor actor,
            ApprovalWorkDtos.TaskView view, ApprovalWorkDtos.SearchFilter filter,
            List<Long> delegators, String delegatedRoles) {
        var parameters = params(actor, filter).addValue("delegators", delegators.isEmpty() ? List.of(-1L) : delegators)
                .addValue("delegatedRoles", delegatedRoles);
        String access = switch (view) {
            case COMPLETED -> "task.decision_actor_user_id = :userId AND (task.candidate_role IS NULL OR task.candidate_role IN (:roles))";
            case DELEGATED -> ApprovalSearchSql.VISIBLE_DELEGATION;
            case INBOX -> "(" + ApprovalQuerySql01.QUERY_SQL_STATEMENT + " OR " + ApprovalSearchSql.VISIBLE_DELEGATION + ")";
        };
        String status = view == ApprovalWorkDtos.TaskView.COMPLETED
                ? "task.status IN ('APPROVED','REJECTED')" : "task.status IN ('PENDING','CLAIMED','INFO_REQUESTED')";
        String where = " WHERE task.tenant_id = :tenantId AND (" + access + ") AND " + status
                + " AND " + ApprovalSearchSql.CLAIMED_DELEGATION_CURRENT + " AND " + ApprovalSearchSql.COMMON
                + " AND (:status = '' OR task.status = :status)"
                + " AND (CAST(:riskScoreMin AS integer) IS NULL OR task.risk_score >= CAST(:riskScoreMin AS integer))"
                + due("task", filter.due());
        String select = ApprovalQuerySql01.QUERY_SELECT_APR_TASKS + where;
        var pinned=new ApprovalRetentionLiveGuard(jdbc).lockQuery(actor.tenantId(),select,parameters);
        if(pinned.isEmpty()) return ApprovalWorkDtos.Page.of(List.of(),0,filter.page(),filter.size(),Instant.now());
        parameters.addValue("retentionRequests",pinned);select+=" AND request.request_id IN(:retentionRequests)";
        long[] total={0};
        var items = jdbc.query("SELECT visible.*,COUNT(*) OVER() AS search_total FROM ("+select+") visible JOIN apr_tasks task ON task.task_id=visible.task_id ORDER BY " + taskOrder(filter.sort(), view)
                + " LIMIT :size OFFSET :offset", parameters, (rs, row) -> {total[0]=rs.getLong("search_total");return ApprovalQueryRowMapper.taskSummary(rs);});
        if(items.isEmpty()) total[0]=count(select,parameters);
        return ApprovalWorkDtos.Page.of(items, total[0], filter.page(), filter.size(), Instant.now());
    }

    public ApprovalWorkDtos.Page<ApprovalDtos.RequestSummary> requests(ApprovalRequestContext.Actor actor,
            ApprovalWorkDtos.RequestView view, ApprovalWorkDtos.SearchFilter filter) {
        String status = switch (view) {
            case DRAFTS, DELETED -> "request.status = 'DRAFT'";
            case ARCHIVE -> "request.status IN ('APPROVED','REJECTED','WITHDRAWN','CANCELLED')";
            case NEEDS_INFO -> "request.status = 'NEEDS_INFO'";
            case SUBMITTED -> "request.status IN ('SUBMITTED','IN_REVIEW','NEEDS_INFO')";
        };
        String common = view == ApprovalWorkDtos.RequestView.DELETED
                ? ApprovalSearchSql.COMMON.replace("request.deleted_at IS NULL", "request.deleted_at IS NOT NULL")
                : ApprovalSearchSql.COMMON;
        String select = ApprovalSearchSql.REQUEST_SELECT
                + " WHERE request.tenant_id = :tenantId AND request.requester_user_id = :userId AND "
                + status + " AND " + common + " AND (:status = '' OR request.status = :status)" + due("request", filter.due());
        var parameters = params(actor, filter);
        var pinned=new ApprovalRetentionLiveGuard(jdbc).lockQuery(actor.tenantId(),select,parameters);
        if(pinned.isEmpty()) return ApprovalWorkDtos.Page.of(List.of(),0,filter.page(),filter.size(),Instant.now());
        parameters.addValue("retentionRequests",pinned);select+=" AND request.request_id IN(:retentionRequests)";
        long[] total={0};
        String order = switch (filter.sort()) {
            case PRIORITY -> "CASE request.priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'NORMAL' THEN 2 ELSE 3 END,request.updated_at DESC";
            case NEWEST -> "request.updated_at DESC";
            case OLDEST -> "request.updated_at ASC";
        };
        var items = jdbc.query("SELECT visible.*,COUNT(*) OVER() AS search_total FROM ("+select+") visible JOIN apr_requests request ON request.tenant_id=:tenantId AND request.request_id=visible.request_id ORDER BY " + order + ",request.request_id LIMIT :size OFFSET :offset",
                parameters, (rs, row) -> {total[0]=rs.getLong("search_total");return ApprovalQueryRowMapper.requestSummary(rs);});
        if(items.isEmpty()) total[0]=count(select,parameters);
        return ApprovalWorkDtos.Page.of(items, total[0], filter.page(), filter.size(), Instant.now());
    }

    private long count(String select, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM (" + select + ") visible", params, Long.class);
        return value == null ? 0 : value;
    }

    private String taskOrder(ApprovalWorkDtos.Sort sort, ApprovalWorkDtos.TaskView view) {
        return switch (sort) {
            case NEWEST -> (view == ApprovalWorkDtos.TaskView.COMPLETED ? "task.completed_at" : "task.created_at") + " DESC NULLS LAST,task.task_id";
            case OLDEST -> (view == ApprovalWorkDtos.TaskView.COMPLETED ? "task.completed_at" : "task.created_at") + " ASC NULLS LAST,task.task_id";
            case PRIORITY -> "CASE WHEN task.due_at < CURRENT_TIMESTAMP THEN 0 ELSE 1 END,task.risk_score DESC,task.due_at NULLS LAST,task.task_id";
        };
    }

    private String due(String alias, ApprovalWorkDtos.DueFilter due) {
        return switch (due) {
            case ALL -> "";
            case OVERDUE -> " AND " + alias + ".due_at < CURRENT_TIMESTAMP";
            case TODAY -> " AND " + alias + ".due_at >= " + tenantDay(false)
                    + " AND " + alias + ".due_at < " + tenantDay(true);
        };
    }

    private String tenantDay(boolean tomorrow) {
        return "(SELECT (date_trunc('day',CURRENT_TIMESTAMP AT TIME ZONE default_time_zone)"
                + (tomorrow ? "+INTERVAL '1 day'" : "")
                + ") AT TIME ZONE default_time_zone FROM apr_tenants WHERE tenant_id=:tenantId)";
    }

    private MapSqlParameterSource params(ApprovalRequestContext.Actor actor, ApprovalWorkDtos.SearchFilter filter) {
        var parameters = new MapSqlParameterSource("tenantId", actor.tenantId()).addValue("userId", actor.userId())
                .addValue("roles", actor.roles().isEmpty() ? List.of("__NO_ROLE__") : actor.roles());
        if (filter == null) return parameters;
        String pattern = "%" + filter.query().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return parameters.addValue("query", filter.query()).addValue("pattern", pattern).addValue("status", filter.status())
                .addValue("priority", filter.priority()).addValue("workflowId", filter.workflowId())
                .addValue("riskScoreMin", filter.minRiskScore())
                .addValue("size", filter.size()).addValue("offset", (long) filter.page() * filter.size());
    }

    public record Delegator(long userId) { }
}

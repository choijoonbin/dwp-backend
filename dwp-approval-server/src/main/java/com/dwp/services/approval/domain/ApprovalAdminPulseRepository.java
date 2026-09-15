package com.dwp.services.approval.domain;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

final class ApprovalAdminPulseRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalAdminTrendRepository trends;

    ApprovalAdminPulseRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.trends = new ApprovalAdminTrendRepository(jdbc);
    }

    ApprovalDtos.AdminPulse load(MapSqlParameterSource params) {
        ApprovalDtos.AdminPulse pulse = jdbc.queryForObject(
                ApprovalQuerySql01.ADMIN_PULSE_SELECT_APR_WORKFLOW_DEFINITIONS,
                params,
                (result, rowNumber) -> new ApprovalDtos.AdminPulse(
                        result.getInt("published_workflows"),
                        result.getInt("draft_workflows"),
                        result.getInt("active_requests"),
                        result.getInt("overdue_tasks"),
                        result.getInt("failed_integrations"),
                        List.of(
                                assurance("identity", result.getInt("identity_gaps")),
                                assurance("segregation", result.getInt("sod_violations")),
                                assurance("evidence", result.getInt("evidence_gaps")),
                                assurance("delivery", result.getInt("failed_integrations")))));
        return new ApprovalDtos.AdminPulse(
                pulse.publishedWorkflows(),
                pulse.draftWorkflows(),
                pulse.activeRequests(),
                pulse.overdueTasks(),
                pulse.failedIntegrations(),
                pulse.assurance(),
                trends.load(params));
    }

    private static ApprovalDtos.AssuranceSignal assurance(String key, int exceptions) {
        return new ApprovalDtos.AssuranceSignal(
                key, exceptions == 0 ? "ENFORCED" : "ATTENTION", exceptions);
    }
}

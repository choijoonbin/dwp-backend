package com.dwp.services.approval.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

final class ApprovalQueryRowMapper {

    private ApprovalQueryRowMapper() {
    }

    record RequestAssetIds(UUID workflowId, UUID formId, java.util.Map<String, Object> formSchema,
            UUID formVersionId, String formSchemaSha256) {
    }

    static RequestAssetIds requestAssets(ResultSet result,
            java.util.function.Function<String, java.util.Map<String, Object>> readJson) throws SQLException {
        return new RequestAssetIds(result.getObject("workflow_id", UUID.class),
                result.getObject("form_id", UUID.class), readJson.apply(result.getString("form_schema")),
                result.getObject("form_version_id", UUID.class), result.getString("schema_sha256"));
    }

    static ApprovalDtos.TaskSummary taskSummary(ResultSet result) throws SQLException {
        return new ApprovalDtos.TaskSummary(
                result.getObject("task_id", UUID.class),
                result.getObject("request_id", UUID.class),
                result.getString("request_number"),
                result.getString("title"),
                result.getString("summary"),
                result.getString("workflow_name_ko"),
                result.getString("workflow_name_en"),
                result.getString("step_key"),
                result.getString("step_name"),
                result.getInt("step_sequence"),
                result.getString("requester_name"),
                result.getString("requester_org_name"),
                result.getString("status"),
                result.getString("priority"),
                result.getString("data_classification"),
                result.getInt("risk_score"),
                instant(result, "submitted_at"),
                instant(result, "due_at"),
                result.getLong("version"));
    }

    static ApprovalDtos.RequestSummary requestSummary(ResultSet result) throws SQLException {
        return new ApprovalDtos.RequestSummary(
                result.getObject("request_id", UUID.class),
                result.getString("request_number"),
                result.getString("title"),
                result.getString("summary"),
                result.getString("workflow_name_ko"),
                result.getString("workflow_name_en"),
                result.getString("current_step_key"),
                result.getString("current_step_name"),
                nullableInteger(result, "current_step_sequence"),
                result.getInt("total_steps"),
                result.getString("status"),
                result.getString("priority"),
                result.getString("data_classification"),
                result.getString("latest_information_request"),
                instant(result, "submitted_at"),
                instant(result, "due_at"),
                instant(result, "completed_at"),
                result.getLong("version"));
    }

    static ApprovalDtos.FormSummary formSummary(ResultSet result) throws SQLException {
        return new ApprovalDtos.FormSummary(
                result.getObject("form_id", UUID.class),
                result.getString("form_key"),
                result.getObject("category_id", UUID.class),
                result.getString("category_key"),
                result.getString("category_name_ko"),
                result.getString("category_name_en"),
                result.getString("name_ko"),
                result.getString("name_en"),
                result.getString("description_ko"),
                result.getString("description_en"),
                result.getString("owner_group_ref"),
                result.getString("form_kind"),
                result.getString("lifecycle_state"),
                result.getInt("current_version"),
                result.getInt("field_count"),
                result.getInt("route_count"),
                result.getLong("usage_count"),
                result.getLong("version"),
                instant(result, "updated_at"));
    }

    private static java.time.Instant instant(ResultSet result, String column)
            throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    static Integer nullableInteger(ResultSet result, String column)
            throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}

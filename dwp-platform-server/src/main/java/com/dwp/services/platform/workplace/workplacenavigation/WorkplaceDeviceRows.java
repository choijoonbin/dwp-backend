package com.dwp.services.platform.workplace.workplacenavigation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceDeviceRepository.*;

/** JDBC row mappings kept separate from the command repository. */
final class WorkplaceDeviceRows {
    private WorkplaceDeviceRows() { }

    static DeviceRow device(ResultSet rs, int row) throws SQLException {
        return new DeviceRow(rs.getObject("device_id", UUID.class), rs.getLong("tenant_id"),
                rs.getString("device_identity_sha256"), rs.getString("display_name"),
                DeviceType.valueOf(rs.getString("device_type")),
                RegistrationState.valueOf(rs.getString("registration_state")),
                rs.getObject("site_id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getObject("resource_id", UUID.class), rs.getString("hardware_model"),
                rs.getString("os_version"), rs.getString("app_version"),
                rs.getString("policy_version"), rs.getObject("heartbeat_at", OffsetDateTime.class),
                rs.getObject("schedule_source_at", OffsetDateTime.class),
                rs.getObject("schedule_received_at", OffsetDateTime.class),
                rs.getString("recent_error_code"), rs.getBoolean("safety_offline_fallback"),
                rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class));
    }

    static ProviderTruthRow providerTruth(ResultSet rs, int row) throws SQLException {
        Long observedVersion = rs.getObject("observed_configuration_version") == null
                ? null : rs.getLong("observed_configuration_version");
        String reported = rs.getString("reported_state");
        return new ProviderTruthRow(rs.getLong("tenant_id"),
                ProviderCapability.valueOf(rs.getString("capability")),
                rs.getString("provider_code"), rs.getLong("configuration_version"), observedVersion,
                reported == null ? null : ProviderReportedState.valueOf(reported),
                rs.getString("evidence_reference"),
                rs.getObject("source_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class),
                rs.getObject("last_success_at", OffsetDateTime.class),
                rs.getString("error_code"), rs.getBoolean("configured"), rs.getLong("version"));
    }

    static CommandRow command(ResultSet rs, int row) throws SQLException {
        return new CommandRow(rs.getObject("command_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"), rs.getObject("device_id", UUID.class),
                rs.getObject("preview_id", UUID.class),
                DeviceCommandType.valueOf(rs.getString("command_type")),
                rs.getString("idempotency_key"), rs.getString("request_fingerprint"),
                DeviceCommandState.valueOf(rs.getString("command_state")), rs.getString("reason"),
                rs.getString("correlation_id"), rs.getString("provider_operation_reference"),
                rs.getString("result_code"), rs.getString("provider_code"),
                rs.getObject("provider_configuration_version") == null ? null
                        : rs.getLong("provider_configuration_version"),
                rs.getString("credential_reference"), rs.getLong("version"),
                rs.getObject("accepted_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}

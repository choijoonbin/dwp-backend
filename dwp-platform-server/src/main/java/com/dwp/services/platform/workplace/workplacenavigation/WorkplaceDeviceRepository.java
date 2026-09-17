package com.dwp.services.platform.workplace.workplacenavigation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;

@Repository
public class WorkplaceDeviceRepository {
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public WorkplaceDeviceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<DeviceRow> device(long tenantId, UUID deviceId) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_devices WHERE tenant_id=? AND device_id=?
                """, WorkplaceDeviceRows::device, tenantId, deviceId).stream().findFirst();
    }

    public Optional<DeviceRow> deviceByIdentity(long tenantId, String identitySha256) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_devices
                 WHERE tenant_id=? AND device_identity_sha256=?
                """, WorkplaceDeviceRows::device, tenantId, identitySha256).stream().findFirst();
    }

    public List<DeviceRow> devices(long tenantId, UUID siteId, RegistrationState state) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_devices
                 WHERE tenant_id=?
                   AND (?::uuid IS NULL OR site_id=?::uuid)
                   AND (?::text IS NULL OR registration_state=?::text)
                 ORDER BY updated_at DESC, device_id
                """, WorkplaceDeviceRows::device, tenantId, siteId, siteId,
                state == null ? null : state.name(), state == null ? null : state.name());
    }

    public DeviceRow register(
            long tenantId,
            String identitySha256,
            DeviceRegistrationRequest request,
            OffsetDateTime now) {
        UUID deviceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_navigation_devices (
                    device_id, tenant_id, device_identity_sha256, display_name,
                    device_type, registration_state, hardware_model, os_version,
                    version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, 1, ?, ?)
                """, deviceId, tenantId, identitySha256, request.displayName().trim(),
                request.deviceType().name(), request.hardwareModel().trim(),
                request.osVersion().trim(), now, now);
        return device(tenantId, deviceId).orElseThrow();
    }

    public boolean approve(
            long tenantId,
            long actorId,
            UUID deviceId,
            long expectedVersion,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_navigation_devices
                   SET registration_state='APPROVED', approved_at=?, approved_by=?,
                       version=version+1, updated_at=?
                 WHERE tenant_id=? AND device_id=? AND version=?
                   AND registration_state='PENDING'
                """, now, actorId, now, tenantId, deviceId, expectedVersion) == 1;
    }

    public boolean bind(
            long tenantId,
            UUID deviceId,
            DeviceType type,
            BindDeviceRequest request,
            OffsetDateTime now) {
        if (type == DeviceType.ROOM_PANEL) {
            return jdbc.update("""
                    UPDATE wp_navigation_devices
                       SET registration_state='BOUND', site_id=?, floor_id=?, resource_id=?,
                           safety_offline_fallback=?, version=version+1, updated_at=?
                     WHERE tenant_id=? AND device_id=? AND version=?
                       AND registration_state IN ('APPROVED','BOUND')
                    """, request.siteId(), request.floorId(), request.resourceId(),
                    request.safetyOfflineFallback(), now, tenantId, deviceId,
                    request.expectedVersion()) == 1;
        }
        return jdbc.update("""
                UPDATE wp_navigation_devices
                   SET registration_state='BOUND', site_id=?, floor_id=?, resource_id=NULL,
                       safety_offline_fallback=?, version=version+1, updated_at=?
                 WHERE tenant_id=? AND device_id=? AND version=?
                   AND registration_state IN ('APPROVED','BOUND')
                """, request.siteId(), request.floorId(), request.safetyOfflineFallback(),
                now, tenantId, deviceId, request.expectedVersion()) == 1;
    }

    public boolean heartbeat(
            long tenantId,
            UUID deviceId,
            DeviceHeartbeatRequest request,
            OffsetDateTime receivedAt) {
        return jdbc.update("""
                UPDATE wp_navigation_devices
                   SET app_version=?, policy_version=?, heartbeat_at=?,
                       schedule_source_at=?, schedule_received_at=?, recent_error_code=?,
                       updated_at=?
                 WHERE tenant_id=? AND device_id=? AND version=?
                   AND registration_state IN ('APPROVED','BOUND')
                   AND (heartbeat_at IS NULL OR heartbeat_at<=?)
                   AND ? <= ?
                   AND (?::timestamptz IS NULL OR ?::timestamptz <= ?)
                """, request.appVersion().trim(), request.policyVersion().trim(),
                request.observedAt(), request.scheduleSourceAt(), receivedAt,
                normalized(request.recentErrorCode()), receivedAt, tenantId, deviceId,
                request.expectedVersion(), request.observedAt(), request.observedAt(),
                receivedAt, request.scheduleSourceAt(), request.scheduleSourceAt(),
                receivedAt) == 1;
    }

    public boolean siteFloorBinding(long tenantId, UUID siteId, UUID floorId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM wp_floors
                 WHERE tenant_id=? AND site_id=? AND floor_id=? AND lifecycle_state<>'CLOSED')
                """, Boolean.class, tenantId, siteId, floorId));
    }

    public boolean resourceBinding(long tenantId, UUID floorId, UUID resourceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM wp_resources
                 WHERE tenant_id=? AND floor_id=? AND resource_id=? AND lifecycle_state<>'RETIRED')
                """, Boolean.class, tenantId, floorId, resourceId));
    }

    public List<ScheduleRow> schedules(
            long tenantId,
            UUID resourceId,
            OffsetDateTime from,
            OffsetDateTime to) {
        return jdbc.query("""
                SELECT booking_id, starts_at, ends_at, purpose,
                       booked_for_display_name, visible_to_colleagues
                  FROM wp_bookings
                 WHERE tenant_id=? AND resource_id=?
                   AND booking_status IN ('RESERVED','CHECKED_IN')
                   AND starts_at<? AND ends_at>?
                 ORDER BY starts_at, booking_id
                """, (rs, row) -> new ScheduleRow(rs.getObject("booking_id", UUID.class),
                rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("ends_at", OffsetDateTime.class), rs.getString("purpose"),
                rs.getString("booked_for_display_name"), rs.getBoolean("visible_to_colleagues")),
                tenantId, resourceId, to, from);
    }

    public List<FloorResourceRow> floorResources(
            long tenantId,
            UUID floorId,
            OffsetDateTime at) {
        return jdbc.query("""
                SELECT resource.resource_id, resource.zone_id,
                       zone.name_ko zone_name_ko, zone.name_en zone_name_en,
                       resource.name_ko, resource.name_en,
                       EXISTS(SELECT 1 FROM wp_bookings booking
                               WHERE booking.tenant_id=resource.tenant_id
                                 AND booking.resource_id=resource.resource_id
                                 AND booking.booking_status IN ('RESERVED','CHECKED_IN')
                                 AND booking.starts_at<=? AND booking.ends_at>?) occupied,
                       poi.direction_hint_ko, poi.direction_hint_en
                  FROM wp_resources resource
                  JOIN wp_zones zone
                    ON zone.tenant_id=resource.tenant_id
                   AND zone.floor_id=resource.floor_id
                   AND zone.zone_id=resource.zone_id
                LEFT JOIN wp_navigation_pois poi
                       ON poi.tenant_id=resource.tenant_id
                      AND poi.resource_id=resource.resource_id AND poi.active
                      AND poi.graph_revision_id=(
                       SELECT graph_revision_id FROM wp_navigation_graph_revisions
                        WHERE tenant_id=resource.tenant_id
                          AND site_id=(SELECT site_id FROM wp_floors
                                       WHERE tenant_id=resource.tenant_id
                                         AND floor_id=resource.floor_id)
                          AND lifecycle_state='PUBLISHED')
                 WHERE resource.tenant_id=? AND resource.floor_id=?
                   AND resource.lifecycle_state='AVAILABLE'
                 ORDER BY resource.resource_type, resource.name_en
                """, (rs, row) -> new FloorResourceRow(
                rs.getObject("resource_id", UUID.class), rs.getObject("zone_id", UUID.class),
                rs.getString("zone_name_ko"), rs.getString("zone_name_en"),
                rs.getString("name_ko"), rs.getString("name_en"), rs.getBoolean("occupied"),
                rs.getString("direction_hint_ko"), rs.getString("direction_hint_en")),
                at, at, tenantId, floorId);
    }

    public Optional<SafetyFrame> activeSafetyFrame(long tenantId, UUID deviceId) {
        return jdbc.query("""
                SELECT safety_frame_id, frame_state, message, direction, issued_at,
                       issued_by_actor_id, offline_fallback, cleared_at, version
                  FROM wp_navigation_safety_frames
                 WHERE tenant_id=? AND device_id=? AND frame_state='ACTIVE'
                """, (rs, row) -> new SafetyFrame(rs.getObject("safety_frame_id", UUID.class),
                SafetyFrameState.valueOf(rs.getString("frame_state")), rs.getString("message"),
                rs.getString("direction"), rs.getObject("issued_at", OffsetDateTime.class),
                rs.getLong("issued_by_actor_id"), rs.getBoolean("offline_fallback"),
                rs.getObject("cleared_at", OffsetDateTime.class), rs.getLong("version")),
                tenantId, deviceId).stream().findFirst();
    }

    public List<ProviderTruthRow> providerTruth(long tenantId) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_provider_truth
                 WHERE tenant_id=? ORDER BY capability
                """, WorkplaceDeviceRows::providerTruth, tenantId);
    }

    public Optional<ProviderTruthRow> providerTruth(long tenantId, ProviderCapability capability) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_provider_truth
                 WHERE tenant_id=? AND capability=?
                """, WorkplaceDeviceRows::providerTruth,
                tenantId, capability.name()).stream().findFirst();
    }

    public List<ProviderTruthRow> configuredProviderTruth(int limit) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_provider_truth
                 WHERE configured
                 ORDER BY COALESCE(received_at, '-infinity'::timestamptz),
                          tenant_id, capability
                 LIMIT ?
                """, WorkplaceDeviceRows::providerTruth, limit);
    }

    public void configureProvider(
            long tenantId,
            ProviderCapability capability,
            String providerCode,
            long configurationVersion,
            boolean configured,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_navigation_provider_truth (
                    provider_truth_id, tenant_id, capability, provider_code,
                    configuration_version, configured, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT (tenant_id, capability) DO UPDATE SET
                    provider_code=EXCLUDED.provider_code,
                    configuration_version=EXCLUDED.configuration_version,
                    configured=EXCLUDED.configured,
                    observed_configuration_version=NULL, reported_state=NULL,
                    evidence_reference=NULL, source_at=NULL, received_at=NULL,
                    last_success_at=NULL, error_code=NULL,
                    version=wp_navigation_provider_truth.version+1,
                    updated_at=EXCLUDED.updated_at
                """, UUID.randomUUID(), tenantId, capability.name(), providerCode,
                configurationVersion, configured, now, now);
    }

    public boolean observeProvider(ProviderObservation observation, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_navigation_provider_truth
                   SET observed_configuration_version=?, reported_state=?, evidence_reference=?,
                       source_at=?, received_at=?, last_success_at=?, error_code=?,
                       version=version+1, updated_at=?
                 WHERE tenant_id=? AND capability=? AND configured
                   AND provider_code=?
                   AND configuration_version=?
                   AND (received_at IS NULL OR received_at<=?)
                """, observation.observedConfigurationVersion(), observation.reportedState().name(),
                observation.evidenceReference(), observation.sourceAt(), observation.receivedAt(),
                observation.lastSuccessAt(), normalized(observation.errorCode()), now,
                observation.tenantId(), observation.capability().name(), observation.providerCode(),
                observation.observedConfigurationVersion(), observation.receivedAt()) == 1;
    }

    public void lockAdminCommand(long tenantId, long actorId, String idempotencyKey) {
        String identity = "workplace-navigation-admin:" + tenantId + ":" + actorId + ":"
                + idempotencyKey.length() + ":" + idempotencyKey;
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, identity), resultSet -> null);
    }

    public Optional<AdminCommandRow> adminCommand(
            long tenantId, long actorId, String idempotencyKey) {
        return jdbc.query("""
                SELECT command_type,resource_type,resource_id,request_fingerprint,
                       result_type,result_snapshot,audit_event_id,correlation_id,created_at
                  FROM wp_navigation_admin_commands
                 WHERE tenant_id=? AND actor_user_id=? AND idempotency_key=?
                """, (rs, row) -> new AdminCommandRow(
                rs.getString("command_type"), rs.getString("resource_type"),
                rs.getObject("resource_id", UUID.class), rs.getString("request_fingerprint"),
                rs.getString("result_type"), rs.getString("result_snapshot"),
                rs.getObject("audit_event_id", UUID.class), rs.getString("correlation_id"),
                rs.getObject("created_at", OffsetDateTime.class)),
                tenantId, actorId, idempotencyKey).stream().findFirst();
    }

    public void saveAdminCommand(
            long tenantId,
            long actorId,
            String commandType,
            String resourceType,
            UUID resourceId,
            String idempotencyKey,
            String requestFingerprint,
            String resultType,
            Object result,
            UUID auditEventId,
            String correlationId,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_navigation_admin_commands (
                    admin_command_id,tenant_id,actor_user_id,command_type,resource_type,
                    resource_id,idempotency_key,request_fingerprint,result_type,result_snapshot,
                    audit_event_id,correlation_id,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)
                """, UUID.randomUUID(), tenantId, actorId, commandType, resourceType,
                resourceId, idempotencyKey, requestFingerprint, resultType, json(result),
                auditEventId, correlationId, now);
    }

    public <T> T adminCommandResult(AdminCommandRow row, Class<T> resultType) {
        try {
            return objectMapper.readValue(row.resultSnapshot(), resultType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted navigation administrator result is invalid.",
                    exception);
        }
    }

    public void savePreview(DeviceCommandPreview preview, long tenantId, long actorId) {
        jdbc.update("""
                INSERT INTO wp_navigation_command_previews (
                    preview_id, tenant_id, actor_user_id, device_id, command_type,
                    expected_device_version, payload, impact, eligible, limitations,
                    expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?, ?)
                """, preview.previewId(), tenantId, actorId, preview.deviceId(),
                preview.commandType().name(), preview.expectedDeviceVersion(), json(preview.payload()),
                json(preview.impact()), preview.eligible(), json(preview.limitations()),
                preview.expiresAt(), preview.createdAt());
    }

    public Optional<DeviceCommandPreview> preview(long tenantId, long actorId, UUID previewId) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_command_previews
                 WHERE tenant_id=? AND actor_user_id=? AND preview_id=?
                """, (rs, row) -> new DeviceCommandPreview(
                rs.getObject("preview_id", UUID.class), rs.getObject("device_id", UUID.class),
                DeviceCommandType.valueOf(rs.getString("command_type")),
                rs.getLong("expected_device_version"), map(rs.getString("payload")),
                list(rs.getString("impact")), rs.getBoolean("eligible"),
                list(rs.getString("limitations")),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class)),
                tenantId, actorId, previewId).stream().findFirst();
    }

    public Optional<CommandRow> commandByIdempotency(
            long tenantId, long actorId, String idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_device_commands
                 WHERE tenant_id=? AND actor_user_id=? AND idempotency_key=?
                """, WorkplaceDeviceRows::command,
                tenantId, actorId, idempotencyKey).stream().findFirst();
    }

    public Optional<CommandRow> command(long tenantId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_device_commands
                 WHERE tenant_id=? AND command_id=?
                """, WorkplaceDeviceRows::command, tenantId, commandId).stream().findFirst();
    }

    public List<CommandRow> commands(long tenantId, UUID deviceId) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_device_commands
                 WHERE tenant_id=? AND device_id=?
                 ORDER BY accepted_at DESC, command_id DESC
                 LIMIT 200
                """, WorkplaceDeviceRows::command, tenantId, deviceId);
    }

    public List<DeviceAuditEvent> auditEvents(long tenantId, UUID deviceId) {
        return jdbc.query("""
                SELECT audit_event_id, actor_user_id, action, resource_type, resource_id,
                       correlation_id, occurred_at
                  FROM wp_navigation_audit_events audit
                 WHERE audit.tenant_id=?
                   AND ((audit.resource_type='DEVICE' AND audit.resource_id=?)
                     OR (audit.resource_type='DEVICE_COMMAND' AND EXISTS (
                         SELECT 1 FROM wp_navigation_device_commands command
                          WHERE command.tenant_id=audit.tenant_id
                            AND command.device_id=?
                            AND command.command_id=audit.resource_id)))
                 ORDER BY occurred_at DESC, audit_event_id DESC
                 LIMIT 500
                """, (rs, row) -> new DeviceAuditEvent(
                rs.getObject("audit_event_id", UUID.class), rs.getLong("actor_user_id"),
                rs.getString("action"),
                rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
                rs.getString("correlation_id"),
                rs.getObject("occurred_at", OffsetDateTime.class)),
                tenantId, deviceId, deviceId);
    }

    public Optional<CommandRow> claimNextCommand(OffsetDateTime now) {
        Optional<CommandRow> candidate = jdbc.query("""
                SELECT command.*
                  FROM wp_navigation_device_outbox outbox
                  JOIN wp_navigation_device_commands command
                    ON command.tenant_id=outbox.tenant_id
                   AND command.command_id=outbox.command_id
                 WHERE outbox.delivery_state IN ('PENDING','RETRY')
                   AND outbox.next_attempt_at<=?
                   AND command.command_state='ACCEPTED'
                 ORDER BY outbox.created_at, outbox.outbox_id
                 LIMIT 1 FOR UPDATE OF outbox SKIP LOCKED
                """, WorkplaceDeviceRows::command, now).stream().findFirst();
        if (candidate.isEmpty()) return Optional.empty();
        CommandRow row = candidate.get();
        jdbc.update("""
                UPDATE wp_navigation_device_outbox
                   SET delivery_state='PROCESSING', attempt_count=attempt_count+1, updated_at=?
                 WHERE tenant_id=? AND command_id=?
                   AND delivery_state IN ('PENDING','RETRY')
                """, now, row.tenantId(), row.commandId());
        jdbc.update("""
                UPDATE wp_navigation_device_commands
                   SET command_state='RESULT_UNKNOWN', version=version+1, updated_at=?
                 WHERE tenant_id=? AND command_id=? AND version=? AND command_state='ACCEPTED'
                """, now, row.tenantId(), row.commandId(), row.version());
        return command(row.tenantId(), row.commandId());
    }

    public Optional<CommandRow> claimNextReconciliation(OffsetDateTime now) {
        Optional<CommandRow> candidate = jdbc.query("""
                SELECT command.*
                  FROM wp_navigation_device_outbox outbox
                  JOIN wp_navigation_device_commands command
                    ON command.tenant_id=outbox.tenant_id
                   AND command.command_id=outbox.command_id
                 WHERE outbox.delivery_state='RESULT_UNKNOWN'
                   AND outbox.next_attempt_at<=?
                   AND outbox.attempt_count<10
                   AND command.command_state='RESULT_UNKNOWN'
                 ORDER BY outbox.updated_at, outbox.outbox_id
                 LIMIT 1 FOR UPDATE OF outbox SKIP LOCKED
                """, WorkplaceDeviceRows::command, now).stream().findFirst();
        if (candidate.isEmpty()) return Optional.empty();
        CommandRow row = candidate.get();
        int changed = jdbc.update("""
                UPDATE wp_navigation_device_outbox
                   SET delivery_state='PROCESSING', attempt_count=attempt_count+1, updated_at=?
                 WHERE tenant_id=? AND command_id=? AND delivery_state='RESULT_UNKNOWN'
                   AND attempt_count<10
                """, now, row.tenantId(), row.commandId());
        return changed == 1 ? command(row.tenantId(), row.commandId()) : Optional.empty();
    }

    public int recoverStaleProcessing(OffsetDateTime staleBefore, OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_navigation_device_commands command
                   SET command_state='RESULT_UNKNOWN', version=version+1, updated_at=?
                  FROM wp_navigation_device_outbox outbox
                 WHERE outbox.tenant_id=command.tenant_id
                   AND outbox.command_id=command.command_id
                   AND outbox.delivery_state='PROCESSING'
                   AND outbox.updated_at<?
                   AND command.command_state='RUNNING'
                """, now, staleBefore);
        return jdbc.update("""
                UPDATE wp_navigation_device_outbox outbox
                   SET delivery_state='RESULT_UNKNOWN', next_attempt_at=?, updated_at=?
                  FROM wp_navigation_device_commands command
                 WHERE command.tenant_id=outbox.tenant_id
                   AND command.command_id=outbox.command_id
                   AND outbox.delivery_state='PROCESSING'
                   AND outbox.updated_at<?
                   AND command.command_state='RESULT_UNKNOWN'
                """, now, now, staleBefore);
    }

    public void createCommand(CommandRow row) {
        jdbc.update("""
                INSERT INTO wp_navigation_device_commands (
                    command_id, tenant_id, actor_user_id, device_id, preview_id,
                    command_type, idempotency_key, request_fingerprint, command_state,
                    reason, correlation_id, provider_code, provider_configuration_version,
                    credential_reference, version, accepted_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.commandId(), row.tenantId(), row.actorUserId(), row.deviceId(),
                row.previewId(), row.type().name(), row.idempotencyKey(), row.requestFingerprint(),
                row.state().name(), row.reason(), row.correlationId(), row.providerCode(),
                row.providerConfigurationVersion(), row.credentialReference(), row.version(),
                row.acceptedAt(), row.updatedAt());
        jdbc.update("""
                INSERT INTO wp_navigation_device_outbox (
                    outbox_id, tenant_id, command_id, device_id, deduplication_key,
                    delivery_state, attempt_count, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                """, UUID.randomUUID(), row.tenantId(), row.commandId(), row.deviceId(),
                "DEVICE_COMMAND:" + row.commandId(), row.acceptedAt(), row.acceptedAt(), row.acceptedAt());
        audit(row.tenantId(), row.actorUserId(), "navigation.device.command.accepted",
                "DEVICE_COMMAND", row.commandId(), row.correlationId(), row.acceptedAt());
    }

    public boolean updateCommand(
            long tenantId,
            UUID commandId,
            long expectedVersion,
            DeviceCommandState state,
            String providerReference,
            String resultCode,
            OffsetDateTime completedAt,
            OffsetDateTime now) {
        int changed = jdbc.update("""
                UPDATE wp_navigation_device_commands
                   SET command_state=?, provider_operation_reference=COALESCE(
                           provider_operation_reference, ?),
                       result_code=?, completed_at=?, version=version+1, updated_at=?
                 WHERE tenant_id=? AND command_id=? AND version=?
                   AND command_state IN ('ACCEPTED','RUNNING','RESULT_UNKNOWN')
                """, state.name(), providerReference, resultCode, completedAt, now,
                tenantId, commandId, expectedVersion);
        if (changed == 1) {
            String delivery = switch (state) {
                case SUCCEEDED, FAILED -> "DELIVERED";
                case RESULT_UNKNOWN -> "RESULT_UNKNOWN";
                case ACCEPTED -> "PENDING";
                case RUNNING -> "PROCESSING";
            };
            jdbc.update("""
                    UPDATE wp_navigation_device_outbox
                       SET delivery_state=?,
                           next_attempt_at=CASE WHEN ?='RESULT_UNKNOWN'
                               THEN ? + LEAST(900, (5 * POWER(2,
                                   LEAST(GREATEST(attempt_count - 1, 0), 8)))::integer)
                                   * INTERVAL '1 second'
                               ELSE ? END,
                           updated_at=?
                     WHERE tenant_id=? AND command_id=?
                    """, delivery, delivery, now, now, now, tenantId, commandId);
        }
        return changed == 1;
    }

    public boolean unbind(
            long tenantId, UUID deviceId, long expectedVersion, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_navigation_devices
                   SET registration_state='APPROVED', site_id=NULL, floor_id=NULL, resource_id=NULL,
                       schedule_source_at=NULL, schedule_received_at=NULL,
                       version=version+1, updated_at=?
                 WHERE tenant_id=? AND device_id=? AND version=? AND registration_state='BOUND'
                """, now, tenantId, deviceId, expectedVersion) == 1;
    }

    public void activateSafetyFrame(
            long tenantId,
            long actorId,
            UUID deviceId,
            UUID commandId,
            Map<String, String> payload,
            boolean offlineFallback,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_navigation_safety_frames (
                    safety_frame_id, tenant_id, device_id, source_command_id, frame_state,
                    message, direction, issued_at, issued_by_actor_id, offline_fallback,
                    version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT (tenant_id, device_id) WHERE frame_state='ACTIVE'
                DO UPDATE SET source_command_id=EXCLUDED.source_command_id,
                    message=EXCLUDED.message, direction=EXCLUDED.direction,
                    issued_at=EXCLUDED.issued_at,
                    issued_by_actor_id=EXCLUDED.issued_by_actor_id,
                    offline_fallback=EXCLUDED.offline_fallback,
                    version=wp_navigation_safety_frames.version+1,
                    updated_at=EXCLUDED.updated_at
                """, UUID.randomUUID(), tenantId, deviceId, commandId,
                payload.getOrDefault("message", "Safety notice"),
                payload.getOrDefault("direction", "Follow onsite safety guidance."),
                now, actorId, offlineFallback, now, now);
    }

    public void clearSafetyFrame(
            long tenantId, long actorId, UUID deviceId, OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_navigation_safety_frames
                   SET frame_state='CLEARED', cleared_at=?, cleared_by=?,
                       version=version+1, updated_at=?
                 WHERE tenant_id=? AND device_id=? AND frame_state='ACTIVE'
                """, now, actorId, now, tenantId, deviceId);
    }

    public UUID audit(
            long tenantId,
            long actorId,
            String action,
            String resourceType,
            UUID resourceId,
            String correlationId,
            OffsetDateTime now) {
        UUID auditEventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_navigation_audit_events (
                    audit_event_id, tenant_id, actor_user_id, action, resource_type,
                    resource_id, correlation_id, snapshot, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?)
                """, auditEventId, tenantId, actorId, action, resourceType,
                resourceId, correlationId, now);
        return auditEventId;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Navigation value could not be serialized.", exception);
        }
    }

    private Map<String, String> map(String value) {
        try {
            return objectMapper.readValue(value, STRING_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted navigation command payload is invalid.", exception);
        }
    }

    private List<String> list(String value) {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted navigation command list is invalid.", exception);
        }
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record DeviceRow(
            UUID deviceId, long tenantId, String identitySha256, String displayName,
            DeviceType deviceType, RegistrationState registrationState,
            UUID siteId, UUID floorId, UUID resourceId,
            String hardwareModel, String osVersion, String appVersion, String policyVersion,
            OffsetDateTime heartbeatAt, OffsetDateTime scheduleSourceAt,
            OffsetDateTime scheduleReceivedAt, String recentErrorCode,
            boolean safetyOfflineFallback, long version, OffsetDateTime updatedAt) { }

    record FloorResourceRow(
            UUID resourceId, UUID zoneId, String zoneNameKo, String zoneNameEn,
            String nameKo, String nameEn, boolean occupied,
            String directionKo, String directionEn) { }

    record AdminCommandRow(
            String commandType,
            String resourceType,
            UUID resourceId,
            String requestFingerprint,
            String resultType,
            String resultSnapshot,
            UUID auditEventId,
            String correlationId,
            OffsetDateTime createdAt) { }
}

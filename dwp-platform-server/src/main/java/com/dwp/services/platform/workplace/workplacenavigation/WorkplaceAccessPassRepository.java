package com.dwp.services.platform.workplace.workplacenavigation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceAccessPassDtos.*;

@Repository
public class WorkplaceAccessPassRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public WorkplaceAccessPassRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public boolean destinationMatches(
            long tenantId, UUID siteId, UUID floorId, UUID resourceId, UUID destinationPoiId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1
                      FROM wp_navigation_pois poi
                      JOIN wp_navigation_graph_revisions graph
                        ON graph.tenant_id=poi.tenant_id
                       AND graph.graph_revision_id=poi.graph_revision_id
                     WHERE poi.tenant_id=? AND poi.poi_id=? AND poi.site_id=?
                       AND poi.floor_id=? AND poi.resource_id=? AND poi.active
                       AND graph.lifecycle_state='PUBLISHED')
                """, Boolean.class, tenantId, destinationPoiId, siteId, floorId, resourceId));
    }

    public Optional<BookingWindow> eligibleBooking(
            long tenantId, long userId, UUID resourceId, OffsetDateTime now) {
        return jdbc.query("""
                SELECT booking_id, ends_at
                  FROM wp_bookings
                 WHERE tenant_id=? AND user_id=? AND resource_id=?
                   AND booking_status IN ('RESERVED','CHECKED_IN')
                   AND starts_at <= ? + INTERVAL '30 minutes'
                   AND ends_at > ? + INTERVAL '30 seconds'
                 ORDER BY starts_at, booking_id
                 LIMIT 1
                """, (rs, row) -> new BookingWindow(
                rs.getObject("booking_id", UUID.class),
                rs.getObject("ends_at", OffsetDateTime.class)),
                tenantId, userId, resourceId, now, now).stream().findFirst();
    }

    public void expireActive(long tenantId, long userId, UUID resourceId, OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_navigation_access_passes
                   SET lifecycle_state='EXPIRED', version=version+1, updated_at=?
                 WHERE tenant_id=? AND owner_user_id=? AND resource_id=?
                   AND lifecycle_state='ACTIVE' AND expires_at<=?
                """, now, tenantId, userId, resourceId, now);
    }

    public Optional<AccessPassRow> pass(long tenantId, long ownerUserId, UUID passId) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_access_passes
                 WHERE tenant_id=? AND owner_user_id=? AND pass_id=?
                """, this::passRow, tenantId, ownerUserId, passId).stream().findFirst();
    }

    public Optional<AccessPassRow> passForPairing(long tenantId, UUID passId) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_access_passes
                 WHERE tenant_id=? AND pass_id=?
                """, this::passRow, tenantId, passId).stream().findFirst();
    }

    public Optional<AccessPassRow> latest(
            long tenantId, long ownerUserId, UUID siteId, UUID resourceId) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_access_passes
                 WHERE tenant_id=? AND owner_user_id=? AND site_id=? AND resource_id=?
                 ORDER BY (lifecycle_state='ACTIVE') DESC, updated_at DESC, pass_id
                 LIMIT 1
                """, this::passRow, tenantId, ownerUserId, siteId, resourceId)
                .stream().findFirst();
    }

    public Optional<AccessPassPreviewRow> previewByKey(
            long tenantId, long actorId, String idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_access_pass_previews
                 WHERE tenant_id=? AND actor_user_id=? AND idempotency_key=?
                """, this::previewRow, tenantId, actorId, idempotencyKey).stream().findFirst();
    }

    public Optional<AccessPassPreviewRow> preview(
            long tenantId, long actorId, UUID previewId) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_access_pass_previews
                 WHERE tenant_id=? AND actor_user_id=? AND preview_id=?
                """, this::previewRow, tenantId, actorId, previewId).stream().findFirst();
    }

    public void insertPreview(AccessPassPreviewRow row) {
        jdbc.update("""
                INSERT INTO wp_navigation_access_pass_previews (
                    preview_id, tenant_id, actor_user_id, idempotency_key,
                    request_fingerprint, command_type, pass_id, site_id, floor_id,
                    resource_id, destination_poi_id, source_booking_id,
                    expected_pass_version, nfc_enabled, qr_enabled, nfc_provider_code,
                    nfc_provider_configuration_version, nfc_provider_evidence_reference,
                    qr_provider_code, qr_provider_configuration_version,
                    qr_provider_evidence_reference,
                    eligible, impact, limitations, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?::jsonb, ?::jsonb, ?, ?)
                """, row.previewId(), row.tenantId(), row.actorUserId(), row.idempotencyKey(),
                row.requestFingerprint(), row.commandType().name(), row.passId(), row.siteId(),
                row.floorId(), row.resourceId(), row.destinationPoiId(), row.sourceBookingId(),
                row.expectedPassVersion(), row.nfcEnabled(), row.qrEnabled(),
                row.nfcProviderCode(), row.nfcProviderConfigurationVersion(),
                row.nfcProviderEvidenceReference(), row.qrProviderCode(),
                row.qrProviderConfigurationVersion(), row.qrProviderEvidenceReference(), row.eligible(),
                json(row.impact()), json(row.limitations()), row.expiresAt(), row.createdAt());
    }

    public AccessPassRow insertPass(
            AccessPassPreviewRow preview,
            UUID passId,
            String credentialHash,
            String credentialLastFour,
            String pairingCodeHash,
            String pairingCodeSalt,
            OffsetDateTime expiresAt,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_navigation_access_passes (
                    pass_id, tenant_id, owner_user_id, source_booking_id, site_id, floor_id,
                    resource_id, destination_poi_id, lifecycle_state, credential_sha256,
                    credential_last_four, pairing_code_hash, pairing_code_salt,
                    nfc_enabled, qr_enabled, nfc_provider_code,
                    nfc_provider_configuration_version, nfc_provider_evidence_reference,
                    qr_provider_code, qr_provider_configuration_version,
                    qr_provider_evidence_reference, version, issued_at, expires_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                """, passId, preview.tenantId(), preview.actorUserId(), preview.sourceBookingId(),
                preview.siteId(), preview.floorId(), preview.resourceId(), preview.destinationPoiId(),
                credentialHash, credentialLastFour, pairingCodeHash, pairingCodeSalt,
                preview.nfcEnabled(), preview.qrEnabled(), preview.nfcProviderCode(),
                preview.nfcProviderConfigurationVersion(), preview.nfcProviderEvidenceReference(),
                preview.qrProviderCode(), preview.qrProviderConfigurationVersion(),
                preview.qrProviderEvidenceReference(),
                now, expiresAt, now);
        return pass(preview.tenantId(), preview.actorUserId(), passId).orElseThrow();
    }

    public boolean rotate(
            AccessPassPreviewRow preview,
            String credentialHash,
            String credentialLastFour,
            String pairingCodeHash,
            String pairingCodeSalt,
            OffsetDateTime expiresAt,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_navigation_access_passes
                   SET credential_sha256=?, credential_last_four=?, pairing_code_hash=?,
                       pairing_code_salt=?, pairing_attempt_count=0, pairing_locked_at=NULL,
                       pairing_consumed_at=NULL, pairing_device_id=NULL,
                       nfc_enabled=?, qr_enabled=?, nfc_provider_code=?,
                       nfc_provider_configuration_version=?, nfc_provider_evidence_reference=?,
                       qr_provider_code=?, qr_provider_configuration_version=?,
                       qr_provider_evidence_reference=?,
                       issued_at=?, expires_at=?, version=version+1, updated_at=?
                 WHERE tenant_id=? AND owner_user_id=? AND pass_id=?
                   AND lifecycle_state='ACTIVE' AND version=? AND expires_at>?
                """, credentialHash, credentialLastFour, pairingCodeHash, pairingCodeSalt,
                preview.nfcEnabled(), preview.qrEnabled(), preview.nfcProviderCode(),
                preview.nfcProviderConfigurationVersion(), preview.nfcProviderEvidenceReference(),
                preview.qrProviderCode(), preview.qrProviderConfigurationVersion(),
                preview.qrProviderEvidenceReference(),
                now, expiresAt, now, preview.tenantId(), preview.actorUserId(), preview.passId(),
                preview.expectedPassVersion(), now) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordPairingFailure(
            long tenantId, UUID passId, long expectedVersion, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_navigation_access_passes
                   SET pairing_attempt_count=pairing_attempt_count+1,
                       pairing_locked_at=CASE WHEN pairing_attempt_count+1>=5 THEN ? ELSE NULL END,
                       pairing_code_hash=CASE WHEN pairing_attempt_count+1>=5 THEN NULL
                                              ELSE pairing_code_hash END,
                       pairing_code_salt=CASE WHEN pairing_attempt_count+1>=5 THEN NULL
                                              ELSE pairing_code_salt END,
                       qr_enabled=CASE WHEN pairing_attempt_count+1>=5 THEN FALSE
                                       ELSE qr_enabled END,
                       qr_provider_code=CASE WHEN pairing_attempt_count+1>=5 THEN NULL
                                             ELSE qr_provider_code END,
                       qr_provider_configuration_version=
                           CASE WHEN pairing_attempt_count+1>=5 THEN NULL
                                ELSE qr_provider_configuration_version END,
                       qr_provider_evidence_reference=
                           CASE WHEN pairing_attempt_count+1>=5 THEN NULL
                                ELSE qr_provider_evidence_reference END,
                       version=version+1, updated_at=?
                 WHERE tenant_id=? AND pass_id=? AND lifecycle_state='ACTIVE'
                   AND version=? AND expires_at>? AND qr_enabled
                   AND pairing_consumed_at IS NULL AND pairing_locked_at IS NULL
                """, now, now, tenantId, passId, expectedVersion, now) == 1;
    }

    public boolean consumePairing(
            long tenantId,
            UUID passId,
            UUID deviceId,
            long expectedVersion,
            String expectedHash,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_navigation_access_passes
                   SET pairing_code_hash=NULL, pairing_code_salt=NULL, qr_enabled=FALSE,
                       qr_provider_code=NULL, qr_provider_configuration_version=NULL,
                       qr_provider_evidence_reference=NULL,
                       pairing_consumed_at=?, pairing_device_id=?, version=version+1, updated_at=?
                 WHERE tenant_id=? AND pass_id=? AND lifecycle_state='ACTIVE'
                   AND version=? AND expires_at>? AND qr_enabled
                   AND pairing_code_hash=? AND pairing_attempt_count<5
                   AND pairing_consumed_at IS NULL AND pairing_locked_at IS NULL
                """, now, deviceId, now, tenantId, passId, expectedVersion, now,
                expectedHash) == 1;
    }

    public Optional<AccessPassPairingReceiptRow> pairingReceiptByKey(
            long tenantId, UUID deviceId, String idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_access_pass_pairing_receipts
                 WHERE tenant_id=? AND device_id=? AND idempotency_key=?
                """, this::pairingReceiptRow, tenantId, deviceId, idempotencyKey)
                .stream().findFirst();
    }

    public void insertPairingReceipt(AccessPassPairingReceiptRow row) {
        jdbc.update("""
                INSERT INTO wp_navigation_access_pass_pairing_receipts (
                    pairing_receipt_id, tenant_id, pass_id, device_id, idempotency_key,
                    correlation_id, request_fingerprint, site_id, floor_id, resource_id,
                    pass_version, expires_at, paired_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.pairingReceiptId(), row.tenantId(), row.passId(), row.deviceId(),
                row.idempotencyKey(), row.correlationId(), row.requestFingerprint(), row.siteId(),
                row.floorId(), row.resourceId(), row.passVersion(), row.expiresAt(),
                row.pairedAt());
    }

    public boolean revoke(AccessPassPreviewRow preview, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_navigation_access_passes
                   SET lifecycle_state='REVOKED', revoked_at=?, version=version+1, updated_at=?
                 WHERE tenant_id=? AND owner_user_id=? AND pass_id=?
                   AND lifecycle_state='ACTIVE' AND version=?
                """, now, now, preview.tenantId(), preview.actorUserId(), preview.passId(),
                preview.expectedPassVersion()) == 1;
    }

    public Optional<AccessPassCommandRow> commandByKey(
            long tenantId, long actorId, String idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_access_pass_commands
                 WHERE tenant_id=? AND actor_user_id=? AND idempotency_key=?
                """, this::commandRow, tenantId, actorId, idempotencyKey).stream().findFirst();
    }

    public Optional<AccessPassCommandRow> command(
            long tenantId, long actorId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_access_pass_commands
                 WHERE tenant_id=? AND actor_user_id=? AND command_id=?
                """, this::commandRow, tenantId, actorId, commandId).stream().findFirst();
    }

    public void insertCommand(AccessPassCommandRow row) {
        jdbc.update("""
                INSERT INTO wp_navigation_access_pass_commands (
                    command_id, tenant_id, actor_user_id, pass_id, preview_id, command_type,
                    idempotency_key, request_fingerprint, command_state, reason, result_code,
                    correlation_id, version, accepted_at, completed_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.commandId(), row.tenantId(), row.actorUserId(), row.passId(),
                row.previewId(), row.commandType().name(), row.idempotencyKey(),
                row.requestFingerprint(), row.state().name(), row.reason(), row.resultCode(),
                row.correlationId(), row.version(), row.acceptedAt(), row.completedAt(),
                row.updatedAt());
    }

    public UUID audit(
            long tenantId,
            long actorId,
            String action,
            UUID passId,
            String correlationId,
            AccessPassCommandType commandType,
            AccessPassState state,
            OffsetDateTime occurredAt) {
        UUID auditId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_navigation_audit_events (
                    audit_event_id, tenant_id, actor_user_id, action, resource_type,
                    resource_id, correlation_id, snapshot, occurred_at)
                VALUES (?, ?, ?, ?, 'ACCESS_PASS', ?, ?, ?::jsonb, ?)
                """, auditId, tenantId, actorId, action, passId, correlationId,
                json(java.util.Map.of("commandType", commandType.name(), "state", state.name())),
                occurredAt);
        return auditId;
    }

    public UUID auditPairing(
            long tenantId,
            long ownerUserId,
            UUID passId,
            UUID deviceId,
            UUID pairingReceiptId,
            String correlationId,
            OffsetDateTime occurredAt) {
        UUID auditId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_navigation_audit_events (
                    audit_event_id, tenant_id, actor_user_id, action, resource_type,
                    resource_id, correlation_id, snapshot, occurred_at)
                VALUES (?, ?, ?, 'navigation.access-pass.paired', 'ACCESS_PASS', ?, ?,
                        ?::jsonb, ?)
                """, auditId, tenantId, ownerUserId, passId, correlationId,
                json(java.util.Map.of(
                        "deviceId", deviceId.toString(),
                        "pairingReceiptId", pairingReceiptId.toString(),
                        "credentialMaterialPersisted", false)), occurredAt);
        return auditId;
    }

    public List<AccessPassAuditEvent> auditEvents(
            long tenantId, long actorId, UUID passId, int limit) {
        return jdbc.query("""
                SELECT audit_event_id, action, resource_id, correlation_id, occurred_at
                  FROM wp_navigation_audit_events
                 WHERE tenant_id=? AND actor_user_id=? AND resource_type='ACCESS_PASS'
                   AND (?::uuid IS NULL OR resource_id=?::uuid)
                 ORDER BY occurred_at DESC, audit_event_id DESC
                 LIMIT ?
                """, (rs, row) -> new AccessPassAuditEvent(
                rs.getObject("audit_event_id", UUID.class), rs.getString("action"),
                rs.getObject("resource_id", UUID.class), rs.getString("correlation_id"),
                rs.getObject("occurred_at", OffsetDateTime.class)),
                tenantId, actorId, passId, passId, limit);
    }

    private AccessPassRow passRow(ResultSet rs, int row) throws SQLException {
        Long nfcProviderVersion = rs.getObject("nfc_provider_configuration_version") == null
                ? null : rs.getLong("nfc_provider_configuration_version");
        Long qrProviderVersion = rs.getObject("qr_provider_configuration_version") == null
                ? null : rs.getLong("qr_provider_configuration_version");
        return new AccessPassRow(
                rs.getObject("pass_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("owner_user_id"), rs.getObject("source_booking_id", UUID.class),
                rs.getObject("site_id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getObject("resource_id", UUID.class),
                rs.getObject("destination_poi_id", UUID.class),
                AccessPassState.valueOf(rs.getString("lifecycle_state")),
                rs.getString("credential_sha256"), rs.getString("credential_last_four"),
                rs.getString("pairing_code_hash"), rs.getString("pairing_code_salt"),
                rs.getInt("pairing_attempt_count"),
                rs.getObject("pairing_locked_at", OffsetDateTime.class),
                rs.getObject("pairing_consumed_at", OffsetDateTime.class),
                rs.getObject("pairing_device_id", UUID.class),
                rs.getBoolean("nfc_enabled"), rs.getBoolean("qr_enabled"),
                rs.getString("nfc_provider_code"), nfcProviderVersion,
                rs.getString("nfc_provider_evidence_reference"),
                rs.getString("qr_provider_code"), qrProviderVersion,
                rs.getString("qr_provider_evidence_reference"), rs.getLong("version"),
                rs.getObject("issued_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private AccessPassPairingReceiptRow pairingReceiptRow(ResultSet rs, int row)
            throws SQLException {
        return new AccessPassPairingReceiptRow(
                rs.getObject("pairing_receipt_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("pass_id", UUID.class), rs.getObject("device_id", UUID.class),
                rs.getString("idempotency_key"), rs.getString("correlation_id"),
                rs.getString("request_fingerprint"),
                rs.getObject("site_id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getObject("resource_id", UUID.class), rs.getLong("pass_version"),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("paired_at", OffsetDateTime.class));
    }

    private AccessPassPreviewRow previewRow(ResultSet rs, int row) throws SQLException {
        Long nfcProviderVersion = rs.getObject("nfc_provider_configuration_version") == null
                ? null : rs.getLong("nfc_provider_configuration_version");
        Long qrProviderVersion = rs.getObject("qr_provider_configuration_version") == null
                ? null : rs.getLong("qr_provider_configuration_version");
        return new AccessPassPreviewRow(
                rs.getObject("preview_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"), rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"),
                AccessPassCommandType.valueOf(rs.getString("command_type")),
                rs.getObject("pass_id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getObject("floor_id", UUID.class), rs.getObject("resource_id", UUID.class),
                rs.getObject("destination_poi_id", UUID.class),
                rs.getObject("source_booking_id", UUID.class), rs.getLong("expected_pass_version"),
                rs.getBoolean("nfc_enabled"), rs.getBoolean("qr_enabled"),
                rs.getString("nfc_provider_code"), nfcProviderVersion,
                rs.getString("nfc_provider_evidence_reference"),
                rs.getString("qr_provider_code"), qrProviderVersion,
                rs.getString("qr_provider_evidence_reference"), rs.getBoolean("eligible"),
                strings(rs.getString("impact")), strings(rs.getString("limitations")),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private AccessPassCommandRow commandRow(ResultSet rs, int row) throws SQLException {
        return new AccessPassCommandRow(
                rs.getObject("command_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"), rs.getObject("pass_id", UUID.class),
                rs.getObject("preview_id", UUID.class),
                AccessPassCommandType.valueOf(rs.getString("command_type")),
                rs.getString("idempotency_key"), rs.getString("request_fingerprint"),
                AccessPassCommandState.valueOf(rs.getString("command_state")),
                rs.getString("reason"), rs.getString("result_code"),
                rs.getString("correlation_id"), rs.getLong("version"),
                rs.getObject("accepted_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode access-pass metadata.", exception);
        }
    }

    private List<String> strings(String value) throws SQLException {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid access-pass metadata.", exception);
        }
    }
}

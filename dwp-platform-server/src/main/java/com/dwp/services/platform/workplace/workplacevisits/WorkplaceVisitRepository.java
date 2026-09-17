package com.dwp.services.platform.workplace.workplacevisits;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.*;

@Repository
public class WorkplaceVisitRepository extends WorkplaceVisitOperationRepositorySupport {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public WorkplaceVisitRepository(JdbcTemplate jdbc, ObjectMapper json) {
        super(jdbc, json);
        this.jdbc = jdbc;
        this.json = json;
    }

    Optional<ReservationSnapshot> reservation(
            long tenantId, long actorId, ReservationReference reference) {
        if (reference.authority() == ReservationAuthority.WORKPLACE) {
            return jdbc.query("""
                    SELECT b.booking_id, b.version, b.user_id, b.starts_at, b.ends_at,
                           s.site_id, b.booking_status IN ('RESERVED','CHECKED_IN') active
                      FROM wp_bookings b
                      JOIN wp_resources r ON r.tenant_id=b.tenant_id AND r.resource_id=b.resource_id
                      JOIN wp_floors f ON f.tenant_id=r.tenant_id AND f.floor_id=r.floor_id
                      JOIN wp_sites s ON s.tenant_id=f.tenant_id AND s.site_id=f.site_id
                     WHERE b.tenant_id=? AND b.booking_id=? AND b.user_id=?
                    """, (rs, n) -> reservation(rs, ReservationAuthority.WORKPLACE),
                    tenantId, reference.id(), actorId).stream().findFirst();
        }
        return jdbc.query("""
                SELECT e.event_id, e.version, e.organizer_user_id, e.starts_at, e.ends_at,
                       s.site_id, e.status IN ('CONFIRMED','TENTATIVE') active
                  FROM cal_events e
                  JOIN cal_resource_bookings rb
                    ON rb.tenant_id=e.tenant_id AND rb.event_id=e.event_id
                   AND rb.booking_status IN ('PENDING','CONFIRMED')
                  JOIN cal_resources cr
                    ON cr.tenant_id=rb.tenant_id AND cr.resource_id=rb.resource_id
                  JOIN wp_resources wr
                    ON wr.tenant_id=cr.tenant_id AND wr.calendar_resource_id=cr.resource_id
                  JOIN wp_floors f
                    ON f.tenant_id=wr.tenant_id AND f.floor_id=wr.floor_id
                  JOIN wp_sites s
                    ON s.tenant_id=f.tenant_id AND s.site_id=f.site_id
                 WHERE e.tenant_id=? AND e.event_id=? AND e.organizer_user_id=?
                """, (rs, n) -> reservation(rs, ReservationAuthority.CALENDAR),
                tenantId, reference.id(), actorId).stream().findFirst();
    }

    Optional<ReservationSourceState> reservationSource(
            long tenantId, ReservationAuthority authority, UUID reservationId) {
        if (authority == ReservationAuthority.WORKPLACE) {
            return jdbc.query("""
                    SELECT version,booking_status IN ('RESERVED','CHECKED_IN') active
                      FROM wp_bookings WHERE tenant_id=? AND booking_id=?
                    """, (rs, n) -> new ReservationSourceState(
                    rs.getLong(1), rs.getBoolean(2)), tenantId, reservationId)
                    .stream().findFirst();
        }
        return jdbc.query("""
                SELECT version,status IN ('CONFIRMED','TENTATIVE') active
                  FROM cal_events WHERE tenant_id=? AND event_id=?
                """, (rs, n) -> new ReservationSourceState(
                rs.getLong(1), rs.getBoolean(2)), tenantId, reservationId)
                .stream().findFirst();
    }

    Optional<PolicyRow> policy(long tenantId, String visitType) {
        return jdbc.query("""
                SELECT * FROM wp_visit_policies
                 WHERE tenant_id=? AND visit_type=? AND active=TRUE
                """, this::policyRow, tenantId, visitType).stream().findFirst();
    }

    boolean zonesEligible(long tenantId, UUID siteId, String visitType, List<UUID> zoneIds) {
        if (zoneIds.isEmpty()) return false;
        return zoneIds.stream().distinct().allMatch(zoneId -> Boolean.TRUE.equals(
                jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM wp_visit_access_zones
                         WHERE tenant_id=? AND zone_id=? AND site_id=? AND active=TRUE
                           AND jsonb_exists(allowed_visit_types, ?))
                        """, Boolean.class, tenantId, zoneId, siteId, visitType)));
    }

    Optional<ProviderRow> provider(long tenantId, ProviderKind kind) {
        return jdbc.query("""
                SELECT * FROM wp_visit_provider_bindings
                 WHERE tenant_id=? AND provider_kind=? AND active=TRUE
                """, this::providerRow, tenantId, kind.name()).stream().findFirst();
    }

    void insertPreview(long tenantId, long actorId, UUID previewId,
                       VisitPreviewRequest request, PolicyRow policy,
                       ProviderTruth visitor, ProviderTruth access,
                       String guestFingerprint, boolean eligible, List<String> limitations,
                       OffsetDateTime expiresAt, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_visit_previews(
                    preview_id,tenant_id,actor_user_id,reservation_authority,reservation_id,
                    reservation_version,visit_type,site_id,starts_at,ends_at,zone_ids,
                    guest_fingerprint,approval_required,nda_required,
                    identity_verification_required,minimum_collection_fields,
                    visitor_truth_snapshot,access_truth_snapshot,eligible,limitations,
                    expires_at,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?, ?,?::jsonb,?::jsonb,
                       ?::jsonb,?,?::jsonb,?,?)
                """, previewId, tenantId, actorId, request.reservation().authority().name(),
                request.reservation().id(), request.reservation().version(), request.visitType(),
                request.siteId(), request.startsAt(), request.endsAt(), encode(request.zoneIds()),
                guestFingerprint, policy.approvalRequired(), policy.ndaRequired(),
                policy.identityVerificationRequired(), encode(policy.minimumCollectionFields()),
                encode(visitor), encode(access), eligible, encode(limitations), expiresAt, now);
    }

    Optional<PreviewRow> preview(long tenantId, long actorId, UUID previewId) {
        return jdbc.query("""
                SELECT * FROM wp_visit_previews
                 WHERE tenant_id=? AND actor_user_id=? AND preview_id=?
                """, this::previewRow, tenantId, actorId, previewId).stream().findFirst();
    }

    void insertVisit(long tenantId, long actorId, UUID visitId, PreviewRow preview,
                     List<GuestRefInput> guests, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_visits(
                    visit_id,tenant_id,requester_user_id,preview_id,reservation_authority,
                    reservation_id,reservation_version,visit_type,site_id,starts_at,ends_at,
                    visit_state,approval_required,version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,1,?,?)
                """, visitId, tenantId, actorId, preview.previewId(),
                preview.authority().name(), preview.reservationId(), preview.reservationVersion(),
                preview.visitType(), preview.siteId(), preview.startsAt(), preview.endsAt(),
                preview.approvalRequired(), now, now);
        for (GuestRefInput guest : guests) {
            jdbc.update("""
                    INSERT INTO wp_visit_guests(
                        guest_id,tenant_id,visit_id,opaque_guest_ref,masked_label,purpose,
                        field_retention_expires_at,search_token_sha256)
                    VALUES(?,?,?,?,?,?,?::jsonb,?)
                    """, UUID.randomUUID(), tenantId, visitId, guest.opaqueRef(),
                    guest.maskedLabel(), guest.purpose(),
                    encode(guest.fieldRetentionExpiresAt()), sha256(guest.opaqueRef()));
        }
        for (UUID zoneId : preview.zoneIds()) {
            jdbc.update("""
                    INSERT INTO wp_visit_zone_selections(tenant_id,visit_id,zone_id)
                    VALUES(?,?,?)
                    """, tenantId, visitId, zoneId);
        }
    }

    Optional<VisitRow> requesterVisit(long tenantId, long actorId, UUID visitId) {
        return jdbc.query("""
                SELECT * FROM wp_visits
                 WHERE tenant_id=? AND requester_user_id=? AND visit_id=?
                """, this::visitRow, tenantId, actorId, visitId).stream().findFirst();
    }

    Optional<VisitRow> adminVisit(long tenantId, UUID visitId) {
        return jdbc.query("SELECT * FROM wp_visits WHERE tenant_id=? AND visit_id=?",
                this::visitRow, tenantId, visitId).stream().findFirst();
    }

    List<VisitRow> requesterVisits(
            long tenantId, long actorId, ReservationAuthority authority, UUID reservationId) {
        return jdbc.query("""
                SELECT * FROM wp_visits
                 WHERE tenant_id=? AND requester_user_id=?
                   AND (? IS NULL OR reservation_authority=?)
                   AND (? IS NULL OR reservation_id=?)
                 ORDER BY starts_at DESC, visit_id
                 LIMIT 200
                """, this::visitRow, tenantId, actorId,
                authority == null ? null : authority.name(), authority == null ? null : authority.name(),
                reservationId, reservationId);
    }

    List<VisitRow> exceptionVisits(long tenantId, VisitState state) {
        return jdbc.query("""
                SELECT * FROM wp_visits
                 WHERE tenant_id=?
                   AND (? IS NULL OR visit_state=?)
                   AND (visit_state IN
                    ('APPROVAL_PENDING','ACCESS_FAILED','OVERSTAY','RESULT_UNKNOWN')
                    OR (visit_state='INVITED' AND updated_at<CURRENT_TIMESTAMP-INTERVAL '15 minutes'))
                 ORDER BY updated_at, visit_id LIMIT 500
                """, this::visitRow, tenantId, state == null ? null : state.name(),
                state == null ? null : state.name());
    }

    List<GuestRow> guests(long tenantId, UUID visitId) {
        return jdbc.query("""
                SELECT * FROM wp_visit_guests
                 WHERE tenant_id=? AND visit_id=? ORDER BY guest_id
                """, this::guestRow, tenantId, visitId);
    }

    List<UUID> zones(long tenantId, UUID visitId) {
        return jdbc.query("""
                SELECT zone_id FROM wp_visit_zone_selections
                 WHERE tenant_id=? AND visit_id=? ORDER BY zone_id
                """, (rs, n) -> rs.getObject(1, UUID.class), tenantId, visitId);
    }

    List<TimelineItem> timeline(long tenantId, UUID visitId) {
        return jdbc.query("""
                SELECT event_id,event_type,visit_state,detail_code,occurred_at
                  FROM wp_visit_timeline WHERE tenant_id=? AND visit_id=?
                 ORDER BY occurred_at,event_id
                """, (rs, n) -> new TimelineItem(rs.getObject(1, UUID.class), rs.getString(2),
                        VisitState.valueOf(rs.getString(3)), rs.getString(4),
                        rs.getObject(5, OffsetDateTime.class)), tenantId, visitId);
    }

    boolean transition(long tenantId, UUID visitId, long expectedVersion,
                       List<VisitState> from, VisitState to, String evidence,
                       String limitation, OffsetDateTime now) {
        String states = String.join(",", from.stream().map(s -> "'" + s.name() + "'").toList());
        return jdbc.update("""
                UPDATE wp_visits SET visit_state=?,version=version+1,
                       provider_operation_evidence_reference=COALESCE(?,provider_operation_evidence_reference),
                       limitation_code=?,updated_at=?
                 WHERE tenant_id=? AND visit_id=? AND version=? AND visit_state IN ("""
                + states + ")", to.name(), evidence, limitation, now,
                tenantId, visitId, expectedVersion) == 1;
    }

    void timeline(long tenantId, UUID visitId, long actorId, String eventType,
                  VisitState state, String detailCode, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_visit_timeline(
                    event_id,tenant_id,visit_id,event_type,visit_state,detail_code,
                    actor_user_id,occurred_at) VALUES(?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), tenantId, visitId, eventType, state.name(),
                detailCode, actorId, now);
    }

    Optional<CommandRow> command(long tenantId, long actorId, String scope, String key) {
        return jdbc.query("""
                SELECT * FROM wp_visit_commands
                 WHERE tenant_id=? AND actor_user_id=? AND command_scope=? AND idempotency_key=?
                """, this::commandRow, tenantId, actorId, scope, key).stream().findFirst();
    }

    CommandRow insertCommand(long tenantId, long actorId, UUID visitId, String scope,
                             String key, String fingerprint, CommandState state,
                             String correlationId, OffsetDateTime now) {
        UUID commandId = UUID.randomUUID();
        String href = "/v1/workplace/visits/" + visitId;
        jdbc.update("""
                INSERT INTO wp_visit_commands(
                    command_id,tenant_id,actor_user_id,visit_id,command_scope,idempotency_key,
                    request_fingerprint,command_state,status_href,correlation_id,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, commandId, tenantId, actorId, visitId, scope, key, fingerprint,
                state.name(), href, correlationId, now);
        return new CommandRow(commandId, visitId, fingerprint, state, href, correlationId, now);
    }

    Optional<ManagementCommandRow> managementCommand(
            long tenantId, long actorId, String scope, String key) {
        return jdbc.query("""
                SELECT * FROM wp_visit_management_commands
                 WHERE tenant_id=? AND actor_user_id=? AND command_scope=? AND idempotency_key=?
                """, (rs, n) -> new ManagementCommandRow(
                        rs.getObject("command_id", UUID.class),
                        rs.getString("request_fingerprint"), rs.getString("resource_type"),
                        rs.getObject("resource_id", UUID.class), rs.getLong("resource_version"),
                        rs.getString("correlation_id"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                tenantId, actorId, scope, key).stream().findFirst();
    }

    ManagementCommandRow insertManagementCommand(
            long tenantId, long actorId, String scope, String key, String fingerprint,
            String resourceType, UUID resourceId, long resourceVersion,
            String correlationId, OffsetDateTime now) {
        UUID commandId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_visit_management_commands(
                    command_id,tenant_id,actor_user_id,command_scope,idempotency_key,
                    request_fingerprint,resource_type,resource_id,resource_version,
                    correlation_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, commandId, tenantId, actorId, scope, key, fingerprint, resourceType,
                resourceId, resourceVersion, correlationId, now);
        return new ManagementCommandRow(commandId, fingerprint, resourceType, resourceId,
                resourceVersion, correlationId, now);
    }


    void audit(long tenantId, UUID visitId, long actorId, String action,
               String correlationId, Map<String, ?> snapshot, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_visit_audit_events(
                    audit_event_id,tenant_id,visit_id,actor_user_id,action,
                    correlation_id,snapshot,occurred_at)
                VALUES(?,?,?,?,?,?,?::jsonb,?)
                """, UUID.randomUUID(), tenantId, visitId, actorId, action,
                correlationId, encode(snapshot), now);
    }

    int purgeExpiredGuestReferences(long tenantId, OffsetDateTime now, int limit) {
        return jdbc.update("""
                WITH expired AS (
                    SELECT guest_id FROM wp_visit_guests
                     WHERE tenant_id=?
                       AND (opaque_guest_ref IS NOT NULL OR search_token_sha256 IS NOT NULL)
                       AND NOT EXISTS(
                           SELECT 1 FROM jsonb_each_text(field_retention_expires_at) e
                            WHERE e.value::timestamptz > ?)
                     ORDER BY guest_id LIMIT ? FOR UPDATE SKIP LOCKED)
                UPDATE wp_visit_guests guest SET opaque_guest_ref=NULL,search_token_sha256=NULL
                 FROM expired WHERE guest.guest_id=expired.guest_id
                """, tenantId, now, Math.max(1, Math.min(limit, 500)));
    }

    List<UUID> markOverstays(long tenantId, OffsetDateTime now, int limit) {
        return jdbc.query("""
                WITH overdue AS (
                    SELECT visit_id FROM wp_visits
                     WHERE tenant_id=? AND visit_state='ARRIVED' AND ends_at<?
                     ORDER BY ends_at,visit_id LIMIT ? FOR UPDATE SKIP LOCKED)
                UPDATE wp_visits visit SET visit_state='OVERSTAY',version=version+1,updated_at=?
                 FROM overdue WHERE visit.visit_id=overdue.visit_id
                RETURNING visit.visit_id
                """, (rs, n) -> rs.getObject(1, UUID.class), tenantId, now,
                Math.max(1, Math.min(limit, 500)), now);
    }

    List<PolicyRow> policies(long tenantId) {
        return jdbc.query("SELECT * FROM wp_visit_policies WHERE tenant_id=? ORDER BY visit_type",
                this::policyRow, tenantId);
    }

    Optional<PolicyRow> policyById(long tenantId, UUID id) {
        return jdbc.query("SELECT * FROM wp_visit_policies WHERE tenant_id=? AND policy_id=?",
                this::policyRow, tenantId, id).stream().findFirst();
    }

    UUID createPolicy(long tenantId, VisitPolicyRequest request, OffsetDateTime now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_visit_policies(
                    policy_id,tenant_id,visit_type,approval_required,nda_required,
                    identity_verification_required,allowed_from,allowed_until,
                    minimum_collection_fields,retention_days,active,version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?::jsonb,?,?,1,?,?)
                """, id, tenantId, request.visitType(), request.approvalRequired(),
                request.ndaRequired(), request.identityVerificationRequired(),
                request.allowedFrom(), request.allowedUntil(),
                encode(request.minimumCollectionFields()), request.retentionDays(),
                request.active(), now, now);
        return id;
    }

    boolean updatePolicy(long tenantId, UUID id, VisitPolicyRequest request, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_visit_policies SET visit_type=?,approval_required=?,nda_required=?,
                       identity_verification_required=?,allowed_from=?,allowed_until=?,
                       minimum_collection_fields=?::jsonb,retention_days=?,active=?,
                       version=version+1,updated_at=?
                 WHERE tenant_id=? AND policy_id=? AND version=?
                """, request.visitType(), request.approvalRequired(), request.ndaRequired(),
                request.identityVerificationRequired(), request.allowedFrom(),
                request.allowedUntil(), encode(request.minimumCollectionFields()),
                request.retentionDays(), request.active(), now, tenantId, id,
                request.expectedVersion()) == 1;
    }

    long policyImpact(long tenantId, UUID policyId) {
        return Optional.ofNullable(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_visits v JOIN wp_visit_policies p
                  ON p.tenant_id=v.tenant_id AND p.visit_type=v.visit_type
                 WHERE p.tenant_id=? AND p.policy_id=? AND v.starts_at>CURRENT_TIMESTAMP
                   AND v.visit_state NOT IN ('CHECKED_OUT','CANCELLED','REJECTED')
                """, Long.class, tenantId, policyId)).orElse(0L);
    }

    List<ZoneRow> accessZones(long tenantId) {
        return jdbc.query("SELECT * FROM wp_visit_access_zones WHERE tenant_id=? ORDER BY zone_code",
                this::zoneRow, tenantId);
    }

    Optional<ZoneRow> zone(long tenantId, UUID id) {
        return jdbc.query("SELECT * FROM wp_visit_access_zones WHERE tenant_id=? AND zone_id=?",
                this::zoneRow, tenantId, id).stream().findFirst();
    }

    UUID createZone(long tenantId, AccessZoneRequest request, OffsetDateTime now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_visit_access_zones(
                    zone_id,tenant_id,site_id,zone_code,name,access_level,
                    provider_mapping_reference,allowed_visit_types,active,version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?::jsonb,?,1,?,?)
                """, id, tenantId, request.siteId(), request.zoneCode(), request.name(),
                request.accessLevel(), request.providerMappingReference(),
                encode(request.allowedVisitTypes()), request.active(), now, now);
        return id;
    }

    boolean updateZone(long tenantId, UUID id, AccessZoneRequest request, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_visit_access_zones SET site_id=?,zone_code=?,name=?,access_level=?,
                       provider_mapping_reference=?,allowed_visit_types=?::jsonb,active=?,
                       version=version+1,updated_at=?
                 WHERE tenant_id=? AND zone_id=? AND version=?
                """, request.siteId(), request.zoneCode(), request.name(), request.accessLevel(),
                request.providerMappingReference(), encode(request.allowedVisitTypes()),
                request.active(), now, tenantId, id, request.expectedVersion()) == 1;
    }

    List<ProviderRow> providers(long tenantId) {
        return jdbc.query("SELECT * FROM wp_visit_provider_bindings WHERE tenant_id=? ORDER BY provider_kind",
                this::providerRow, tenantId);
    }

    Optional<ProviderRow> providerById(long tenantId, UUID id) {
        return jdbc.query("SELECT * FROM wp_visit_provider_bindings WHERE tenant_id=? AND binding_id=?",
                this::providerRow, tenantId, id).stream().findFirst();
    }

    UUID createProvider(long tenantId, ProviderBindingRequest request, OffsetDateTime now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_visit_provider_bindings(
                    binding_id,tenant_id,provider_kind,provider_code,configuration_version,
                    manual_owner,manual_procedure,active,version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,1,?,?)
                """, id, tenantId, request.kind().name(), request.providerCode(),
                request.configurationVersion(), request.manualOwner(), request.manualProcedure(),
                request.active(), now, now);
        return id;
    }

    boolean updateProvider(long tenantId, UUID id, ProviderBindingRequest request, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_visit_provider_bindings SET provider_kind=?,provider_code=?,
                       configuration_version=?,observed_configuration_version=NULL,
                       reported_state=NULL,evidence_reference=NULL,last_success_at=NULL,
                       source_at=NULL,received_at=NULL,manual_owner=?,manual_procedure=?,
                       active=?,version=version+1,updated_at=?
                 WHERE tenant_id=? AND binding_id=? AND version=?
                """, request.kind().name(), request.providerCode(), request.configurationVersion(),
                request.manualOwner(), request.manualProcedure(), request.active(), now,
                tenantId, id, request.expectedVersion()) == 1;
    }

    boolean recordProviderEvidence(long tenantId, UUID id, ProviderEvidenceRequest request,
                                   OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_visit_provider_bindings SET observed_configuration_version=?,
                       reported_state=?,evidence_reference=?,source_at=?,received_at=?,
                       last_success_at=?,version=version+1,updated_at=?
                 WHERE tenant_id=? AND binding_id=? AND version=?
                """, request.observedConfigurationVersion(), request.reportedState().name(),
                request.evidenceReference(), request.sourceAt(), request.receivedAt(),
                request.lastSuccessAt(), now, tenantId, id, request.expectedVersion()) == 1;
    }

    List<DeviceRow> devices(long tenantId) {
        return jdbc.query("SELECT * FROM wp_visit_kiosk_devices WHERE tenant_id=? ORDER BY created_at",
                this::deviceRow, tenantId);
    }

    Optional<DeviceRow> device(long tenantId, UUID id) {
        return jdbc.query("SELECT * FROM wp_visit_kiosk_devices WHERE tenant_id=? AND device_id=?",
                this::deviceRow, tenantId, id).stream().findFirst();
    }

    Optional<DeviceRow> deviceByIdentity(long tenantId, String identityHash) {
        return jdbc.query("""
                SELECT * FROM wp_visit_kiosk_devices
                 WHERE tenant_id=? AND device_identity_sha256=?
                """, this::deviceRow, tenantId, identityHash).stream().findFirst();
    }

    UUID createDevice(long tenantId, KioskDeviceRequest request, OffsetDateTime now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_visit_kiosk_devices(
                    device_id,tenant_id,device_identity_sha256,site_id,policy_id,
                    privacy_notice_version,active,version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,1,?,?)
                """, id, tenantId, request.deviceIdentitySha256(), request.siteId(),
                request.policyId(), request.privacyNoticeVersion(), request.active(), now, now);
        return id;
    }

    boolean updateDevice(long tenantId, UUID id, KioskDeviceRequest request, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_visit_kiosk_devices SET device_identity_sha256=?,site_id=?,policy_id=?,
                       privacy_notice_version=?,privacy_notice_accepted=FALSE,active=?,
                       version=version+1,updated_at=?
                 WHERE tenant_id=? AND device_id=? AND version=?
                """, request.deviceIdentitySha256(), request.siteId(), request.policyId(),
                request.privacyNoticeVersion(), request.active(), now, tenantId, id,
                request.expectedVersion()) == 1;
    }

    boolean heartbeat(long tenantId, UUID id, KioskHeartbeatRequest request, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_visit_kiosk_devices SET privacy_notice_accepted=?,last_heartbeat_at=?,
                       help_requested=FALSE,version=version+1,updated_at=?
                 WHERE tenant_id=? AND device_id=? AND version=?
                   AND privacy_notice_version=?
                """, request.privacyNoticeAccepted(), request.observedAt(), now,
                tenantId, id, request.expectedVersion(), request.privacyNoticeVersion()) == 1;
    }

    boolean requestHelp(long tenantId, UUID id, long expectedVersion, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_visit_kiosk_devices SET help_requested=TRUE,version=version+1,updated_at=?
                 WHERE tenant_id=? AND device_id=? AND version=? AND active=TRUE
                """, now, tenantId, id, expectedVersion) == 1;
    }

    private ReservationSnapshot reservation(ResultSet rs, ReservationAuthority authority)
            throws SQLException {
        return new ReservationSnapshot(authority, rs.getObject(1, UUID.class), rs.getLong(2),
                rs.getLong(3), rs.getObject(4, OffsetDateTime.class),
                rs.getObject(5, OffsetDateTime.class), rs.getObject(6, UUID.class),
                rs.getBoolean(7));
    }

    private PreviewRow previewRow(ResultSet rs, int n) throws SQLException {
        return new PreviewRow(rs.getObject("preview_id", UUID.class),
                ReservationAuthority.valueOf(rs.getString("reservation_authority")),
                rs.getObject("reservation_id", UUID.class), rs.getLong("reservation_version"),
                rs.getString("visit_type"), rs.getObject("site_id", UUID.class),
                rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("ends_at", OffsetDateTime.class),
                decode(rs.getString("zone_ids"), new TypeReference<>() {}),
                rs.getString("guest_fingerprint"),
                rs.getBoolean("approval_required"), rs.getBoolean("eligible"),
                rs.getLong("version"), rs.getObject("expires_at", OffsetDateTime.class));
    }

    private VisitRow visitRow(ResultSet rs, int n) throws SQLException {
        return new VisitRow(rs.getObject("visit_id", UUID.class), rs.getLong("requester_user_id"),
                ReservationAuthority.valueOf(rs.getString("reservation_authority")),
                rs.getObject("reservation_id", UUID.class), rs.getLong("reservation_version"),
                rs.getString("visit_type"), rs.getObject("site_id", UUID.class),
                rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("ends_at", OffsetDateTime.class),
                VisitState.valueOf(rs.getString("visit_state")),
                rs.getBoolean("approval_required"), rs.getLong("version"),
                rs.getString("provider_operation_evidence_reference"),
                rs.getString("limitation_code"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private GuestRow guestRow(ResultSet rs, int n) throws SQLException {
        return new GuestRow(rs.getString("opaque_guest_ref"), rs.getString("masked_label"),
                rs.getString("purpose"), decode(rs.getString("field_retention_expires_at"),
                new TypeReference<>() {}));
    }

    private PolicyRow policyRow(ResultSet rs, int n) throws SQLException {
        return new PolicyRow(rs.getObject("policy_id", UUID.class), rs.getString("visit_type"),
                rs.getBoolean("approval_required"), rs.getBoolean("nda_required"),
                rs.getBoolean("identity_verification_required"), rs.getTime("allowed_from").toLocalTime(),
                rs.getTime("allowed_until").toLocalTime(), decode(rs.getString("minimum_collection_fields"),
                new TypeReference<>() {}), rs.getInt("retention_days"), rs.getBoolean("active"),
                rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private ZoneRow zoneRow(ResultSet rs, int n) throws SQLException {
        return new ZoneRow(rs.getObject("zone_id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getString("zone_code"), rs.getString("name"), rs.getString("access_level"),
                rs.getString("provider_mapping_reference"), decode(rs.getString("allowed_visit_types"),
                new TypeReference<>() {}), rs.getBoolean("active"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private ProviderRow providerRow(ResultSet rs, int n) throws SQLException {
        return new ProviderRow(rs.getObject("binding_id", UUID.class),
                ProviderKind.valueOf(rs.getString("provider_kind")), rs.getString("provider_code"),
                rs.getLong("configuration_version"), nullableLong(rs, "observed_configuration_version"),
                rs.getString("reported_state"), rs.getString("evidence_reference"),
                rs.getObject("last_success_at", OffsetDateTime.class),
                rs.getObject("source_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class), rs.getString("manual_owner"),
                rs.getString("manual_procedure"), rs.getBoolean("active"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private DeviceRow deviceRow(ResultSet rs, int n) throws SQLException {
        return new DeviceRow(rs.getObject("device_id", UUID.class),
                rs.getString("device_identity_sha256"),
                rs.getObject("site_id", UUID.class), rs.getObject("policy_id", UUID.class),
                rs.getString("privacy_notice_version"), rs.getBoolean("privacy_notice_accepted"),
                rs.getObject("last_heartbeat_at", OffsetDateTime.class),
                rs.getBoolean("help_requested"), rs.getBoolean("active"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private CommandRow commandRow(ResultSet rs, int n) throws SQLException {
        return new CommandRow(rs.getObject("command_id", UUID.class),
                rs.getObject("visit_id", UUID.class), rs.getString("request_fingerprint"),
                CommandState.valueOf(rs.getString("command_state")), rs.getString("status_href"),
                rs.getString("correlation_id"), rs.getObject("created_at", OffsetDateTime.class));
    }


    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid JSON value", e); }
    }

    private <T> T decode(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid persisted JSON", e); }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record PreviewRow(UUID previewId, ReservationAuthority authority, UUID reservationId,
                      long reservationVersion, String visitType, UUID siteId,
                      OffsetDateTime startsAt, OffsetDateTime endsAt, List<UUID> zoneIds,
                      String guestFingerprint, boolean approvalRequired, boolean eligible,
                      long version, OffsetDateTime expiresAt) { }
    record VisitRow(UUID visitId, long requesterUserId, ReservationAuthority authority,
                    UUID reservationId, long reservationVersion, String visitType, UUID siteId,
                    OffsetDateTime startsAt, OffsetDateTime endsAt, VisitState state,
                    boolean approvalRequired, long version, String evidenceReference,
                    String limitationCode, OffsetDateTime updatedAt) { }
    record GuestRow(String opaqueRef, String maskedLabel, String purpose,
                    Map<String, OffsetDateTime> retention) { }
    record CommandRow(UUID commandId, UUID visitId, String fingerprint, CommandState state,
                      String statusHref, String correlationId, OffsetDateTime createdAt) { }
    record ManagementCommandRow(UUID commandId, String fingerprint, String resourceType,
                                UUID resourceId, long resourceVersion, String correlationId,
                                OffsetDateTime createdAt) { }
    record PolicyRow(UUID id, String visitType, boolean approvalRequired, boolean ndaRequired,
                     boolean identityVerificationRequired, java.time.LocalTime allowedFrom,
                     java.time.LocalTime allowedUntil, List<String> minimumCollectionFields,
                     int retentionDays, boolean active, long version, OffsetDateTime updatedAt) { }
    record ZoneRow(UUID id, UUID siteId, String code, String name, String accessLevel,
                   String providerMappingReference, List<String> allowedVisitTypes,
                   boolean active, long version, OffsetDateTime updatedAt) { }
    record ProviderRow(UUID id, ProviderKind kind, String providerCode,
                       long configurationVersion, Long observedConfigurationVersion,
                       String reportedState, String evidenceReference, OffsetDateTime lastSuccessAt,
                       OffsetDateTime sourceAt, OffsetDateTime receivedAt, String manualOwner,
                       String manualProcedure, boolean active, long version, OffsetDateTime updatedAt) { }
    record DeviceRow(UUID id, String identitySha256, UUID siteId, UUID policyId,
                     String privacyNoticeVersion,
                     boolean privacyNoticeAccepted, OffsetDateTime lastHeartbeatAt,
                     boolean helpRequested, boolean active, long version, OffsetDateTime updatedAt) { }
    record ReservationSourceState(long version, boolean active) { }
}

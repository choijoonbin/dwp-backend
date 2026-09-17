package com.dwp.services.platform.workplace.safetyoperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;
@Repository
public class SafetyIncidentRepository extends SafetyRepositorySupport {
    public SafetyIncidentRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper);
    }
    void insertPreview(long tenantId, long actorId, UUID previewId, UUID commandId,
                       ActivationPreviewRequest request, UUID snapshotId,
                       boolean eligible, List<String> limitations,
                       OffsetDateTime expiresAt, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_safety_activation_previews(
                    activation_preview_id,tenant_id,command_id,actor_user_id,incident_type,severity,
                    site_id,floor_ids,zone_ids,message,safety_action,assembly_point,channels,
                    excluded_subject_keys,audience_snapshot_id,eligible,limitations,expires_at,created_at)
                VALUES(?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?::jsonb,?::jsonb,?,?,?::jsonb,?,?)
                """, previewId, tenantId, commandId, actorId, request.incidentType(),
                request.severity().name(), request.siteId(), json(request.floorIds()),
                json(request.zoneIds()), request.message(), request.safetyAction(),
                request.assemblyPoint(), json(request.channels()),
                json(request.excludedSubjectKeys()), snapshotId, eligible,
                json(limitations), expiresAt, now);
    }
    Optional<PreviewRow> previewByCommand(long tenantId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_activation_previews
                 WHERE tenant_id=? AND command_id=?
                """, this::previewRow, tenantId, commandId).stream().findFirst();
    }
    Optional<PreviewRow> preview(long tenantId, long actorId, UUID previewId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_activation_previews
                 WHERE tenant_id=? AND actor_user_id=? AND activation_preview_id=?
                """, this::previewRow, tenantId, actorId, previewId).stream().findFirst();
    }
    Optional<PreviewRow> preview(long tenantId, UUID previewId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_activation_previews
                 WHERE tenant_id=? AND activation_preview_id=?
                """, this::previewRow, tenantId, previewId).stream().findFirst();
    }
    void insertIncident(long tenantId, long actorId, UUID incidentId, String incidentNumber,
                        PreviewRow preview, UUID snapshotId, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_safety_incidents(
                    incident_id,tenant_id,activation_preview_id,incident_number,incident_type,
                    severity,incident_state,site_id,floor_ids,zone_ids,message,safety_action,
                    assembly_point,channels,audience_snapshot_id,activated_at,activated_by,
                    version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,'ACTIVE',?,?::jsonb,?::jsonb,?,?,?,?::jsonb,?, ?,?,1,?,?)
                """, incidentId, tenantId, preview.id(), incidentNumber, preview.incidentType(),
                preview.severity().name(), preview.siteId(), json(preview.floorIds()),
                json(preview.zoneIds()), preview.message(), preview.safetyAction(),
                preview.assemblyPoint(), json(preview.channels()), snapshotId,
                now, actorId, now, now);
    }
    Optional<IncidentRow> incident(long tenantId, UUID incidentId) {
        return jdbc.query("SELECT * FROM wp_safety_incidents WHERE tenant_id=? AND incident_id=?",
                this::incidentRow, tenantId, incidentId).stream().findFirst();
    }
    List<IncidentRow> incidents(long tenantId, IncidentState state) {
        return jdbc.query("""
                SELECT * FROM wp_safety_incidents
                 WHERE tenant_id=? AND (? IS NULL OR incident_state=?)
                 ORDER BY activated_at DESC,incident_id LIMIT 200
                """, this::incidentRow, tenantId,
                state == null ? null : state.name(), state == null ? null : state.name());
    }
    List<IncidentRow> activeForUser(long tenantId, long userId) {
        return jdbc.query("""
                SELECT i.* FROM wp_safety_incidents i
                 WHERE i.tenant_id=? AND i.incident_state IN ('ACTIVE','CLOSURE_PENDING')
                   AND EXISTS(SELECT 1 FROM wp_safety_audience_members m
                     WHERE m.tenant_id=i.tenant_id
                       AND m.audience_snapshot_id=i.audience_snapshot_id
                       AND m.subject_user_id=? AND m.included=TRUE)
                 ORDER BY i.severity DESC,i.activated_at DESC
                """, this::incidentRow, tenantId, userId);
    }
    Optional<IncidentRow> incidentForUser(long tenantId, long userId, UUID incidentId) {
        return jdbc.query("""
                SELECT i.* FROM wp_safety_incidents i
                 WHERE i.tenant_id=? AND i.incident_id=?
                   AND EXISTS(SELECT 1 FROM wp_safety_audience_members m
                     WHERE m.tenant_id=i.tenant_id
                       AND m.audience_snapshot_id=i.audience_snapshot_id
                       AND m.subject_user_id=? AND m.included=TRUE)
                """, this::incidentRow, tenantId, incidentId, userId).stream().findFirst();
    }
    Optional<CommandRow> command(
            long tenantId, long actorId, String type, String idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM wp_safety_commands
                 WHERE tenant_id=? AND actor_user_id=? AND command_type=? AND idempotency_key=?
                """, this::commandRow, tenantId, actorId, type, idempotencyKey)
                .stream().findFirst();
    }
    void lockCommandKey(long tenantId, long actorId, String type, String idempotencyKey) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", result -> {
            if (result.next()) result.getObject(1);
            return null;
        }, "safety:" + tenantId + ":" + actorId + ":" + type + ":" + idempotencyKey);
    }
    Optional<CommandRow> command(long tenantId, UUID incidentId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_commands
                 WHERE tenant_id=? AND incident_id=? AND command_id=?
                """, this::commandRow, tenantId, incidentId, commandId).stream().findFirst();
    }
    Optional<CommandRow> command(long tenantId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_commands WHERE tenant_id=? AND command_id=?
                """, this::commandRow, tenantId, commandId).stream().findFirst();
    }
    CommandRow insertCommand(
            long tenantId, long actorId, UUID incidentId, String type,
            String idempotencyKey, String fingerprint, String reason,
            String correlationId, String statusHref, OffsetDateTime now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_safety_commands(
                    command_id,tenant_id,actor_user_id,incident_id,command_type,idempotency_key,
                    request_fingerprint,command_state,reason,correlation_id,status_href,
                    version,accepted_at,updated_at)
                VALUES(?,?,?,?,?,?,?,'ACCEPTED',?,?,?,?,?,?)
                """, id, tenantId, actorId, incidentId, type, idempotencyKey,
                fingerprint, reason, correlationId, statusHref, 1, now, now);
        return new CommandRow(id, incidentId, type, fingerprint, CommandState.ACCEPTED,
                reason, correlationId, statusHref, null, null, 1, now, null, now);
    }
    void completeLocalCommand(long tenantId, UUID commandId, String resultCode,
                              OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_safety_commands SET command_state='SUCCEEDED',result_code=?,
                       completed_at=?,version=version+1,updated_at=?
                 WHERE tenant_id=? AND command_id=? AND command_state='ACCEPTED'
                """, resultCode, now, now, tenantId, commandId);
    }
    UUID insertDispatchBatch(
            long tenantId, UUID incidentId, UUID commandId, UUID snapshotId,
            String kind, List<DeliveryChannel> channels, OffsetDateTime now,
            List<AudienceMember> members) {
        UUID batchId = UUID.randomUUID();
        int attempts = Math.toIntExact(members.stream().filter(AudienceMember::included).count())
                * channels.size();
        jdbc.update("""
                INSERT INTO wp_safety_dispatch_batches(
                    dispatch_batch_id,tenant_id,incident_id,command_id,audience_snapshot_id,
                    batch_kind,dispatch_state,channels,attempt_count,created_at,updated_at)
                VALUES(?,?,?,?,?,?,'ACCEPTED',?::jsonb,?,?,?)
                """, batchId, tenantId, incidentId, commandId, snapshotId, kind,
                json(channels), attempts, now, now);
        for (AudienceMember member : members) {
            if (!member.included()) continue;
            for (DeliveryChannel channel : channels) {
                jdbc.update("""
                        INSERT INTO wp_safety_dispatch_attempts(
                            dispatch_attempt_id,tenant_id,dispatch_batch_id,audience_member_id,
                            channel,attempt_state,attempt_number,next_attempt_at,created_at,updated_at)
                        VALUES(?,?,?,?,?,'QUEUED',1,?,?,?)
                        """, UUID.randomUUID(), tenantId, batchId,
                        member.audienceMemberId(), channel.name(), now, now, now);
            }
        }
        outbox(tenantId, commandId, "DISPATCH_BATCH", batchId,
                "dispatch:" + batchId, java.util.Map.of("dispatchBatchId", batchId), now);
        return batchId;
    }

    List<DispatchSummary> dispatches(long tenantId, UUID incidentId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_dispatch_batches
                 WHERE tenant_id=? AND incident_id=? ORDER BY created_at,dispatch_batch_id
                """, (rs, n) -> new DispatchSummary(
                rs.getObject("dispatch_batch_id", UUID.class),
                DispatchState.valueOf(rs.getString("dispatch_state")),
                rs.getInt("attempt_count"), rs.getInt("delivered_count"),
                rs.getInt("failed_count"), rs.getInt("unknown_count"),
                list(rs.getString("channels"), DeliveryChannel.class),
                rs.getObject("updated_at", OffsetDateTime.class)), tenantId, incidentId);
    }

    List<UUID> retryMemberIds(long tenantId, UUID incidentId, List<AttemptState> states) {
        if (states.isEmpty()) return List.of();
        String values = String.join(",", states.stream().map(state -> "'" + state.name() + "'").toList());
        return jdbc.query("""
                SELECT DISTINCT a.audience_member_id
                  FROM wp_safety_dispatch_attempts a
                  JOIN wp_safety_dispatch_batches b
                    ON b.tenant_id=a.tenant_id AND b.dispatch_batch_id=a.dispatch_batch_id
                 WHERE b.tenant_id=? AND b.incident_id=? AND a.attempt_state IN ("""
                + values + ")", (rs, n) -> rs.getObject(1, UUID.class), tenantId, incidentId);
    }

    Optional<AttemptRow> attempt(long tenantId, UUID attemptId) {
        return jdbc.query("""
                SELECT a.*,b.command_id,b.incident_id
                  FROM wp_safety_dispatch_attempts a
                  JOIN wp_safety_dispatch_batches b
                    ON b.tenant_id=a.tenant_id AND b.dispatch_batch_id=a.dispatch_batch_id
                 WHERE a.tenant_id=? AND a.dispatch_attempt_id=?
                """, (rs, n) -> new AttemptRow(rs.getObject("dispatch_attempt_id", UUID.class),
                rs.getObject("dispatch_batch_id", UUID.class),
                rs.getObject("command_id", UUID.class), rs.getObject("incident_id", UUID.class),
                AttemptState.valueOf(rs.getString("attempt_state"))), tenantId, attemptId)
                .stream().findFirst();
    }

    List<DispatchWorkRow> queuedDispatches(int limit) {
        return dispatchable(limit, OffsetDateTime.parse("1900-01-01T00:00:00Z"));
    }

    List<DispatchWorkRow> dispatchable(int limit, OffsetDateTime staleBefore) {
        return jdbc.query("""
                SELECT a.dispatch_attempt_id,a.tenant_id,a.channel,m.subject_key_sha256,
                       m.subject_user_id,i.incident_id,i.message,i.safety_action,i.severity,
                       a.attempt_state,
                       a.provider_code,a.provider_configuration_version,
                       a.provider_credential_reference
                  FROM wp_safety_dispatch_attempts a
                  JOIN wp_safety_dispatch_batches b
                    ON b.tenant_id=a.tenant_id AND b.dispatch_batch_id=a.dispatch_batch_id
                  JOIN wp_safety_audience_members m
                    ON m.tenant_id=a.tenant_id AND m.audience_member_id=a.audience_member_id
                  JOIN wp_safety_incidents i
                    ON i.tenant_id=b.tenant_id AND i.incident_id=b.incident_id
                 WHERE a.attempt_state='QUEUED'
                    OR (a.attempt_state='OFFLINE_QUEUED' AND a.next_attempt_at<=CURRENT_TIMESTAMP
                        AND a.offline_retry_count<3)
                 ORDER BY a.created_at,a.dispatch_attempt_id LIMIT ?
                """, (rs, n) -> new DispatchWorkRow(
                rs.getObject("dispatch_attempt_id", UUID.class), rs.getLong("tenant_id"),
                DeliveryChannel.valueOf(rs.getString("channel")),
                rs.getString("subject_key_sha256"), nullableLong(rs, "subject_user_id"),
                rs.getObject("incident_id", UUID.class), rs.getString("message"),
                rs.getString("safety_action"), Severity.valueOf(rs.getString("severity")),
                AttemptState.valueOf(rs.getString("attempt_state")), providerContext(rs)),
                limit);
    }

    boolean claimDispatch(long tenantId, UUID attemptId, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_safety_dispatch_attempts
                   SET attempt_state='DISPATCHING',updated_at=?
                 WHERE tenant_id=? AND dispatch_attempt_id=?
                   AND (attempt_state='QUEUED' OR (attempt_state='OFFLINE_QUEUED'
                     AND next_attempt_at<=? AND offline_retry_count<3))
                """, now, tenantId, attemptId, now) == 1;
    }

    boolean claimDispatch(
            long tenantId, UUID attemptId, SafetyDispatchProvider.ProviderContext context,
            OffsetDateTime now, OffsetDateTime staleBefore) {
        String provider = context == null ? null : context.providerCode();
        Long version = context == null ? null : context.providerConfigurationVersion();
        String credential = context == null ? null : context.credentialReference();
        return jdbc.update("""
                UPDATE wp_safety_dispatch_attempts
                   SET attempt_state='DISPATCHING',
                       provider_code=COALESCE(provider_code,?),
                       provider_configuration_version=COALESCE(provider_configuration_version,?),
                       provider_credential_reference=COALESCE(provider_credential_reference,?),
                       dispatch_started_at=?,updated_at=?
                 WHERE tenant_id=? AND dispatch_attempt_id=?
                   AND (attempt_state='QUEUED'
                     OR (attempt_state='OFFLINE_QUEUED' AND next_attempt_at<=?
                         AND offline_retry_count<3))
                   AND ((provider_code IS NULL AND provider_configuration_version IS NULL
                         AND provider_credential_reference IS NULL)
                     OR (provider_code=? AND provider_configuration_version=?
                         AND provider_credential_reference=?))
                """, provider, version, credential, now, now, tenantId, attemptId, now,
                provider, version, credential) == 1;
    }

    boolean markOffline(long tenantId, UUID attemptId, String code, OffsetDateTime now) {
        int changed = jdbc.update("""
                UPDATE wp_safety_dispatch_attempts
                   SET attempt_state='OFFLINE_QUEUED',result_code=?,
                       offline_retry_count=LEAST(3,offline_retry_count+1),
                       next_attempt_at=? + INTERVAL '30 seconds',dispatch_started_at=NULL,updated_at=?
                 WHERE tenant_id=? AND dispatch_attempt_id=? AND attempt_state='DISPATCHING'
                """, code, now, now, tenantId, attemptId);
        if (changed == 1) {
            attempt(tenantId, attemptId).ifPresent(row ->
                    refreshBatch(tenantId, row.batchId(), now));
        }
        return changed == 1;
    }

    boolean recordOutcome(DispatchOutcome outcome) {
        AttemptRow attempt = attempt(outcome.tenantId(), outcome.dispatchAttemptId())
                .orElse(null);
        if (attempt == null || terminal(attempt.state())) return false;
        int updated = jdbc.update("""
                UPDATE wp_safety_dispatch_attempts
                   SET attempt_state=?,provider_operation_reference=?,result_code=?,
                       next_reconcile_at=CASE WHEN ?='RESULT_UNKNOWN'
                         THEN ? + INTERVAL '15 seconds' ELSE NULL END,
                       dispatch_started_at=NULL,updated_at=?
                 WHERE tenant_id=? AND dispatch_attempt_id=?
                   AND attempt_state IN ('QUEUED','DISPATCHING','OFFLINE_QUEUED')
                """, outcome.state().name(), outcome.providerOperationReference(),
                outcome.resultCode(), outcome.state().name(), outcome.receivedAt(),
                outcome.receivedAt(), outcome.tenantId(),
                outcome.dispatchAttemptId());
        if (updated == 0) return false;
        jdbc.update("""
                INSERT INTO wp_safety_dispatch_receipts(
                    dispatch_receipt_id,tenant_id,dispatch_attempt_id,receipt_state,
                    evidence_reference,source_at,received_at)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT (tenant_id,dispatch_attempt_id) DO NOTHING
                """, UUID.randomUUID(), outcome.tenantId(), outcome.dispatchAttemptId(),
                outcome.state().name(), outcome.evidenceReference(), outcome.sourceAt(),
                outcome.receivedAt());
        refreshBatch(outcome.tenantId(), attempt.batchId(), outcome.receivedAt());
        return true;
    }

    void refreshBatch(long tenantId, UUID batchId, OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_safety_dispatch_batches b SET
                    delivered_count=s.delivered,
                    failed_count=s.failed,
                    unknown_count=s.unknown,
                    dispatch_state=CASE
                      WHEN s.pending>0 THEN 'DISPATCHING'
                      WHEN s.unknown>0 THEN 'RESULT_UNKNOWN'
                      WHEN s.delivered>0 AND s.failed>0 THEN 'PARTIAL'
                      WHEN s.failed>0 THEN 'FAILED' ELSE 'SUCCEEDED' END,
                    version=version+1,updated_at=?
                  FROM (SELECT dispatch_batch_id,
                         COUNT(*) FILTER (WHERE attempt_state='DELIVERED') delivered,
                         COUNT(*) FILTER (WHERE attempt_state='DELIVERY_FAILED') failed,
                         COUNT(*) FILTER (WHERE attempt_state='RESULT_UNKNOWN') unknown,
                         COUNT(*) FILTER (WHERE attempt_state IN
                           ('QUEUED','DISPATCHING','OFFLINE_QUEUED')) pending
                          FROM wp_safety_dispatch_attempts
                         WHERE tenant_id=? AND dispatch_batch_id=? GROUP BY dispatch_batch_id) s
                 WHERE b.tenant_id=? AND b.dispatch_batch_id=s.dispatch_batch_id
                """, now, tenantId, batchId, tenantId);
        jdbc.update("""
                UPDATE wp_safety_commands c SET command_state=CASE
                         WHEN b.dispatch_state='SUCCEEDED' THEN 'SUCCEEDED'
                         WHEN b.dispatch_state IN ('FAILED','PARTIAL') THEN 'FAILED'
                         WHEN b.dispatch_state='RESULT_UNKNOWN' THEN 'RESULT_UNKNOWN'
                         ELSE c.command_state END,
                       completed_at=CASE WHEN b.dispatch_state IN
                         ('SUCCEEDED','FAILED','PARTIAL','RESULT_UNKNOWN') THEN ? ELSE NULL END,
                       version=c.version+1,updated_at=?
                  FROM wp_safety_dispatch_batches b
                 WHERE b.tenant_id=? AND b.dispatch_batch_id=?
                   AND c.tenant_id=b.tenant_id AND c.command_id=b.command_id
                """, now, now, tenantId, batchId);
    }

    Optional<ResponseRow> response(long tenantId, UUID incidentId, long userId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_responses
                 WHERE tenant_id=? AND incident_id=? AND subject_user_id=?
                """, (rs, n) -> new ResponseRow(rs.getObject("safety_response_id", UUID.class),
                SafetyResponseState.valueOf(rs.getString("response_state")),
                rs.getString("assistance_note"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class)), tenantId, incidentId, userId)
                .stream().findFirst();
    }

    void upsertResponse(long tenantId, UUID incidentId, long userId,
                        SafetyResponseRequest request, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_safety_responses(
                    safety_response_id,tenant_id,incident_id,subject_user_id,response_state,
                    assistance_note,version,responded_at,updated_at)
                VALUES(?,?,?,?,?,?,1,?,?)
                ON CONFLICT (tenant_id,incident_id,subject_user_id) DO UPDATE SET
                    response_state=EXCLUDED.response_state,
                    assistance_note=EXCLUDED.assistance_note,
                    version=wp_safety_responses.version+1,responded_at=EXCLUDED.responded_at,
                    updated_at=EXCLUDED.updated_at
                """, UUID.randomUUID(), tenantId, incidentId, userId,
                request.response().name(), request.assistanceNote(), now, now);
    }

    ResponseSummary responseSummary(long tenantId, UUID incidentId, UUID snapshotId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE r.response_state='SAFE') safe,
                       COUNT(*) FILTER (WHERE r.response_state='NEEDS_HELP') needs_help,
                       COUNT(*) FILTER (WHERE r.safety_response_id IS NULL) no_response
                  FROM wp_safety_audience_members m
                  LEFT JOIN wp_safety_responses r
                    ON r.tenant_id=m.tenant_id AND r.incident_id=?
                   AND r.subject_user_id=m.subject_user_id
                 WHERE m.tenant_id=? AND m.audience_snapshot_id=? AND m.included=TRUE
                """, (rs, n) -> new ResponseSummary(rs.getInt(1), rs.getInt(2), rs.getInt(3)),
                incidentId, tenantId, snapshotId);
    }

    Optional<AssemblyConfirmation> assemblyConfirmation(
            long tenantId, UUID incidentId, String subjectKey) {
        return jdbc.query("""
                SELECT * FROM wp_safety_assembly_confirmations
                 WHERE tenant_id=? AND incident_id=? AND subject_key_sha256=?
                """, (rs, n) -> new AssemblyConfirmation(
                rs.getObject("assembly_confirmation_id", UUID.class), incidentId,
                rs.getString("subject_key_sha256"), nullableLong(rs, "subject_user_id"),
                rs.getBoolean("confirmed"), rs.getObject("observed_at", OffsetDateTime.class),
                rs.getLong("confirmed_by"), rs.getString("evidence_reference"),
                rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class)),
                tenantId, incidentId, subjectKey).stream().findFirst();
    }

    boolean audienceContains(long tenantId, UUID snapshotId, String subjectKey) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM wp_safety_audience_members
                 WHERE tenant_id=? AND audience_snapshot_id=?
                   AND subject_key_sha256=? AND included=TRUE)
                """, Boolean.class, tenantId, snapshotId, subjectKey));
    }

    Optional<AssemblyConfirmation> upsertAssembly(
            long tenantId, UUID incidentId, long actorId,
            AssemblyConfirmationRequest request, OffsetDateTime now) {
        AssemblyConfirmation existing = assemblyConfirmation(
                tenantId, incidentId, request.subjectKeySha256()).orElse(null);
        if (existing == null) {
            if (request.expectedAssemblyVersion() != 0) return Optional.empty();
            jdbc.update("""
                    INSERT INTO wp_safety_assembly_confirmations(
                        assembly_confirmation_id,tenant_id,incident_id,subject_key_sha256,
                        subject_user_id,confirmed,observed_at,confirmed_by,evidence_reference,
                        version,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,1,?,?)
                    """, UUID.randomUUID(), tenantId, incidentId,
                    request.subjectKeySha256(), request.subjectUserId(), request.confirmed(),
                    request.observedAt(), actorId, request.evidenceReference(), now, now);
        } else {
            int changed = jdbc.update("""
                    UPDATE wp_safety_assembly_confirmations SET confirmed=?,observed_at=?,
                           confirmed_by=?,evidence_reference=?,version=version+1,updated_at=?
                     WHERE tenant_id=? AND incident_id=? AND subject_key_sha256=? AND version=?
                    """, request.confirmed(), request.observedAt(), actorId,
                    request.evidenceReference(), now, tenantId, incidentId,
                    request.subjectKeySha256(), request.expectedAssemblyVersion());
            if (changed == 0) return Optional.empty();
        }
        return assemblyConfirmation(tenantId, incidentId, request.subjectKeySha256());
    }

    AssemblySummary assemblySummary(long tenantId, UUID incidentId, UUID snapshotId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE c.confirmed=TRUE) confirmed,
                       COUNT(*) FILTER (WHERE c.assembly_confirmation_id IS NULL
                         OR c.confirmed=FALSE) pending
                  FROM wp_safety_audience_members m
                  LEFT JOIN wp_safety_assembly_confirmations c
                    ON c.tenant_id=m.tenant_id AND c.incident_id=?
                   AND c.subject_key_sha256=m.subject_key_sha256
                 WHERE m.tenant_id=? AND m.audience_snapshot_id=? AND m.included=TRUE
                """, (rs, n) -> new AssemblySummary(rs.getInt(1), rs.getInt(2)),
                incidentId, tenantId, snapshotId);
    }

    IncidentMessage insertMessage(long tenantId, UUID incidentId, UUID commandId, long actorId,
                                  Long targetUserId, MessageDirection direction,
                                  String body, OffsetDateTime now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_safety_messages(
                    message_id,tenant_id,incident_id,command_id,sender_user_id,target_user_id,
                    direction,masked_body,created_at) VALUES(?,?,?,?,?,?,?,?,?)
                """, id, tenantId, incidentId, commandId, actorId, targetUserId,
                direction.name(), body, now);
        return new IncidentMessage(id, incidentId, targetUserId, direction, body, now);
    }

    Optional<IncidentMessage> messageByCommand(long tenantId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_messages WHERE tenant_id=? AND command_id=?
                """, (rs, n) -> new IncidentMessage(rs.getObject("message_id", UUID.class),
                rs.getObject("incident_id", UUID.class), nullableLong(rs, "target_user_id"),
                MessageDirection.valueOf(rs.getString("direction")), rs.getString("masked_body"),
                rs.getObject("created_at", OffsetDateTime.class)), tenantId, commandId)
                .stream().findFirst();
    }

    List<IncidentMessage> messages(long tenantId, UUID incidentId, Long userId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_messages
                 WHERE tenant_id=? AND incident_id=?
                   AND (? IS NULL OR target_user_id IS NULL OR target_user_id=?
                        OR sender_user_id=?)
                 ORDER BY created_at,message_id
                """, (rs, n) -> new IncidentMessage(rs.getObject("message_id", UUID.class),
                incidentId, nullableLong(rs, "target_user_id"),
                MessageDirection.valueOf(rs.getString("direction")),
                rs.getString("masked_body"), rs.getObject("created_at", OffsetDateTime.class)),
                tenantId, incidentId, userId, userId, userId);
    }

    void insertScopeRevision(long tenantId, long actorId, UUID revisionId, UUID commandId,
                             IncidentRow incident, ScopeRevisionPreviewRequest request,
                             UUID snapshotId, OffsetDateTime expiresAt, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_safety_scope_revisions(
                    scope_revision_id,tenant_id,incident_id,command_id,actor_user_id,revision_state,
                    previous_floor_ids,previous_zone_ids,proposed_floor_ids,proposed_zone_ids,
                    proposed_message,audience_snapshot_id,incident_version,expires_at,created_at)
                VALUES(?,?,?,?,?,'PREVIEW',?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?,?,?)
                """, revisionId, tenantId, incident.id(), commandId, actorId, json(incident.floorIds()),
                json(incident.zoneIds()), json(request.floorIds()), json(request.zoneIds()),
                request.message(), snapshotId, incident.version(), expiresAt, now);
    }

    Optional<ScopeRow> scopeRevisionByCommand(long tenantId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_scope_revisions WHERE tenant_id=? AND command_id=?
                """, this::scopeRow, tenantId, commandId).stream().findFirst();
    }

    Optional<ScopeRow> scopeRevision(long tenantId, UUID incidentId, UUID revisionId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_scope_revisions
                 WHERE tenant_id=? AND incident_id=? AND scope_revision_id=?
                """, this::scopeRow, tenantId, incidentId, revisionId)
                .stream().findFirst();
    }

    private ScopeRow scopeRow(ResultSet rs, int row) throws SQLException {
        return new ScopeRow(rs.getObject("scope_revision_id", UUID.class),
                rs.getObject("incident_id", UUID.class), rs.getLong("incident_version"),
                list(rs.getString("previous_floor_ids"), UUID.class),
                list(rs.getString("previous_zone_ids"), UUID.class),
                list(rs.getString("proposed_floor_ids"), UUID.class),
                list(rs.getString("proposed_zone_ids"), UUID.class),
                rs.getString("proposed_message"),
                rs.getObject("audience_snapshot_id", UUID.class),
                rs.getString("revision_state"), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    boolean applyScope(long tenantId, UUID incidentId, ScopeRow revision,
                       long expectedVersion, OffsetDateTime now) {
        int updated = jdbc.update("""
                UPDATE wp_safety_incidents SET floor_ids=?::jsonb,zone_ids=?::jsonb,
                       message=?,audience_snapshot_id=?,version=version+1,updated_at=?
                 WHERE tenant_id=? AND incident_id=? AND version=? AND incident_state='ACTIVE'
                """, json(revision.proposedFloors()), json(revision.proposedZones()),
                revision.message(), revision.snapshotId(), now, tenantId, incidentId,
                expectedVersion);
        if (updated == 1) jdbc.update("""
                UPDATE wp_safety_scope_revisions SET revision_state='APPLIED',applied_at=?
                 WHERE tenant_id=? AND scope_revision_id=? AND revision_state='PREVIEW'
                """, now, tenantId, revision.id());
        return updated == 1;
    }

    List<ConnectorRow> connectors(long tenantId) {
        return jdbc.query("SELECT * FROM wp_safety_connector_truth WHERE tenant_id=?",
                this::connectorRow, tenantId);
    }

    Optional<ConnectorRow> connector(long tenantId, ConnectorKind kind) {
        return jdbc.query("""
                SELECT * FROM wp_safety_connector_truth
                 WHERE tenant_id=? AND connector_kind=?
                """, this::connectorRow, tenantId, kind.name()).stream().findFirst();
    }

    List<ObservableConnectorRow> observableConnectors(int limit) {
        return jdbc.query("""
                SELECT tenant_id,connector_kind,provider_code,configuration_version
                  FROM wp_safety_connector_truth
                 WHERE configured=TRUE
                 ORDER BY updated_at,connector_id LIMIT ?
                """, (rs, row) -> new ObservableConnectorRow(
                rs.getLong("tenant_id"),
                ConnectorKind.valueOf(rs.getString("connector_kind")),
                rs.getString("provider_code"), rs.getLong("configuration_version")), limit);
    }

    ConnectorRow configureConnector(long tenantId, ConnectorConfigurationRequest request,
                                    OffsetDateTime now) {
        UUID id = connector(tenantId, request.kind()).map(ConnectorRow::id)
                .orElse(UUID.randomUUID());
        jdbc.update("""
                INSERT INTO wp_safety_connector_truth(
                    connector_id,tenant_id,connector_kind,provider_code,configured,
                    configuration_version,version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,1,?,?)
                ON CONFLICT (tenant_id,connector_kind) DO UPDATE SET
                    provider_code=EXCLUDED.provider_code,configured=EXCLUDED.configured,
                    configuration_version=EXCLUDED.configuration_version,
                    observed_configuration_version=NULL,reported_state=NULL,
                    evidence_reference=NULL,source_at=NULL,received_at=NULL,last_success_at=NULL,
                    error_code=NULL,version=wp_safety_connector_truth.version+1,updated_at=EXCLUDED.updated_at
                 WHERE wp_safety_connector_truth.version=?
                """, id, tenantId, request.kind().name(), request.providerCode(),
                request.configured(), request.configurationVersion(), now, now,
                request.expectedVersion());
        return connector(tenantId, request.kind()).orElseThrow();
    }

    boolean observeConnector(ConnectorObservation observation, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_safety_connector_truth SET
                    observed_configuration_version=?,reported_state=?,evidence_reference=?,
                    source_at=?,received_at=?,last_success_at=?,error_code=?,
                    version=version+1,updated_at=?
                 WHERE tenant_id=? AND connector_kind=? AND provider_code=? AND configured=TRUE
                """, observation.observedConfigurationVersion(),
                observation.reportedState().name(), observation.evidenceReference(),
                observation.sourceAt(), observation.receivedAt(), observation.lastSuccessAt(),
                observation.errorCode(), now, observation.tenantId(), observation.kind().name(),
                observation.providerCode()) == 1;
    }

    void outbox(long tenantId, UUID commandId, String operation, UUID aggregateId,
                String deduplicationKey, Object payload, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_safety_outbox(
                    outbox_id,tenant_id,command_id,operation_type,aggregate_id,payload,
                    deduplication_key,next_attempt_at,created_at,updated_at)
                VALUES(?,?,?,?,?,?::jsonb,?,?,?,?) ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), tenantId, commandId, operation, aggregateId,
                json(payload), deduplicationKey, now, now, now);
    }

    void audit(long tenantId, UUID incidentId, long actorId, String action,
               String resourceType, UUID resourceId, String correlationId,
               Object snapshot, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_safety_audit_events(
                    audit_event_id,tenant_id,incident_id,actor_user_id,action,resource_type,
                    resource_id,correlation_id,snapshot,occurred_at)
                VALUES(?,?,?,?,?,?,?,?,?::jsonb,?)
                """, UUID.randomUUID(), tenantId, incidentId, actorId, action,
                resourceType, resourceId, correlationId, json(snapshot), now);
    }

    private PreviewRow previewRow(ResultSet rs, int row) throws SQLException {
        return new PreviewRow(rs.getObject("activation_preview_id", UUID.class),
                rs.getString("incident_type"), Severity.valueOf(rs.getString("severity")),
                rs.getObject("site_id", UUID.class), list(rs.getString("floor_ids"), UUID.class),
                list(rs.getString("zone_ids"), UUID.class), rs.getString("message"),
                rs.getString("safety_action"), rs.getString("assembly_point"),
                list(rs.getString("channels"), DeliveryChannel.class),
                list(rs.getString("excluded_subject_keys"), String.class),
                rs.getObject("audience_snapshot_id", UUID.class), rs.getBoolean("eligible"),
                list(rs.getString("limitations"), String.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private IncidentRow incidentRow(ResultSet rs, int row) throws SQLException {
        return new IncidentRow(rs.getObject("incident_id", UUID.class),
                rs.getString("incident_number"), rs.getString("incident_type"),
                Severity.valueOf(rs.getString("severity")),
                IncidentState.valueOf(rs.getString("incident_state")),
                rs.getObject("site_id", UUID.class), list(rs.getString("floor_ids"), UUID.class),
                list(rs.getString("zone_ids"), UUID.class), rs.getString("message"),
                rs.getString("safety_action"), rs.getString("assembly_point"),
                list(rs.getString("channels"), DeliveryChannel.class),
                rs.getObject("audience_snapshot_id", UUID.class), rs.getLong("version"),
                rs.getObject("activated_at", OffsetDateTime.class),
                rs.getObject("closed_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private CommandRow commandRow(ResultSet rs, int row) throws SQLException {
        return new CommandRow(rs.getObject("command_id", UUID.class),
                rs.getObject("incident_id", UUID.class), rs.getString("command_type"),
                rs.getString("request_fingerprint"),
                CommandState.valueOf(rs.getString("command_state")), rs.getString("reason"),
                rs.getString("correlation_id"), rs.getString("status_href"),
                rs.getString("result_code"), rs.getString("provider_operation_reference"),
                rs.getLong("version"), rs.getObject("accepted_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private ConnectorRow connectorRow(ResultSet rs, int row) throws SQLException {
        return new ConnectorRow(rs.getObject("connector_id", UUID.class),
                ConnectorKind.valueOf(rs.getString("connector_kind")),
                rs.getString("provider_code"), rs.getBoolean("configured"),
                rs.getLong("configuration_version"),
                nullableLong(rs, "observed_configuration_version"),
                rs.getString("reported_state"), rs.getString("evidence_reference"),
                rs.getObject("source_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class),
                rs.getObject("last_success_at", OffsetDateTime.class),
                rs.getString("error_code"), rs.getLong("version"));
    }

    private static SafetyDispatchProvider.ProviderContext providerContext(ResultSet rs)
            throws SQLException {
        String provider = rs.getString("provider_code");
        if (provider == null) return null;
        return new SafetyDispatchProvider.ProviderContext(
                DeliveryChannel.valueOf(rs.getString("channel")), provider,
                rs.getLong("provider_configuration_version"),
                rs.getString("provider_credential_reference"));
    }

    private static boolean terminal(AttemptState state) {
        return state == AttemptState.DELIVERED || state == AttemptState.DELIVERY_FAILED
                || state == AttemptState.RESULT_UNKNOWN;
    }

    record PreviewRow(UUID id, String incidentType, Severity severity, UUID siteId,
                      List<UUID> floorIds, List<UUID> zoneIds, String message,
                      String safetyAction, String assemblyPoint, List<DeliveryChannel> channels,
                      List<String> excludedKeys, UUID snapshotId, boolean eligible,
                      List<String> limitations, OffsetDateTime expiresAt, OffsetDateTime createdAt) { }
    record IncidentRow(UUID id, String number, String type, Severity severity, IncidentState state,
                       UUID siteId, List<UUID> floorIds, List<UUID> zoneIds, String message,
                       String safetyAction, String assemblyPoint, List<DeliveryChannel> channels,
                       UUID snapshotId, long version, OffsetDateTime activatedAt,
                       OffsetDateTime closedAt, OffsetDateTime updatedAt) { }
    record CommandRow(UUID id, UUID incidentId, String type, String fingerprint,
                      CommandState state, String reason, String correlationId, String statusHref,
                      String resultCode, String providerReference, long version,
                      OffsetDateTime acceptedAt, OffsetDateTime completedAt,
                      OffsetDateTime updatedAt) { }
    record AttemptRow(UUID id, UUID batchId, UUID commandId, UUID incidentId,
                      AttemptState state) { }
    record ResponseRow(UUID id, SafetyResponseState state, String note, long version,
                       OffsetDateTime updatedAt) { }
    record ScopeRow(UUID id, UUID incidentId, long incidentVersion,
                    List<UUID> previousFloors, List<UUID> previousZones,
                    List<UUID> proposedFloors, List<UUID> proposedZones, String message,
                    UUID snapshotId, String state, OffsetDateTime expiresAt,
                    OffsetDateTime createdAt) { }
    record ConnectorRow(UUID id, ConnectorKind kind, String provider, boolean configured,
                        long configurationVersion, Long observedVersion, String reportedState,
                        String evidenceReference, OffsetDateTime sourceAt,
                        OffsetDateTime receivedAt, OffsetDateTime lastSuccessAt,
                        String errorCode, long version) { }
    record ObservableConnectorRow(long tenantId, ConnectorKind kind, String provider,
                                  long configurationVersion) { }
    record DispatchWorkRow(UUID attemptId, long tenantId, DeliveryChannel channel,
                           String subjectKey, Long subjectUserId, UUID incidentId,
                           String message, String safetyAction, Severity severity,
                           AttemptState state,
                           SafetyDispatchProvider.ProviderContext providerContext) {
        DispatchWorkRow(UUID attemptId, long tenantId, DeliveryChannel channel,
                        String subjectKey, Long subjectUserId, UUID incidentId,
                        String message, String safetyAction, Severity severity) {
            this(attemptId, tenantId, channel, subjectKey, subjectUserId, incidentId,
                    message, safetyAction, severity, AttemptState.QUEUED, null);
        }
    }
}

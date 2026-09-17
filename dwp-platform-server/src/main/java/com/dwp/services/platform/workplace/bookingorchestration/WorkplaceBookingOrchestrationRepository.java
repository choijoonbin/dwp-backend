package com.dwp.services.platform.workplace.bookingorchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.*;

@Repository
public class WorkplaceBookingOrchestrationRepository
        extends WorkplaceBookingOrchestrationRepositorySupport {

    public WorkplaceBookingOrchestrationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
    }

    public Optional<IntentRow> intentByIdempotency(long tenantId, long actorId, String key) {
        return jdbc.query("""
                SELECT * FROM wp_booking_intents
                 WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
                """, this::intent, tenantId, actorId, key).stream().findFirst();
    }

    public Optional<CommandReceiptRow> commandReceipt(
            long tenantId, long actorId, String scope, String key) {
        return jdbc.query("""
                SELECT * FROM wp_booking_orchestration_command_receipts
                 WHERE tenant_id = ? AND actor_user_id = ?
                   AND command_scope = ? AND idempotency_key = ?
                """, (rs, ignored) -> new CommandReceiptRow(
                rs.getLong("tenant_id"), rs.getLong("actor_user_id"),
                rs.getString("command_scope"), rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"), rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class)),
                tenantId, actorId, scope, key).stream().findFirst();
    }

    public void createCommandReceipt(CommandReceiptRow row) {
        jdbc.update("""
                INSERT INTO wp_booking_orchestration_command_receipts (
                    tenant_id, actor_user_id, command_scope, idempotency_key,
                    request_fingerprint, aggregate_type, aggregate_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, row.tenantId(), row.actorUserId(), row.scope(), row.idempotencyKey(),
                row.requestFingerprint(), row.aggregateType(), row.aggregateId(), row.createdAt());
    }

    public Optional<IntentRow> intent(long tenantId, long actorId, UUID intentId) {
        return jdbc.query("""
                SELECT * FROM wp_booking_intents
                 WHERE tenant_id = ? AND actor_user_id = ? AND intent_id = ?
                """, this::intent, tenantId, actorId, intentId).stream().findFirst();
    }

    public void createIntent(IntentRow row) {
        jdbc.update("""
                INSERT INTO wp_booking_intents (
                    intent_id, tenant_id, actor_user_id, intent_state, reason,
                    requested_hold_ttl_seconds, allow_alternatives,
                    team_placement_constraints, placement_constraint_evidence, idempotency_key,
                    request_fingerprint, correlation_id, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
                """, row.intentId(), row.tenantId(), row.actorUserId(), row.state().name(),
                row.reason(), row.holdTtlSeconds(), row.allowAlternatives(),
                json(row.teamPlacementConstraints()), json(row.placementConstraintEvidence()),
                row.idempotencyKey(),
                row.requestFingerprint(), row.correlationId(), row.version(), row.createdAt(), row.updatedAt());
    }

    public void createIntentItem(IntentItemRow row) {
        jdbc.update("""
                INSERT INTO wp_booking_intent_items (
                    intent_item_id, intent_id, tenant_id, client_item_key, actor_user_id,
                    beneficiary_user_id, beneficiary_person_public_id, beneficiary_display_name,
                    delegation_grant_id, resource_type, preferred_resource_id, site_id, floor_id,
                    starts_at, ends_at, purpose, visible_to_colleagues, accessible_only,
                    required_features, decision, decision_code, candidate_resource_ids,
                    version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                        ?, ?, ?::jsonb, ?, ?)
                """, row.itemId(), row.intentId(), row.tenantId(), row.clientItemKey(), row.actorUserId(),
                row.beneficiaryUserId(), row.beneficiaryPersonPublicId(), row.beneficiaryDisplayName(),
                row.delegationGrantId(), row.resourceType().name(), row.preferredResourceId(),
                row.siteId(), row.floorId(), row.startsAt(), row.endsAt(), row.purpose(),
                row.visibleToColleagues(), row.accessibleOnly(), json(row.requiredFeatures()),
                row.decision().name(), row.decisionCode(), json(row.candidateResourceIds()),
                row.version(), row.createdAt());
    }

    public List<IntentItemRow> intentItems(long tenantId, UUID intentId) {
        return jdbc.query("""
                SELECT * FROM wp_booking_intent_items
                 WHERE tenant_id = ? AND intent_id = ?
                 ORDER BY created_at, intent_item_id
                """, this::intentItem, tenantId, intentId);
    }

    public Optional<IntentItemRow> intentItem(long tenantId, UUID intentId, UUID itemId) {
        return jdbc.query("""
                SELECT * FROM wp_booking_intent_items
                 WHERE tenant_id = ? AND intent_id = ? AND intent_item_id = ?
                """, this::intentItem, tenantId, intentId, itemId).stream().findFirst();
    }

    public boolean delegationValid(
            long tenantId,
            UUID grantId,
            long actorId,
            long beneficiaryId,
            ResourceType resourceType,
            String verifiedGroupRefs,
            OffsetDateTime now) {
        if (actorId == beneficiaryId) return grantId == null;
        if (grantId == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM wp_booking_delegate_grants grant_row
                     WHERE grant_row.tenant_id = ? AND grant_row.grant_id = ?
                       AND (grant_row.actor_user_id = ? OR grant_row.actor_group_ref = ANY (
                           string_to_array(?, ',')::uuid[]))
                       AND grant_row.beneficiary_user_id = ?
                       AND grant_row.grant_state = 'ACTIVE'
                       AND grant_row.valid_from <= ?
                       AND (grant_row.valid_until IS NULL OR grant_row.valid_until > ?)
                       AND (grant_row.resource_types = '[]'::jsonb
                            OR jsonb_exists(grant_row.resource_types, ?)))
                """, Boolean.class, tenantId, grantId, actorId, groupCsv(verifiedGroupRefs), beneficiaryId,
                now, now, resourceType.name()));
    }

    public boolean groupDelegation(long tenantId, UUID grantId) {
        if (grantId == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM wp_booking_delegate_grants
                     WHERE tenant_id = ? AND grant_id = ?
                       AND actor_group_ref IS NOT NULL)
                """, Boolean.class, tenantId, grantId));
    }

    public List<BeneficiaryGrantRow> beneficiaries(
            long tenantId, long actorId, String verifiedGroupRefs, OffsetDateTime now) {
        return jdbc.query("""
                SELECT grant_id, beneficiary_user_id, beneficiary_person_public_id,
                       beneficiary_display_name, resource_types::text, valid_until
                  FROM wp_booking_delegate_grants
                 WHERE tenant_id = ?
                   AND (actor_user_id = ? OR actor_group_ref = ANY (
                       string_to_array(?, ',')::uuid[]))
                   AND grant_state = 'ACTIVE' AND valid_from <= ?
                   AND (valid_until IS NULL OR valid_until > ?)
                 ORDER BY lower(beneficiary_display_name), beneficiary_user_id, grant_id
                """, (rs, ignored) -> new BeneficiaryGrantRow(
                rs.getObject("grant_id", UUID.class), rs.getLong("beneficiary_user_id"),
                rs.getObject("beneficiary_person_public_id", UUID.class),
                rs.getString("beneficiary_display_name"),
                strings(rs.getString("resource_types")).stream().map(ResourceType::valueOf).toList(),
                rs.getObject("valid_until", OffsetDateTime.class)),
                tenantId, actorId, groupCsv(verifiedGroupRefs), now, now);
    }

    public List<CandidateRow> candidates(
            long tenantId,
            ResourceType resourceType,
            UUID preferredResourceId,
            UUID siteId,
            UUID floorId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            boolean accessibleOnly,
            List<String> requiredFeatures,
            int limit) {
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT resource.resource_id, resource.calendar_resource_id,
                           resource.name_ko, resource.name_en, resource.resource_type,
                           site.site_id, floor.floor_id, site.time_zone, resource.accessible,
                           resource.features::text, resource.neighborhood,
                           resource.position_x, resource.position_y,
                           resource.width_percent, resource.height_percent, resource.version,
                           resource.resource_id = ? AS preferred
                      FROM wp_resources resource
                      JOIN wp_floors floor ON floor.tenant_id = resource.tenant_id
                           AND floor.floor_id = resource.floor_id
                      JOIN wp_sites site ON site.tenant_id = floor.tenant_id
                           AND site.site_id = floor.site_id
                     WHERE resource.tenant_id = ? AND resource.resource_type = ?
                       AND resource.booking_mode = 'RESERVABLE'
                       AND resource.lifecycle_state = 'AVAILABLE'
                       AND floor.lifecycle_state = 'ACTIVE'
                       AND site.lifecycle_state = 'ACTIVE'
                       AND (?::uuid IS NULL OR resource.resource_id = ?)
                       AND (?::uuid IS NULL OR site.site_id = ?)
                       AND (?::uuid IS NULL OR floor.floor_id = ?)
                       AND (NOT ? OR resource.accessible)
                       AND resource.features @> ?::jsonb
                       AND NOT EXISTS (
                           SELECT 1 FROM wp_bookings booking
                            WHERE booking.tenant_id = resource.tenant_id
                              AND booking.resource_id = resource.resource_id
                              AND booking.booking_status IN ('RESERVED', 'CHECKED_IN')
                              AND booking.starts_at < ? AND booking.ends_at > ?)
                       AND NOT EXISTS (
                           SELECT 1 FROM wp_experience_facility_closures closure_row
                            WHERE closure_row.tenant_id = resource.tenant_id
                              AND closure_row.resource_id = resource.resource_id
                              AND closure_row.closure_status = 'ACTIVE'
                              AND closure_row.starts_at < ? AND closure_row.ends_at > ?)
                       AND NOT EXISTS (
                           SELECT 1 FROM wp_reservation_holds hold
                            WHERE hold.tenant_id = resource.tenant_id
                              AND hold.resource_id = resource.resource_id
                              AND hold.hold_state IN ('ACTIVE', 'BATCHED')
                              AND hold.expires_at > CURRENT_TIMESTAMP
                              AND hold.starts_at < ? AND hold.ends_at > ?)
                     ORDER BY preferred DESC, resource.accessible DESC,
                              site.site_id, floor.floor_number, resource.resource_id
                     LIMIT ?
                    """);
            int index = 1;
            statement.setObject(index++, preferredResourceId);
            statement.setLong(index++, tenantId);
            statement.setString(index++, resourceType.name());
            statement.setObject(index++, preferredResourceId);
            statement.setObject(index++, preferredResourceId);
            statement.setObject(index++, siteId);
            statement.setObject(index++, siteId);
            statement.setObject(index++, floorId);
            statement.setObject(index++, floorId);
            statement.setBoolean(index++, accessibleOnly);
            statement.setString(index++, json(requiredFeatures));
            statement.setObject(index++, endsAt);
            statement.setObject(index++, startsAt);
            statement.setObject(index++, endsAt);
            statement.setObject(index++, startsAt);
            statement.setObject(index++, endsAt);
            statement.setObject(index++, startsAt);
            statement.setInt(index, limit);
            return statement;
        }, this::candidate);
    }

    public void lockResource(long tenantId, UUID resourceId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1,
                        "workplace-resource:" + tenantId + ":" + resourceId), result -> null);
    }

    public void expireHolds(long tenantId, UUID resourceId, OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_reservation_holds
                   SET hold_state = 'EXPIRED', version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND resource_id = ?
                   AND hold_state IN ('ACTIVE', 'BATCHED') AND expires_at <= ?
                """, now, tenantId, resourceId, now);
    }

    public boolean hasHoldConflict(
            long tenantId, UUID resourceId, OffsetDateTime startsAt, OffsetDateTime endsAt,
            OffsetDateTime now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM wp_reservation_holds
                     WHERE tenant_id = ? AND resource_id = ?
                       AND hold_state IN ('ACTIVE', 'BATCHED') AND expires_at > ?
                       AND starts_at < ? AND ends_at > ?)
                """, Boolean.class, tenantId, resourceId, now, endsAt, startsAt));
    }

    public boolean hasBookingConflict(
            long tenantId, CandidateRow candidate, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        if (candidate.resourceType() == ResourceType.ROOM && candidate.calendarResourceId() != null) {
            return Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM cal_resource_bookings booking
                         WHERE booking.tenant_id = ? AND booking.resource_id = ?
                           AND booking.booking_status IN ('PENDING', 'CONFIRMED')
                           AND booking.starts_at < ? AND booking.ends_at > ?)
                    """, Boolean.class, tenantId, candidate.calendarResourceId(), endsAt, startsAt));
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM wp_bookings booking
                     WHERE booking.tenant_id = ? AND booking.resource_id = ?
                       AND booking.booking_status IN ('RESERVED', 'CHECKED_IN')
                       AND booking.starts_at < ? AND booking.ends_at > ?)
                """, Boolean.class, tenantId, candidate.resourceId(), endsAt, startsAt));
    }

    public Optional<CandidateRow> candidate(long tenantId, UUID resourceId) {
        return jdbc.query("""
                SELECT resource.resource_id, resource.calendar_resource_id,
                       resource.name_ko, resource.name_en, resource.resource_type,
                       site.site_id, floor.floor_id, site.time_zone, resource.accessible,
                       resource.features::text, resource.neighborhood,
                       resource.position_x, resource.position_y,
                       resource.width_percent, resource.height_percent, resource.version,
                       TRUE AS preferred
                  FROM wp_resources resource
                  JOIN wp_floors floor ON floor.tenant_id = resource.tenant_id
                       AND floor.floor_id = resource.floor_id
                  JOIN wp_sites site ON site.tenant_id = floor.tenant_id
                       AND site.site_id = floor.site_id
                 WHERE resource.tenant_id = ? AND resource.resource_id = ?
                   AND resource.booking_mode = 'RESERVABLE'
                   AND resource.lifecycle_state = 'AVAILABLE'
                   AND floor.lifecycle_state = 'ACTIVE'
                   AND site.lifecycle_state = 'ACTIVE'
                """, this::candidate, tenantId, resourceId).stream().findFirst();
    }

    public Optional<ResourcePresentationRow> resourcePresentation(
            long tenantId, UUID resourceId) {
        return jdbc.query("""
                SELECT COALESCE(NULLIF(resource.name_en, ''), resource.name_ko) resource_name,
                       site.site_id, floor.floor_id, site.time_zone
                  FROM wp_resources resource
                  JOIN wp_floors floor ON floor.tenant_id = resource.tenant_id
                   AND floor.floor_id = resource.floor_id
                  JOIN wp_sites site ON site.tenant_id = floor.tenant_id
                   AND site.site_id = floor.site_id
                 WHERE resource.tenant_id = ? AND resource.resource_id = ?
                """, (rs, ignored) -> new ResourcePresentationRow(
                rs.getString("resource_name"), rs.getObject("site_id", UUID.class),
                rs.getObject("floor_id", UUID.class), rs.getString("time_zone")),
                tenantId, resourceId).stream().findFirst();
    }

    public void createHold(HoldRow row) {
        jdbc.update("""
                INSERT INTO wp_reservation_holds (
                    hold_id, tenant_id, intent_id, intent_item_id, resource_id,
                    actor_user_id, beneficiary_user_id, hold_state, starts_at, ends_at,
                    expires_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.holdId(), row.tenantId(), row.intentId(), row.intentItemId(), row.resourceId(),
                row.actorUserId(), row.beneficiaryUserId(), row.state().name(), row.startsAt(),
                row.endsAt(), row.expiresAt(), row.version(), row.createdAt(), row.updatedAt());
    }

    public List<HoldRow> holds(long tenantId, UUID intentId) {
        return jdbc.query("""
                SELECT * FROM wp_reservation_holds
                 WHERE tenant_id = ? AND intent_id = ?
                 ORDER BY created_at, hold_id
                """, this::hold, tenantId, intentId);
    }

    public Optional<HoldRow> hold(long tenantId, UUID holdId) {
        return jdbc.query("""
                SELECT * FROM wp_reservation_holds
                 WHERE tenant_id = ? AND hold_id = ?
                """, this::hold, tenantId, holdId).stream().findFirst();
    }

    public boolean updateIntentState(
            long tenantId, long actorId, UUID intentId, long expectedVersion,
            IntentState current, IntentState next, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_booking_intents
                   SET intent_state = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND actor_user_id = ? AND intent_id = ?
                   AND version = ? AND intent_state = ?
                """, next.name(), now, tenantId, actorId, intentId, expectedVersion,
                current.name()) == 1;
    }

    public boolean completeIntent(long tenantId, UUID intentId, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_booking_intents
                   SET intent_state = 'COMPLETED', version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND intent_id = ? AND intent_state = 'CONFIRMING'
                """, now, tenantId, intentId) == 1;
    }

    public Optional<BatchRow> batchByIdempotency(long tenantId, long actorId, String key) {
        return jdbc.query("""
                SELECT * FROM wp_booking_batches
                 WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
                """, this::batch, tenantId, actorId, key).stream().findFirst();
    }

    public Optional<BatchRow> batch(long tenantId, long actorId, UUID batchId) {
        return jdbc.query("""
                SELECT * FROM wp_booking_batches
                 WHERE tenant_id = ? AND actor_user_id = ? AND batch_id = ?
                """, this::batch, tenantId, actorId, batchId).stream().findFirst();
    }

    public Optional<BatchRow> batch(long tenantId, UUID batchId) {
        return jdbc.query("""
                SELECT * FROM wp_booking_batches WHERE tenant_id = ? AND batch_id = ?
                """, this::batch, tenantId, batchId).stream().findFirst();
    }

    public Optional<BatchRow> latestBatchForIntent(
            long tenantId, long actorId, UUID intentId) {
        return jdbc.query("""
                SELECT * FROM wp_booking_batches
                 WHERE tenant_id = ? AND actor_user_id = ? AND intent_id = ?
                 ORDER BY created_at DESC, batch_id DESC
                 LIMIT 1
                """, this::batch, tenantId, actorId, intentId).stream().findFirst();
    }

    public void createBatch(BatchRow row) {
        jdbc.update("""
                INSERT INTO wp_booking_batches (
                    batch_id, tenant_id, intent_id, actor_user_id, batch_state,
                    failure_policy, reason, explicit_confirmation, idempotency_key,
                    request_fingerprint, correlation_id, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.batchId(), row.tenantId(), row.intentId(), row.actorUserId(),
                row.state().name(), row.failurePolicy().name(), row.reason(),
                row.explicitConfirmation(), row.idempotencyKey(), row.requestFingerprint(),
                row.correlationId(), row.version(), row.createdAt(), row.updatedAt());
    }

    public void createBatchItem(BatchItemRow row) {
        jdbc.update("""
                INSERT INTO wp_booking_batch_items (
                    batch_item_id, batch_id, tenant_id, intent_id, intent_item_id, hold_id,
                    authority, item_state, compensation_available, requery_required,
                    version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.batchItemId(), row.batchId(), row.tenantId(), row.intentId(),
                row.intentItemId(), row.holdId(), row.authority().name(), row.state().name(),
                row.compensationAvailable(), row.requeryRequired(), row.version(),
                row.createdAt(), row.updatedAt());
    }

    public void createBatchItem(
            UUID batchItemId,
            BatchRow batch,
            IntentItemRow item,
            HoldRow hold,
            BookingAuthority authority,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_booking_batch_items (
                    batch_item_id, batch_id, tenant_id, intent_id, intent_item_id, hold_id,
                    authority, item_state, compensation_available, requery_required,
                    version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', FALSE, FALSE, 1, ?, ?)
                """, batchItemId, batch.batchId(), batch.tenantId(), batch.intentId(),
                item.itemId(), hold.holdId(), authority.name(), now, now);
    }

    public boolean attachHoldToBatch(
            long tenantId, UUID holdId, long expectedVersion, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_reservation_holds
                   SET hold_state = 'BATCHED', version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND hold_id = ? AND hold_state = 'ACTIVE'
                   AND version = ? AND expires_at > ?
                """, now, tenantId, holdId, expectedVersion, now) == 1;
    }

    public List<BatchItemRow> batchItems(long tenantId, UUID batchId) {
        return jdbc.query("""
                SELECT batch_item.*, item.client_item_key, item.beneficiary_user_id,
                       item.beneficiary_person_public_id, item.beneficiary_display_name,
                       item.delegation_grant_id, item.resource_type, item.purpose,
                       item.visible_to_colleagues, hold.resource_id, hold.starts_at,
                       hold.ends_at, hold.expires_at, resource.calendar_resource_id,
                       COALESCE(NULLIF(resource.name_en, ''), resource.name_ko) resource_name,
                       site.site_id, floor.floor_id, site.time_zone
                  FROM wp_booking_batch_items batch_item
                  JOIN wp_booking_intent_items item
                    ON item.tenant_id = batch_item.tenant_id
                   AND item.intent_item_id = batch_item.intent_item_id
                  JOIN wp_reservation_holds hold
                    ON hold.tenant_id = batch_item.tenant_id
                   AND hold.hold_id = batch_item.hold_id
                  JOIN wp_resources resource
                    ON resource.tenant_id = hold.tenant_id
                   AND resource.resource_id = hold.resource_id
                  JOIN wp_floors floor ON floor.tenant_id = resource.tenant_id
                   AND floor.floor_id = resource.floor_id
                  JOIN wp_sites site ON site.tenant_id = floor.tenant_id
                   AND site.site_id = floor.site_id
                 WHERE batch_item.tenant_id = ? AND batch_item.batch_id = ?
                 ORDER BY batch_item.created_at, batch_item.batch_item_id
                """, this::batchItem, tenantId, batchId);
    }

    public Optional<BatchRow> claimBatchExecution(
            long tenantId,
            UUID batchId,
            UUID claimToken,
            OffsetDateTime now,
            OffsetDateTime leaseExpiresAt,
            OffsetDateTime legacyStaleBefore) {
        return jdbc.query("""
                UPDATE wp_booking_batches
                   SET batch_state = 'PROCESSING',
                       started_at = COALESCE(started_at, ?),
                       completed_at = NULL,
                       execution_claim_token = ?,
                       execution_lease_expires_at = ?,
                       execution_attempt_count = execution_attempt_count + 1,
                       version = version + 1,
                       updated_at = ?
                 WHERE tenant_id = ? AND batch_id = ?
                   AND (batch_state = 'ACCEPTED'
                        OR (batch_state = 'PROCESSING'
                            AND ((execution_claim_token IS NULL
                                  AND updated_at <= ?)
                                 OR (execution_claim_token IS NOT NULL
                                     AND (execution_lease_expires_at IS NULL
                                          OR execution_lease_expires_at <= ?)))))
                 RETURNING *
                """, this::batch, now, claimToken, leaseExpiresAt, now,
                tenantId, batchId, legacyStaleBefore, now).stream().findFirst();
    }

    public Optional<BatchRow> claimCompensationExecution(
            long tenantId,
            long actorId,
            UUID batchId,
            UUID claimToken,
            OffsetDateTime now,
            OffsetDateTime leaseExpiresAt) {
        return jdbc.query("""
                UPDATE wp_booking_batches
                   SET execution_claim_token = ?, execution_lease_expires_at = ?,
                       execution_attempt_count = execution_attempt_count + 1,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND actor_user_id = ? AND batch_id = ?
                   AND batch_state = 'COMPENSATING'
                   AND (execution_claim_token IS NULL
                        OR execution_lease_expires_at IS NULL
                        OR execution_lease_expires_at <= ?)
                 RETURNING *
                """, this::batch, claimToken, leaseExpiresAt, now,
                tenantId, actorId, batchId, now).stream().findFirst();
    }

    public boolean renewBatchExecution(
            long tenantId,
            UUID batchId,
            UUID claimToken,
            OffsetDateTime leaseExpiresAt,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_booking_batches
                   SET execution_lease_expires_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND batch_id = ?
                   AND execution_claim_token = ?
                   AND batch_state IN ('PROCESSING', 'COMPENSATING')
                """, leaseExpiresAt, now, tenantId, batchId, claimToken) == 1;
    }

    public int recoverInterruptedBatchItems(
            long tenantId, UUID batchId, OffsetDateTime now) {
        int creation = jdbc.update("""
                UPDATE wp_booking_batch_items
                   SET item_state = 'RESULT_UNKNOWN',
                       error_code = 'INTERRUPTED_OWNER_RESULT_UNKNOWN',
                       error_message = 'Execution was interrupted before the owner result was durably recorded.',
                       compensation_available = FALSE, requery_required = TRUE,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND batch_id = ? AND item_state = 'PROCESSING'
                """, now, tenantId, batchId);
        int compensation = jdbc.update("""
                UPDATE wp_booking_batch_items
                   SET item_state = 'COMPENSATION_FAILED',
                       error_code = 'INTERRUPTED_COMPENSATION_RESULT_UNKNOWN',
                       error_message = 'Compensation was interrupted before its owner result was durably recorded.',
                       compensation_available = FALSE, requery_required = TRUE,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND batch_id = ?
                   AND item_state = 'COMPENSATION_PENDING'
                """, now, tenantId, batchId);
        return creation + compensation;
    }

    public boolean beginBatchItem(
            long tenantId, UUID batchItemId, long expectedVersion, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_booking_batch_items
                   SET item_state = 'PROCESSING', version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND batch_item_id = ? AND version = ?
                   AND item_state = 'PENDING'
                """, now, tenantId, batchItemId, expectedVersion) == 1;
    }

    public boolean completeBatchItemIfState(
            long tenantId,
            UUID batchItemId,
            BatchItemState expectedState,
            BatchItemState state,
            UUID ownerReferenceId,
            Long ownerVersion,
            String errorCode,
            String errorMessage,
            boolean compensationAvailable,
            boolean requeryRequired,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_booking_batch_items
                   SET item_state = ?, owner_reference_id = ?, owner_version = ?,
                       error_code = ?, error_message = ?, compensation_available = ?,
                       requery_required = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND batch_item_id = ?
                   AND item_state = ?
                """, state.name(), ownerReferenceId, ownerVersion, errorCode,
                truncate(errorMessage, 500), compensationAvailable, requeryRequired,
                now, tenantId, batchItemId, expectedState.name()) == 1;
    }

    public void consumeHold(long tenantId, UUID holdId, OffsetDateTime now) {
        int updated = jdbc.update("""
                UPDATE wp_reservation_holds
                   SET hold_state = 'CONSUMED', version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND hold_id = ? AND hold_state = 'BATCHED'
                """, now, tenantId, holdId);
        if (updated != 1) throw new IllegalStateException("Reservation hold state changed concurrently.");
    }

    public void setHoldContext(UUID holdId) {
        jdbc.queryForObject("SELECT set_config('dwp.workplace_hold_id', ?, true)",
                String.class, holdId.toString());
    }

    public boolean markCompensationPending(
            long tenantId, UUID batchItemId, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_booking_batch_items
                   SET item_state = 'COMPENSATION_PENDING', version = version + 1,
                       updated_at = ?
                 WHERE tenant_id = ? AND batch_item_id = ?
                   AND item_state = 'SUCCEEDED' AND compensation_available
                """, now, tenantId, batchItemId) == 1;
    }

    public boolean beginManualCompensation(
            long tenantId, long actorId, UUID batchId, long expectedVersion,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_booking_batches
                   SET batch_state = 'COMPENSATING', completed_at = NULL,
                       execution_claim_token = NULL,
                       execution_lease_expires_at = NULL,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND actor_user_id = ? AND batch_id = ?
                   AND version = ?
                   AND batch_state IN ('SUCCEEDED', 'PARTIAL')
                """, now, tenantId, actorId, batchId, expectedVersion) == 1;
    }

    public boolean touchBatchForReplan(
            long tenantId, long actorId, UUID batchId, long expectedVersion,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_booking_batches
                   SET version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND actor_user_id = ? AND batch_id = ?
                   AND version = ?
                   AND batch_state IN ('PARTIAL', 'FAILED', 'COMPENSATED')
                """, now, tenantId, actorId, batchId, expectedVersion) == 1;
    }

    public boolean finishBatchExecution(
            long tenantId,
            UUID batchId,
            UUID claimToken,
            BatchState state,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_booking_batches
                   SET batch_state = ?, completed_at = ?,
                       execution_claim_token = NULL,
                       execution_lease_expires_at = NULL,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND batch_id = ?
                   AND execution_claim_token = ?
                   AND batch_state IN ('PROCESSING', 'COMPENSATING')
                   AND NOT EXISTS (
                       SELECT 1 FROM wp_booking_batch_items item
                        WHERE item.tenant_id = wp_booking_batches.tenant_id
                          AND item.batch_id = wp_booking_batches.batch_id
                          AND item.item_state IN (
                              'PENDING', 'PROCESSING', 'COMPENSATION_PENDING'))
                """, state.name(), now, now, tenantId, batchId, claimToken) == 1;
    }
}

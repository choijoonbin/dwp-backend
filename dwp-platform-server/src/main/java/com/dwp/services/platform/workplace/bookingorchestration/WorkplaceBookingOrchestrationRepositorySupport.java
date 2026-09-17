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

public abstract class WorkplaceBookingOrchestrationRepositorySupport {
    protected final JdbcTemplate jdbc;
    protected final ObjectMapper objectMapper;

    protected WorkplaceBookingOrchestrationRepositorySupport(
            JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<WaitlistRow> waitlistByIdempotency(long tenantId, long actorId, String key) {
        return jdbc.query("""
                SELECT * FROM wp_waitlist_entries
                 WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
                """, this::waitlist, tenantId, actorId, key).stream().findFirst();
    }

    public Optional<WaitlistRow> waitlist(long tenantId, long actorId, UUID entryId) {
        return jdbc.query("""
                SELECT * FROM wp_waitlist_entries
                 WHERE tenant_id = ? AND actor_user_id = ? AND waitlist_entry_id = ?
                """, this::waitlist, tenantId, actorId, entryId).stream().findFirst();
    }

    public List<WaitlistRow> waitlists(
            long tenantId,
            long actorId,
            OffsetDateTime from,
            OffsetDateTime to,
            Long beneficiaryUserId,
            int page,
            int size) {
        return jdbc.query("""
                SELECT * FROM wp_waitlist_entries
                 WHERE tenant_id = ? AND actor_user_id = ?
                   AND starts_at < ? AND ends_at > ?
                   AND (?::bigint IS NULL OR beneficiary_user_id = ?)
                 ORDER BY starts_at, waitlist_entry_id
                 LIMIT ? OFFSET ?
                """, this::waitlist, tenantId, actorId, to, from,
                beneficiaryUserId, beneficiaryUserId, size, page * size);
    }

    public long waitlistCount(
            long tenantId,
            long actorId,
            OffsetDateTime from,
            OffsetDateTime to,
            Long beneficiaryUserId) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_waitlist_entries
                 WHERE tenant_id = ? AND actor_user_id = ?
                   AND starts_at < ? AND ends_at > ?
                   AND (?::bigint IS NULL OR beneficiary_user_id = ?)
                """, Long.class, tenantId, actorId, to, from,
                beneficiaryUserId, beneficiaryUserId);
        return value == null ? 0 : value;
    }

    public List<WaitlistKey> promotionCandidateKeys(OffsetDateTime now, int limit) {
        return jdbc.query("""
                SELECT tenant_id, waitlist_entry_id
                  FROM wp_waitlist_entries
                 WHERE waitlist_state = 'ACTIVE'
                   AND promotion_evaluation_state <> 'BLOCKED'
                   AND starts_at > ?
                   AND COALESCE(latest_end, ends_at) > ?
                 ORDER BY created_at, tenant_id, waitlist_entry_id
                 LIMIT ?
                """, (rs, ignored) -> new WaitlistKey(
                rs.getLong("tenant_id"), rs.getObject("waitlist_entry_id", UUID.class)),
                now, now, limit);
    }

    public Optional<WaitlistRow> lockWaitlistForPromotion(
            long tenantId, UUID entryId, OffsetDateTime now) {
        return jdbc.query("""
                SELECT * FROM wp_waitlist_entries
                 WHERE tenant_id = ? AND waitlist_entry_id = ?
                   AND waitlist_state = 'ACTIVE'
                   AND promotion_evaluation_state <> 'BLOCKED'
                   AND starts_at > ?
                   AND COALESCE(latest_end, ends_at) > ?
                 FOR UPDATE SKIP LOCKED
                """, this::waitlist, tenantId, entryId, now, now).stream().findFirst();
    }

    public Optional<WaitlistRow> waitlist(long tenantId, UUID entryId) {
        return jdbc.query("""
                SELECT * FROM wp_waitlist_entries
                 WHERE tenant_id = ? AND waitlist_entry_id = ?
                """, this::waitlist, tenantId, entryId).stream().findFirst();
    }

    public boolean recordPromotionEvaluation(
            WaitlistRow entry,
            PromotionEvaluationState state,
            String decisionCode,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_waitlist_entries
                   SET promotion_evaluation_state = ?, promotion_decision_code = ?,
                       promotion_evaluated_at = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND waitlist_entry_id = ?
                   AND waitlist_state = 'ACTIVE'
                   AND (promotion_evaluation_state IS DISTINCT FROM ?
                        OR promotion_decision_code IS DISTINCT FROM ?)
                """, state.name(), decisionCode, now, now, entry.tenantId(), entry.entryId(),
                state.name(), decisionCode) == 1;
    }

    public List<OfferKey> expiredOfferKeys(OffsetDateTime now, int limit) {
        return jdbc.query("""
                SELECT tenant_id, offer_id
                  FROM wp_alternative_offers
                 WHERE offer_state = 'OFFERED' AND expires_at <= ?
                 ORDER BY expires_at, tenant_id, offer_id
                 LIMIT ?
                """, (rs, ignored) -> new OfferKey(
                rs.getLong("tenant_id"), rs.getObject("offer_id", UUID.class)), now, limit);
    }

    public Optional<OfferRow> lockExpiredOffer(
            long tenantId, UUID offerId, OffsetDateTime now) {
        return jdbc.query("""
                SELECT * FROM wp_alternative_offers
                 WHERE tenant_id = ? AND offer_id = ?
                   AND offer_state = 'OFFERED' AND expires_at <= ?
                 FOR UPDATE SKIP LOCKED
                """, this::offer, tenantId, offerId, now).stream().findFirst();
    }

    public void expireOffer(OfferRow offer, OffsetDateTime now) {
        int updated = jdbc.update("""
                UPDATE wp_alternative_offers
                   SET offer_state = 'EXPIRED', version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND offer_id = ? AND version = ?
                   AND offer_state = 'OFFERED' AND expires_at <= ?
                """, now, offer.tenantId(), offer.offerId(), offer.version(), now);
        if (updated != 1) {
            throw new IllegalStateException("Alternative offer changed before expiry.");
        }
        jdbc.update("""
                UPDATE wp_reservation_holds
                   SET hold_state = 'EXPIRED', version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND hold_id = ? AND hold_state = 'ACTIVE'
                """, now, offer.tenantId(), offer.holdId());
        jdbc.update("""
                UPDATE wp_waitlist_entries
                   SET waitlist_state = CASE
                           WHEN starts_at > ? AND COALESCE(latest_end, ends_at) > ?
                           THEN 'ACTIVE' ELSE 'EXPIRED' END,
                       promotion_evaluation_state = 'PENDING',
                       promotion_decision_code = 'OFFER_EXPIRED',
                       promotion_evaluated_at = ?,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND waitlist_entry_id = ?
                   AND waitlist_state = 'OFFERED'
                """, now, now, now, now, offer.tenantId(), offer.waitlistEntryId());
    }

    public void createWaitlist(WaitlistRow row) {
        jdbc.update("""
                INSERT INTO wp_waitlist_entries (
                    waitlist_entry_id, tenant_id, actor_user_id, beneficiary_user_id,
                    beneficiary_person_public_id, beneficiary_display_name, delegation_grant_id,
                    resource_type, preferred_resource_id, site_id, floor_id, starts_at, ends_at,
                    purpose, visible_to_colleagues, accessible_only, required_features,
                    auto_confirm, maximum_distance_meters,
                    earliest_start, latest_end, pricing_mode, maximum_price, currency,
                    notification_channels, waitlist_state,
                    promotion_evaluation_state, promotion_decision_code,
                    promotion_evaluated_at,
                    rank_visible, rank_value, idempotency_key, request_fingerprint,
                    correlation_id, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                        ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.entryId(), row.tenantId(), row.actorUserId(), row.beneficiaryUserId(),
                row.beneficiaryPersonPublicId(), row.beneficiaryDisplayName(), row.delegationGrantId(),
                row.resourceType().name(), row.preferredResourceId(), row.siteId(), row.floorId(),
                row.startsAt(), row.endsAt(), row.purpose(), row.visibleToColleagues(),
                row.accessibleOnly(), json(row.requiredFeatures()), row.autoConfirm(),
                row.maximumDistanceMeters(), row.earliestStart(), row.latestEnd(),
                row.pricingMode().name(), row.maximumPrice(), row.currency(),
                json(row.notificationChannels().stream().map(Enum::name).toList()), row.state().name(),
                row.promotionEvaluationState().name(), row.promotionDecisionCode(),
                row.promotionEvaluatedAt(),
                row.rankVisible(), row.rank(), row.idempotencyKey(), row.requestFingerprint(),
                row.correlationId(), row.version(), row.createdAt(), row.updatedAt());
    }

    public boolean updateWaitlist(
            long tenantId,
            long actorId,
            UUID entryId,
            long expectedVersion,
            boolean autoConfirm,
            Integer maximumDistanceMeters,
            OffsetDateTime earliestStart,
            OffsetDateTime latestEnd,
            PricingMode pricingMode,
            BigDecimal maximumPrice,
            String currency,
            List<NotificationChannel> channels,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_waitlist_entries
                   SET auto_confirm = ?, maximum_distance_meters = ?, earliest_start = ?,
                       latest_end = ?, pricing_mode = ?, maximum_price = ?, currency = ?,
                       notification_channels = ?::jsonb,
                       promotion_evaluation_state = 'PENDING',
                       promotion_decision_code = NULL, promotion_evaluated_at = NULL,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND actor_user_id = ? AND waitlist_entry_id = ?
                   AND version = ? AND waitlist_state IN ('ACTIVE', 'OFFERED')
                """, autoConfirm, maximumDistanceMeters, earliestStart, latestEnd,
                pricingMode.name(), maximumPrice, currency,
                json(channels.stream().map(Enum::name).toList()), now, tenantId, actorId,
                entryId, expectedVersion) == 1;
    }

    public boolean cancelWaitlist(
            long tenantId,
            long actorId,
            UUID entryId,
            long expectedVersion,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_waitlist_entries
                   SET waitlist_state = 'CANCELLED', version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND actor_user_id = ? AND waitlist_entry_id = ?
                   AND version = ? AND waitlist_state IN ('ACTIVE', 'OFFERED')
                """, now, tenantId, actorId, entryId, expectedVersion) == 1;
    }

    public Optional<OfferRow> activeOffer(long tenantId, UUID entryId) {
        return jdbc.query("""
                SELECT * FROM wp_alternative_offers
                 WHERE tenant_id = ? AND waitlist_entry_id = ?
                   AND offer_state IN ('OFFERED', 'ACCEPTING', 'ACCEPTED', 'RESULT_UNKNOWN')
                 ORDER BY created_at DESC LIMIT 1
                """, this::offer, tenantId, entryId).stream().findFirst();
    }

    public Optional<OfferRow> offer(long tenantId, long actorId, UUID offerId) {
        return jdbc.query("""
                SELECT offer.* FROM wp_alternative_offers offer
                  JOIN wp_waitlist_entries entry
                    ON entry.tenant_id = offer.tenant_id
                   AND entry.waitlist_entry_id = offer.waitlist_entry_id
                 WHERE offer.tenant_id = ? AND entry.actor_user_id = ? AND offer.offer_id = ?
                """, this::offer, tenantId, actorId, offerId).stream().findFirst();
    }

    public Optional<OfferRow> offerByAcceptedBatch(long tenantId, UUID batchId) {
        return jdbc.query("""
                SELECT * FROM wp_alternative_offers
                 WHERE tenant_id = ? AND accepted_batch_id = ?
                 ORDER BY created_at DESC LIMIT 1
                """, this::offer, tenantId, batchId).stream().findFirst();
    }

    public void reconcileOfferBatch(
            OfferRow offer, BatchState batchState, OffsetDateTime now) {
        OfferState offerState;
        WaitlistState waitlistState;
        if (batchState == BatchState.SUCCEEDED) {
            offerState = OfferState.ACCEPTED;
            waitlistState = WaitlistState.CONFIRMED;
        } else if (batchState == BatchState.RESULT_UNKNOWN) {
            offerState = OfferState.RESULT_UNKNOWN;
            waitlistState = WaitlistState.CONFIRMING;
        } else {
            offerState = OfferState.WITHDRAWN;
            WaitlistRow entry = waitlist(offer.tenantId(), offer.waitlistEntryId())
                    .orElseThrow(() -> new IllegalStateException("Waitlist entry no longer exists."));
            waitlistState = entry.startsAt().isAfter(now)
                    && (entry.latestEnd() == null || entry.latestEnd().isAfter(now))
                    ? WaitlistState.ACTIVE : WaitlistState.EXPIRED;
        }
        int updated = jdbc.update("""
                UPDATE wp_alternative_offers
                   SET offer_state = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND offer_id = ? AND version = ?
                   AND offer_state IN ('ACCEPTED', 'RESULT_UNKNOWN')
                """, offerState.name(), now, offer.tenantId(), offer.offerId(), offer.version());
        if (updated != 1) {
            throw new IllegalStateException("Alternative offer changed during batch reconciliation.");
        }
        jdbc.update("""
                UPDATE wp_waitlist_entries
                   SET waitlist_state = ?, promotion_evaluation_state = 'MATCHED',
                       promotion_decision_code = ?, promotion_evaluated_at = ?,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND waitlist_entry_id = ?
                   AND waitlist_state = 'CONFIRMING'
                """, waitlistState.name(), "BATCH_" + batchState.name(), now, now,
                offer.tenantId(), offer.waitlistEntryId());
        if (offerState == OfferState.WITHDRAWN) {
            jdbc.update("""
                    UPDATE wp_reservation_holds
                       SET hold_state = 'RELEASED', version = version + 1, updated_at = ?
                     WHERE tenant_id = ? AND hold_id = ?
                       AND hold_state IN ('ACTIVE', 'BATCHED')
                    """, now, offer.tenantId(), offer.holdId());
        }
    }

    public void createOffer(OfferRow row, String decisionCode) {
        jdbc.update("""
                INSERT INTO wp_alternative_offers (
                    offer_id, tenant_id, waitlist_entry_id, hold_id, resource_id,
                    offer_state, expires_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.offerId(), row.tenantId(), row.waitlistEntryId(), row.holdId(),
                row.resourceId(), row.state().name(), row.expiresAt(), row.version(),
                row.createdAt(), row.updatedAt());
        int updated = jdbc.update("""
                UPDATE wp_waitlist_entries SET waitlist_state = 'OFFERED',
                       promotion_evaluation_state = 'MATCHED',
                       promotion_decision_code = ?,
                       promotion_evaluated_at = ?,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND waitlist_entry_id = ? AND waitlist_state = 'ACTIVE'
                """, decisionCode, row.updatedAt(), row.updatedAt(),
                row.tenantId(), row.waitlistEntryId());
        if (updated != 1) {
            throw new IllegalStateException("Waitlist entry changed before offer creation.");
        }
    }

    public boolean beginOfferAcceptance(
            long tenantId, UUID offerId, long expectedVersion, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_alternative_offers
                   SET offer_state = 'ACCEPTING', version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND offer_id = ? AND version = ?
                   AND offer_state = 'OFFERED' AND expires_at > ?
                """, now, tenantId, offerId, expectedVersion, now) == 1;
    }

    public void completeOfferAcceptance(
            long tenantId, UUID offerId, UUID batchId, OfferState state, OffsetDateTime now) {
        int updated = jdbc.update("""
                UPDATE wp_alternative_offers
                   SET offer_state = ?, accepted_batch_id = ?, version = version + 1,
                       updated_at = ?
                 WHERE tenant_id = ? AND offer_id = ? AND offer_state = 'ACCEPTING'
                """, state.name(), batchId, now, tenantId, offerId);
        if (updated != 1) throw new IllegalStateException("Alternative offer changed concurrently.");
        jdbc.update("""
                UPDATE wp_waitlist_entries entry
                   SET waitlist_state = 'CONFIRMING', version = entry.version + 1,
                       updated_at = ?
                  FROM wp_alternative_offers offer
                 WHERE offer.tenant_id = entry.tenant_id
                   AND offer.waitlist_entry_id = entry.waitlist_entry_id
                   AND offer.tenant_id = ? AND offer.offer_id = ?
                   AND entry.waitlist_state = 'OFFERED'
                """, now, tenantId, offerId);
    }

    public void auditAndOutbox(
            long tenantId,
            long actorId,
            String action,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String eventType,
            String correlationId,
            Object payload,
            OffsetDateTime now) {
        String snapshot = json(payload);
        jdbc.update("""
                INSERT INTO wp_audit_events (
                    audit_event_id, tenant_id, action, aggregate_type, aggregate_id,
                    actor_user_id, correlation_id, snapshot, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), tenantId, action, aggregateType, aggregateId,
                actorId, correlationId, snapshot, now);
        jdbc.update("""
                INSERT INTO wp_booking_orchestration_outbox (
                    outbox_id, tenant_id, aggregate_type, aggregate_id, aggregate_version,
                    event_type, payload, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """, UUID.randomUUID(), tenantId, aggregateType, aggregateId,
                aggregateVersion, eventType, snapshot, correlationId, now);
    }

    protected IntentRow intent(ResultSet rs, int row) throws SQLException {
        return new IntentRow(rs.getObject("intent_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"), IntentState.valueOf(rs.getString("intent_state")),
                rs.getString("reason"), rs.getInt("requested_hold_ttl_seconds"),
                rs.getBoolean("allow_alternatives"), rs.getString("idempotency_key"),
                placementConstraints(rs.getString("team_placement_constraints")),
                placementEvidence(rs.getString("placement_constraint_evidence")),
                rs.getString("request_fingerprint"), rs.getString("correlation_id"),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    protected IntentItemRow intentItem(ResultSet rs, int row) throws SQLException {
        return new IntentItemRow(rs.getObject("intent_item_id", UUID.class),
                rs.getObject("intent_id", UUID.class), rs.getLong("tenant_id"),
                rs.getString("client_item_key"), rs.getLong("actor_user_id"),
                rs.getLong("beneficiary_user_id"),
                rs.getObject("beneficiary_person_public_id", UUID.class),
                rs.getString("beneficiary_display_name"),
                rs.getObject("delegation_grant_id", UUID.class),
                ResourceType.valueOf(rs.getString("resource_type")),
                rs.getObject("preferred_resource_id", UUID.class),
                rs.getObject("site_id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("ends_at", OffsetDateTime.class), rs.getString("purpose"),
                rs.getBoolean("visible_to_colleagues"), rs.getBoolean("accessible_only"),
                strings(rs.getString("required_features")),
                IntentItemDecision.valueOf(rs.getString("decision")), rs.getString("decision_code"),
                uuids(rs.getString("candidate_resource_ids")), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    protected CandidateRow candidate(ResultSet rs, int row) throws SQLException {
        return new CandidateRow(rs.getObject("resource_id", UUID.class),
                rs.getObject("calendar_resource_id", UUID.class), rs.getString("name_ko"),
                rs.getString("name_en"), ResourceType.valueOf(rs.getString("resource_type")),
                rs.getObject("site_id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getString("time_zone"), rs.getBoolean("accessible"),
                strings(rs.getString("features")), rs.getString("neighborhood"),
                rs.getBigDecimal("position_x"), rs.getBigDecimal("position_y"),
                rs.getBigDecimal("width_percent"), rs.getBigDecimal("height_percent"),
                rs.getBoolean("preferred"),
                rs.getLong("version"));
    }

    protected HoldRow hold(ResultSet rs, int row) throws SQLException {
        return new HoldRow(rs.getObject("hold_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("intent_id", UUID.class), rs.getObject("intent_item_id", UUID.class),
                rs.getObject("resource_id", UUID.class), rs.getLong("actor_user_id"),
                rs.getLong("beneficiary_user_id"), HoldState.valueOf(rs.getString("hold_state")),
                rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("ends_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    protected BatchRow batch(ResultSet rs, int row) throws SQLException {
        return new BatchRow(rs.getObject("batch_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("intent_id", UUID.class), rs.getLong("actor_user_id"),
                BatchState.valueOf(rs.getString("batch_state")),
                FailurePolicy.valueOf(rs.getString("failure_policy")), rs.getString("reason"),
                rs.getBoolean("explicit_confirmation"), rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"), rs.getString("correlation_id"),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("execution_claim_token", UUID.class),
                rs.getObject("execution_lease_expires_at", OffsetDateTime.class),
                rs.getInt("execution_attempt_count"));
    }

    protected BatchItemRow batchItem(ResultSet rs, int row) throws SQLException {
        return new BatchItemRow(rs.getObject("batch_item_id", UUID.class),
                rs.getObject("batch_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("intent_id", UUID.class), rs.getObject("intent_item_id", UUID.class),
                rs.getObject("hold_id", UUID.class),
                BookingAuthority.valueOf(rs.getString("authority")),
                BatchItemState.valueOf(rs.getString("item_state")),
                rs.getObject("owner_reference_id", UUID.class), nullableLong(rs, "owner_version"),
                rs.getString("error_code"), rs.getString("error_message"),
                rs.getBoolean("compensation_available"), rs.getBoolean("requery_required"),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getString("client_item_key"),
                rs.getLong("beneficiary_user_id"),
                rs.getObject("beneficiary_person_public_id", UUID.class),
                rs.getString("beneficiary_display_name"),
                rs.getObject("delegation_grant_id", UUID.class),
                ResourceType.valueOf(rs.getString("resource_type")), rs.getString("purpose"),
                rs.getBoolean("visible_to_colleagues"), rs.getObject("resource_id", UUID.class),
                rs.getObject("calendar_resource_id", UUID.class), rs.getString("resource_name"),
                rs.getObject("site_id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getString("time_zone"), rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("ends_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class));
    }

    protected WaitlistRow waitlist(ResultSet rs, int row) throws SQLException {
        return new WaitlistRow(rs.getObject("waitlist_entry_id", UUID.class),
                rs.getLong("tenant_id"), rs.getLong("actor_user_id"),
                rs.getLong("beneficiary_user_id"),
                rs.getObject("beneficiary_person_public_id", UUID.class),
                rs.getString("beneficiary_display_name"),
                rs.getObject("delegation_grant_id", UUID.class),
                ResourceType.valueOf(rs.getString("resource_type")),
                rs.getObject("preferred_resource_id", UUID.class),
                rs.getObject("site_id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("ends_at", OffsetDateTime.class), rs.getString("purpose"),
                rs.getBoolean("visible_to_colleagues"), rs.getBoolean("accessible_only"),
                strings(rs.getString("required_features")), rs.getBoolean("auto_confirm"),
                nullableInteger(rs, "maximum_distance_meters"),
                rs.getObject("earliest_start", OffsetDateTime.class),
                rs.getObject("latest_end", OffsetDateTime.class),
                PricingMode.valueOf(rs.getString("pricing_mode")),
                rs.getBigDecimal("maximum_price"), rs.getString("currency"),
                enums(rs.getString("notification_channels"), NotificationChannel.class),
                WaitlistState.valueOf(rs.getString("waitlist_state")),
                PromotionEvaluationState.valueOf(rs.getString("promotion_evaluation_state")),
                rs.getString("promotion_decision_code"),
                rs.getObject("promotion_evaluated_at", OffsetDateTime.class),
                rs.getBoolean("rank_visible"), nullableInteger(rs, "rank_value"),
                rs.getString("idempotency_key"), rs.getString("request_fingerprint"),
                rs.getString("correlation_id"), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    protected OfferRow offer(ResultSet rs, int row) throws SQLException {
        return new OfferRow(rs.getObject("offer_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("waitlist_entry_id", UUID.class), rs.getObject("hold_id", UUID.class),
                rs.getObject("resource_id", UUID.class), OfferState.valueOf(rs.getString("offer_state")),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("accepted_batch_id", UUID.class), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    protected String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Booking orchestration JSON could not be serialized.", exception);
        }
    }

    protected List<String> strings(String value) {
        if (value == null) return List.of();
        try {
            return Arrays.asList(objectMapper.readValue(value, String[].class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted booking orchestration JSON is invalid.", exception);
        }
    }

    protected List<UUID> uuids(String value) {
        return strings(value).stream().map(UUID::fromString).toList();
    }

    protected List<TeamPlacementConstraint> placementConstraints(String value) {
        if (value == null) return List.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted team placement constraints are invalid.", exception);
        }
    }

    protected List<TeamPlacementConstraintEvidence> placementEvidence(String value) {
        if (value == null) return List.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted placement evidence is invalid.", exception);
        }
    }

    protected <E extends Enum<E>> List<E> enums(String value, Class<E> type) {
        return strings(value).stream().map(item -> Enum.valueOf(type, item)).toList();
    }

    protected static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    protected static Integer nullableInteger(ResultSet rs, String name) throws SQLException {
        int value = rs.getInt(name);
        return rs.wasNull() ? null : value;
    }

    protected static String truncate(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value;
        return value.substring(0, maximum);
    }

    protected static String groupCsv(String verifiedGroupRefs) {
        return WorkplaceBookingOrchestrationGroupRefs.csv(verifiedGroupRefs);
    }

    public record IntentRow(
            UUID intentId, long tenantId, long actorUserId, IntentState state,
            String reason, int holdTtlSeconds, boolean allowAlternatives,
            String idempotencyKey, List<TeamPlacementConstraint> teamPlacementConstraints,
            List<TeamPlacementConstraintEvidence> placementConstraintEvidence,
            String requestFingerprint, String correlationId,
            long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record IntentItemRow(
            UUID itemId, UUID intentId, long tenantId, String clientItemKey,
            long actorUserId, long beneficiaryUserId, UUID beneficiaryPersonPublicId,
            String beneficiaryDisplayName, UUID delegationGrantId, ResourceType resourceType,
            UUID preferredResourceId, UUID siteId, UUID floorId,
            OffsetDateTime startsAt, OffsetDateTime endsAt, String purpose,
            boolean visibleToColleagues, boolean accessibleOnly, List<String> requiredFeatures,
            IntentItemDecision decision, String decisionCode, List<UUID> candidateResourceIds,
            long version, OffsetDateTime createdAt) { }

    public record CandidateRow(
            UUID resourceId, UUID calendarResourceId, String nameKo, String nameEn,
            ResourceType resourceType, UUID siteId, UUID floorId, String timeZone,
            boolean accessible, List<String> features, String neighborhood,
            BigDecimal positionX, BigDecimal positionY,
            BigDecimal widthPercent, BigDecimal heightPercent,
            boolean preferred, long version) { }

    public record ResourcePresentationRow(
            String resourceDisplayName, UUID siteId, UUID floorId, String timeZone) { }

    public record HoldRow(
            UUID holdId, long tenantId, UUID intentId, UUID intentItemId, UUID resourceId,
            long actorUserId, long beneficiaryUserId, HoldState state,
            OffsetDateTime startsAt, OffsetDateTime endsAt, OffsetDateTime expiresAt,
            long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
    public record BatchRow(
            UUID batchId, long tenantId, UUID intentId, long actorUserId, BatchState state,
            FailurePolicy failurePolicy, String reason, boolean explicitConfirmation,
            String idempotencyKey, String requestFingerprint, String correlationId,
            long version, OffsetDateTime createdAt, OffsetDateTime startedAt,
            OffsetDateTime completedAt, OffsetDateTime updatedAt, UUID executionClaimToken,
            OffsetDateTime executionLeaseExpiresAt, int executionAttemptCount) { }
    public record BatchItemRow(
            UUID batchItemId, UUID batchId, long tenantId, UUID intentId,
            UUID intentItemId, UUID holdId,
            BookingAuthority authority, BatchItemState state, UUID ownerReferenceId,
            Long ownerVersion, String errorCode, String errorMessage,
            boolean compensationAvailable, boolean requeryRequired, long version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, String clientItemKey,
            long beneficiaryUserId, UUID beneficiaryPersonPublicId,
            String beneficiaryDisplayName, UUID delegationGrantId, ResourceType resourceType,
            String purpose, boolean visibleToColleagues, UUID resourceId,
            UUID calendarResourceId, String resourceName, UUID siteId, UUID floorId,
            String timeZone,
            OffsetDateTime startsAt, OffsetDateTime endsAt, OffsetDateTime holdExpiresAt) { }

    public record WaitlistRow(
            UUID entryId, long tenantId, long actorUserId, long beneficiaryUserId,
            UUID beneficiaryPersonPublicId, String beneficiaryDisplayName,
            UUID delegationGrantId, ResourceType resourceType, UUID preferredResourceId,
            UUID siteId, UUID floorId, OffsetDateTime startsAt, OffsetDateTime endsAt,
            String purpose, boolean visibleToColleagues, boolean accessibleOnly,
            List<String> requiredFeatures, boolean autoConfirm,
            Integer maximumDistanceMeters, OffsetDateTime earliestStart, OffsetDateTime latestEnd,
            PricingMode pricingMode, BigDecimal maximumPrice, String currency,
            List<NotificationChannel> notificationChannels, WaitlistState state,
            PromotionEvaluationState promotionEvaluationState,
            String promotionDecisionCode, OffsetDateTime promotionEvaluatedAt,
            boolean rankVisible, Integer rank, String idempotencyKey, String requestFingerprint,
            String correlationId, long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record OfferRow(
            UUID offerId, long tenantId, UUID waitlistEntryId, UUID holdId, UUID resourceId,
            OfferState state, OffsetDateTime expiresAt, UUID acceptedBatchId,
            long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record WaitlistKey(long tenantId, UUID entryId) { }

    public record OfferKey(long tenantId, UUID offerId) { }

    public record BeneficiaryGrantRow(
            UUID grantId, long beneficiaryUserId, UUID beneficiaryPersonPublicId,
            String beneficiaryDisplayName, List<ResourceType> resourceTypes,
            OffsetDateTime validUntil) { }

    public record CommandReceiptRow(
            long tenantId, long actorUserId, String scope, String idempotencyKey,
            String requestFingerprint, String aggregateType, UUID aggregateId,
            OffsetDateTime createdAt) { }
}

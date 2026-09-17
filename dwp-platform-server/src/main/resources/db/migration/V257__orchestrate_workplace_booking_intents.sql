CREATE TABLE wp_booking_delegate_grants (
    grant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT,
    actor_group_ref UUID,
    beneficiary_user_id BIGINT NOT NULL,
    beneficiary_person_public_id UUID,
    beneficiary_display_name VARCHAR(160) NOT NULL,
    resource_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    valid_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_until TIMESTAMPTZ,
    grant_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wp_booking_delegate_tenant_grant UNIQUE (tenant_id, grant_id),
    updated_by BIGINT NOT NULL,
    CONSTRAINT ck_wp_booking_delegate_principals CHECK (
        ((actor_user_id IS NOT NULL AND actor_user_id > 0 AND actor_group_ref IS NULL)
         OR (actor_user_id IS NULL AND actor_group_ref IS NOT NULL))
        AND beneficiary_user_id > 0
        AND (actor_user_id IS NULL OR actor_user_id <> beneficiary_user_id)),
    CONSTRAINT ck_wp_booking_delegate_resource_types CHECK (
        jsonb_typeof(resource_types) = 'array'),
    CONSTRAINT ck_wp_booking_delegate_period CHECK (
        valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_wp_booking_delegate_state CHECK (
        grant_state IN ('ACTIVE', 'REVOKED', 'EXPIRED'))
);

CREATE INDEX idx_wp_booking_delegate_lookup
    ON wp_booking_delegate_grants (
        tenant_id, beneficiary_user_id, grant_state, valid_from, valid_until);
CREATE INDEX idx_wp_booking_delegate_actor
    ON wp_booking_delegate_grants (tenant_id, actor_user_id)
    WHERE actor_user_id IS NOT NULL;
CREATE INDEX idx_wp_booking_delegate_group
    ON wp_booking_delegate_grants (tenant_id, actor_group_ref)
    WHERE actor_group_ref IS NOT NULL;

CREATE TABLE wp_booking_intents (
    intent_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    intent_state VARCHAR(24) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    requested_hold_ttl_seconds INTEGER NOT NULL,
    allow_alternatives BOOLEAN NOT NULL,
    team_placement_constraints JSONB NOT NULL DEFAULT '[]'::jsonb,
    placement_constraint_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wp_booking_intent_tenant_intent UNIQUE (tenant_id, intent_id),
    CONSTRAINT uk_wp_booking_intent_idempotency UNIQUE (
        tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT ck_wp_booking_intent_state CHECK (
        intent_state IN ('PREVIEWED', 'HELD', 'CONFIRMING', 'COMPLETED', 'EXPIRED')),
    CONSTRAINT ck_wp_booking_intent_reason CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_wp_booking_intent_ttl CHECK (requested_hold_ttl_seconds BETWEEN 30 AND 300),
    CONSTRAINT ck_wp_booking_intent_placement_json CHECK (
        jsonb_typeof(team_placement_constraints) = 'array'
        AND jsonb_typeof(placement_constraint_evidence) = 'array'),
    CONSTRAINT ck_wp_booking_intent_key CHECK (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_booking_intent_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE TABLE wp_booking_intent_items (
    intent_item_id UUID PRIMARY KEY,
    intent_id UUID NOT NULL REFERENCES wp_booking_intents(intent_id) ON DELETE CASCADE,
    tenant_id BIGINT NOT NULL,
    client_item_key VARCHAR(120) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    beneficiary_user_id BIGINT NOT NULL,
    beneficiary_person_public_id UUID,
    beneficiary_display_name VARCHAR(160) NOT NULL,
    delegation_grant_id UUID,
    resource_type VARCHAR(24) NOT NULL,
    preferred_resource_id UUID,
    site_id UUID,
    floor_id UUID,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    purpose VARCHAR(500),
    visible_to_colleagues BOOLEAN NOT NULL DEFAULT TRUE,
    accessible_only BOOLEAN NOT NULL DEFAULT FALSE,
    required_features JSONB NOT NULL DEFAULT '[]'::jsonb,
    decision VARCHAR(32) NOT NULL,
    decision_code VARCHAR(80),
    candidate_resource_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wp_booking_intent_item_key UNIQUE (intent_id, client_item_key),
    CONSTRAINT uk_wp_booking_intent_tenant_item UNIQUE (tenant_id, intent_item_id),
    CONSTRAINT uk_wp_booking_intent_tenant_intent_item UNIQUE (
        tenant_id, intent_id, intent_item_id),
    CONSTRAINT fk_wp_booking_intent_item_intent FOREIGN KEY (tenant_id, intent_id)
        REFERENCES wp_booking_intents(tenant_id, intent_id) ON DELETE CASCADE,
    CONSTRAINT fk_wp_booking_intent_item_delegate FOREIGN KEY (tenant_id, delegation_grant_id)
        REFERENCES wp_booking_delegate_grants(tenant_id, grant_id),
    CONSTRAINT fk_wp_booking_intent_item_tenant_resource FOREIGN KEY (tenant_id, preferred_resource_id)
        REFERENCES wp_resources(tenant_id, resource_id),
    CONSTRAINT fk_wp_booking_intent_item_tenant_site FOREIGN KEY (tenant_id, site_id)
        REFERENCES wp_sites(tenant_id, site_id),
    CONSTRAINT fk_wp_booking_intent_item_tenant_floor FOREIGN KEY (tenant_id, floor_id)
        REFERENCES wp_floors(tenant_id, floor_id),
    CONSTRAINT ck_wp_booking_intent_item_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_wp_booking_intent_item_principals CHECK (
        actor_user_id > 0 AND beneficiary_user_id > 0),
    CONSTRAINT ck_wp_booking_intent_item_type CHECK (resource_type IN (
        'ROOM', 'DESK', 'LOCKER', 'PARKING', 'FOCUS_POD', 'PHONE_BOOTH', 'EQUIPMENT')),
    CONSTRAINT ck_wp_booking_intent_item_features CHECK (jsonb_typeof(required_features) = 'array'),
    CONSTRAINT ck_wp_booking_intent_item_candidates CHECK (jsonb_typeof(candidate_resource_ids) = 'array'),
    CONSTRAINT ck_wp_booking_intent_item_decision CHECK (decision IN (
        'AVAILABLE', 'ALTERNATIVES_AVAILABLE', 'UNAVAILABLE', 'POLICY_DENIED'))
);

CREATE INDEX idx_wp_booking_intent_items_intent
    ON wp_booking_intent_items (tenant_id, intent_id, starts_at, intent_item_id);

CREATE TABLE wp_reservation_holds (
    hold_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    intent_id UUID NOT NULL REFERENCES wp_booking_intents(intent_id),
    intent_item_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    beneficiary_user_id BIGINT NOT NULL,
    hold_state VARCHAR(20) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wp_reservation_hold_item UNIQUE (tenant_id, intent_item_id),
    CONSTRAINT uk_wp_reservation_hold_tenant_hold UNIQUE (tenant_id, hold_id),
    CONSTRAINT uk_wp_reservation_hold_intent_item_hold UNIQUE (
        tenant_id, intent_id, intent_item_id, hold_id),
    CONSTRAINT fk_wp_reservation_hold_intent_item FOREIGN KEY (
        tenant_id, intent_id, intent_item_id)
        REFERENCES wp_booking_intent_items(
            tenant_id, intent_id, intent_item_id),
    CONSTRAINT fk_wp_reservation_hold_resource FOREIGN KEY (tenant_id, resource_id)
        REFERENCES wp_resources(tenant_id, resource_id),
    CONSTRAINT ck_wp_reservation_hold_state CHECK (
        hold_state IN ('ACTIVE', 'BATCHED', 'CONSUMED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT ck_wp_reservation_hold_period CHECK (
        ends_at > starts_at AND expires_at > created_at)
);

CREATE INDEX idx_wp_reservation_hold_conflict
    ON wp_reservation_holds (tenant_id, resource_id, starts_at, ends_at, expires_at)
    WHERE hold_state IN ('ACTIVE', 'BATCHED');

CREATE TABLE wp_booking_batches (
    batch_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    intent_id UUID NOT NULL REFERENCES wp_booking_intents(intent_id),
    actor_user_id BIGINT NOT NULL,
    batch_state VARCHAR(32) NOT NULL,
    failure_policy VARCHAR(24) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    explicit_confirmation BOOLEAN NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wp_booking_batch_tenant_batch UNIQUE (tenant_id, batch_id),
    CONSTRAINT uk_wp_booking_batch_tenant_batch_intent UNIQUE (
        tenant_id, batch_id, intent_id),
    CONSTRAINT uk_wp_booking_batch_idempotency UNIQUE (
        tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT fk_wp_booking_batch_intent FOREIGN KEY (tenant_id, intent_id)
        REFERENCES wp_booking_intents(tenant_id, intent_id),
    CONSTRAINT ck_wp_booking_batch_state CHECK (batch_state IN (
        'ACCEPTED', 'PROCESSING', 'SUCCEEDED', 'PARTIAL', 'FAILED',
        'COMPENSATING', 'COMPENSATED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_booking_batch_policy CHECK (
        failure_policy IN ('KEEP_SUCCEEDED', 'COMPENSATE_ALL')),
    CONSTRAINT ck_wp_booking_batch_reason CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_wp_booking_batch_key CHECK (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_booking_batch_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE TABLE wp_booking_batch_items (
    batch_item_id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES wp_booking_batches(batch_id) ON DELETE CASCADE,
    tenant_id BIGINT NOT NULL,
    intent_id UUID NOT NULL,
    intent_item_id UUID NOT NULL,
    hold_id UUID NOT NULL,
    authority VARCHAR(20) NOT NULL,
    item_state VARCHAR(32) NOT NULL,
    owner_reference_id UUID,
    owner_version BIGINT,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    compensation_available BOOLEAN NOT NULL DEFAULT FALSE,
    requery_required BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wp_booking_batch_item UNIQUE (batch_id, intent_item_id),
    CONSTRAINT uk_wp_booking_batch_tenant_item UNIQUE (tenant_id, batch_item_id),
    CONSTRAINT fk_wp_booking_batch_item_batch_intent FOREIGN KEY (
        tenant_id, batch_id, intent_id)
        REFERENCES wp_booking_batches(tenant_id, batch_id, intent_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_wp_booking_batch_item_intent_item FOREIGN KEY (
        tenant_id, intent_id, intent_item_id)
        REFERENCES wp_booking_intent_items(
            tenant_id, intent_id, intent_item_id),
    CONSTRAINT fk_wp_booking_batch_item_hold FOREIGN KEY (
        tenant_id, intent_id, intent_item_id, hold_id)
        REFERENCES wp_reservation_holds(
            tenant_id, intent_id, intent_item_id, hold_id),
    CONSTRAINT ck_wp_booking_batch_item_authority CHECK (authority IN ('WORKPLACE', 'CALENDAR')),
    CONSTRAINT ck_wp_booking_batch_item_state CHECK (item_state IN (
        'PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'RESULT_UNKNOWN',
        'COMPENSATION_PENDING', 'COMPENSATED', 'COMPENSATION_FAILED'))
);

CREATE INDEX idx_wp_booking_batch_items_batch
    ON wp_booking_batch_items (tenant_id, batch_id, created_at, batch_item_id);

CREATE TABLE wp_waitlist_entries (
    waitlist_entry_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    beneficiary_user_id BIGINT NOT NULL,
    beneficiary_person_public_id UUID,
    beneficiary_display_name VARCHAR(160) NOT NULL,
    delegation_grant_id UUID,
    resource_type VARCHAR(24) NOT NULL,
    preferred_resource_id UUID,
    site_id UUID,
    floor_id UUID,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    purpose VARCHAR(500),
    visible_to_colleagues BOOLEAN NOT NULL DEFAULT TRUE,
    accessible_only BOOLEAN NOT NULL DEFAULT FALSE,
    required_features JSONB NOT NULL DEFAULT '[]'::jsonb,
    auto_confirm BOOLEAN NOT NULL DEFAULT FALSE,
    maximum_distance_meters INTEGER,
    earliest_start TIMESTAMPTZ,
    latest_end TIMESTAMPTZ,
    pricing_mode VARCHAR(24) NOT NULL DEFAULT 'NOT_APPLICABLE',
    maximum_price NUMERIC(19,4),
    currency CHAR(3),
    notification_channels JSONB NOT NULL DEFAULT '[]'::jsonb,
    waitlist_state VARCHAR(24) NOT NULL,
    promotion_evaluation_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    promotion_decision_code VARCHAR(100),
    promotion_evaluated_at TIMESTAMPTZ,
    rank_visible BOOLEAN NOT NULL DEFAULT FALSE,
    rank_value INTEGER,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wp_waitlist_tenant_entry UNIQUE (tenant_id, waitlist_entry_id),
    CONSTRAINT uk_wp_waitlist_idempotency UNIQUE (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT fk_wp_waitlist_delegate FOREIGN KEY (tenant_id, delegation_grant_id)
        REFERENCES wp_booking_delegate_grants(tenant_id, grant_id),
    CONSTRAINT fk_wp_waitlist_resource FOREIGN KEY (tenant_id, preferred_resource_id)
        REFERENCES wp_resources(tenant_id, resource_id),
    CONSTRAINT fk_wp_waitlist_site FOREIGN KEY (tenant_id, site_id)
        REFERENCES wp_sites(tenant_id, site_id),
    CONSTRAINT fk_wp_waitlist_floor FOREIGN KEY (tenant_id, floor_id)
        REFERENCES wp_floors(tenant_id, floor_id),
    CONSTRAINT ck_wp_waitlist_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_wp_waitlist_state CHECK (
        waitlist_state IN ('ACTIVE', 'OFFERED', 'CONFIRMING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_wp_waitlist_promotion_evaluation CHECK (
        promotion_evaluation_state IN ('PENDING', 'NO_MATCH', 'BLOCKED', 'MATCHED')),
    CONSTRAINT ck_wp_waitlist_distance CHECK (
        maximum_distance_meters IS NULL OR maximum_distance_meters BETWEEN 0 AND 100000),
    CONSTRAINT ck_wp_waitlist_pricing CHECK (
        (pricing_mode = 'NOT_APPLICABLE' AND maximum_price IS NULL AND currency IS NULL)
        OR (pricing_mode = 'MANAGED' AND maximum_price IS NOT NULL
            AND maximum_price >= 0 AND currency ~ '^[A-Z]{3}$')),
    CONSTRAINT ck_wp_waitlist_channels CHECK (jsonb_typeof(notification_channels) = 'array'),
    CONSTRAINT ck_wp_waitlist_features CHECK (jsonb_typeof(required_features) = 'array'),
    CONSTRAINT ck_wp_waitlist_key CHECK (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_waitlist_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_wp_waitlist_matching
    ON wp_waitlist_entries (tenant_id, waitlist_state, starts_at, resource_type, created_at);

CREATE TABLE wp_alternative_offers (
    offer_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    waitlist_entry_id UUID NOT NULL,
    hold_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    offer_state VARCHAR(24) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_batch_id UUID,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wp_alternative_offer_tenant_offer UNIQUE (tenant_id, offer_id),
    CONSTRAINT uk_wp_alternative_offer_hold UNIQUE (tenant_id, hold_id),
    CONSTRAINT fk_wp_alternative_offer_waitlist FOREIGN KEY (tenant_id, waitlist_entry_id)
        REFERENCES wp_waitlist_entries(tenant_id, waitlist_entry_id),
    CONSTRAINT fk_wp_alternative_offer_hold FOREIGN KEY (tenant_id, hold_id)
        REFERENCES wp_reservation_holds(tenant_id, hold_id),
    CONSTRAINT fk_wp_alternative_offer_resource FOREIGN KEY (tenant_id, resource_id)
        REFERENCES wp_resources(tenant_id, resource_id),
    CONSTRAINT fk_wp_alternative_offer_accepted_batch FOREIGN KEY (
        tenant_id, accepted_batch_id)
        REFERENCES wp_booking_batches(tenant_id, batch_id),
    CONSTRAINT ck_wp_alternative_offer_state CHECK (
        offer_state IN ('OFFERED', 'ACCEPTING', 'ACCEPTED', 'EXPIRED', 'WITHDRAWN', 'RESULT_UNKNOWN'))
);

CREATE UNIQUE INDEX uk_wp_alternative_offer_active_waitlist
    ON wp_alternative_offers (tenant_id, waitlist_entry_id)
    WHERE offer_state IN ('OFFERED', 'ACCEPTING', 'RESULT_UNKNOWN');

CREATE TABLE wp_booking_orchestration_outbox (
    outbox_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    CONSTRAINT ck_wp_booking_orchestration_outbox_payload CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX idx_wp_booking_orchestration_outbox_pending
    ON wp_booking_orchestration_outbox (created_at, outbox_id)
    WHERE published_at IS NULL;

CREATE TABLE wp_booking_orchestration_command_receipts (
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_scope VARCHAR(220) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, actor_user_id, command_scope, idempotency_key),
    CONSTRAINT ck_wp_booking_command_receipt_key CHECK (
        idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_booking_command_receipt_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE OR REPLACE FUNCTION wp_guard_active_reservation_hold()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    supplied_hold UUID;
    blocking_hold UUID;
BEGIN
    IF NEW.booking_status NOT IN ('RESERVED', 'CHECKED_IN') THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'workplace-resource:' || NEW.tenant_id || ':' || NEW.resource_id, 0));
    supplied_hold := NULLIF(current_setting('dwp.workplace_hold_id', TRUE), '')::UUID;
    IF supplied_hold IS NOT NULL THEN
        PERFORM 1 FROM wp_reservation_holds hold
         WHERE hold.tenant_id = NEW.tenant_id
           AND hold.hold_id = supplied_hold
           AND hold.resource_id = NEW.resource_id
           AND hold.starts_at = NEW.starts_at
           AND hold.ends_at = NEW.ends_at
           AND hold.hold_state = 'BATCHED'
           AND hold.expires_at > clock_timestamp()
         FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Reservation hold is no longer valid'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    SELECT hold.hold_id INTO blocking_hold
      FROM wp_reservation_holds hold
     WHERE hold.tenant_id = NEW.tenant_id
       AND hold.resource_id = NEW.resource_id
       AND hold.hold_state IN ('ACTIVE', 'BATCHED')
       AND hold.expires_at > clock_timestamp()
       AND tstzrange(hold.starts_at, hold.ends_at, '[)')
           && tstzrange(NEW.starts_at, NEW.ends_at, '[)')
       AND (supplied_hold IS NULL OR hold.hold_id <> supplied_hold)
     ORDER BY hold.created_at, hold.hold_id
     LIMIT 1;
    IF blocking_hold IS NOT NULL THEN
        RAISE EXCEPTION 'Workplace resource is protected by an active reservation hold'
            USING ERRCODE = '23P01';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wp_bookings_active_hold_guard
BEFORE INSERT OR UPDATE OF tenant_id, resource_id, starts_at, ends_at, booking_status
ON wp_bookings
FOR EACH ROW EXECUTE FUNCTION wp_guard_active_reservation_hold();

CREATE OR REPLACE FUNCTION wp_guard_active_room_reservation_hold()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    workplace_resource UUID;
    supplied_hold UUID;
    blocking_hold UUID;
BEGIN
    IF NEW.booking_status NOT IN ('PENDING', 'CONFIRMED') THEN
        RETURN NEW;
    END IF;
    SELECT resource.resource_id INTO workplace_resource
      FROM wp_resources resource
     WHERE resource.tenant_id = NEW.tenant_id
       AND resource.calendar_resource_id = NEW.resource_id;
    IF workplace_resource IS NULL THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'workplace-resource:' || NEW.tenant_id || ':' || workplace_resource, 0));
    supplied_hold := NULLIF(current_setting('dwp.workplace_hold_id', TRUE), '')::UUID;
    IF supplied_hold IS NOT NULL THEN
        PERFORM 1 FROM wp_reservation_holds hold
         WHERE hold.tenant_id = NEW.tenant_id
           AND hold.hold_id = supplied_hold
           AND hold.resource_id = workplace_resource
           AND hold.starts_at = NEW.starts_at
           AND hold.ends_at = NEW.ends_at
           AND hold.hold_state = 'BATCHED'
           AND hold.expires_at > clock_timestamp()
         FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Room reservation hold is no longer valid'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    SELECT hold.hold_id INTO blocking_hold
      FROM wp_reservation_holds hold
     WHERE hold.tenant_id = NEW.tenant_id
       AND hold.resource_id = workplace_resource
       AND hold.hold_state IN ('ACTIVE', 'BATCHED')
       AND hold.expires_at > clock_timestamp()
       AND tstzrange(hold.starts_at, hold.ends_at, '[)')
           && tstzrange(NEW.starts_at, NEW.ends_at, '[)')
       AND (supplied_hold IS NULL OR hold.hold_id <> supplied_hold)
     ORDER BY hold.created_at, hold.hold_id
     LIMIT 1;
    IF blocking_hold IS NOT NULL THEN
        RAISE EXCEPTION 'Room is protected by an active reservation hold'
            USING ERRCODE = '23P01';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_cal_resource_bookings_active_hold_guard
BEFORE INSERT OR UPDATE OF tenant_id, resource_id, starts_at, ends_at, booking_status
ON cal_resource_bookings
FOR EACH ROW EXECUTE FUNCTION wp_guard_active_room_reservation_hold();

COMMENT ON TABLE wp_booking_intents IS
    'Tenant-scoped, actor/beneficiary-separated multi-resource booking plans.';
COMMENT ON TABLE wp_reservation_holds IS
    'Server-time authoritative short-lived resource holds; clients never supply expiry.';
COMMENT ON TABLE wp_booking_batches IS
    'Durable booking saga receipts. RESULT_UNKNOWN requires status lookup, not command replay.';
COMMENT ON TABLE wp_booking_orchestration_outbox IS
    'Transactionally appended booking-intent lifecycle facts; rows are not provider delivery proof.';

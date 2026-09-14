CREATE TABLE wp_experience_facility_closures (
    closure_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    resource_id UUID NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    closure_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    reason VARCHAR(500) NOT NULL,
    cancellation_reason VARCHAR(500),
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    resource_version_at_create BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    CONSTRAINT fk_wp_facility_closure_resource FOREIGN KEY (tenant_id, resource_id)
        REFERENCES wp_resources(tenant_id, resource_id),
    CONSTRAINT uk_wp_facility_closure_request UNIQUE (tenant_id, created_by, idempotency_key),
    CONSTRAINT ck_wp_facility_closure_range CHECK (ends_at > starts_at),
    CONSTRAINT ck_wp_facility_closure_status CHECK (closure_status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT ck_wp_facility_closure_reason CHECK (length(trim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_wp_facility_closure_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_facility_closure_version CHECK (version >= 0 AND resource_version_at_create >= 0)
);

CREATE INDEX idx_wp_facility_closure_range ON wp_experience_facility_closures
    (tenant_id, resource_id, starts_at, ends_at) WHERE closure_status = 'ACTIVE';

CREATE TABLE wp_experience_facility_requests (
    request_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    resource_id UUID NOT NULL,
    requester_user_id BIGINT NOT NULL,
    category VARCHAR(20) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    request_status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    status_reason VARCHAR(500),
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT fk_wp_facility_request_resource FOREIGN KEY (tenant_id, resource_id)
        REFERENCES wp_resources(tenant_id, resource_id),
    CONSTRAINT uk_wp_facility_request_command UNIQUE (tenant_id, requester_user_id, idempotency_key),
    CONSTRAINT ck_wp_facility_request_category CHECK (category IN ('REPAIR', 'CLEANING', 'ACCESS', 'OTHER')),
    CONSTRAINT ck_wp_facility_request_status CHECK (request_status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CANCELLED')),
    CONSTRAINT ck_wp_facility_request_description CHECK (length(trim(description)) BETWEEN 1 AND 2000),
    CONSTRAINT ck_wp_facility_request_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_facility_request_version CHECK (version >= 0)
);

CREATE INDEX idx_wp_facility_request_owner ON wp_experience_facility_requests
    (tenant_id, requester_user_id, created_at DESC, request_id);
CREATE INDEX idx_wp_facility_request_resource ON wp_experience_facility_requests
    (tenant_id, resource_id, request_status, created_at DESC);

-- Closure writes and booking inserts/moves acquire exactly the same resource lock.
-- Existing reservations are preserved: this guard never cancels or relocates one.
CREATE OR REPLACE FUNCTION wp_lock_facility_closure_resource()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'workplace-resource:' || NEW.tenant_id || ':' || NEW.resource_id, 0));
    IF EXISTS (SELECT 1 FROM wp_resources
        WHERE tenant_id = NEW.tenant_id AND resource_id = NEW.resource_id AND resource_type = 'ROOM') THEN
        RAISE EXCEPTION 'Rooms closures require the Calendar/Rooms owner contract' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wp_facility_closure_resource_lock
BEFORE INSERT OR UPDATE ON wp_experience_facility_closures
FOR EACH ROW EXECUTE FUNCTION wp_lock_facility_closure_resource();

CREATE OR REPLACE FUNCTION wp_guard_facility_closed_booking()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.booking_status NOT IN ('RESERVED', 'CHECKED_IN') THEN RETURN NEW; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'workplace-resource:' || NEW.tenant_id || ':' || NEW.resource_id, 0));
    IF EXISTS (SELECT 1 FROM wp_experience_facility_closures c
        WHERE c.tenant_id = NEW.tenant_id AND c.resource_id = NEW.resource_id
          AND c.closure_status = 'ACTIVE' AND c.starts_at < NEW.ends_at AND c.ends_at > NEW.starts_at) THEN
        RAISE EXCEPTION 'Workplace resource is closed for the requested booking period' USING ERRCODE = '23P01';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wp_bookings_facility_closure_guard
BEFORE INSERT OR UPDATE OF resource_id, starts_at, ends_at ON wp_bookings
FOR EACH ROW EXECUTE FUNCTION wp_guard_facility_closed_booking();


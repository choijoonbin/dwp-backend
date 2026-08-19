ALTER TABLE wp_bookings
    ADD COLUMN release_window_id UUID,
    ADD COLUMN policy_snapshot JSONB,
    ADD COLUMN policy_snapshot_hash CHAR(64),
    ADD COLUMN require_check_in_snapshot BOOLEAN,
    ADD COLUMN check_in_lead_minutes_snapshot INTEGER,
    ADD COLUMN auto_release_minutes_snapshot INTEGER,
    ADD COLUMN booking_retention_days_snapshot INTEGER;

UPDATE wp_bookings booking
   SET policy_snapshot = jsonb_build_object(
           'bookingWindowDays', policy.booking_window_days,
           'maximumActiveBookings', policy.maximum_active_bookings,
           'minimumBookingMinutes', policy.minimum_booking_minutes,
           'maximumBookingMinutes', policy.maximum_booking_minutes,
           'maximumConsecutiveDays', policy.maximum_consecutive_days,
           'workingDayStart', policy.working_day_start::TEXT,
           'workingDayEnd', policy.working_day_end::TEXT,
           'allowRecurring', policy.allow_recurring,
           'requireCheckIn', policy.require_check_in,
           'checkInLeadMinutes', policy.check_in_lead_minutes,
           'autoReleaseMinutes', policy.auto_release_minutes,
           'allowAssignedDeskLending', policy.allow_assigned_desk_lending,
           'showColleagueNames', policy.show_colleague_names,
           'bookingRetentionDays', policy.booking_retention_days),
       require_check_in_snapshot = policy.require_check_in,
       check_in_lead_minutes_snapshot = policy.check_in_lead_minutes,
       auto_release_minutes_snapshot = policy.auto_release_minutes,
       booking_retention_days_snapshot = policy.booking_retention_days
  FROM wp_tenant_policies policy
 WHERE policy.tenant_id = booking.tenant_id;

UPDATE wp_bookings
   SET policy_snapshot_hash = md5(policy_snapshot::TEXT) || md5('wp:' || policy_snapshot::TEXT);

ALTER TABLE wp_bookings
    ALTER COLUMN policy_snapshot SET NOT NULL,
    ALTER COLUMN policy_snapshot_hash SET NOT NULL,
    ALTER COLUMN require_check_in_snapshot SET NOT NULL,
    ALTER COLUMN check_in_lead_minutes_snapshot SET NOT NULL,
    ALTER COLUMN auto_release_minutes_snapshot SET NOT NULL,
    ALTER COLUMN booking_retention_days_snapshot SET NOT NULL,
    ADD CONSTRAINT ck_wp_bookings_policy_snapshot CHECK (
        jsonb_typeof(policy_snapshot) = 'object'
        AND policy_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND check_in_lead_minutes_snapshot BETWEEN 0 AND 240
        AND auto_release_minutes_snapshot BETWEEN 5 AND 240
        AND booking_retention_days_snapshot BETWEEN 30 AND 3650);

ALTER TABLE wp_resource_release_windows
    ADD CONSTRAINT uk_wp_release_windows_booking_provenance
        UNIQUE (tenant_id, release_window_id, resource_id);

ALTER TABLE wp_bookings
    ADD CONSTRAINT fk_wp_bookings_release_window_resource
        FOREIGN KEY (tenant_id, release_window_id, resource_id)
        REFERENCES wp_resource_release_windows(
            tenant_id, release_window_id, resource_id);

CREATE OR REPLACE FUNCTION wp_validate_booking_release_window()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.release_window_id IS NULL THEN
        RETURN NEW;
    END IF;
    PERFORM 1
      FROM wp_resource_release_windows release
     WHERE release.tenant_id = NEW.tenant_id
       AND release.release_window_id = NEW.release_window_id
       AND release.resource_id = NEW.resource_id
       AND release.release_status = 'ACTIVE'
       AND release.starts_at <= NEW.starts_at
       AND release.ends_at >= NEW.ends_at
     FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Workplace release window is inactive or does not cover booking period'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wp_bookings_release_window
BEFORE INSERT OR UPDATE OF release_window_id, resource_id, starts_at, ends_at
ON wp_bookings
FOR EACH ROW EXECUTE FUNCTION wp_validate_booking_release_window();

CREATE INDEX idx_wp_bookings_release_window
    ON wp_bookings (tenant_id, release_window_id)
    WHERE release_window_id IS NOT NULL;

CREATE OR REPLACE FUNCTION wp_apply_booking_privacy_expiry()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.anonymized_at IS NOT NULL THEN
        RETURN NEW;
    END IF;
    NEW.personal_data_expires_at := NEW.ends_at
            + (COALESCE(NEW.booking_retention_days_snapshot, 365) * INTERVAL '1 day');
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_wp_bookings_privacy_expiry ON wp_bookings;
CREATE TRIGGER trg_wp_bookings_privacy_expiry
BEFORE INSERT OR UPDATE OF ends_at, booking_retention_days_snapshot ON wp_bookings
FOR EACH ROW EXECUTE FUNCTION wp_apply_booking_privacy_expiry();

ALTER TABLE wp_resource_release_windows
    ADD COLUMN personal_data_expires_at TIMESTAMPTZ,
    ADD COLUMN legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN anonymized_at TIMESTAMPTZ;

UPDATE wp_resource_release_windows release
   SET personal_data_expires_at = release.ends_at
           + (policy.booking_retention_days * INTERVAL '1 day')
  FROM wp_tenant_policies policy
 WHERE policy.tenant_id = release.tenant_id;

ALTER TABLE wp_resource_release_windows
    ALTER COLUMN personal_data_expires_at SET NOT NULL,
    ADD CONSTRAINT ck_wp_release_windows_anonymized CHECK (
        anonymized_at IS NULL
        OR (released_by_user_id = 0
            AND released_by_person_public_id IS NULL
            AND note IS NULL
            AND created_by IS NULL
            AND idempotency_key IS NULL
            AND request_fingerprint IS NULL));

CREATE INDEX idx_wp_release_windows_privacy_retention
    ON wp_resource_release_windows (personal_data_expires_at, tenant_id, release_window_id)
    WHERE anonymized_at IS NULL AND legal_hold = FALSE;

CREATE TABLE wp_floor_plan_media_assets (
    tenant_id BIGINT NOT NULL,
    storage_key VARCHAR(1000) NOT NULL,
    reference_count INTEGER NOT NULL DEFAULT 0,
    asset_status VARCHAR(20) NOT NULL DEFAULT 'REFERENCED',
    first_referenced_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_referenced_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    unreferenced_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, storage_key),
    CONSTRAINT ck_wp_floor_plan_media_asset_count CHECK (reference_count >= 0),
    CONSTRAINT ck_wp_floor_plan_media_asset_status CHECK (
        asset_status IN ('STAGED', 'REFERENCED', 'PENDING_DELETE')),
    CONSTRAINT ck_wp_floor_plan_media_asset_state CHECK (
        (reference_count > 0 AND asset_status = 'REFERENCED' AND unreferenced_at IS NULL)
        OR (reference_count = 0 AND asset_status IN ('STAGED', 'PENDING_DELETE')))
);

INSERT INTO wp_floor_plan_media_assets (
    tenant_id, storage_key, reference_count, asset_status,
    first_referenced_at, last_referenced_at)
SELECT tenant_id, background_asset_key, COUNT(*), 'REFERENCED',
       MIN(created_at), MAX(updated_at)
  FROM wp_floor_plan_revisions
 WHERE background_asset_key IS NOT NULL
 GROUP BY tenant_id, background_asset_key;

CREATE OR REPLACE FUNCTION wp_reconcile_floor_plan_media_asset(
    target_tenant_id BIGINT,
    target_storage_key TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    refs INTEGER;
BEGIN
    IF target_storage_key IS NULL THEN
        RETURN;
    END IF;
    SELECT COUNT(*) INTO refs
      FROM wp_floor_plan_revisions
     WHERE tenant_id = target_tenant_id
       AND background_asset_key = target_storage_key;

    INSERT INTO wp_floor_plan_media_assets (
        tenant_id, storage_key, reference_count, asset_status,
        first_referenced_at, last_referenced_at, unreferenced_at)
    VALUES (
        target_tenant_id, target_storage_key, refs,
        CASE WHEN refs > 0 THEN 'REFERENCED' ELSE 'PENDING_DELETE' END,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
        CASE WHEN refs = 0 THEN CURRENT_TIMESTAMP ELSE NULL END)
    ON CONFLICT (tenant_id, storage_key) DO UPDATE
       SET reference_count = EXCLUDED.reference_count,
           asset_status = EXCLUDED.asset_status,
           last_referenced_at = CASE WHEN refs > 0
               THEN CURRENT_TIMESTAMP ELSE wp_floor_plan_media_assets.last_referenced_at END,
           unreferenced_at = EXCLUDED.unreferenced_at;

    IF refs = 0 THEN
        INSERT INTO sys_tenant_media_cleanup_outbox (
            tenant_id, storage_key, cleanup_reason)
        VALUES (target_tenant_id, target_storage_key, 'FLOOR_PLAN_UNREFERENCED')
        ON CONFLICT DO NOTHING;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION wp_track_floor_plan_media_asset()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM wp_reconcile_floor_plan_media_asset(OLD.tenant_id, OLD.background_asset_key);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.background_asset_key IS DISTINCT FROM NEW.background_asset_key THEN
        PERFORM wp_reconcile_floor_plan_media_asset(OLD.tenant_id, OLD.background_asset_key);
    END IF;
    PERFORM wp_reconcile_floor_plan_media_asset(NEW.tenant_id, NEW.background_asset_key);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wp_floor_plan_media_asset
AFTER INSERT OR UPDATE OF background_asset_key OR DELETE ON wp_floor_plan_revisions
FOR EACH ROW EXECUTE FUNCTION wp_track_floor_plan_media_asset();

COMMENT ON COLUMN wp_bookings.policy_snapshot IS
    'Immutable effective policy applied at reservation creation; lifecycle processing never re-resolves current policy.';
COMMENT ON COLUMN wp_bookings.release_window_id IS
    'Assigned-workspace lending authority consumed by this reservation.';
COMMENT ON TABLE wp_floor_plan_media_assets IS
    'Transactional reference ledger for immutable floor-plan revision assets; cleanup is queued only at zero references.';

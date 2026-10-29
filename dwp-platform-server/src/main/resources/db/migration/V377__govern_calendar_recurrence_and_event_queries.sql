-- Additive recurrence evolution. NULL recurrence_days/count preserve the legacy
-- DAILY/WEEKLY/MONTHLY + optional UNTIL contract for existing clients and rows.
CREATE OR REPLACE FUNCTION cal_valid_recurrence_days(value SMALLINT[])
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT value IS NULL OR (
        cardinality(value) BETWEEN 1 AND 7
        AND value <@ ARRAY[1,2,3,4,5,6,7]::SMALLINT[]
        AND cardinality(value) = (
            SELECT COUNT(DISTINCT day_value) FROM unnest(value) day_value));
$$;

ALTER TABLE cal_events
    ADD COLUMN recurrence_days SMALLINT[],
    ADD COLUMN recurrence_count INTEGER;

ALTER TABLE cal_events
    DROP CONSTRAINT ck_cal_events_recurrence,
    ADD CONSTRAINT ck_cal_events_recurrence CHECK (
        recurrence_pattern IN ('NONE', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY')
        AND recurrence_interval BETWEEN 1 AND 52
        AND (recurrence_count IS NULL
             OR recurrence_count BETWEEN 1 AND 4000)
        AND NOT (recurrence_until IS NOT NULL AND recurrence_count IS NOT NULL)
        AND cal_valid_recurrence_days(recurrence_days)
        AND (recurrence_pattern = 'WEEKLY' OR recurrence_days IS NULL)
        AND (recurrence_days IS NULL
             OR EXTRACT(ISODOW FROM starts_at AT TIME ZONE time_zone)::SMALLINT
                    = ANY (recurrence_days))
        AND (recurrence_pattern <> 'NONE'
             OR (recurrence_until IS NULL
                 AND recurrence_count IS NULL
                 AND recurrence_days IS NULL)));

CREATE OR REPLACE FUNCTION cal_project_recurrences(
    p_starts_at TIMESTAMPTZ,
    p_ends_at TIMESTAMPTZ,
    p_time_zone VARCHAR,
    p_pattern VARCHAR,
    p_interval INTEGER,
    p_until DATE,
    p_count INTEGER,
    p_days SMALLINT[],
    p_window_from TIMESTAMPTZ,
    p_window_to TIMESTAMPTZ,
    p_limit INTEGER)
RETURNS TABLE (
    ordinal INTEGER,
    original_starts_at TIMESTAMPTZ,
    starts_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ)
LANGUAGE SQL
STABLE
PARALLEL SAFE
AS $$
    WITH RECURSIVE parameters AS (
        SELECT p_starts_at AT TIME ZONE p_time_zone AS anchor_local,
               (p_window_from - (p_ends_at - p_starts_at))
                    AT TIME ZONE p_time_zone AS target_local
    ), sequential_prefix(step, local_starts_at) AS (
        SELECT 0::bigint, parameters.anchor_local
          FROM parameters
        UNION ALL
        SELECT sequential_prefix.step + 1,
               CASE p_pattern
                   WHEN 'MONTHLY' THEN sequential_prefix.local_starts_at
                       + make_interval(months => p_interval)
                   WHEN 'YEARLY' THEN sequential_prefix.local_starts_at
                       + make_interval(years => p_interval)
               END
          FROM sequential_prefix
         CROSS JOIN parameters
         WHERE p_count IS NULL
           AND p_pattern IN ('MONTHLY', 'YEARLY')
           AND sequential_prefix.local_starts_at < parameters.target_local
           -- 400 advances cover the Gregorian leap/century cycle as well as every
           -- month residue. This matters for MONTHLY interval 12/24/36/48, which
           -- otherwise could jump past the first non-leap February before clamping.
           AND sequential_prefix.step < 400
    ), sequential_seed AS (
        SELECT sequential_prefix.step, sequential_prefix.local_starts_at
          FROM sequential_prefix
         ORDER BY sequential_prefix.step DESC
         LIMIT 1
    ), seed_values AS (
        SELECT parameters.anchor_local,
               sequential_seed.local_starts_at AS sequential_local,
               sequential_seed.step AS sequential_steps,
               CASE
                   WHEN p_count IS NULL AND p_pattern = 'MONTHLY'
                        AND parameters.target_local > sequential_seed.local_starts_at
                   THEN GREATEST(
                       0,
                       (((EXTRACT(YEAR FROM parameters.target_local)::bigint
                            - EXTRACT(YEAR FROM sequential_seed.local_starts_at)::bigint) * 12
                           + EXTRACT(MONTH FROM parameters.target_local)::bigint
                           - EXTRACT(MONTH FROM sequential_seed.local_starts_at)::bigint)
                            / p_interval) - 1)
                   WHEN p_count IS NULL AND p_pattern = 'YEARLY'
                        AND parameters.target_local > sequential_seed.local_starts_at
                   THEN GREATEST(
                       0,
                       ((EXTRACT(YEAR FROM parameters.target_local)::bigint
                           - EXTRACT(YEAR FROM sequential_seed.local_starts_at)::bigint)
                            / p_interval) - 1)
                   ELSE 0::bigint
               END AS sequential_jump,
               CASE
                   WHEN p_count IS NOT NULL
                        OR parameters.target_local <= parameters.anchor_local
                        OR p_pattern IN ('NONE', 'MONTHLY', 'YEARLY') THEN 0::bigint
                   WHEN p_pattern = 'DAILY' THEN GREATEST(
                       0,
                       ((parameters.target_local::date
                           - parameters.anchor_local::date) / p_interval) - 1)::bigint
                   WHEN p_pattern = 'WEEKLY'
                        AND (p_days IS NULL OR cardinality(p_days) = 0) THEN GREATEST(
                       0,
                       (((parameters.target_local::date
                           - (EXTRACT(ISODOW FROM parameters.target_local)::int - 1))
                         - (parameters.anchor_local::date
                           - (EXTRACT(ISODOW FROM parameters.anchor_local)::int - 1)))
                           / 7 / p_interval) - 1)::bigint
                   ELSE 0::bigint
               END AS fixed_steps,
               CASE
                   WHEN p_count IS NULL
                        AND p_pattern = 'WEEKLY'
                        AND cardinality(p_days) > 0
                        AND parameters.target_local > parameters.anchor_local
                   THEN GREATEST(
                       0,
                       (((parameters.target_local::date
                           - (EXTRACT(ISODOW FROM parameters.target_local)::int - 1))
                         - (parameters.anchor_local::date
                           - (EXTRACT(ISODOW FROM parameters.anchor_local)::int - 1)))
                           / 7 / p_interval) - 1)::bigint
                   ELSE 0::bigint
               END AS weekly_cycle
          FROM parameters
         CROSS JOIN sequential_seed
    ), seed AS (
        SELECT CASE
                   WHEN p_pattern = 'DAILY' THEN
                       seed_values.anchor_local
                           + make_interval(days =>
                               (seed_values.fixed_steps * p_interval)::int)
                   WHEN p_pattern = 'WEEKLY'
                        AND (p_days IS NULL OR cardinality(p_days) = 0) THEN
                       seed_values.anchor_local
                           + make_interval(days =>
                               (seed_values.fixed_steps * 7 * p_interval)::int)
                   WHEN p_pattern = 'WEEKLY'
                        AND seed_values.weekly_cycle > 0 THEN
                       ((seed_values.anchor_local::date
                           - (EXTRACT(ISODOW FROM seed_values.anchor_local)::int - 1))
                         + (seed_values.weekly_cycle * 7 * p_interval)::int
                         + ((SELECT MIN(day_value) FROM unnest(p_days) day_value) - 1))::date
                           + seed_values.anchor_local::time
                   WHEN p_pattern = 'MONTHLY' THEN
                       seed_values.sequential_local
                           + make_interval(months =>
                               (seed_values.sequential_jump * p_interval)::int)
                   WHEN p_pattern = 'YEARLY' THEN
                       seed_values.sequential_local
                           + make_interval(years =>
                               (seed_values.sequential_jump * p_interval)::int)
                   ELSE seed_values.anchor_local
               END AS local_starts_at,
               CASE
                   WHEN p_pattern = 'WEEKLY'
                        AND cardinality(p_days) > 0
                        AND seed_values.weekly_cycle > 0 THEN
                       (SELECT COUNT(*) FROM unnest(p_days) day_value
                         WHERE day_value >= EXTRACT(
                             ISODOW FROM seed_values.anchor_local)::smallint)
                       + ((seed_values.weekly_cycle - 1) * cardinality(p_days)) + 1
                   WHEN p_pattern IN ('MONTHLY', 'YEARLY') THEN
                       seed_values.sequential_steps
                           + seed_values.sequential_jump + 1
                   ELSE seed_values.fixed_steps + 1
               END AS ordinal
          FROM seed_values
    ), projected(ordinal, scanned, local_starts_at) AS (
        SELECT seed.ordinal, 1, seed.local_starts_at
          FROM seed
         WHERE p_starts_at < p_window_to
           AND (p_count IS NULL OR seed.ordinal <= p_count)
           AND (p_until IS NULL
                OR seed.local_starts_at::date <= p_until)
        UNION ALL
        SELECT projected.ordinal + 1, projected.scanned + 1,
               next_value.local_starts_at
          FROM projected
         CROSS JOIN LATERAL (
             SELECT CASE p_pattern
                 WHEN 'DAILY' THEN
                     projected.local_starts_at + make_interval(days => p_interval)
                 WHEN 'WEEKLY' THEN
                     CASE WHEN p_days IS NULL THEN
                         projected.local_starts_at
                             + make_interval(days => 7 * p_interval)
                     ELSE (
                         SELECT (projected.local_starts_at::date + candidate.step)::date
                                    + projected.local_starts_at::time
                           FROM generate_series(
                               1, 7 * p_interval + 7) candidate(step)
                          WHERE EXTRACT(ISODOW FROM
                                    projected.local_starts_at::date + candidate.step)::smallint
                                    = ANY (p_days)
                            AND MOD(
                                ((projected.local_starts_at::date + candidate.step)
                                  - ((p_starts_at AT TIME ZONE p_time_zone)::date
                                     - (EXTRACT(ISODOW FROM
                                            p_starts_at AT TIME ZONE p_time_zone)::int - 1)))
                                    / 7,
                                p_interval) = 0
                          ORDER BY candidate.step
                          LIMIT 1)
                     END
                 WHEN 'MONTHLY' THEN
                     projected.local_starts_at + make_interval(months => p_interval)
                 WHEN 'YEARLY' THEN
                     projected.local_starts_at + make_interval(years => p_interval)
                 ELSE projected.local_starts_at
                 END AS local_starts_at
         ) next_value
         WHERE p_pattern <> 'NONE'
           AND projected.scanned < p_limit
           AND (p_count IS NULL OR projected.ordinal < p_count)
           AND next_value.local_starts_at IS NOT NULL
           AND (p_until IS NULL OR next_value.local_starts_at::date <= p_until)
           AND next_value.local_starts_at AT TIME ZONE p_time_zone < p_window_to
    )
    SELECT LEAST(projected.ordinal, 2147483647)::integer,
           projected.local_starts_at AT TIME ZONE p_time_zone,
           projected.local_starts_at AT TIME ZONE p_time_zone,
           (projected.local_starts_at AT TIME ZONE p_time_zone)
               + (p_ends_at - p_starts_at)
      FROM projected
     WHERE projected.local_starts_at AT TIME ZONE p_time_zone < p_window_to
       AND (projected.local_starts_at AT TIME ZONE p_time_zone)
               + (p_ends_at - p_starts_at) > p_window_from;
$$;

COMMENT ON FUNCTION cal_project_recurrences(
    TIMESTAMPTZ, TIMESTAMPTZ, VARCHAR, VARCHAR, INTEGER,
    DATE, INTEGER, SMALLINT[], TIMESTAMPTZ, TIMESTAMPTZ, INTEGER) IS
    'Canonical bounded Calendar SQL projection: local-wall DST semantics, weekly BYDAY, COUNT/UNTIL, sequential monthly/yearly clamp, and fast-forward for legacy long series.';

-- Compatibility overload for callers migrated independently. New bounded query paths must pass
-- both window bounds so an old unbounded DAILY/WEEKLY series does not spend its scan budget from
-- the series anchor.
CREATE OR REPLACE FUNCTION cal_project_recurrences(
    p_starts_at TIMESTAMPTZ,
    p_ends_at TIMESTAMPTZ,
    p_time_zone VARCHAR,
    p_pattern VARCHAR,
    p_interval INTEGER,
    p_until DATE,
    p_count INTEGER,
    p_days SMALLINT[],
    p_window_to TIMESTAMPTZ,
    p_limit INTEGER DEFAULT 4000)
RETURNS TABLE (
    ordinal INTEGER,
    original_starts_at TIMESTAMPTZ,
    starts_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ)
LANGUAGE SQL
STABLE
PARALLEL SAFE
AS $$
    SELECT *
      FROM cal_project_recurrences(
          p_starts_at, p_ends_at, p_time_zone, p_pattern, p_interval,
          p_until, p_count, p_days, p_starts_at, p_window_to, p_limit);
$$;

COMMENT ON FUNCTION cal_project_recurrences(
    TIMESTAMPTZ, TIMESTAMPTZ, VARCHAR, VARCHAR, INTEGER,
    DATE, INTEGER, SMALLINT[], TIMESTAMPTZ, INTEGER) IS
    'Compatibility overload. Prefer the bounded overload with window_from and window_to.';

ALTER TABLE cal_resource_bookings
    ADD COLUMN trash_recurrence_days SMALLINT[],
    ADD COLUMN trash_recurrence_count INTEGER,
    DROP CONSTRAINT ck_cal_resource_booking_trash_series_snapshot,
    ADD CONSTRAINT ck_cal_resource_booking_trash_series_snapshot CHECK (
        (trash_recurrence_pattern IS NULL
            AND trash_recurrence_interval IS NULL
            AND trash_recurrence_until IS NULL
            AND trash_recurrence_days IS NULL
            AND trash_recurrence_count IS NULL
            AND trash_event_starts_at IS NULL
            AND trash_event_ends_at IS NULL
            AND trash_event_time_zone IS NULL)
        OR
        (trash_recurrence_pattern IS NOT NULL
            AND trash_recurrence_pattern IN (
                'NONE', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY')
            AND trash_recurrence_interval BETWEEN 1 AND 52
            AND (trash_recurrence_count IS NULL
                 OR trash_recurrence_count BETWEEN 1 AND 4000)
            AND NOT (trash_recurrence_until IS NOT NULL
                     AND trash_recurrence_count IS NOT NULL)
            AND cal_valid_recurrence_days(trash_recurrence_days)
            AND (trash_recurrence_pattern = 'WEEKLY'
                 OR trash_recurrence_days IS NULL)
            AND (trash_recurrence_days IS NULL
                 OR EXTRACT(ISODOW FROM
                        trash_event_starts_at AT TIME ZONE trash_event_time_zone)::SMALLINT
                        = ANY (trash_recurrence_days))
            AND (trash_recurrence_pattern <> 'NONE'
                 OR (trash_recurrence_until IS NULL
                     AND trash_recurrence_count IS NULL
                     AND trash_recurrence_days IS NULL))
            AND trash_event_starts_at IS NOT NULL
            AND trash_event_ends_at > trash_event_starts_at
            AND trash_event_time_zone IS NOT NULL
            AND length(trim(trash_event_time_zone)) > 0));

-- Direct-object and bounded range/keyset query support.
CREATE INDEX idx_cal_events_tenant_active_start_keyset
    ON cal_events (tenant_id, starts_at, event_id)
    WHERE deleted_at IS NULL AND status <> 'CANCELLED';

CREATE INDEX idx_cal_events_tenant_active_recurrence_range
    ON cal_events (tenant_id, recurrence_until, starts_at, event_id)
    WHERE deleted_at IS NULL AND status <> 'CANCELLED'
      AND recurrence_pattern <> 'NONE';

CREATE INDEX idx_cal_attendees_tenant_event_projection
    ON cal_event_attendees (tenant_id, event_id, attendee_type, attendee_name);

CREATE INDEX idx_cal_occurrence_overrides_tenant_event_projection
    ON cal_event_occurrence_overrides (
        tenant_id, event_id, original_starts_at, starts_at, ends_at);

CREATE INDEX idx_cal_resource_bookings_tenant_event_active
    ON cal_resource_bookings (tenant_id, event_id, resource_id)
    WHERE booking_status IN ('PENDING', 'CONFIRMED');

COMMENT ON COLUMN cal_events.recurrence_days IS
    'ISO weekday numbers (Monday=1) for WEEKLY BYDAY; NULL preserves legacy anchor-weekday recurrence.';
COMMENT ON COLUMN cal_events.recurrence_count IS
    'Inclusive occurrence COUNT, mutually exclusive with recurrence_until and bounded to 4000.';
COMMENT ON COLUMN cal_resource_bookings.trash_recurrence_days IS
    'Weekly BYDAY snapshot captured when a recurring resource booking is trashed.';
COMMENT ON COLUMN cal_resource_bookings.trash_recurrence_count IS
    'Occurrence COUNT snapshot captured when a recurring resource booking is trashed.';

CREATE TABLE cal_series_split_receipts (
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    source_event_id UUID NOT NULL,
    resulting_event_id UUID NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    resulting_source_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT fk_cal_series_split_source FOREIGN KEY (tenant_id, source_event_id)
        REFERENCES cal_events (tenant_id, event_id) ON DELETE CASCADE,
    CONSTRAINT fk_cal_series_split_result FOREIGN KEY (tenant_id, resulting_event_id)
        REFERENCES cal_events (tenant_id, event_id) ON DELETE CASCADE,
    CONSTRAINT ck_cal_series_split_distinct
        CHECK (source_event_id <> resulting_event_id),
    CONSTRAINT ck_cal_series_split_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_cal_series_split_source
    ON cal_series_split_receipts (tenant_id, source_event_id, created_at DESC);

COMMENT ON TABLE cal_series_split_receipts IS
    'Idempotent THIS_AND_FOLLOWING series split receipts, bound to actor and source series.';

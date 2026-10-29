ALTER TABLE cal_resource_bookings
    ADD COLUMN trash_recurrence_pattern VARCHAR(20),
    ADD COLUMN trash_recurrence_interval INTEGER,
    ADD COLUMN trash_recurrence_until DATE,
    ADD COLUMN trash_event_starts_at TIMESTAMPTZ,
    ADD COLUMN trash_event_ends_at TIMESTAMPTZ,
    ADD COLUMN trash_event_time_zone VARCHAR(80),
    ADD CONSTRAINT ck_cal_resource_booking_trash_series_snapshot CHECK (
        (trash_recurrence_pattern IS NULL
            AND trash_recurrence_interval IS NULL
            AND trash_recurrence_until IS NULL
            AND trash_event_starts_at IS NULL
            AND trash_event_ends_at IS NULL
            AND trash_event_time_zone IS NULL)
        OR
        (trash_recurrence_pattern IS NOT NULL
            AND trash_recurrence_pattern IN ('NONE', 'DAILY', 'WEEKLY', 'MONTHLY')
            AND trash_recurrence_interval IS NOT NULL
            AND trash_recurrence_interval BETWEEN 1 AND 52
            AND trash_event_starts_at IS NOT NULL
            AND trash_event_ends_at IS NOT NULL
            AND trash_event_ends_at > trash_event_starts_at
            AND trash_event_time_zone IS NOT NULL
            AND length(trim(trash_event_time_zone)) > 0));

-- Existing trash-cancelled rows intentionally remain NULL. Their original series
-- cannot be reconstructed safely, so disabled recurrence policy must fail closed.

COMMENT ON COLUMN cal_resource_bookings.trash_recurrence_pattern IS
    'Series pattern at event-trash cancellation; used to prevent restore rebook policy bypass.';
COMMENT ON COLUMN cal_resource_bookings.trash_recurrence_interval IS
    'Series interval at event-trash cancellation; null means no trustworthy restore snapshot.';
COMMENT ON COLUMN cal_resource_bookings.trash_recurrence_until IS
    'Series end date at event-trash cancellation; null is valid for NONE or legacy unbounded recurrence.';
COMMENT ON COLUMN cal_resource_bookings.trash_event_starts_at IS
    'Event start at event-trash cancellation; null means restore must fail closed when recurring is disabled.';
COMMENT ON COLUMN cal_resource_bookings.trash_event_ends_at IS
    'Event end at event-trash cancellation; null means restore must fail closed when recurring is disabled.';
COMMENT ON COLUMN cal_resource_bookings.trash_event_time_zone IS
    'Event time zone at event-trash cancellation; null means restore must fail closed when recurring is disabled.';

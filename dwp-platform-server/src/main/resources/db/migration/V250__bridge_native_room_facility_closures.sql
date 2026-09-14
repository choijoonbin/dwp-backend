-- Calendar -> Workplace is the single lock order for canonical rooms.
CREATE OR REPLACE FUNCTION wp_lock_facility_closure_resource()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE mapped UUID; kind VARCHAR;
BEGIN
    SELECT calendar_resource_id, resource_type INTO mapped, kind FROM wp_resources
      WHERE tenant_id = NEW.tenant_id AND resource_id = NEW.resource_id;
    IF kind = 'ROOM' THEN
        IF mapped IS NULL THEN
            RAISE EXCEPTION 'Room closure requires a canonical Calendar resource' USING ERRCODE = '23514';
        END IF;
        PERFORM pg_advisory_xact_lock(hashtextextended(NEW.tenant_id || ':' || mapped, 0));
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'workplace-resource:' || NEW.tenant_id || ':' || NEW.resource_id, 0));
    RETURN NEW;
END;
$$;

-- Match Calendar's iterative ZonedDateTime recurrence increment, including
-- month-end clamping and retaining the previous UTC offset in a DST fold.
CREATE FUNCTION wp_facility_calendar_occurrences(anchor TIMESTAMPTZ, finish TIMESTAMPTZ,
    zone_name VARCHAR, pattern VARCHAR, step INTEGER, until_date DATE)
RETURNS TABLE(occurrence_starts_at TIMESTAMPTZ, occurrence_ends_at TIMESTAMPTZ)
LANGUAGE plpgsql STABLE AS $$
DECLARE current_start TIMESTAMPTZ := anchor; last_day DATE; local_next TIMESTAMP;
    next_start TIMESTAMPTZ; retained TIMESTAMPTZ; count INTEGER := 0; delta INTERVAL := finish - anchor;
BEGIN
    last_day := COALESCE(until_date, (anchor AT TIME ZONE zone_name)::DATE);
    IF step < 1 OR step > 52 OR finish <= anchor THEN
        RAISE EXCEPTION 'Invalid Calendar reservation range' USING ERRCODE = '23514';
    END IF;
    IF pattern <> 'NONE' AND until_date IS NULL THEN
        RAISE EXCEPTION 'Recurring resource reservation requires an end date' USING ERRCODE = '23514';
    END IF;
    WHILE (current_start AT TIME ZONE zone_name)::DATE <= last_day LOOP
        count := count + 1;
        IF count >= 4000 THEN
            RAISE EXCEPTION 'Recurring reservation exceeds Calendar scheduling policy' USING ERRCODE = '23514';
        END IF;
        occurrence_starts_at := current_start;
        occurrence_ends_at := current_start + delta;
        RETURN NEXT;
        IF pattern = 'NONE' THEN EXIT; END IF;
        local_next := current_start AT TIME ZONE zone_name;
        local_next := CASE pattern
          WHEN 'DAILY' THEN local_next + make_interval(days => step)
          WHEN 'WEEKLY' THEN local_next + make_interval(days => step * 7)
          WHEN 'MONTHLY' THEN local_next + make_interval(months => step)
          ELSE NULL END;
        IF local_next IS NULL THEN RAISE EXCEPTION 'Unknown Calendar recurrence' USING ERRCODE = '23514'; END IF;
        retained := (local_next AT TIME ZONE 'UTC')
          + (current_start - ((current_start AT TIME ZONE zone_name) AT TIME ZONE 'UTC'));
        next_start := CASE WHEN retained AT TIME ZONE zone_name = local_next
          THEN retained ELSE local_next AT TIME ZONE zone_name END;
        current_start := next_start;
    END LOOP;
END;
$$;

CREATE FUNCTION wp_check_calendar_facility_closure(tenant BIGINT, calendar_resource UUID,
    anchor TIMESTAMPTZ, finish TIMESTAMPTZ, zone_name VARCHAR, pattern VARCHAR, step INTEGER, until_date DATE)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE native_resource UUID;
BEGIN
    SELECT resource_id INTO native_resource FROM wp_resources
      WHERE tenant_id = tenant AND calendar_resource_id = calendar_resource AND resource_type = 'ROOM';
    IF native_resource IS NULL THEN RETURN; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(tenant || ':' || calendar_resource, 0));
    PERFORM pg_advisory_xact_lock(hashtextextended('workplace-resource:' || tenant || ':' || native_resource, 0));
    IF EXISTS (SELECT 1 FROM wp_experience_facility_closures c
        CROSS JOIN LATERAL wp_facility_calendar_occurrences(anchor, finish, zone_name, pattern, step, until_date) o
        WHERE c.tenant_id = tenant AND c.resource_id = native_resource AND c.closure_status = 'ACTIVE'
          AND c.starts_at < o.occurrence_ends_at AND c.ends_at > o.occurrence_starts_at) THEN
        RAISE EXCEPTION 'Room is closed for a requested Calendar occurrence' USING ERRCODE = '23P01';
    END IF;
END;
$$;

CREATE FUNCTION wp_guard_calendar_facility_booking()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE event_row cal_events%ROWTYPE;
BEGIN
    IF NEW.booking_status NOT IN ('PENDING', 'CONFIRMED') THEN RETURN NEW; END IF;
    SELECT * INTO event_row FROM cal_events WHERE tenant_id = NEW.tenant_id AND event_id = NEW.event_id;
    IF NOT FOUND OR event_row.status = 'CANCELLED' THEN RETURN NEW; END IF;
    PERFORM wp_check_calendar_facility_closure(NEW.tenant_id, NEW.resource_id, NEW.starts_at, NEW.ends_at,
      event_row.time_zone, event_row.recurrence_pattern, event_row.recurrence_interval, event_row.recurrence_until);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_booking_facility_closure_guard
BEFORE INSERT OR UPDATE OF resource_id, starts_at, ends_at, booking_status ON cal_resource_bookings
FOR EACH ROW EXECUTE FUNCTION wp_guard_calendar_facility_booking();

-- Deferred checks inspect the final owner state, allowing a room relocation to
-- cancel its previous booking within the same transaction before revalidation.
CREATE FUNCTION wp_guard_calendar_facility_event()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE event_row cal_events%ROWTYPE; reservation cal_resource_bookings%ROWTYPE;
BEGIN
    SELECT * INTO event_row FROM cal_events WHERE tenant_id = NEW.tenant_id AND event_id = NEW.event_id;
    IF NOT FOUND OR event_row.status = 'CANCELLED' THEN RETURN NULL; END IF;
    FOR reservation IN SELECT * FROM cal_resource_bookings
      WHERE tenant_id = event_row.tenant_id AND event_id = event_row.event_id
        AND booking_status IN ('PENDING', 'CONFIRMED') LOOP
        PERFORM wp_check_calendar_facility_closure(reservation.tenant_id, reservation.resource_id,
          reservation.starts_at, reservation.ends_at, event_row.time_zone, event_row.recurrence_pattern,
          event_row.recurrence_interval, event_row.recurrence_until);
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_calendar_event_facility_closure_guard
AFTER UPDATE OF starts_at, ends_at, time_zone, recurrence_pattern, recurrence_interval, recurrence_until ON cal_events
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wp_guard_calendar_facility_event();

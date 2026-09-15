-- Expand-only retention of the approved deprecation deadline. Existing deprecated rows whose
-- deadline was discarded remain readable, but catalog evaluation denies them until reviewed.
ALTER TABLE plt_widget_definition_versions
    ADD COLUMN deprecation_ends_at TIMESTAMPTZ;

-- NOT VALID preserves legacy rows without inventing an approval date; every new/updated row
-- must carry a deadline while DEPRECATED. A compatible rollback must retain this safety gate.
ALTER TABLE plt_widget_definition_versions
    ADD CONSTRAINT ck_widget_deprecation_deadline_present
    CHECK (release_state <> 'DEPRECATED' OR deprecation_ends_at IS NOT NULL) NOT VALID;

CREATE FUNCTION protect_widget_deprecation_deadline()
RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.deprecation_ends_at IS NOT NULL
       AND NEW.deprecation_ends_at IS DISTINCT FROM OLD.deprecation_ends_at THEN
        RAISE EXCEPTION 'approved widget deprecation deadline is immutable';
    END IF;
    IF NEW.deprecation_ends_at IS NOT NULL
       AND (TG_OP = 'INSERT' OR OLD.deprecation_ends_at IS NULL)
       AND (NEW.release_state <> 'DEPRECATED'
            OR NEW.deprecation_ends_at <= statement_timestamp()
            OR NEW.deprecation_ends_at > statement_timestamp() + INTERVAL '365 days') THEN
        RAISE EXCEPTION 'widget deprecation requires a bounded future deadline';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_widget_deprecation_deadline
BEFORE INSERT OR UPDATE ON plt_widget_definition_versions
FOR EACH ROW EXECUTE FUNCTION protect_widget_deprecation_deadline();

COMMENT ON COLUMN plt_widget_definition_versions.deprecation_ends_at IS
    'Immutable approved deadline retained across safety and channel rollback operations. Legacy NULL DEPRECATED rows deny discovery; no date is inferred.';

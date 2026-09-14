-- Legacy unmarked form schemas retain their existing lifecycle behavior.
CREATE FUNCTION approval_typed_form_canonical_json(value JSONB)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE STRICT
AS $$
DECLARE
    result TEXT;
BEGIN
    CASE jsonb_typeof(value)
        WHEN 'object' THEN
            SELECT '{' || COALESCE(string_agg(to_json(key)::text || ':' ||
                       approval_typed_form_canonical_json(item), ',' ORDER BY key COLLATE "C"), '') || '}'
              INTO result FROM jsonb_each(value) AS entry(key, item);
        WHEN 'array' THEN
            SELECT '[' || COALESCE(string_agg(approval_typed_form_canonical_json(item),
                       ',' ORDER BY ordinal), '') || ']'
              INTO result FROM jsonb_array_elements(value) WITH ORDINALITY AS entry(item, ordinal);
        WHEN 'number' THEN result := trim_scale(value::text::numeric)::text;
        ELSE result := value::text;
    END CASE;
    RETURN result;
END;
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM apr_form_versions
         WHERE schema_payload->>'schemaContract' = 'DWP_APPROVAL_FORM_TYPED_V2'
           AND (schema_payload->>'schemaVersion' IS DISTINCT FROM '2'
                OR schema_sha256::text IS DISTINCT FROM encode(sha256(convert_to(
                    approval_typed_form_canonical_json(schema_payload), 'UTF8')), 'hex'))
    ) THEN
        RAISE EXCEPTION 'Existing typed approval form schema canonical hash is invalid';
    END IF;
END;
$$;

CREATE FUNCTION protect_published_typed_approval_form_schema()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT'
       AND OLD.schema_payload->>'schemaContract' = 'DWP_APPROVAL_FORM_TYPED_V2'
       AND OLD.lifecycle_state = 'PUBLISHED' THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'Published typed approval form schema is immutable';
        END IF;
        IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
           OR NEW.form_id IS DISTINCT FROM OLD.form_id
           OR NEW.form_version_id IS DISTINCT FROM OLD.form_version_id
           OR NEW.version_number IS DISTINCT FROM OLD.version_number
           OR NEW.schema_payload IS DISTINCT FROM OLD.schema_payload
           OR NEW.schema_sha256 IS DISTINCT FROM OLD.schema_sha256
           OR NEW.lifecycle_state IS DISTINCT FROM OLD.lifecycle_state
           OR NEW.published_at IS DISTINCT FROM OLD.published_at
           OR NEW.published_by IS DISTINCT FROM OLD.published_by
           OR NEW.created_at IS DISTINCT FROM OLD.created_at
           OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
            RAISE EXCEPTION 'Published typed approval form schema is immutable';
        END IF;
    END IF;
    IF TG_OP <> 'DELETE'
       AND NEW.schema_payload->>'schemaContract' = 'DWP_APPROVAL_FORM_TYPED_V2' THEN
        IF NEW.schema_payload->>'schemaVersion' IS DISTINCT FROM '2'
           OR NEW.schema_sha256::text IS DISTINCT FROM
               encode(sha256(convert_to(approval_typed_form_canonical_json(NEW.schema_payload), 'UTF8')), 'hex') THEN
            RAISE EXCEPTION 'Typed approval form schema canonical hash is invalid';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_protect_published_typed_approval_form_schema
BEFORE INSERT OR UPDATE OR DELETE ON apr_form_versions
FOR EACH ROW EXECUTE FUNCTION protect_published_typed_approval_form_schema();

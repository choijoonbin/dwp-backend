DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM ppl_attribute_values LIMIT 1) THEN
        RAISE EXCEPTION 'ppl_attribute_values contains data and requires an explicit migration plan';
    END IF;
    IF EXISTS (SELECT 1 FROM ppl_attribute_definitions LIMIT 1) THEN
        RAISE EXCEPTION 'ppl_attribute_definitions contains data and requires an explicit migration plan';
    END IF;
    IF EXISTS (SELECT 1 FROM ppl_person_private LIMIT 1) THEN
        RAISE EXCEPTION 'ppl_person_private contains data and requires an explicit migration plan';
    END IF;
    IF EXISTS (SELECT 1 FROM ppl_person_identifiers LIMIT 1) THEN
        RAISE EXCEPTION 'ppl_person_identifiers contains data and requires an explicit migration plan';
    END IF;
END
$$;

-- Extensible and restricted workforce fields require a field-level authorization and
-- encryption contract before physical storage is introduced.
DROP TABLE ppl_attribute_values;
DROP TABLE ppl_attribute_definitions;
DROP TABLE ppl_person_private;
DROP TABLE ppl_person_identifiers;

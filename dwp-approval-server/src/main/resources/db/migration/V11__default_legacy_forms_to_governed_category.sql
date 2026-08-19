CREATE OR REPLACE FUNCTION assign_approval_form_general_category()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.category_id IS NOT NULL THEN
        RETURN NEW;
    END IF;

    SELECT category.category_id
      INTO NEW.category_id
      FROM apr_form_categories category
     WHERE category.tenant_id = NEW.tenant_id
       AND category.category_key = 'GENERAL';

    IF NEW.category_id IS NULL THEN
        RAISE EXCEPTION
            'Tenant % must initialize the governed approval form catalog before creating forms',
            NEW.tenant_id;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_apr_forms_general_category ON apr_forms;
CREATE TRIGGER trg_apr_forms_general_category
BEFORE INSERT OR UPDATE OF category_id ON apr_forms
FOR EACH ROW
EXECUTE FUNCTION assign_approval_form_general_category();

COMMENT ON FUNCTION assign_approval_form_general_category() IS
    'Compatibility guard that maps legacy form writers to the tenant GENERAL category; governed APIs still require an explicit category.';

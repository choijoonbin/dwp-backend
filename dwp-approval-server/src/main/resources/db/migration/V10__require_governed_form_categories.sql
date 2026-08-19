SELECT seed_approval_form_catalog(tenant_id)
  FROM apr_tenants;

UPDATE apr_forms form
   SET category_id = category.category_id,
       updated_at = CURRENT_TIMESTAMP
  FROM apr_form_categories category
 WHERE category.tenant_id = form.tenant_id
   AND category.category_key = 'GENERAL'
   AND form.category_id IS NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM apr_forms WHERE category_id IS NULL) THEN
        RAISE EXCEPTION 'Every approval form must belong to a governed category';
    END IF;
END;
$$;

ALTER TABLE apr_forms
    ALTER COLUMN category_id SET NOT NULL;

COMMENT ON COLUMN apr_forms.category_id IS
    'Required tenant-scoped catalog category used for governed discovery and administration.';

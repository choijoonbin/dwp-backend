DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM com_tenants
         WHERE public_id = '00000000-0000-0000-0000-000000000001'::UUID
           AND code <> 'default'
    ) THEN
        RAISE EXCEPTION 'The default provider tenant identifier is already assigned';
    END IF;
END
$$;

UPDATE com_tenants
SET public_id = '00000000-0000-0000-0000-000000000001'::UUID,
    updated_at = CURRENT_TIMESTAMP
WHERE code = 'default'
  AND public_id <> '00000000-0000-0000-0000-000000000001'::UUID;

ALTER TABLE com_users
    ADD COLUMN worker_number VARCHAR(100);

ALTER TABLE com_users
    ADD CONSTRAINT ck_com_users_worker_number
        CHECK (worker_number IS NULL OR length(btrim(worker_number)) BETWEEN 1 AND 100);

CREATE UNIQUE INDEX uk_com_users_tenant_worker_number
    ON com_users (tenant_id, worker_number)
    WHERE worker_number IS NOT NULL;

-- These local reference identities were created from the same authoritative worker
-- number in V21/V23. Other identities remain null until a governed HRIS event arrives.
UPDATE com_users
   SET worker_number = substring(external_id FROM length('SKAX-HRIS-') + 1)
 WHERE tenant_id = 1
   AND source_type = 'HRIS'
   AND external_id LIKE 'SKAX-HRIS-%';

UPDATE com_users
   SET worker_number = substring(external_id FROM length('DWP-REF-') + 1)
 WHERE tenant_id = 1
   AND source_type = 'HRIS'
   AND external_id LIKE 'DWP-REF-%';

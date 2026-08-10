ALTER TABLE com_users
    ADD COLUMN job_title VARCHAR(160);

UPDATE com_users
SET job_title = 'Platform administrator'
WHERE tenant_id = 1
  AND user_id = 1
  AND job_title IS NULL;

-- Mirror the synthetic V24 People reference workers at the Auth boundary.
-- Accounts remain INVITED with no credential; delivery environments replace
-- this seed through the governed HRIS identity synchronization contract.
CREATE TEMP TABLE tmp_dwp_reference_identities (
    worker_number VARCHAR(100) PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    work_email VARCHAR(255) NOT NULL,
    job_title VARCHAR(160) NOT NULL,
    role_code VARCHAR(50) NOT NULL
) ON COMMIT DROP;

WITH organizations(ordinal, member_count) AS (
    VALUES
        (1, 0), (2, 3), (3, 3), (4, 2), (5, 2), (6, 0), (7, 3), (8, 2),
        (9, 3), (10, 0), (11, 3), (12, 3), (13, 3), (14, 3), (15, 0),
        (16, 3), (17, 3), (18, 0), (19, 3), (20, 3), (21, 0), (22, 2),
        (23, 2), (24, 0), (25, 3), (26, 3), (27, 0), (28, 3), (29, 3),
        (30, 0), (31, 0), (32, 3), (33, 3), (34, 3), (35, 0), (36, 0),
        (37, 3), (38, 3), (39, 3), (40, 3), (41, 0), (42, 0), (43, 3),
        (44, 3), (45, 0), (46, 3)
), name_pool AS (
    SELECT ARRAY['김','이','박','최','정','강','조','윤','장','임','한']::text[] AS families,
           ARRAY['서준','지우','하윤','민재','예린','도현','수아','태민','채원','시우','유진','현우','아린','준호','서진','도윤','지민']::text[] AS given_names
), identities AS (
    SELECT 'RFL' || LPAD(organization.ordinal::text, 3, '0') AS worker_number,
           pool.families[((organization.ordinal - 1) % CARDINALITY(pool.families)) + 1]
               || pool.given_names[((organization.ordinal * 7 - 1) % CARDINALITY(pool.given_names)) + 1]
               AS display_name,
           organization.ordinal AS role_seed,
           'Organization Lead'::varchar AS job_title
      FROM organizations organization
     CROSS JOIN name_pool pool
    UNION ALL
    SELECT 'RFM' || LPAD(organization.ordinal::text, 3, '0') || member.sequence,
           pool.families[((organization.ordinal * 3 + member.sequence + 59) % CARDINALITY(pool.families)) + 1]
               || pool.given_names[((organization.ordinal * 5 + member.sequence + 41) % CARDINALITY(pool.given_names)) + 1],
           organization.ordinal * 10 + member.sequence,
           'Enterprise Specialist'::varchar
      FROM organizations organization
     CROSS JOIN LATERAL GENERATE_SERIES(1, organization.member_count) member(sequence)
     CROSS JOIN name_pool pool
)
INSERT INTO tmp_dwp_reference_identities (
    worker_number, display_name, work_email, job_title, role_code)
SELECT identity.worker_number,
       identity.display_name,
       LOWER(identity.worker_number) || '@dwp-reference.example',
       identity.job_title,
       CASE MOD(identity.role_seed, 3)
           WHEN 0 THEN 'AUDITOR'
           WHEN 1 THEN 'AUDIT_ADMIN'
           ELSE 'PROVIDER_ADMIN'
       END
  FROM identities identity;

INSERT INTO com_users (
    tenant_id, display_name, email, status, job_title,
    person_public_id, preferred_locale, source_type, external_id,
    created_by, updated_by)
SELECT 1, seed.display_name, seed.work_email, 'INVITED', seed.job_title,
       md5('dwp-reference-person:' || seed.worker_number)::uuid,
       'ko-KR', 'HRIS', 'DWP-REF-' || seed.worker_number, 1, 1
  FROM tmp_dwp_reference_identities seed
ON CONFLICT (tenant_id, email_normalized)
    WHERE email_normalized IS NOT NULL
DO UPDATE SET
    display_name = EXCLUDED.display_name,
    status = 'INVITED',
    job_title = EXCLUDED.job_title,
    person_public_id = EXCLUDED.person_public_id,
    preferred_locale = EXCLUDED.preferred_locale,
    source_type = 'HRIS',
    external_id = EXCLUDED.external_id,
    version = com_users.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO com_user_accounts (
    tenant_id, user_id, provider_type, provider_id, principal,
    password_hash, status, created_by, updated_by)
SELECT 1, user_record.user_id, 'LOCAL', 'local', seed.work_email,
       NULL, 'INVITED', 1, 1
  FROM tmp_dwp_reference_identities seed
  JOIN com_users user_record
    ON user_record.tenant_id = 1
   AND user_record.email_normalized = LOWER(seed.work_email)
 WHERE NOT EXISTS (
       SELECT 1
         FROM com_user_accounts account
        WHERE account.tenant_id = user_record.tenant_id
          AND account.user_id = user_record.user_id
          AND account.provider_type = 'LOCAL');

DELETE FROM com_role_members membership
 USING com_users user_record, tmp_dwp_reference_identities seed
 WHERE membership.tenant_id = 1
   AND user_record.tenant_id = membership.tenant_id
   AND user_record.user_id = membership.user_id
   AND user_record.email_normalized = LOWER(seed.work_email);

INSERT INTO com_role_members (
    tenant_id, role_id, user_id, created_by, updated_by)
SELECT 1, role.role_id, user_record.user_id, 1, 1
  FROM tmp_dwp_reference_identities seed
  JOIN com_users user_record
    ON user_record.tenant_id = 1
   AND user_record.email_normalized = LOWER(seed.work_email)
  JOIN com_roles role
    ON role.tenant_id = 1 AND role.code = seed.role_code
ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING;

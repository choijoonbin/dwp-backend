UPDATE com_tenants
   SET name = 'SKAX', updated_at = CURRENT_TIMESTAMP, updated_by = 1
 WHERE tenant_id = 1;

UPDATE com_users
   SET display_name = 'SKAX Administrator',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE tenant_id = 1 AND user_id = 1;

CREATE TEMP TABLE tmp_skax_identities (
    worker_number VARCHAR(100) PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    work_email VARCHAR(255) NOT NULL,
    job_title VARCHAR(160) NOT NULL,
    preferred_locale VARCHAR(35) NOT NULL,
    role_code VARCHAR(50) NOT NULL,
    old_external_id VARCHAR(255)
) ON COMMIT DROP;

INSERT INTO tmp_skax_identities VALUES
    ('SK0001', '김민준', 'minjun.kim@skax.example', 'Chief Executive Officer', 'ko-KR', 'ADMIN', NULL),
    ('SK0002', '이서연', 'seoyeon.lee@skax.example', 'Executive Strategy Officer', 'ko-KR', 'AUDIT_ADMIN', NULL),
    ('SK0003', '박현우', 'hyunwoo.park@skax.example', 'Digital Platform 부문장', 'ko-KR', 'PROVIDER_ADMIN', NULL),
    ('SK0004', '최유진', 'yujin.choi@skax.example', 'Enterprise Transformation 부문장', 'ko-KR', 'AUDITOR', NULL),
    ('SK0005', '정우성', 'woosung.jung@skax.example', 'Corporate Center 장', 'ko-KR', 'AUDIT_ADMIN', NULL),
    ('SK0006', '한지민', 'jimin.han@skax.example', 'Semiconductor AX 부문장', 'ko-KR', 'PROVIDER_ADMIN', NULL),
    ('SK0007', '김도윤', 'doyun.kim@skax.example', 'AI Platform 본부장', 'ko-KR', 'AUDITOR', NULL),
    ('SK0008', '윤서진', 'seojin.yoon@skax.example', 'Cloud & Infra 본부장', 'ko-KR', 'AUDIT_ADMIN', NULL),
    ('SK0009', '장민석', 'minseok.jang@skax.example', 'GenAI Engineering 팀장', 'ko-KR', 'PROVIDER_ADMIN', NULL),
    ('SK0010', '조하은', 'haeun.cho@skax.example', 'AI Product Engineer', 'ko-KR', 'AUDITOR', NULL),
    ('SK0011', '임재현', 'jaehyun.lim@skax.example', 'LLM Platform Engineer', 'ko-KR', 'AUDITOR', NULL),
    ('SK0012', 'Sofia Chen', 'sofia.chen@skax.example', 'AI Safety Researcher', 'en-US', 'AUDIT_ADMIN', NULL),
    ('SK0013', '오수빈', 'subin.oh@skax.example', 'Data Platform 팀장', 'ko-KR', 'PROVIDER_ADMIN', NULL),
    ('SK0014', '강태훈', 'taehoon.kang@skax.example', 'Data Architect', 'ko-KR', 'AUDITOR', NULL),
    ('SK0015', '문예린', 'yerin.moon@skax.example', 'Analytics Engineer', 'ko-KR', 'AUDITOR', NULL),
    ('SK0016', '송준호', 'junho.song@skax.example', 'Cloud Platform 팀장', 'ko-KR', 'AUDIT_ADMIN', NULL),
    ('SK0017', '배지우', 'jiwoo.bae@skax.example', 'Site Reliability Engineer', 'ko-KR', 'AUDITOR', NULL),
    ('SK0018', 'Alex Morgan', 'alex.morgan@skax.example', 'Cloud Security Engineer', 'en-US', 'PROVIDER_ADMIN', NULL),
    ('E100001', '김민서', 'minseo.kim@skax.example', 'Network Operations Lead', 'ko-KR', 'AUDIT_ADMIN', 'WD-WORKER-0001'),
    ('E100002', '박지호', 'jiho.park@skax.example', 'Network Automation Engineer', 'ko-KR', 'AUDITOR', 'WD-WORKER-0002'),
    ('SK0019', '신예준', 'yejun.shin@skax.example', 'ERP Innovation 본부장', 'ko-KR', 'PROVIDER_ADMIN', NULL),
    ('SK0020', '김채원', 'chaewon.kim@skax.example', 'SAP Transformation Consultant', 'ko-KR', 'AUDITOR', NULL),
    ('SK0021', '류민재', 'minjae.ryu@skax.example', 'Enterprise Architect', 'ko-KR', 'AUDIT_ADMIN', NULL),
    ('SK0022', '서아린', 'arin.seo@skax.example', 'Digital Consulting 본부장', 'ko-KR', 'PROVIDER_ADMIN', NULL),
    ('SK0023', '정서우', 'seowoo.jung@skax.example', 'Business Consultant', 'ko-KR', 'AUDITOR', NULL),
    ('SK0024', '이도현', 'dohyun.lee@skax.example', 'Change Management Lead', 'ko-KR', 'AUDIT_ADMIN', NULL),
    ('SK0025', '박나연', 'nayeon.park@skax.example', 'Customer Experience 팀장', 'ko-KR', 'PROVIDER_ADMIN', NULL),
    ('SK0026', '최건우', 'gunwoo.choi@skax.example', 'UX Strategist', 'ko-KR', 'AUDITOR', NULL),
    ('SK0027', 'Emily Johnson', 'emily.johnson@skax.example', 'Service Designer', 'en-US', 'AUDITOR', NULL),
    ('SK0028', '홍지수', 'jisoo.hong@skax.example', 'People & Culture 팀장', 'ko-KR', 'AUDIT_ADMIN', NULL),
    ('SK0029', '남도윤', 'doyoon.nam@skax.example', 'HR Business Partner', 'ko-KR', 'AUDITOR', NULL),
    ('SK0030', '고서윤', 'seoyoon.ko@skax.example', 'Talent Development Manager', 'ko-KR', 'PROVIDER_ADMIN', NULL),
    ('SK0031', '김태연', 'taeyeon.kim@skax.example', 'Finance & Risk 팀장', 'ko-KR', 'AUDIT_ADMIN', NULL),
    ('SK0032', '유승민', 'seungmin.yoo@skax.example', 'Financial Controller', 'ko-KR', 'AUDITOR', NULL),
    ('SK0033', 'James Wilson', 'james.wilson@skax.example', 'Risk Analyst', 'en-US', 'AUDITOR', NULL),
    ('SK0034', '노하린', 'harin.noh@skax.example', 'Strategy & ESG 팀장', 'ko-KR', 'PROVIDER_ADMIN', NULL),
    ('SK0035', '안지훈', 'jihoon.ahn@skax.example', 'ESG Program Manager', 'ko-KR', 'AUDITOR', NULL),
    ('SK0036', '백예은', 'yeeun.baek@skax.example', 'Corporate Strategy Analyst', 'ko-KR', 'AUDIT_ADMIN', NULL),
    ('C200001', 'Elena Garcia', 'elena.garcia@skax.example', 'Yield Data Specialist', 'en-US', 'AUDITOR', 'WD-WORKER-0003'),
    ('SK0037', '권민성', 'minsung.kwon@skax.example', 'Semiconductor Data Operations 팀장', 'ko-KR', 'PROVIDER_ADMIN', NULL),
    ('SK0038', '김라온', 'raon.kim@skax.example', 'Yield Analytics Engineer', 'ko-KR', 'AUDITOR', NULL),
    ('SK0039', '나준서', 'junseo.na@skax.example', 'Smart Factory 팀장', 'ko-KR', 'AUDIT_ADMIN', NULL),
    ('SK0040', '전유나', 'yuna.jeon@skax.example', 'Manufacturing AI Engineer', 'ko-KR', 'AUDITOR', NULL);

UPDATE com_users target
   SET display_name = seed.display_name,
       email = seed.work_email,
       job_title = seed.job_title,
       person_public_id = md5('skax-person:' || seed.worker_number)::uuid,
       preferred_locale = seed.preferred_locale,
       source_type = 'HRIS',
       external_id = 'SKAX-HRIS-' || seed.worker_number,
       status = 'INVITED',
       access_revision = target.access_revision + 1,
       version = target.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_skax_identities seed
 WHERE target.tenant_id = 1
   AND seed.old_external_id IS NOT NULL
   AND target.external_id = seed.old_external_id;

INSERT INTO com_users (
    tenant_id, display_name, email, status, job_title,
    person_public_id, preferred_locale, source_type, external_id,
    created_by, updated_by)
SELECT 1, seed.display_name, seed.work_email, 'INVITED', seed.job_title,
       md5('skax-person:' || seed.worker_number)::uuid,
       seed.preferred_locale, 'HRIS', 'SKAX-HRIS-' || seed.worker_number, 1, 1
  FROM tmp_skax_identities seed
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

UPDATE com_user_accounts account
   SET principal = seed.work_email,
       status = 'INVITED',
       password_hash = NULL,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM com_users user_record
  JOIN tmp_skax_identities seed
    ON user_record.tenant_id = 1
   AND user_record.email_normalized = LOWER(seed.work_email)
 WHERE account.tenant_id = user_record.tenant_id
   AND account.user_id = user_record.user_id
   AND account.provider_type = 'LOCAL';

INSERT INTO com_user_accounts (
    tenant_id, user_id, provider_type, provider_id, principal,
    password_hash, status, created_by, updated_by)
SELECT 1, user_record.user_id, 'LOCAL', 'local', seed.work_email,
       NULL, 'INVITED', 1, 1
  FROM tmp_skax_identities seed
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
 USING com_users user_record, tmp_skax_identities seed
 WHERE membership.tenant_id = 1
   AND user_record.tenant_id = membership.tenant_id
   AND user_record.user_id = membership.user_id
   AND user_record.email_normalized = LOWER(seed.work_email);

INSERT INTO com_role_members (
    tenant_id, role_id, user_id, created_by, updated_by)
SELECT 1, role.role_id, user_record.user_id, 1, 1
  FROM tmp_skax_identities seed
  JOIN com_users user_record
    ON user_record.tenant_id = 1
   AND user_record.email_normalized = LOWER(seed.work_email)
  JOIN com_roles role
    ON role.tenant_id = 1 AND role.code = seed.role_code
ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING;

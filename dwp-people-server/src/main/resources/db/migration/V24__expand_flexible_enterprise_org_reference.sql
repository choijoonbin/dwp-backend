-- This is a synthetic reference profile, never a prescribed hierarchy.
-- It intentionally mixes four- and five-layer branches and tenant-defined types
-- to prove that the effective graph is not constrained to company/division/team.
INSERT INTO ppl_organization_type_catalog (
    tenant_id, type_key, display_name, description, label_i18n,
    icon_key, hierarchy_rank, root_candidate, created_by, updated_by)
SELECT 1, seed.type_key, seed.display_name, seed.description, seed.label_i18n::jsonb,
       seed.icon_key, seed.hierarchy_rank, FALSE, 1, 1
  FROM (VALUES
        ('CENTER', 'Center', 'Cross-functional center or center of excellence.',
         '{"ko-KR":"센터","en-US":"Center"}', 'landmark', 35),
        ('PRODUCT_GROUP', 'Product group', 'Persistent product or capability portfolio.',
         '{"ko-KR":"프로덕트 그룹","en-US":"Product group"}', 'blocks', 45),
        ('SQUAD', 'Squad', 'Outcome-oriented multidisciplinary squad.',
         '{"ko-KR":"스쿼드","en-US":"Squad"}', 'component', 55),
        ('CHAPTER', 'Chapter', 'Professional practice and capability chapter.',
         '{"ko-KR":"챕터","en-US":"Chapter"}', 'book-open-check', 55),
        ('REGION', 'Region', 'Geographic operating region.',
         '{"ko-KR":"지역 조직","en-US":"Region"}', 'globe-2', 30),
        ('DELIVERY_POD', 'Delivery pod', 'Market-facing delivery pod.',
         '{"ko-KR":"딜리버리 포드","en-US":"Delivery pod"}', 'package-open', 50))
       seed(type_key, display_name, description, label_i18n, icon_key, hierarchy_rank)
ON CONFLICT (tenant_id, type_key) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    label_i18n = EXCLUDED.label_i18n,
    icon_key = EXCLUDED.icon_key,
    hierarchy_rank = EXCLUDED.hierarchy_rank,
    lifecycle_state = 'ACTIVE',
    version = ppl_organization_type_catalog.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

CREATE TEMP TABLE tmp_dwp_reference_organizations (
    ordinal INTEGER PRIMARY KEY,
    organization_key VARCHAR(100) NOT NULL UNIQUE,
    organization_type VARCHAR(100) NOT NULL,
    name VARCHAR(240) NOT NULL,
    short_name VARCHAR(80) NOT NULL,
    parent_key VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    color_token VARCHAR(30) NOT NULL,
    location_key VARCHAR(100) NOT NULL,
    seed_members INTEGER NOT NULL DEFAULT 0
) ON COMMIT DROP;

INSERT INTO tmp_dwp_reference_organizations VALUES
    (1, 'ORG-TECH-AGENTIC', 'PRODUCT_GROUP', 'Agentic Product Group', 'Agentic Products', 'ORG-AI-PLATFORM', '에이전틱 제품 포트폴리오와 공통 런타임을 책임집니다.', 'VIOLET', 'PANGYO-CAMPUS', 0),
    (2, 'ORG-AGENT-RUNTIME', 'SQUAD', 'Agent Runtime Squad', 'Agent Runtime', 'ORG-TECH-AGENTIC', '멀티 에이전트 실행, 도구 연결과 관측성을 개발합니다.', 'VIOLET', 'PANGYO-CAMPUS', 3),
    (3, 'ORG-AI-TRUST', 'SQUAD', 'AI Trust & Evaluation Squad', 'AI Trust', 'ORG-TECH-AGENTIC', 'AI 평가, 안전성, 정책 준수 체계를 제품화합니다.', 'CORAL', 'PANGYO-CAMPUS', 3),
    (4, 'ORG-DATA-GOV', 'CHAPTER', 'Data Governance Chapter', 'Data Governance', 'ORG-DATA', '데이터 품질, 계보와 접근 정책 역량을 관리합니다.', 'TEAL', 'PANGYO-CAMPUS', 2),
    (5, 'ORG-MLOPS', 'CHAPTER', 'MLOps Chapter', 'MLOps', 'ORG-DATA', '모델 수명주기와 AI 플랫폼 신뢰성을 표준화합니다.', 'BLUE', 'PANGYO-CAMPUS', 2),
    (6, 'ORG-CLOUD-PRODUCT', 'PRODUCT_GROUP', 'Cloud Platform Product Group', 'Cloud Products', 'ORG-CLOUD-INFRA', '개발자 플랫폼과 클라우드 운영 제품을 총괄합니다.', 'CYAN', 'PANGYO-CAMPUS', 0),
    (7, 'ORG-SRE', 'SQUAD', 'Site Reliability Squad', 'SRE', 'ORG-CLOUD-PRODUCT', '서비스 신뢰성, 관측성, 복원력을 엔지니어링합니다.', 'CYAN', 'DAEJEON-CENTER', 3),
    (8, 'ORG-FINOPS', 'SQUAD', 'Cloud FinOps Squad', 'FinOps', 'ORG-CLOUD-PRODUCT', '클라우드 비용과 탄소 효율을 최적화합니다.', 'GREEN', 'PANGYO-CAMPUS', 2),
    (9, 'ORG-CYBER-RESILIENCE', 'SUPERVISORY', 'Cyber Resilience Team', 'Cyber Resilience', 'ORG-CLOUD-INFRA', '제로 트러스트와 사이버 복원력을 운영합니다.', 'CORAL', 'SEOUL-HQ', 3),
    (10, 'ORG-DX-ERP-PLATFORM', 'PRODUCT_GROUP', 'Enterprise Core Product Group', 'Enterprise Core', 'ORG-ERP', 'ERP 코어와 확장 플랫폼 제품군을 담당합니다.', 'GREEN', 'SEOUL-HQ', 0),
    (11, 'ORG-ERP-CORE', 'SQUAD', 'ERP Core Engineering Squad', 'ERP Core', 'ORG-DX-ERP-PLATFORM', '차세대 ERP 코어와 도메인 서비스를 개발합니다.', 'GREEN', 'SEOUL-HQ', 3),
    (12, 'ORG-ERP-INTEGRATION', 'SQUAD', 'Enterprise Integration Squad', 'Integration', 'ORG-DX-ERP-PLATFORM', '이벤트 및 API 기반 기업 시스템 통합을 제공합니다.', 'BLUE', 'SEOUL-HQ', 3),
    (13, 'ORG-DX-STRATEGY', 'SUPERVISORY', 'Transformation Strategy Team', 'DX Strategy', 'ORG-CONSULT', '산업별 전환 전략과 가치 실현 계획을 설계합니다.', 'AMBER', 'SEOUL-HQ', 3),
    (14, 'ORG-DX-CHANGE', 'SUPERVISORY', 'Change & Adoption Team', 'Change & Adoption', 'ORG-CONSULT', '변화관리와 디지털 채택을 데이터로 가속합니다.', 'PINK', 'SEOUL-HQ', 3),
    (15, 'ORG-CORP-PEOPLE-CENTER', 'CENTER', 'People Experience Center', 'People Experience', 'ORG-CORP', '구성원 경험과 인재 운영 제품을 통합합니다.', 'PINK', 'SEOUL-HQ', 0),
    (16, 'ORG-WORKFORCE-EXPERIENCE', 'SUPERVISORY', 'Workforce Experience Team', 'Workforce EX', 'ORG-CORP-PEOPLE-CENTER', '입사부터 성장까지 구성원 여정을 설계합니다.', 'PINK', 'SEOUL-HQ', 3),
    (17, 'ORG-TALENT-INTELLIGENCE', 'SQUAD', 'Talent Intelligence Squad', 'Talent Intelligence', 'ORG-CORP-PEOPLE-CENTER', '스킬 그래프와 인재 의사결정 분석을 제공합니다.', 'VIOLET', 'SEOUL-HQ', 3),
    (18, 'ORG-CORP-FINANCE-CENTER', 'CENTER', 'Finance Operations Center', 'Finance Operations', 'ORG-CORP', '재무 운영, 구매와 통제 자동화를 총괄합니다.', 'AMBER', 'SEOUL-HQ', 0),
    (19, 'ORG-FINANCE-OPS', 'SUPERVISORY', 'Digital Finance Operations Team', 'Digital Finance', 'ORG-CORP-FINANCE-CENTER', '결산과 관리회계 프로세스를 지능화합니다.', 'AMBER', 'SEOUL-HQ', 3),
    (20, 'ORG-RISK-CONTROL', 'SUPERVISORY', 'Enterprise Risk Control Team', 'Risk Control', 'ORG-CORP-FINANCE-CENTER', '전사 리스크와 내부통제를 연속 모니터링합니다.', 'CORAL', 'SEOUL-HQ', 3),
    (21, 'ORG-CORP-GOV-CENTER', 'CENTER', 'Corporate Governance Center', 'Governance', 'ORG-CORP', '법무, 컴플라이언스와 ESG 거버넌스를 통합합니다.', 'SLATE', 'SEOUL-HQ', 0),
    (22, 'ORG-LEGAL-COMPLIANCE', 'SUPERVISORY', 'Legal & Compliance Team', 'Legal & Compliance', 'ORG-CORP-GOV-CENTER', '법률 리스크와 규제 준수를 관리합니다.', 'SLATE', 'SEOUL-HQ', 2),
    (23, 'ORG-ESG-DATA', 'SQUAD', 'ESG Data & Disclosure Squad', 'ESG Data', 'ORG-CORP-GOV-CENTER', 'ESG 데이터와 공시 증적을 관리합니다.', 'GREEN', 'SEOUL-HQ', 2),
    (24, 'ORG-SEMI-MFG-INTEL', 'DIVISION', 'Manufacturing Intelligence Division', 'Manufacturing AI', 'ORG-SEMI', '반도체 제조 지능화와 디지털 트윈을 총괄합니다.', 'CORAL', 'PANGYO-CAMPUS', 0),
    (25, 'ORG-YIELD-OPT', 'SUPERVISORY', 'Yield Optimization Team', 'Yield Optimization', 'ORG-SEMI-MFG-INTEL', '수율 원인 분석과 공정 최적화를 수행합니다.', 'CORAL', 'PANGYO-CAMPUS', 3),
    (26, 'ORG-FAB-AUTOMATION', 'SUPERVISORY', 'Fab Automation Team', 'Fab Automation', 'ORG-SEMI-MFG-INTEL', '팹 자동화와 설비 예지보전을 개발합니다.', 'TEAL', 'DAEJEON-CENTER', 3),
    (27, 'ORG-SEMI-SUPPLY', 'DIVISION', 'Semiconductor Supply Intelligence Division', 'Supply Intelligence', 'ORG-SEMI', '수요, 자재와 공급망 의사결정을 지능화합니다.', 'AMBER', 'PANGYO-CAMPUS', 0),
    (28, 'ORG-DEMAND-INTELLIGENCE', 'SUPERVISORY', 'Demand Intelligence Team', 'Demand Intelligence', 'ORG-SEMI-SUPPLY', '수요 감지와 생산 배분 시나리오를 운영합니다.', 'AMBER', 'PANGYO-CAMPUS', 3),
    (29, 'ORG-MATERIALS-LOGISTICS', 'SUPERVISORY', 'Materials & Logistics Team', 'Materials & Logistics', 'ORG-SEMI-SUPPLY', '원부자재와 글로벌 물류 흐름을 최적화합니다.', 'BLUE', 'BUSAN-HUB', 3),
    (30, 'ORG-TELCO', 'BUSINESS_UNIT', 'Telecom Intelligence Business Unit', 'Telecom Intelligence', 'ROOT', '통신 네트워크와 고객 경험을 AI 네이티브로 전환합니다.', 'BLUE', 'SEOUL-HQ', 0),
    (31, 'ORG-TELCO-NETWORK', 'DIVISION', 'Network Intelligence Division', 'Network Intelligence', 'ORG-TELCO', '자율 네트워크와 엣지 플랫폼을 총괄합니다.', 'BLUE', 'DAEJEON-CENTER', 0),
    (32, 'ORG-5G-CORE', 'SUPERVISORY', '5G Core Engineering Team', '5G Core', 'ORG-TELCO-NETWORK', '클라우드 네이티브 코어와 서비스 자동화를 개발합니다.', 'BLUE', 'DAEJEON-CENTER', 3),
    (33, 'ORG-NETWORK-AI', 'SUPERVISORY', 'Autonomous Network AI Team', 'Network AI', 'ORG-TELCO-NETWORK', '네트워크 예측과 폐루프 최적화를 개발합니다.', 'VIOLET', 'DAEJEON-CENTER', 3),
    (34, 'ORG-EDGE-PLATFORM', 'SUPERVISORY', 'Edge Platform Team', 'Edge Platform', 'ORG-TELCO-NETWORK', '분산 엣지 컴퓨팅과 워크로드 오케스트레이션을 제공합니다.', 'CYAN', 'BUSAN-HUB', 3),
    (35, 'ORG-TELCO-GROWTH', 'DIVISION', 'Customer Growth Division', 'Customer Growth', 'ORG-TELCO', '통신 고객 여정과 B2B 성장을 총괄합니다.', 'PINK', 'SEOUL-HQ', 0),
    (36, 'ORG-DIGITAL-CHANNELS', 'PRODUCT_GROUP', 'Digital Channels Product Group', 'Digital Channels', 'ORG-TELCO-GROWTH', '모바일과 웹 채널 제품군을 운영합니다.', 'PINK', 'SEOUL-HQ', 0),
    (37, 'ORG-MOBILE-EXPERIENCE', 'SQUAD', 'Mobile Experience Squad', 'Mobile Experience', 'ORG-DIGITAL-CHANNELS', '모바일 셀프서비스와 초개인화 경험을 개발합니다.', 'PINK', 'SEOUL-HQ', 3),
    (38, 'ORG-WEB-COMMERCE', 'SQUAD', 'Web Commerce Squad', 'Web Commerce', 'ORG-DIGITAL-CHANNELS', '웹 가입과 커머스 전환을 최적화합니다.', 'GREEN', 'SEOUL-HQ', 3),
    (39, 'ORG-B2B-PLATFORM', 'SUPERVISORY', 'B2B Platform Team', 'B2B Platform', 'ORG-TELCO-GROWTH', '기업 고객용 연결성과 운영 플랫폼을 개발합니다.', 'BLUE', 'SEOUL-HQ', 3),
    (40, 'ORG-CARE-AI', 'SUPERVISORY', 'Customer Care AI Team', 'Customer Care AI', 'ORG-TELCO-GROWTH', '상담 에이전트와 고객 케어 자동화를 제공합니다.', 'VIOLET', 'SEOUL-HQ', 3),
    (41, 'ORG-GLOBAL', 'BUSINESS_UNIT', 'Global Delivery Business Unit', 'Global Delivery', 'ROOT', '지역별 딜리버리와 파트너 생태계를 운영합니다.', 'GREEN', 'SEOUL-HQ', 0),
    (42, 'ORG-GLOBAL-APAC', 'REGION', 'APAC Region', 'APAC', 'ORG-GLOBAL', '아시아 태평양 시장과 딜리버리를 총괄합니다.', 'GREEN', 'SEOUL-HQ', 0),
    (43, 'ORG-GLOBAL-JAPAN', 'DELIVERY_POD', 'Japan Delivery Pod', 'Japan Pod', 'ORG-GLOBAL-APAC', '일본 고객의 현지화 딜리버리를 담당합니다.', 'CORAL', 'SEOUL-HQ', 3),
    (44, 'ORG-GLOBAL-SEA', 'DELIVERY_POD', 'Southeast Asia Delivery Pod', 'SEA Pod', 'ORG-GLOBAL-APAC', '동남아시아 시장의 통합 딜리버리를 담당합니다.', 'TEAL', 'BUSAN-HUB', 3),
    (45, 'ORG-GLOBAL-AMERICAS', 'REGION', 'Americas Region', 'Americas', 'ORG-GLOBAL', '미주 시장과 파트너 운영을 총괄합니다.', 'BLUE', 'AUSTIN-LAB', 0),
    (46, 'ORG-GLOBAL-US', 'DELIVERY_POD', 'United States Delivery Pod', 'US Pod', 'ORG-GLOBAL-AMERICAS', '미국 고객의 제품 및 전환 딜리버리를 담당합니다.', 'BLUE', 'AUSTIN-LAB', 3);

INSERT INTO ppl_organizations (
    public_id, tenant_id, organization_key, organization_type, name, short_name,
    description, cost_center_key, color_token, lifecycle_state,
    source_system_id, external_id, valid_from, created_by, updated_by)
SELECT md5('dwp-reference-org:' || seed.organization_key)::uuid,
       1, seed.organization_key, seed.organization_type, seed.name, seed.short_name,
       seed.description, 'CC-R' || LPAD(seed.ordinal::text, 3, '0'), seed.color_token,
       'ACTIVE', source.source_system_id, seed.organization_key,
       DATE '2026-01-01', 1, 1
  FROM tmp_dwp_reference_organizations seed
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
ON CONFLICT (tenant_id, organization_key) DO UPDATE SET
    organization_type = EXCLUDED.organization_type,
    name = EXCLUDED.name,
    short_name = EXCLUDED.short_name,
    description = EXCLUDED.description,
    cost_center_key = EXCLUDED.cost_center_key,
    color_token = EXCLUDED.color_token,
    lifecycle_state = 'ACTIVE',
    valid_to = NULL,
    source_system_id = EXCLUDED.source_system_id,
    version = ppl_organizations.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

UPDATE ppl_organizations child
   SET parent_organization_id = parent.organization_id,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_dwp_reference_organizations seed
  JOIN ppl_organizations parent
    ON parent.tenant_id = 1 AND parent.organization_key = seed.parent_key
 WHERE child.tenant_id = 1
   AND child.organization_key = seed.organization_key;

INSERT INTO ppl_organization_relationships (
    tenant_id, child_organization_id, parent_organization_id,
    relationship_type, primary_relationship, effective_start_date,
    source_system_id, external_id, created_by, updated_by)
SELECT 1, child.organization_id, parent.organization_id,
       'SUPERVISORY', TRUE, DATE '2026-01-01', source.source_system_id,
       seed.organization_key || ':supervisory', 1, 1
  FROM tmp_dwp_reference_organizations seed
  JOIN ppl_organizations child
    ON child.tenant_id = 1 AND child.organization_key = seed.organization_key
  JOIN ppl_organizations parent
    ON parent.tenant_id = 1 AND parent.organization_key = seed.parent_key
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
ON CONFLICT (
    tenant_id, child_organization_id, parent_organization_id,
    relationship_type, effective_start_date)
DO UPDATE SET
    primary_relationship = TRUE,
    effective_end_date = NULL,
    source_system_id = EXCLUDED.source_system_id,
    version = ppl_organization_relationships.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

CREATE TEMP TABLE tmp_dwp_reference_existing_leaders (
    organization_key VARCHAR(100) PRIMARY KEY,
    worker_number VARCHAR(100) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_dwp_reference_existing_leaders VALUES
    ('ROOT', 'SK0001'),
    ('ORG-TECH', 'SK0003'),
    ('ORG-AI-PLATFORM', 'SK0007'),
    ('ORG-DATA', 'SK0013'),
    ('ORG-CLOUD-INFRA', 'SK0008'),
    ('ORG-ERP', 'SK0019'),
    ('ORG-CONSULT', 'SK0022'),
    ('ORG-CORP', 'SK0005'),
    ('ORG-SEMI', 'SK0006');

CREATE TEMP TABLE tmp_dwp_reference_workers (
    worker_number VARCHAR(100) PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    given_name VARCHAR(120) NOT NULL,
    family_name VARCHAR(120) NOT NULL,
    work_email VARCHAR(320) NOT NULL,
    hire_date DATE NOT NULL,
    organization_key VARCHAR(100) NOT NULL,
    business_title VARCHAR(240) NOT NULL,
    manager_worker_number VARCHAR(100),
    job_key VARCHAR(100) NOT NULL,
    job_name VARCHAR(240) NOT NULL,
    management_level VARCHAR(80) NOT NULL,
    grade_key VARCHAR(80) NOT NULL,
    location_key VARCHAR(100) NOT NULL,
    is_leader BOOLEAN NOT NULL
) ON COMMIT DROP;

WITH name_pool AS (
    SELECT ARRAY['김','이','박','최','정','강','조','윤','장','임','한']::text[] AS families,
           ARRAY['서준','지우','하윤','민재','예린','도현','수아','태민','채원','시우','유진','현우','아린','준호','서진','도윤','지민']::text[] AS given_names
)
INSERT INTO tmp_dwp_reference_workers
SELECT 'RFL' || LPAD(seed.ordinal::text, 3, '0'),
       pool.families[((seed.ordinal - 1) % CARDINALITY(pool.families)) + 1]
           || pool.given_names[((seed.ordinal * 7 - 1) % CARDINALITY(pool.given_names)) + 1],
       pool.given_names[((seed.ordinal * 7 - 1) % CARDINALITY(pool.given_names)) + 1],
       pool.families[((seed.ordinal - 1) % CARDINALITY(pool.families)) + 1],
       LOWER('rfl' || LPAD(seed.ordinal::text, 3, '0')) || '@dwp-reference.example',
       DATE '2021-01-04' + seed.ordinal,
       seed.organization_key,
       seed.short_name || CASE seed.organization_type
           WHEN 'BUSINESS_UNIT' THEN ' 부문장'
           WHEN 'DIVISION' THEN ' 본부장'
           WHEN 'CENTER' THEN ' 센터장'
           WHEN 'REGION' THEN ' 지역 총괄'
           WHEN 'PRODUCT_GROUP' THEN ' 그룹 리드'
           WHEN 'SQUAD' THEN ' 스쿼드 리드'
           WHEN 'DELIVERY_POD' THEN ' 딜리버리 리드'
           WHEN 'CHAPTER' THEN ' 챕터 리드'
           ELSE ' 팀장' END,
       COALESCE('RFL' || LPAD(parent_seed.ordinal::text, 3, '0'), existing.worker_number),
       'JOB-DWP-ORG-LEAD', 'Organization Lead', 'MANAGER', 'G5',
       seed.location_key, TRUE
  FROM tmp_dwp_reference_organizations seed
 CROSS JOIN name_pool pool
  LEFT JOIN tmp_dwp_reference_organizations parent_seed
    ON parent_seed.organization_key = seed.parent_key
  LEFT JOIN tmp_dwp_reference_existing_leaders existing
    ON existing.organization_key = seed.parent_key;

WITH name_pool AS (
    SELECT ARRAY['김','이','박','최','정','강','조','윤','장','임','한']::text[] AS families,
           ARRAY['서준','지우','하윤','민재','예린','도현','수아','태민','채원','시우','유진','현우','아린','준호','서진','도윤','지민']::text[] AS given_names
)
INSERT INTO tmp_dwp_reference_workers
SELECT 'RFM' || LPAD(seed.ordinal::text, 3, '0') || member.sequence,
       pool.families[((seed.ordinal * 3 + member.sequence + 59) % CARDINALITY(pool.families)) + 1]
           || pool.given_names[((seed.ordinal * 5 + member.sequence + 41) % CARDINALITY(pool.given_names)) + 1],
       pool.given_names[((seed.ordinal * 5 + member.sequence + 41) % CARDINALITY(pool.given_names)) + 1],
       pool.families[((seed.ordinal * 3 + member.sequence + 59) % CARDINALITY(pool.families)) + 1],
       LOWER('rfm' || LPAD(seed.ordinal::text, 3, '0') || member.sequence) || '@dwp-reference.example',
       DATE '2022-01-03' + seed.ordinal + member.sequence,
       seed.organization_key,
       seed.short_name || ' Specialist ' || member.sequence,
       'RFL' || LPAD(seed.ordinal::text, 3, '0'),
       'JOB-DWP-SPECIALIST', 'Enterprise Specialist', 'INDIVIDUAL', 'G2',
       seed.location_key, FALSE
  FROM tmp_dwp_reference_organizations seed
 CROSS JOIN LATERAL generate_series(1, seed.seed_members) member(sequence)
 CROSS JOIN name_pool pool;

INSERT INTO ppl_job_profiles (
    tenant_id, job_key, name, job_family_key, management_level,
    lifecycle_state, source_system_id, external_id, created_by, updated_by)
SELECT 1, profile.job_key, profile.name, profile.job_family_key,
       profile.management_level, 'ACTIVE', source.source_system_id,
       profile.job_key, 1, 1
  FROM (VALUES
        ('JOB-DWP-ORG-LEAD', 'Organization Lead', 'LEADERSHIP', 'MANAGER'),
        ('JOB-DWP-SPECIALIST', 'Enterprise Specialist', 'DIGITAL_WORKPLACE', 'INDIVIDUAL'))
       profile(job_key, name, job_family_key, management_level)
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
ON CONFLICT (tenant_id, job_key) DO UPDATE SET
    name = EXCLUDED.name,
    job_family_key = EXCLUDED.job_family_key,
    management_level = EXCLUDED.management_level,
    lifecycle_state = 'ACTIVE',
    source_system_id = EXCLUDED.source_system_id,
    version = ppl_job_profiles.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_persons (
    public_id, tenant_id, person_key, display_name, preferred_locale, time_zone,
    lifecycle_state, source_system_id, external_id, created_by, updated_by)
SELECT md5('dwp-reference-person:' || seed.worker_number)::uuid,
       1, seed.worker_number, seed.display_name, 'ko-KR',
       CASE WHEN seed.location_key = 'AUSTIN-LAB' THEN 'America/Chicago' ELSE 'Asia/Seoul' END,
       'ACTIVE', source.source_system_id, 'DWP-REF-' || seed.worker_number, 1, 1
  FROM tmp_dwp_reference_workers seed
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
ON CONFLICT (tenant_id, person_key) DO UPDATE SET
    public_id = EXCLUDED.public_id,
    display_name = EXCLUDED.display_name,
    preferred_locale = EXCLUDED.preferred_locale,
    time_zone = EXCLUDED.time_zone,
    lifecycle_state = 'ACTIVE',
    source_system_id = EXCLUDED.source_system_id,
    version = ppl_persons.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_person_names (
    tenant_id, person_id, name_type, locale, given_name, family_name,
    formatted_name, effective_start_date, created_by, updated_by)
SELECT 1, person.person_id, 'PREFERRED', 'ko-KR', seed.given_name,
       seed.family_name, seed.display_name, seed.hire_date, 1, 1
  FROM tmp_dwp_reference_workers seed
  JOIN ppl_persons person
    ON person.tenant_id = 1 AND person.person_key = seed.worker_number
ON CONFLICT (
    tenant_id, person_id, name_type, locale,
    effective_start_date, effective_sequence)
DO UPDATE SET
    given_name = EXCLUDED.given_name,
    family_name = EXCLUDED.family_name,
    formatted_name = EXCLUDED.formatted_name,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_workers (
    tenant_id, person_id, worker_number, worker_type, worker_status,
    original_hire_date, source_system_id, external_id, created_by, updated_by)
SELECT 1, person.person_id, seed.worker_number, 'EMPLOYEE', 'ACTIVE',
       seed.hire_date, source.source_system_id, 'DWP-REF-' || seed.worker_number, 1, 1
  FROM tmp_dwp_reference_workers seed
  JOIN ppl_persons person
    ON person.tenant_id = 1 AND person.person_key = seed.worker_number
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
ON CONFLICT (tenant_id, worker_number) DO UPDATE SET
    person_id = EXCLUDED.person_id,
    worker_type = EXCLUDED.worker_type,
    worker_status = EXCLUDED.worker_status,
    original_hire_date = EXCLUDED.original_hire_date,
    source_system_id = EXCLUDED.source_system_id,
    version = ppl_workers.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_work_relationships (
    tenant_id, relationship_key, worker_id, legal_employer_id,
    relationship_type, primary_relationship, start_date,
    source_system_id, external_id, created_by, updated_by)
SELECT 1, seed.worker_number || '-PRIMARY', worker.worker_id,
       employer.legal_employer_id, 'EMPLOYEE', TRUE, seed.hire_date,
       source.source_system_id, 'DWP-REF-' || seed.worker_number || ':relationship', 1, 1
  FROM tmp_dwp_reference_workers seed
  JOIN ppl_workers worker
    ON worker.tenant_id = 1 AND worker.worker_number = seed.worker_number
  JOIN ppl_legal_employers employer
    ON employer.tenant_id = 1 AND employer.employer_key = 'SKAX-KR'
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
ON CONFLICT (tenant_id, relationship_key) DO UPDATE SET
    worker_id = EXCLUDED.worker_id,
    legal_employer_id = EXCLUDED.legal_employer_id,
    relationship_type = EXCLUDED.relationship_type,
    primary_relationship = TRUE,
    start_date = EXCLUDED.start_date,
    end_date = NULL,
    source_system_id = EXCLUDED.source_system_id,
    version = ppl_work_relationships.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_positions (
    public_id, tenant_id, position_key, title, organization_id, job_profile_id,
    location_id, position_status, position_type, criticality, budgeted_fte,
    annual_cost_amount, cost_currency, valid_from, source_system_id,
    external_id, created_by, updated_by)
SELECT md5('dwp-reference-position:' || seed.worker_number)::uuid,
       1, 'POS-' || seed.worker_number, seed.business_title,
       organization.organization_id, job.job_profile_id, location.location_id,
       'FILLED', 'REGULAR', CASE WHEN seed.is_leader THEN 'HIGH' ELSE 'MEDIUM' END,
       1.0000, CASE WHEN seed.is_leader THEN 150000000 ELSE 85000000 END,
       'KRW', DATE '2026-01-01', source.source_system_id,
       'POS-' || seed.worker_number, 1, 1
  FROM tmp_dwp_reference_workers seed
  JOIN ppl_organizations organization
    ON organization.tenant_id = 1 AND organization.organization_key = seed.organization_key
  JOIN ppl_job_profiles job
    ON job.tenant_id = 1 AND job.job_key = seed.job_key
  JOIN ppl_locations location
    ON location.tenant_id = 1 AND location.location_key = seed.location_key
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
ON CONFLICT (tenant_id, position_key) DO UPDATE SET
    title = EXCLUDED.title,
    organization_id = EXCLUDED.organization_id,
    job_profile_id = EXCLUDED.job_profile_id,
    location_id = EXCLUDED.location_id,
    position_status = 'FILLED',
    position_type = EXCLUDED.position_type,
    criticality = EXCLUDED.criticality,
    budgeted_fte = EXCLUDED.budgeted_fte,
    annual_cost_amount = EXCLUDED.annual_cost_amount,
    cost_currency = EXCLUDED.cost_currency,
    valid_to = NULL,
    source_system_id = EXCLUDED.source_system_id,
    version = ppl_positions.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_assignments (
    tenant_id, assignment_key, work_relationship_id,
    effective_start_date, assignment_status, primary_assignment,
    position_id, job_profile_id, job_grade_id, organization_id, location_id,
    manager_assignment_key, business_title, cost_center_key, change_reason_code,
    full_time_equivalent, source_system_id, external_id, source_version,
    created_by, updated_by)
SELECT 1, 'ASG-' || seed.worker_number || '-1', relationship.work_relationship_id,
       DATE '2026-01-01', 'ACTIVE', TRUE, position.position_id,
       job.job_profile_id, grade.job_grade_id, organization.organization_id,
       location.location_id,
       CASE WHEN seed.manager_worker_number IS NULL THEN NULL
            ELSE 'ASG-' || seed.manager_worker_number || '-1' END,
       seed.business_title, organization.cost_center_key, 'REFERENCE_PROFILE',
       1.0000, source.source_system_id, 'DWP-REF-ASG-' || seed.worker_number,
       '2026.08-flexible-org', 1, 1
  FROM tmp_dwp_reference_workers seed
  JOIN ppl_work_relationships relationship
    ON relationship.tenant_id = 1
   AND relationship.relationship_key = seed.worker_number || '-PRIMARY'
  JOIN ppl_positions position
    ON position.tenant_id = 1 AND position.position_key = 'POS-' || seed.worker_number
  JOIN ppl_job_profiles job
    ON job.tenant_id = 1 AND job.job_key = seed.job_key
  JOIN ppl_job_grades grade
    ON grade.tenant_id = 1 AND grade.grade_key = seed.grade_key
  JOIN ppl_organizations organization
    ON organization.tenant_id = 1 AND organization.organization_key = seed.organization_key
  JOIN ppl_locations location
    ON location.tenant_id = 1 AND location.location_key = seed.location_key
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
ON CONFLICT (tenant_id, assignment_key, effective_start_date, effective_sequence)
DO UPDATE SET
    assignment_status = 'ACTIVE',
    primary_assignment = TRUE,
    position_id = EXCLUDED.position_id,
    job_profile_id = EXCLUDED.job_profile_id,
    job_grade_id = EXCLUDED.job_grade_id,
    organization_id = EXCLUDED.organization_id,
    location_id = EXCLUDED.location_id,
    manager_assignment_key = EXCLUDED.manager_assignment_key,
    business_title = EXCLUDED.business_title,
    cost_center_key = EXCLUDED.cost_center_key,
    full_time_equivalent = EXCLUDED.full_time_equivalent,
    source_system_id = EXCLUDED.source_system_id,
    version = ppl_assignments.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_contacts (
    tenant_id, person_id, contact_type, usage_type, display_value,
    primary_contact, visibility, valid_from, created_by, updated_by)
SELECT 1, person.person_id, 'EMAIL', 'WORK', seed.work_email,
       TRUE, 'INTERNAL', seed.hire_date, 1, 1
  FROM tmp_dwp_reference_workers seed
  JOIN ppl_persons person
    ON person.tenant_id = 1 AND person.person_key = seed.worker_number;

INSERT INTO ppl_organization_role_assignments (
    tenant_id, organization_id, role_code, person_id, position_id,
    primary_assignment, effective_start_date, source_system_id,
    created_by, updated_by)
SELECT 1, organization.organization_id, 'LEADER', person.person_id,
       position.position_id, TRUE, DATE '2026-01-01', source.source_system_id, 1, 1
  FROM tmp_dwp_reference_workers seed
  JOIN ppl_persons person
    ON person.tenant_id = 1 AND person.person_key = seed.worker_number
  JOIN ppl_positions position
    ON position.tenant_id = 1 AND position.position_key = 'POS-' || seed.worker_number
  JOIN ppl_organizations organization
    ON organization.tenant_id = 1 AND organization.organization_key = seed.organization_key
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
 WHERE seed.is_leader
ON CONFLICT (tenant_id, organization_id, role_code)
    WHERE primary_assignment = TRUE AND effective_end_date IS NULL
DO UPDATE SET
    person_id = EXCLUDED.person_id,
    position_id = EXCLUDED.position_id,
    source_system_id = EXCLUDED.source_system_id,
    version = ppl_organization_role_assignments.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

UPDATE ppl_positions position
   SET reports_to_position_id = manager_position.position_id,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_dwp_reference_workers seed
  JOIN ppl_positions manager_position
    ON manager_position.tenant_id = 1
   AND manager_position.position_key = 'POS-' || seed.manager_worker_number
 WHERE position.tenant_id = 1
   AND position.position_key = 'POS-' || seed.worker_number
   AND seed.manager_worker_number IS NOT NULL;

INSERT INTO ppl_position_relationships (
    public_id, tenant_id, child_position_id, parent_position_id,
    relationship_type, primary_relationship, relationship_source,
    effective_start_date, source_system_id, external_id,
    created_by, updated_by)
SELECT md5('dwp-reference-position-relationship:' || seed.worker_number)::uuid,
       1, child.position_id, parent.position_id,
       'SUPERVISORY', TRUE, 'POSITION', DATE '2026-01-01',
       source.source_system_id, 'POS-' || seed.worker_number || ':supervisory', 1, 1
  FROM tmp_dwp_reference_workers seed
  JOIN ppl_positions child
    ON child.tenant_id = 1 AND child.position_key = 'POS-' || seed.worker_number
  JOIN ppl_positions parent
    ON parent.tenant_id = 1 AND parent.position_key = 'POS-' || seed.manager_worker_number
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
 WHERE seed.manager_worker_number IS NOT NULL
ON CONFLICT (tenant_id, child_position_id, relationship_type, relationship_source)
    WHERE primary_relationship = TRUE AND effective_end_date IS NULL
DO UPDATE SET
    parent_position_id = EXCLUDED.parent_position_id,
    relationship_source = EXCLUDED.relationship_source,
    source_system_id = EXCLUDED.source_system_id,
    version = ppl_position_relationships.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

-- Complete two effective-dated assignment slices that the original reference
-- reconciliation expected but did not create on a completely empty install.
INSERT INTO ppl_assignments (
    tenant_id, assignment_key, work_relationship_id,
    effective_start_date, effective_end_date, effective_sequence,
    assignment_status, primary_assignment, position_id, job_profile_id,
    job_grade_id, organization_id, location_id, manager_assignment_key,
    business_title, cost_center_key, change_reason_code, worker_hours,
    full_time_equivalent, source_system_id, external_id, source_version,
    created_by, updated_by)
SELECT prior.tenant_id, prior.assignment_key, prior.work_relationship_id,
       DATE '2024-01-01', NULL, 1, 'ACTIVE', prior.primary_assignment,
       prior.position_id, prior.job_profile_id, grade.job_grade_id,
       prior.organization_id, prior.location_id, 'ASG-SK0008-1',
       prior.business_title, prior.cost_center_key, 'REFERENCE_CONTINUITY',
       prior.worker_hours, COALESCE(prior.full_time_equivalent, 1.0000),
       prior.source_system_id, prior.external_id, '2026.08-continuity', 1, 1
  FROM ppl_assignments prior
  JOIN ppl_job_grades grade
    ON grade.tenant_id = prior.tenant_id AND grade.grade_key = 'G5'
 WHERE prior.tenant_id = 1
   AND prior.assignment_key = 'ASG-E100001-1'
   AND prior.effective_start_date = DATE '2021-03-15'
ON CONFLICT (tenant_id, assignment_key, effective_start_date, effective_sequence)
DO NOTHING;

UPDATE ppl_organization_scenarios
   SET baseline_fingerprint = '4f8fe7c494b58d6215711d63875b1748d2ea6b5795a2ba34ecf00dd31fe4572c',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE tenant_id = 1
   AND scenario_key IN ('ai-scale-up-2027', 'ai-scale-up-2027-growth')
   AND lifecycle_state = 'DRAFT';

INSERT INTO ppl_assignments (
    tenant_id, assignment_key, work_relationship_id,
    effective_start_date, effective_end_date, effective_sequence,
    assignment_status, primary_assignment, position_id, job_profile_id,
    job_grade_id, organization_id, location_id, manager_assignment_key,
    business_title, cost_center_key, change_reason_code, worker_hours,
    full_time_equivalent, source_system_id, external_id, source_version,
    created_by, updated_by)
SELECT prior.tenant_id, prior.assignment_key, prior.work_relationship_id,
       DATE '2026-08-15', NULL, 1, 'ACTIVE', prior.primary_assignment,
       prior.position_id, prior.job_profile_id, grade.job_grade_id,
       prior.organization_id, prior.location_id, 'ASG-E100001-1',
       prior.business_title, prior.cost_center_key, 'REFERENCE_CONTINUITY',
       prior.worker_hours, COALESCE(prior.full_time_equivalent, 1.0000),
       prior.source_system_id, prior.external_id, '2026.08-continuity', 1, 1
  FROM ppl_assignments prior
  JOIN ppl_job_grades grade
    ON grade.tenant_id = prior.tenant_id AND grade.grade_key = 'G3'
 WHERE prior.tenant_id = 1
   AND prior.assignment_key = 'ASG-E100002-1'
   AND prior.effective_start_date = DATE '2023-07-03'
ON CONFLICT (tenant_id, assignment_key, effective_start_date, effective_sequence)
DO NOTHING;

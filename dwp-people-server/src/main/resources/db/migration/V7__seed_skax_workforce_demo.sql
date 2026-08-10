CREATE TEMP TABLE tmp_skax_organizations (
    organization_key VARCHAR(100) PRIMARY KEY,
    organization_type VARCHAR(30) NOT NULL,
    name VARCHAR(240) NOT NULL,
    short_name VARCHAR(80),
    parent_key VARCHAR(100),
    description VARCHAR(1000),
    cost_center_key VARCHAR(100),
    color_token VARCHAR(30)
) ON COMMIT DROP;

INSERT INTO tmp_skax_organizations VALUES
    ('ROOT', 'COMPANY', 'SKAX', 'SKAX', NULL,
     'AI와 디지털 기술로 고객의 비즈니스 혁신을 설계하는 엔터프라이즈 AX 기업', 'CC-0000', 'SK_RED'),
    ('ORG-CEO', 'SUPERVISORY', 'CEO Staff', 'CEO Staff', 'ROOT',
     '전사 전략, 이사회 운영과 경영 의사결정을 지원합니다.', 'CC-0100', 'CORAL'),
    ('ORG-TECH', 'BUSINESS_UNIT', 'Digital Platform 부문', 'Digital Platform', 'ROOT',
     'AI, 데이터, 클라우드와 디지털 플랫폼 역량을 통합합니다.', 'CC-1000', 'BLUE'),
    ('ORG-AI-PLATFORM', 'DIVISION', 'AI Platform 본부', 'AI Platform', 'ORG-TECH',
     '생성형 AI 플랫폼과 데이터 제품을 개발합니다.', 'CC-1100', 'VIOLET'),
    ('ORG-GENAI', 'SUPERVISORY', 'GenAI Engineering 팀', 'GenAI Engineering', 'ORG-AI-PLATFORM',
     'LLM 서비스, AI 안전성과 에이전트 플랫폼을 구축합니다.', 'CC-1110', 'VIOLET'),
    ('ORG-DATA', 'SUPERVISORY', 'Data Platform 팀', 'Data Platform', 'ORG-AI-PLATFORM',
     '엔터프라이즈 데이터 플랫폼과 분석 기반을 운영합니다.', 'CC-1120', 'TEAL'),
    ('ORG-CLOUD-INFRA', 'DIVISION', 'Cloud & Infra 본부', 'Cloud & Infra', 'ORG-TECH',
     '클라우드, 네트워크와 신뢰성 엔지니어링을 담당합니다.', 'CC-1200', 'CYAN'),
    ('ORG-CLOUD', 'SUPERVISORY', 'Cloud Platform 팀', 'Cloud Platform', 'ORG-CLOUD-INFRA',
     '멀티 클라우드 기반과 플랫폼 엔지니어링을 제공합니다.', 'CC-1210', 'CYAN'),
    ('ORG-NET-OPS', 'SUPERVISORY', 'Network Operations 팀', 'Network Operations', 'ORG-CLOUD-INFRA',
     '글로벌 네트워크 자동화와 운영 안정성을 책임집니다.', 'CC-1220', 'BLUE'),
    ('ORG-DX', 'BUSINESS_UNIT', 'Enterprise Transformation 부문', 'Enterprise Transformation', 'ROOT',
     '산업별 디지털 전환과 업무 혁신을 실행합니다.', 'CC-2000', 'GREEN'),
    ('ORG-ERP', 'DIVISION', 'ERP Innovation 본부', 'ERP Innovation', 'ORG-DX',
     '차세대 ERP와 엔터프라이즈 아키텍처를 설계합니다.', 'CC-2100', 'GREEN'),
    ('ORG-CONSULT', 'DIVISION', 'Digital Consulting 본부', 'Digital Consulting', 'ORG-DX',
     '전략부터 변화관리까지 실행 가능한 컨설팅을 제공합니다.', 'CC-2200', 'AMBER'),
    ('ORG-CX', 'SUPERVISORY', 'Customer Experience 팀', 'Customer Experience', 'ORG-DX',
     '고객 여정과 디지털 서비스 경험을 혁신합니다.', 'CC-2300', 'PINK'),
    ('ORG-CORP', 'BUSINESS_UNIT', 'Corporate Center', 'Corporate Center', 'ROOT',
     '사람, 재무, 리스크와 지속가능 경영을 지원합니다.', 'CC-3000', 'SLATE'),
    ('ORG-PEOPLE', 'DEPARTMENT', 'People & Culture 팀', 'People & Culture', 'ORG-CORP',
     '인재 경험, 조직문화와 리더십 성장을 담당합니다.', 'CC-3100', 'PINK'),
    ('ORG-FIN', 'DEPARTMENT', 'Finance & Risk 팀', 'Finance & Risk', 'ORG-CORP',
     '재무 건전성과 전사 리스크 통제를 담당합니다.', 'CC-3200', 'AMBER'),
    ('ORG-STRATEGY', 'DEPARTMENT', 'Strategy & ESG 팀', 'Strategy & ESG', 'ORG-CORP',
     '기업 전략과 지속가능 경영 과제를 추진합니다.', 'CC-3300', 'GREEN'),
    ('ORG-SEMI', 'BUSINESS_UNIT', 'Semiconductor AX 부문', 'Semiconductor AX', 'ROOT',
     '반도체 제조 데이터와 AI 기반 생산 혁신을 수행합니다.', 'CC-4000', 'CORAL'),
    ('ORG-SEMI-DATA', 'DEPARTMENT', 'Semiconductor Data Operations', 'Semi Data', 'ORG-SEMI',
     '수율 데이터 분석과 제조 데이터 운영을 담당합니다.', 'CC-4100', 'CORAL'),
    ('ORG-SEMI-SMART', 'SUPERVISORY', 'Smart Factory 팀', 'Smart Factory', 'ORG-SEMI',
     '제조 AI와 지능형 자동화 솔루션을 개발합니다.', 'CC-4200', 'TEAL');

CREATE TEMP TABLE tmp_skax_workers (
    worker_number VARCHAR(100) PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    given_name VARCHAR(120),
    family_name VARCHAR(120),
    locale VARCHAR(35) NOT NULL,
    work_email VARCHAR(320) NOT NULL,
    worker_type VARCHAR(24) NOT NULL,
    worker_status VARCHAR(24) NOT NULL,
    hire_date DATE NOT NULL,
    organization_key VARCHAR(100) NOT NULL,
    business_title VARCHAR(240) NOT NULL,
    manager_worker_number VARCHAR(100),
    job_key VARCHAR(100) NOT NULL,
    job_name VARCHAR(240) NOT NULL,
    job_family_key VARCHAR(100),
    management_level VARCHAR(80),
    grade_key VARCHAR(80) NOT NULL,
    location_key VARCHAR(100) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_skax_workers VALUES
    ('SK0001', '김민준', '민준', '김', 'ko-KR', 'minjun.kim@skax.example', 'EMPLOYEE', 'ACTIVE', '2016-01-04', 'ROOT', 'Chief Executive Officer', NULL, 'JOB-CEO', 'Chief Executive Officer', 'EXECUTIVE', 'CHIEF_EXECUTIVE', 'G7', 'SEOUL-HQ'),
    ('SK0002', '이서연', '서연', '이', 'ko-KR', 'seoyeon.lee@skax.example', 'EMPLOYEE', 'ACTIVE', '2018-04-02', 'ORG-CEO', 'Executive Strategy Officer', 'SK0001', 'JOB-EXEC-STRATEGY', 'Executive Strategy Officer', 'STRATEGY', 'EXECUTIVE', 'G6', 'SEOUL-HQ'),
    ('SK0003', '박현우', '현우', '박', 'ko-KR', 'hyunwoo.park@skax.example', 'EMPLOYEE', 'ACTIVE', '2017-03-06', 'ORG-TECH', 'Digital Platform 부문장', 'SK0001', 'JOB-DIVISION-HEAD', 'Division Head', 'LEADERSHIP', 'EXECUTIVE', 'G6', 'PANGYO-CAMPUS'),
    ('SK0004', '최유진', '유진', '최', 'ko-KR', 'yujin.choi@skax.example', 'EMPLOYEE', 'ACTIVE', '2017-07-03', 'ORG-DX', 'Enterprise Transformation 부문장', 'SK0001', 'JOB-DIVISION-HEAD', 'Division Head', 'LEADERSHIP', 'EXECUTIVE', 'G6', 'SEOUL-HQ'),
    ('SK0005', '정우성', '우성', '정', 'ko-KR', 'woosung.jung@skax.example', 'EMPLOYEE', 'ACTIVE', '2019-01-07', 'ORG-CORP', 'Corporate Center 장', 'SK0001', 'JOB-CORP-HEAD', 'Corporate Center Head', 'LEADERSHIP', 'EXECUTIVE', 'G6', 'SEOUL-HQ'),
    ('SK0006', '한지민', '지민', '한', 'ko-KR', 'jimin.han@skax.example', 'EMPLOYEE', 'ACTIVE', '2018-02-05', 'ORG-SEMI', 'Semiconductor AX 부문장', 'SK0001', 'JOB-DIVISION-HEAD', 'Division Head', 'LEADERSHIP', 'EXECUTIVE', 'G6', 'PANGYO-CAMPUS'),
    ('SK0007', '김도윤', '도윤', '김', 'ko-KR', 'doyun.kim@skax.example', 'EMPLOYEE', 'ACTIVE', '2019-05-13', 'ORG-AI-PLATFORM', 'AI Platform 본부장', 'SK0003', 'JOB-CENTER-LEAD', 'Center Lead', 'LEADERSHIP', 'MANAGER', 'G5', 'PANGYO-CAMPUS'),
    ('SK0008', '윤서진', '서진', '윤', 'ko-KR', 'seojin.yoon@skax.example', 'EMPLOYEE', 'ACTIVE', '2019-08-19', 'ORG-CLOUD-INFRA', 'Cloud & Infra 본부장', 'SK0003', 'JOB-CENTER-LEAD', 'Center Lead', 'LEADERSHIP', 'MANAGER', 'G5', 'PANGYO-CAMPUS'),
    ('SK0009', '장민석', '민석', '장', 'ko-KR', 'minseok.jang@skax.example', 'EMPLOYEE', 'ACTIVE', '2020-02-03', 'ORG-GENAI', 'GenAI Engineering 팀장', 'SK0007', 'JOB-TEAM-LEAD', 'Team Lead', 'LEADERSHIP', 'MANAGER', 'G5', 'PANGYO-CAMPUS'),
    ('SK0010', '조하은', '하은', '조', 'ko-KR', 'haeun.cho@skax.example', 'EMPLOYEE', 'ACTIVE', '2024-01-08', 'ORG-GENAI', 'AI Product Engineer', 'SK0009', 'JOB-AI-ENGINEER', 'AI Product Engineer', 'ARTIFICIAL_INTELLIGENCE', 'INDIVIDUAL', 'G2', 'PANGYO-CAMPUS'),
    ('SK0011', '임재현', '재현', '임', 'ko-KR', 'jaehyun.lim@skax.example', 'EMPLOYEE', 'ACTIVE', '2022-11-07', 'ORG-GENAI', 'LLM Platform Engineer', 'SK0009', 'JOB-LLM-ENGINEER', 'LLM Platform Engineer', 'ARTIFICIAL_INTELLIGENCE', 'INDIVIDUAL', 'G3', 'PANGYO-CAMPUS'),
    ('SK0012', 'Sofia Chen', 'Sofia', 'Chen', 'en-US', 'sofia.chen@skax.example', 'CONTINGENT', 'ACTIVE', '2025-05-12', 'ORG-GENAI', 'AI Safety Researcher', 'SK0009', 'JOB-AI-SAFETY', 'AI Safety Researcher', 'ARTIFICIAL_INTELLIGENCE', 'CONTRACTOR', 'C1', 'PANGYO-CAMPUS'),
    ('SK0013', '오수빈', '수빈', '오', 'ko-KR', 'subin.oh@skax.example', 'EMPLOYEE', 'ACTIVE', '2020-09-14', 'ORG-DATA', 'Data Platform 팀장', 'SK0007', 'JOB-TEAM-LEAD', 'Team Lead', 'LEADERSHIP', 'MANAGER', 'G5', 'PANGYO-CAMPUS'),
    ('SK0014', '강태훈', '태훈', '강', 'ko-KR', 'taehoon.kang@skax.example', 'EMPLOYEE', 'ACTIVE', '2021-06-01', 'ORG-DATA', 'Data Architect', 'SK0013', 'JOB-DATA-ARCHITECT', 'Data Architect', 'DATA', 'INDIVIDUAL', 'G4', 'PANGYO-CAMPUS'),
    ('SK0015', '문예린', '예린', '문', 'ko-KR', 'yerin.moon@skax.example', 'EMPLOYEE', 'ACTIVE', '2024-03-04', 'ORG-DATA', 'Analytics Engineer', 'SK0013', 'JOB-ANALYTICS-ENGINEER', 'Analytics Engineer', 'DATA', 'INDIVIDUAL', 'G2', 'PANGYO-CAMPUS'),
    ('SK0016', '송준호', '준호', '송', 'ko-KR', 'junho.song@skax.example', 'EMPLOYEE', 'ACTIVE', '2020-04-06', 'ORG-CLOUD', 'Cloud Platform 팀장', 'SK0008', 'JOB-TEAM-LEAD', 'Team Lead', 'LEADERSHIP', 'MANAGER', 'G5', 'PANGYO-CAMPUS'),
    ('SK0017', '배지우', '지우', '배', 'ko-KR', 'jiwoo.bae@skax.example', 'EMPLOYEE', 'ACTIVE', '2022-02-14', 'ORG-CLOUD', 'Site Reliability Engineer', 'SK0016', 'JOB-SRE', 'Site Reliability Engineer', 'CLOUD', 'INDIVIDUAL', 'G3', 'DAEJEON-CENTER'),
    ('SK0018', 'Alex Morgan', 'Alex', 'Morgan', 'en-US', 'alex.morgan@skax.example', 'EMPLOYEE', 'ACTIVE', '2023-09-05', 'ORG-CLOUD', 'Cloud Security Engineer', 'SK0016', 'JOB-CLOUD-SECURITY', 'Cloud Security Engineer', 'SECURITY', 'INDIVIDUAL', 'G3', 'AUSTIN-LAB'),
    ('E100001', '김민서', '민서', '김', 'ko-KR', 'minseo.kim@skax.example', 'EMPLOYEE', 'ACTIVE', '2021-03-15', 'ORG-NET-OPS', 'Network Operations Lead', 'SK0008', 'JOB-NET-LEAD', 'Network Operations Lead', 'NETWORK', 'MANAGER', 'G5', 'SEOUL-HQ'),
    ('E100002', '박지호', '지호', '박', 'ko-KR', 'jiho.park@skax.example', 'EMPLOYEE', 'ACTIVE', '2023-07-03', 'ORG-NET-OPS', 'Network Automation Engineer', 'E100001', 'JOB-NET-ENG', 'Network Automation Engineer', 'NETWORK', 'INDIVIDUAL', 'G2', 'SEOUL-HQ'),
    ('SK0019', '신예준', '예준', '신', 'ko-KR', 'yejun.shin@skax.example', 'EMPLOYEE', 'ACTIVE', '2019-11-04', 'ORG-ERP', 'ERP Innovation 본부장', 'SK0004', 'JOB-CENTER-LEAD', 'Center Lead', 'LEADERSHIP', 'MANAGER', 'G5', 'SEOUL-HQ'),
    ('SK0020', '김채원', '채원', '김', 'ko-KR', 'chaewon.kim@skax.example', 'EMPLOYEE', 'ACTIVE', '2021-08-02', 'ORG-ERP', 'SAP Transformation Consultant', 'SK0019', 'JOB-SAP-CONSULTANT', 'SAP Transformation Consultant', 'ERP', 'INDIVIDUAL', 'G3', 'SEOUL-HQ'),
    ('SK0021', '류민재', '민재', '류', 'ko-KR', 'minjae.ryu@skax.example', 'EMPLOYEE', 'ACTIVE', '2019-10-07', 'ORG-ERP', 'Enterprise Architect', 'SK0019', 'JOB-ENTERPRISE-ARCH', 'Enterprise Architect', 'ARCHITECTURE', 'INDIVIDUAL', 'G4', 'SEOUL-HQ'),
    ('SK0022', '서아린', '아린', '서', 'ko-KR', 'arin.seo@skax.example', 'EMPLOYEE', 'ACTIVE', '2020-01-06', 'ORG-CONSULT', 'Digital Consulting 본부장', 'SK0004', 'JOB-CENTER-LEAD', 'Center Lead', 'LEADERSHIP', 'MANAGER', 'G5', 'SEOUL-HQ'),
    ('SK0023', '정서우', '서우', '정', 'ko-KR', 'seowoo.jung@skax.example', 'EMPLOYEE', 'ACTIVE', '2023-01-09', 'ORG-CONSULT', 'Business Consultant', 'SK0022', 'JOB-BIZ-CONSULTANT', 'Business Consultant', 'CONSULTING', 'INDIVIDUAL', 'G2', 'SEOUL-HQ'),
    ('SK0024', '이도현', '도현', '이', 'ko-KR', 'dohyun.lee@skax.example', 'EMPLOYEE', 'ACTIVE', '2020-06-08', 'ORG-CONSULT', 'Change Management Lead', 'SK0022', 'JOB-CHANGE-LEAD', 'Change Management Lead', 'CONSULTING', 'INDIVIDUAL', 'G4', 'SEOUL-HQ'),
    ('SK0025', '박나연', '나연', '박', 'ko-KR', 'nayeon.park@skax.example', 'EMPLOYEE', 'ACTIVE', '2020-12-01', 'ORG-CX', 'Customer Experience 팀장', 'SK0004', 'JOB-TEAM-LEAD', 'Team Lead', 'LEADERSHIP', 'MANAGER', 'G5', 'SEOUL-HQ'),
    ('SK0026', '최건우', '건우', '최', 'ko-KR', 'gunwoo.choi@skax.example', 'EMPLOYEE', 'ACTIVE', '2022-04-11', 'ORG-CX', 'UX Strategist', 'SK0025', 'JOB-UX-STRATEGIST', 'UX Strategist', 'DESIGN', 'INDIVIDUAL', 'G3', 'SEOUL-HQ'),
    ('SK0027', 'Emily Johnson', 'Emily', 'Johnson', 'en-US', 'emily.johnson@skax.example', 'EMPLOYEE', 'ACTIVE', '2024-07-01', 'ORG-CX', 'Service Designer', 'SK0025', 'JOB-SERVICE-DESIGNER', 'Service Designer', 'DESIGN', 'INDIVIDUAL', 'G2', 'AUSTIN-LAB'),
    ('SK0028', '홍지수', '지수', '홍', 'ko-KR', 'jisoo.hong@skax.example', 'EMPLOYEE', 'ACTIVE', '2019-09-02', 'ORG-PEOPLE', 'People & Culture 팀장', 'SK0005', 'JOB-TEAM-LEAD', 'Team Lead', 'LEADERSHIP', 'MANAGER', 'G5', 'SEOUL-HQ'),
    ('SK0029', '남도윤', '도윤', '남', 'ko-KR', 'doyoon.nam@skax.example', 'EMPLOYEE', 'LEAVE', '2021-02-01', 'ORG-PEOPLE', 'HR Business Partner', 'SK0028', 'JOB-HRBP', 'HR Business Partner', 'HUMAN_RESOURCES', 'INDIVIDUAL', 'G3', 'SEOUL-HQ'),
    ('SK0030', '고서윤', '서윤', '고', 'ko-KR', 'seoyoon.ko@skax.example', 'EMPLOYEE', 'ACTIVE', '2020-05-04', 'ORG-PEOPLE', 'Talent Development Manager', 'SK0028', 'JOB-TALENT-MANAGER', 'Talent Development Manager', 'HUMAN_RESOURCES', 'INDIVIDUAL', 'G4', 'SEOUL-HQ'),
    ('SK0031', '김태연', '태연', '김', 'ko-KR', 'taeyeon.kim@skax.example', 'EMPLOYEE', 'ACTIVE', '2019-03-04', 'ORG-FIN', 'Finance & Risk 팀장', 'SK0005', 'JOB-TEAM-LEAD', 'Team Lead', 'LEADERSHIP', 'MANAGER', 'G5', 'SEOUL-HQ'),
    ('SK0032', '유승민', '승민', '유', 'ko-KR', 'seungmin.yoo@skax.example', 'EMPLOYEE', 'ACTIVE', '2021-05-03', 'ORG-FIN', 'Financial Controller', 'SK0031', 'JOB-FIN-CONTROLLER', 'Financial Controller', 'FINANCE', 'INDIVIDUAL', 'G3', 'SEOUL-HQ'),
    ('SK0033', 'James Wilson', 'James', 'Wilson', 'en-US', 'james.wilson@skax.example', 'CONTINGENT', 'ACTIVE', '2025-01-13', 'ORG-FIN', 'Risk Analyst', 'SK0031', 'JOB-RISK-ANALYST', 'Risk Analyst', 'RISK', 'CONTRACTOR', 'C1', 'AUSTIN-LAB'),
    ('SK0034', '노하린', '하린', '노', 'ko-KR', 'harin.noh@skax.example', 'EMPLOYEE', 'ACTIVE', '2020-10-05', 'ORG-STRATEGY', 'Strategy & ESG 팀장', 'SK0005', 'JOB-TEAM-LEAD', 'Team Lead', 'LEADERSHIP', 'MANAGER', 'G5', 'SEOUL-HQ'),
    ('SK0035', '안지훈', '지훈', '안', 'ko-KR', 'jihoon.ahn@skax.example', 'EMPLOYEE', 'ACTIVE', '2022-08-01', 'ORG-STRATEGY', 'ESG Program Manager', 'SK0034', 'JOB-ESG-MANAGER', 'ESG Program Manager', 'STRATEGY', 'INDIVIDUAL', 'G3', 'SEOUL-HQ'),
    ('SK0036', '백예은', '예은', '백', 'ko-KR', 'yeeun.baek@skax.example', 'EMPLOYEE', 'ACTIVE', '2024-02-05', 'ORG-STRATEGY', 'Corporate Strategy Analyst', 'SK0034', 'JOB-STRATEGY-ANALYST', 'Corporate Strategy Analyst', 'STRATEGY', 'INDIVIDUAL', 'G2', 'SEOUL-HQ'),
    ('C200001', 'Elena Garcia', 'Elena', 'Garcia', 'en-US', 'elena.garcia@skax.example', 'CONTINGENT', 'ACTIVE', '2026-02-02', 'ORG-SEMI-DATA', 'Yield Data Specialist', 'SK0037', 'JOB-YIELD-DATA', 'Yield Data Specialist', 'SEMICONDUCTOR', 'CONTRACTOR', 'C1', 'AUSTIN-LAB'),
    ('SK0037', '권민성', '민성', '권', 'ko-KR', 'minsung.kwon@skax.example', 'EMPLOYEE', 'ACTIVE', '2019-06-03', 'ORG-SEMI-DATA', 'Semiconductor Data Operations 팀장', 'SK0006', 'JOB-TEAM-LEAD', 'Team Lead', 'LEADERSHIP', 'MANAGER', 'G5', 'PANGYO-CAMPUS'),
    ('SK0038', '김라온', '라온', '김', 'ko-KR', 'raon.kim@skax.example', 'EMPLOYEE', 'ACTIVE', '2022-06-07', 'ORG-SEMI-DATA', 'Yield Analytics Engineer', 'SK0037', 'JOB-YIELD-ANALYTICS', 'Yield Analytics Engineer', 'SEMICONDUCTOR', 'INDIVIDUAL', 'G3', 'PANGYO-CAMPUS'),
    ('SK0039', '나준서', '준서', '나', 'ko-KR', 'junseo.na@skax.example', 'EMPLOYEE', 'ACTIVE', '2020-03-02', 'ORG-SEMI-SMART', 'Smart Factory 팀장', 'SK0006', 'JOB-TEAM-LEAD', 'Team Lead', 'LEADERSHIP', 'MANAGER', 'G5', 'DAEJEON-CENTER'),
    ('SK0040', '전유나', '유나', '전', 'ko-KR', 'yuna.jeon@skax.example', 'EMPLOYEE', 'ACTIVE', '2023-03-06', 'ORG-SEMI-SMART', 'Manufacturing AI Engineer', 'SK0039', 'JOB-MFG-AI', 'Manufacturing AI Engineer', 'SEMICONDUCTOR', 'INDIVIDUAL', 'G2', 'DAEJEON-CENTER');

INSERT INTO int_source_systems (
    tenant_id, source_key, system_type, name, authoritative_domains,
    lifecycle_state, created_by, updated_by)
VALUES (
    1, 'skax-demo-hris', 'CUSTOM', 'SKAX Synthetic HRIS',
    '["PERSON","WORKER","ASSIGNMENT","ORGANIZATION","POSITION"]'::jsonb,
    'ACTIVE', 1, 1)
ON CONFLICT (tenant_id, source_key) DO UPDATE SET
    name = EXCLUDED.name,
    authoritative_domains = EXCLUDED.authoritative_domains,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_legal_employers (
    tenant_id, employer_key, legal_name, country_code, lifecycle_state,
    source_system_id, external_id, created_by, updated_by)
SELECT 1, 'SKAX-KR', 'SK AX Co., Ltd.', 'KR', 'ACTIVE',
       source.source_system_id, 'SKAX-KR', 1, 1
  FROM int_source_systems source
 WHERE source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
ON CONFLICT (tenant_id, employer_key) DO UPDATE SET
    legal_name = EXCLUDED.legal_name,
    country_code = EXCLUDED.country_code,
    lifecycle_state = 'ACTIVE',
    source_system_id = EXCLUDED.source_system_id,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_organizations (
    tenant_id, organization_key, organization_type, name, short_name,
    description, cost_center_key, color_token, lifecycle_state,
    source_system_id, external_id, valid_from, created_by, updated_by)
SELECT 1, seed.organization_key, seed.organization_type, seed.name, seed.short_name,
       seed.description, seed.cost_center_key, seed.color_token, 'ACTIVE',
       source.source_system_id, seed.organization_key, DATE '2020-01-01', 1, 1
  FROM tmp_skax_organizations seed
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
    source_system_id = EXCLUDED.source_system_id,
    external_id = EXCLUDED.external_id,
    valid_from = EXCLUDED.valid_from,
    valid_to = NULL,
    version = ppl_organizations.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

UPDATE ppl_organizations child
   SET parent_organization_id = parent.organization_id,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_skax_organizations seed
  LEFT JOIN ppl_organizations parent
    ON parent.tenant_id = 1 AND parent.organization_key = seed.parent_key
 WHERE child.tenant_id = 1
   AND child.organization_key = seed.organization_key;

DELETE FROM ppl_organization_relationships relationship
 USING ppl_organizations child, tmp_skax_organizations seed
 WHERE relationship.tenant_id = 1
   AND child.tenant_id = relationship.tenant_id
   AND child.organization_id = relationship.child_organization_id
   AND child.organization_key = seed.organization_key;

INSERT INTO ppl_organization_relationships (
    tenant_id, child_organization_id, parent_organization_id,
    relationship_type, primary_relationship, effective_start_date,
    source_system_id, external_id, created_by, updated_by)
SELECT 1, child.organization_id, parent.organization_id,
       'SUPERVISORY', TRUE, DATE '2020-01-01', source.source_system_id,
       seed.organization_key || ':supervisory', 1, 1
  FROM tmp_skax_organizations seed
  JOIN ppl_organizations child
    ON child.tenant_id = 1 AND child.organization_key = seed.organization_key
  JOIN ppl_organizations parent
    ON parent.tenant_id = 1 AND parent.organization_key = seed.parent_key
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
 WHERE seed.parent_key IS NOT NULL;

INSERT INTO ppl_organization_relationships (
    tenant_id, child_organization_id, parent_organization_id,
    relationship_type, primary_relationship, effective_start_date,
    source_system_id, external_id, created_by, updated_by)
SELECT 1, child.organization_id, parent.organization_id,
       'MATRIX', FALSE, DATE '2026-01-01', source.source_system_id,
       relation.child_key || ':' || relation.parent_key || ':matrix', 1, 1
  FROM (VALUES
        ('ORG-GENAI', 'ORG-CONSULT'),
        ('ORG-DATA', 'ORG-SEMI'),
        ('ORG-CX', 'ORG-AI-PLATFORM')) relation(child_key, parent_key)
  JOIN ppl_organizations child
    ON child.tenant_id = 1 AND child.organization_key = relation.child_key
  JOIN ppl_organizations parent
    ON parent.tenant_id = 1 AND parent.organization_key = relation.parent_key
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris';

INSERT INTO ppl_job_grades (
    tenant_id, grade_key, name, level_order, career_track,
    lifecycle_state, source_system_id, external_id, created_by, updated_by)
SELECT 1, grade.grade_key, grade.name, grade.level_order, grade.career_track,
       'ACTIVE', source.source_system_id, grade.grade_key, 1, 1
  FROM (VALUES
        ('C1', '전문 계약직', 1, 'CONTRACTOR'),
        ('G1', '사원', 1, 'PROFESSIONAL'),
        ('G2', '선임', 2, 'PROFESSIONAL'),
        ('G3', '책임', 3, 'PROFESSIONAL'),
        ('G4', '수석', 4, 'PROFESSIONAL'),
        ('G5', '팀장/본부장', 5, 'MANAGEMENT'),
        ('G6', '임원', 6, 'EXECUTIVE'),
        ('G7', '대표이사', 7, 'EXECUTIVE')) grade(grade_key, name, level_order, career_track)
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
ON CONFLICT (tenant_id, grade_key) DO UPDATE SET
    name = EXCLUDED.name,
    level_order = EXCLUDED.level_order,
    career_track = EXCLUDED.career_track,
    lifecycle_state = 'ACTIVE',
    source_system_id = EXCLUDED.source_system_id,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_locations (
    tenant_id, location_key, name, country_code, time_zone,
    address_payload, lifecycle_state, source_system_id, external_id,
    created_by, updated_by)
SELECT 1, location.location_key, location.name, location.country_code, location.time_zone,
       location.address_payload::jsonb, 'ACTIVE', source.source_system_id,
       location.location_key, 1, 1
  FROM (VALUES
        ('SEOUL-HQ', '서울 종로 HQ', 'KR', 'Asia/Seoul', '{"city":"Seoul","workMode":"HYBRID"}'),
        ('PANGYO-CAMPUS', '판교 Digital Campus', 'KR', 'Asia/Seoul', '{"city":"Seongnam","workMode":"HYBRID"}'),
        ('DAEJEON-CENTER', '대전 Technology Center', 'KR', 'Asia/Seoul', '{"city":"Daejeon","workMode":"ONSITE"}'),
        ('BUSAN-HUB', '부산 Delivery Hub', 'KR', 'Asia/Seoul', '{"city":"Busan","workMode":"HYBRID"}'),
        ('AUSTIN-LAB', 'Austin Innovation Lab', 'US', 'America/Chicago', '{"city":"Austin","workMode":"HYBRID"}'))
       location(location_key, name, country_code, time_zone, address_payload)
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
ON CONFLICT (tenant_id, location_key) DO UPDATE SET
    name = EXCLUDED.name,
    country_code = EXCLUDED.country_code,
    time_zone = EXCLUDED.time_zone,
    address_payload = EXCLUDED.address_payload,
    lifecycle_state = 'ACTIVE',
    source_system_id = EXCLUDED.source_system_id,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_job_profiles (
    tenant_id, job_key, name, job_family_key, management_level,
    lifecycle_state, source_system_id, external_id, created_by, updated_by)
SELECT DISTINCT ON (seed.job_key)
       1, seed.job_key, seed.job_name, seed.job_family_key, seed.management_level,
       'ACTIVE', source.source_system_id, seed.job_key, 1, 1
  FROM tmp_skax_workers seed
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
 ORDER BY seed.job_key, seed.worker_number
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
SELECT md5('skax-person:' || seed.worker_number)::uuid,
       1, seed.worker_number, seed.display_name, seed.locale,
       CASE WHEN seed.location_key = 'AUSTIN-LAB' THEN 'America/Chicago' ELSE 'Asia/Seoul' END,
       CASE WHEN seed.worker_status = 'TERMINATED' THEN 'INACTIVE' ELSE 'ACTIVE' END,
       source.source_system_id, 'SKAX-HRIS-' || seed.worker_number, 1, 1
  FROM tmp_skax_workers seed
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
ON CONFLICT (tenant_id, person_key) DO UPDATE SET
    public_id = EXCLUDED.public_id,
    display_name = EXCLUDED.display_name,
    preferred_locale = EXCLUDED.preferred_locale,
    time_zone = EXCLUDED.time_zone,
    lifecycle_state = EXCLUDED.lifecycle_state,
    source_system_id = EXCLUDED.source_system_id,
    external_id = EXCLUDED.external_id,
    version = ppl_persons.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

DELETE FROM ppl_person_names name
 USING ppl_persons person, tmp_skax_workers seed
 WHERE name.tenant_id = 1
   AND person.tenant_id = name.tenant_id
   AND person.person_id = name.person_id
   AND person.person_key = seed.worker_number;

INSERT INTO ppl_person_names (
    tenant_id, person_id, name_type, locale, given_name, family_name,
    formatted_name, effective_start_date, created_by, updated_by)
SELECT 1, person.person_id, 'PREFERRED', seed.locale,
       seed.given_name, seed.family_name, seed.display_name, seed.hire_date, 1, 1
  FROM tmp_skax_workers seed
  JOIN ppl_persons person
    ON person.tenant_id = 1 AND person.person_key = seed.worker_number;

INSERT INTO ppl_workers (
    tenant_id, person_id, worker_number, worker_type, worker_status,
    original_hire_date, source_system_id, external_id, created_by, updated_by)
SELECT 1, person.person_id, seed.worker_number, seed.worker_type, seed.worker_status,
       seed.hire_date, source.source_system_id, 'SKAX-HRIS-' || seed.worker_number,
       1, 1
  FROM tmp_skax_workers seed
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
    external_id = EXCLUDED.external_id,
    version = ppl_workers.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_work_relationships (
    tenant_id, relationship_key, worker_id, legal_employer_id,
    relationship_type, primary_relationship, start_date,
    source_system_id, external_id, created_by, updated_by)
SELECT 1, seed.worker_number || '-PRIMARY', worker.worker_id, employer.legal_employer_id,
       seed.worker_type, TRUE, seed.hire_date, source.source_system_id,
       'SKAX-HRIS-' || seed.worker_number || ':relationship', 1, 1
  FROM tmp_skax_workers seed
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
    external_id = EXCLUDED.external_id,
    version = ppl_work_relationships.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_positions (
    tenant_id, position_key, title, organization_id, job_profile_id,
    location_id, position_status, source_system_id, external_id,
    created_by, updated_by)
SELECT 1, 'POS-' || seed.worker_number, seed.business_title,
       organization.organization_id, job.job_profile_id, location.location_id,
       'FILLED', source.source_system_id, 'POS-' || seed.worker_number, 1, 1
  FROM tmp_skax_workers seed
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
    source_system_id = EXCLUDED.source_system_id,
    version = ppl_positions.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_assignments (
    tenant_id, assignment_key, work_relationship_id,
    effective_start_date, assignment_status, primary_assignment,
    position_id, job_profile_id, job_grade_id, organization_id, location_id,
    manager_assignment_key, business_title, cost_center_key, change_reason_code,
    source_system_id, external_id, source_version, created_by, updated_by)
SELECT 1, 'ASG-' || seed.worker_number || '-1', relationship.work_relationship_id,
       seed.hire_date,
       CASE WHEN seed.worker_status = 'LEAVE' THEN 'SUSPENDED' ELSE 'ACTIVE' END,
       TRUE, position.position_id, job.job_profile_id, grade.job_grade_id,
       organization.organization_id, location.location_id,
       CASE WHEN seed.manager_worker_number IS NULL
            THEN NULL ELSE 'ASG-' || seed.manager_worker_number || '-1' END,
       seed.business_title, organization.cost_center_key, 'SEED_IMPORT',
       source.source_system_id, 'SKAX-ASG-' || seed.worker_number,
       '2026-08-10T13:00:00Z', 1, 1
  FROM tmp_skax_workers seed
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
    assignment_status = EXCLUDED.assignment_status,
    primary_assignment = TRUE,
    position_id = EXCLUDED.position_id,
    job_profile_id = EXCLUDED.job_profile_id,
    job_grade_id = EXCLUDED.job_grade_id,
    organization_id = EXCLUDED.organization_id,
    location_id = EXCLUDED.location_id,
    manager_assignment_key = EXCLUDED.manager_assignment_key,
    business_title = EXCLUDED.business_title,
    cost_center_key = EXCLUDED.cost_center_key,
    change_reason_code = EXCLUDED.change_reason_code,
    source_system_id = EXCLUDED.source_system_id,
    external_id = EXCLUDED.external_id,
    source_version = EXCLUDED.source_version,
    version = ppl_assignments.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

DELETE FROM ppl_contacts contact
 USING ppl_persons person, tmp_skax_workers seed
 WHERE contact.tenant_id = 1
   AND person.tenant_id = contact.tenant_id
   AND person.person_id = contact.person_id
   AND person.person_key = seed.worker_number
   AND contact.contact_type = 'EMAIL'
   AND contact.usage_type = 'WORK';

INSERT INTO ppl_contacts (
    tenant_id, person_id, contact_type, usage_type, display_value,
    primary_contact, visibility, valid_from, created_by, updated_by)
SELECT 1, person.person_id, 'EMAIL', 'WORK', seed.work_email,
       TRUE, 'INTERNAL', seed.hire_date, 1, 1
  FROM tmp_skax_workers seed
  JOIN ppl_persons person
    ON person.tenant_id = 1 AND person.person_key = seed.worker_number;

INSERT INTO int_external_mappings (
    tenant_id, source_system_id, entity_type, internal_key, external_id,
    external_version, last_seen_at, created_by, updated_by)
SELECT 1, source.source_system_id, 'PERSON', person.public_id::text,
       'SKAX-HRIS-' || seed.worker_number, '2026.08', CURRENT_TIMESTAMP, 1, 1
  FROM tmp_skax_workers seed
  JOIN ppl_persons person
    ON person.tenant_id = 1 AND person.person_key = seed.worker_number
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
ON CONFLICT (tenant_id, source_system_id, entity_type, external_id)
DO UPDATE SET
    internal_key = EXCLUDED.internal_key,
    external_version = EXCLUDED.external_version,
    last_seen_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_positions (
    tenant_id, position_key, title, organization_id, job_profile_id,
    location_id, availability_date, position_status, source_system_id,
    external_id, created_by, updated_by)
SELECT 1, vacancy.position_key, vacancy.title, organization.organization_id,
       job.job_profile_id, location.location_id, vacancy.availability_date,
       'OPEN', source.source_system_id, vacancy.position_key, 1, 1
  FROM (VALUES
        ('OPEN-GENAI-01', 'Senior AI Agent Engineer', 'ORG-GENAI', 'JOB-LLM-ENGINEER', 'PANGYO-CAMPUS', DATE '2026-09-01'),
        ('OPEN-DATA-01', 'Data Governance Lead', 'ORG-DATA', 'JOB-DATA-ARCHITECT', 'PANGYO-CAMPUS', DATE '2026-10-01'),
        ('OPEN-CLOUD-01', 'Cloud FinOps Engineer', 'ORG-CLOUD', 'JOB-SRE', 'DAEJEON-CENTER', DATE '2026-09-15'),
        ('OPEN-CONSULT-01', 'Principal Transformation Consultant', 'ORG-CONSULT', 'JOB-BIZ-CONSULTANT', 'SEOUL-HQ', DATE '2026-08-15'),
        ('OPEN-SEMI-01', 'Manufacturing Data Scientist', 'ORG-SEMI-SMART', 'JOB-MFG-AI', 'DAEJEON-CENTER', DATE '2026-11-01'),
        ('OPEN-CX-01', 'Senior Product Designer', 'ORG-CX', 'JOB-SERVICE-DESIGNER', 'SEOUL-HQ', DATE '2026-09-01'))
       vacancy(position_key, title, organization_key, job_key, location_key, availability_date)
  JOIN ppl_organizations organization
    ON organization.tenant_id = 1 AND organization.organization_key = vacancy.organization_key
  JOIN ppl_job_profiles job
    ON job.tenant_id = 1 AND job.job_key = vacancy.job_key
  JOIN ppl_locations location
    ON location.tenant_id = 1 AND location.location_key = vacancy.location_key
  JOIN int_source_systems source
    ON source.tenant_id = 1 AND source.source_key = 'skax-demo-hris'
ON CONFLICT (tenant_id, position_key) DO UPDATE SET
    title = EXCLUDED.title,
    organization_id = EXCLUDED.organization_id,
    job_profile_id = EXCLUDED.job_profile_id,
    location_id = EXCLUDED.location_id,
    availability_date = EXCLUDED.availability_date,
    position_status = 'OPEN',
    source_system_id = EXCLUDED.source_system_id,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

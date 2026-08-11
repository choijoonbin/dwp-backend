INSERT INTO adm_reference_sets (
    tenant_id,
    set_key,
    name,
    description,
    lifecycle_state,
    content_revision,
    version,
    created_by,
    updated_by
)
VALUES
    (1, 'WORK_PRIORITY', '업무 우선순위',
     '업무 요청, 승인 및 에이전트 실행에서 공통으로 사용하는 우선순위입니다.',
     'ACTIVE', 1, 0, 1, 1),
    (1, 'SERVICE_REGION', '서비스 운영 지역',
     '서비스 제공 범위와 운영 거점을 계층으로 관리합니다.',
     'ACTIVE', 1, 0, 1, 1),
    (1, 'WORK_CATEGORY', '업무 요청 분류',
     '업무 포털과 연계 시스템이 공유하는 최상위 요청 분류입니다.',
     'ACTIVE', 1, 0, 1, 1),
    (1, 'DELIVERY_CHANNEL', '업무 전달 채널',
     '알림과 업무 결과를 전달할 수 있는 채널 및 적용 일정을 관리합니다.',
     'ACTIVE', 1, 0, 1, 1)
ON CONFLICT (tenant_id, set_key) DO NOTHING;

WITH seed(set_key, code, lifecycle_state, sort_order, parent_code, valid_from, valid_to) AS (
    VALUES
        ('WORK_PRIORITY', 'CRITICAL', 'ACTIVE', 10, NULL, NULL, NULL),
        ('WORK_PRIORITY', 'HIGH',     'ACTIVE', 20, NULL, NULL, NULL),
        ('WORK_PRIORITY', 'MEDIUM',   'ACTIVE', 30, NULL, NULL, NULL),
        ('WORK_PRIORITY', 'LOW',      'ACTIVE', 40, NULL, NULL, NULL),

        ('SERVICE_REGION', 'KOREA',    'ACTIVE', 10, NULL,     NULL, NULL),
        ('SERVICE_REGION', 'SEOUL',    'ACTIVE', 11, 'KOREA',  NULL, NULL),
        ('SERVICE_REGION', 'PANGYO',   'ACTIVE', 12, 'KOREA',  NULL, NULL),
        ('SERVICE_REGION', 'DAEJEON',  'ACTIVE', 13, 'KOREA',  NULL, NULL),
        ('SERVICE_REGION', 'BUSAN',    'ACTIVE', 14, 'KOREA',  NULL, NULL),
        ('SERVICE_REGION', 'GLOBAL',   'ACTIVE', 20, NULL,     NULL, NULL),
        ('SERVICE_REGION', 'APAC',     'ACTIVE', 21, 'GLOBAL', NULL, NULL),
        ('SERVICE_REGION', 'AMERICAS', 'ACTIVE', 22, 'GLOBAL', NULL, NULL),
        ('SERVICE_REGION', 'EMEA',     'ACTIVE', 23, 'GLOBAL', NULL, NULL),

        ('WORK_CATEGORY', 'ACCESS',   'ACTIVE', 10, NULL, NULL, NULL),
        ('WORK_CATEGORY', 'SOFTWARE', 'ACTIVE', 20, NULL, NULL, NULL),
        ('WORK_CATEGORY', 'HARDWARE', 'ACTIVE', 30, NULL, NULL, NULL),
        ('WORK_CATEGORY', 'PEOPLE',   'ACTIVE', 40, NULL, NULL, NULL),
        ('WORK_CATEGORY', 'FACILITY', 'ACTIVE', 50, NULL, NULL, NULL),
        ('WORK_CATEGORY', 'SECURITY', 'ACTIVE', 60, NULL, NULL, NULL),

        ('DELIVERY_CHANNEL', 'PORTAL', 'ACTIVE', 10, NULL, NULL, NULL),
        ('DELIVERY_CHANNEL', 'EMAIL',  'ACTIVE', 20, NULL, NULL, NULL),
        ('DELIVERY_CHANNEL', 'CHAT',   'ACTIVE', 30, NULL, NULL, NULL),
        ('DELIVERY_CHANNEL', 'MOBILE', 'ACTIVE', 40, NULL, NULL, NULL),
        ('DELIVERY_CHANNEL', 'VOICE',  'DRAFT',  50, NULL,
         CURRENT_TIMESTAMP + INTERVAL '14 days', NULL),
        ('DELIVERY_CHANNEL', 'FAX',    'RETIRED', 60, NULL, NULL,
         CURRENT_TIMESTAMP - INTERVAL '1 day')
)
INSERT INTO adm_reference_items (
    tenant_id,
    reference_set_id,
    code,
    lifecycle_state,
    sort_order,
    parent_code,
    valid_from,
    valid_to,
    version,
    created_by,
    updated_by
)
SELECT
    reference_set.tenant_id,
    reference_set.reference_set_id,
    seed.code,
    seed.lifecycle_state,
    seed.sort_order,
    seed.parent_code,
    seed.valid_from,
    seed.valid_to,
    0,
    1,
    1
FROM seed
JOIN adm_reference_sets reference_set
  ON reference_set.tenant_id = 1
 AND reference_set.set_key = seed.set_key
ON CONFLICT (tenant_id, reference_set_id, code) DO NOTHING;

UPDATE adm_reference_items child
   SET parent_reference_item_id = parent.reference_item_id,
       updated_at = CURRENT_TIMESTAMP
  FROM adm_reference_sets reference_set,
       adm_reference_items parent
 WHERE child.tenant_id = 1
   AND child.reference_set_id = reference_set.reference_set_id
   AND child.tenant_id = reference_set.tenant_id
   AND child.parent_code IS NOT NULL
   AND parent.tenant_id = child.tenant_id
   AND parent.reference_set_id = child.reference_set_id
   AND parent.code = child.parent_code
   AND child.parent_reference_item_id IS DISTINCT FROM parent.reference_item_id;

WITH labels(set_key, code, locale, label, description) AS (
    VALUES
        ('WORK_PRIORITY', 'CRITICAL', 'ko', '긴급', '즉시 대응이 필요하며 서비스 또는 보안 영향이 큰 업무'),
        ('WORK_PRIORITY', 'CRITICAL', 'en', 'Critical', 'Immediate response required with major service or security impact'),
        ('WORK_PRIORITY', 'HIGH', 'ko', '높음', '당일 우선 처리와 담당자 확인이 필요한 업무'),
        ('WORK_PRIORITY', 'HIGH', 'en', 'High', 'Requires same-day prioritization and owner acknowledgement'),
        ('WORK_PRIORITY', 'MEDIUM', 'ko', '보통', '표준 처리 기한 내 완료하는 일반 업무'),
        ('WORK_PRIORITY', 'MEDIUM', 'en', 'Medium', 'Standard work completed within the normal service target'),
        ('WORK_PRIORITY', 'LOW', 'ko', '낮음', '업무 영향이 낮고 일정 조정이 가능한 요청'),
        ('WORK_PRIORITY', 'LOW', 'en', 'Low', 'Low-impact request with flexible scheduling'),

        ('SERVICE_REGION', 'KOREA', 'ko', '대한민국', '국내 서비스 운영 범위'),
        ('SERVICE_REGION', 'KOREA', 'en', 'Korea', 'Domestic service operating scope'),
        ('SERVICE_REGION', 'SEOUL', 'ko', '서울', '서울 운영 거점'),
        ('SERVICE_REGION', 'SEOUL', 'en', 'Seoul', 'Seoul operating hub'),
        ('SERVICE_REGION', 'PANGYO', 'ko', '판교', '판교 운영 거점'),
        ('SERVICE_REGION', 'PANGYO', 'en', 'Pangyo', 'Pangyo operating hub'),
        ('SERVICE_REGION', 'DAEJEON', 'ko', '대전', '대전 운영 거점'),
        ('SERVICE_REGION', 'DAEJEON', 'en', 'Daejeon', 'Daejeon operating hub'),
        ('SERVICE_REGION', 'BUSAN', 'ko', '부산', '부산 운영 거점'),
        ('SERVICE_REGION', 'BUSAN', 'en', 'Busan', 'Busan operating hub'),
        ('SERVICE_REGION', 'GLOBAL', 'ko', '글로벌', '해외 서비스 운영 범위'),
        ('SERVICE_REGION', 'GLOBAL', 'en', 'Global', 'International service operating scope'),
        ('SERVICE_REGION', 'APAC', 'ko', '아시아 태평양', '아시아 태평양 서비스 권역'),
        ('SERVICE_REGION', 'APAC', 'en', 'Asia Pacific', 'Asia Pacific service region'),
        ('SERVICE_REGION', 'AMERICAS', 'ko', '미주', '북미 및 중남미 서비스 권역'),
        ('SERVICE_REGION', 'AMERICAS', 'en', 'Americas', 'North and Latin America service region'),
        ('SERVICE_REGION', 'EMEA', 'ko', '유럽·중동·아프리카', '유럽, 중동 및 아프리카 서비스 권역'),
        ('SERVICE_REGION', 'EMEA', 'en', 'Europe, Middle East and Africa', 'EMEA service region'),

        ('WORK_CATEGORY', 'ACCESS', 'ko', '접근 및 권한', '계정, 접근 권한 및 승인 요청'),
        ('WORK_CATEGORY', 'ACCESS', 'en', 'Access and permissions', 'Account, access, and approval requests'),
        ('WORK_CATEGORY', 'SOFTWARE', 'ko', '소프트웨어', '업무 소프트웨어 설치 및 사용 지원'),
        ('WORK_CATEGORY', 'SOFTWARE', 'en', 'Software', 'Business software installation and support'),
        ('WORK_CATEGORY', 'HARDWARE', 'ko', '하드웨어', '단말, 장비 및 주변기기 지원'),
        ('WORK_CATEGORY', 'HARDWARE', 'en', 'Hardware', 'Device, equipment, and peripheral support'),
        ('WORK_CATEGORY', 'PEOPLE', 'ko', '구성원 서비스', '인사 및 구성원 지원 업무'),
        ('WORK_CATEGORY', 'PEOPLE', 'en', 'People services', 'Workforce and employee support'),
        ('WORK_CATEGORY', 'FACILITY', 'ko', '시설 및 공간', '오피스 시설과 업무 공간 지원'),
        ('WORK_CATEGORY', 'FACILITY', 'en', 'Facilities and workplace', 'Office facility and workplace support'),
        ('WORK_CATEGORY', 'SECURITY', 'ko', '보안', '정보 보호와 보안 사고 대응'),
        ('WORK_CATEGORY', 'SECURITY', 'en', 'Security', 'Information protection and security response'),

        ('DELIVERY_CHANNEL', 'PORTAL', 'ko', '업무 포털', 'DWP 알림 센터와 업무 화면으로 전달'),
        ('DELIVERY_CHANNEL', 'PORTAL', 'en', 'Work portal', 'Delivered through the DWP notification center and workspace'),
        ('DELIVERY_CHANNEL', 'EMAIL', 'ko', '이메일', '사용자 기본 업무 이메일로 전달'),
        ('DELIVERY_CHANNEL', 'EMAIL', 'en', 'Email', 'Delivered to the user primary business email'),
        ('DELIVERY_CHANNEL', 'CHAT', 'ko', '협업 메시지', '연결된 협업 채널의 메시지로 전달'),
        ('DELIVERY_CHANNEL', 'CHAT', 'en', 'Collaboration message', 'Delivered through a connected collaboration channel'),
        ('DELIVERY_CHANNEL', 'MOBILE', 'ko', '모바일 알림', '등록된 모바일 기기로 푸시 알림 전달'),
        ('DELIVERY_CHANNEL', 'MOBILE', 'en', 'Mobile notification', 'Push notification to a registered mobile device'),
        ('DELIVERY_CHANNEL', 'VOICE', 'ko', '음성 알림', '승인 후 적용 예정인 음성 기반 알림 채널'),
        ('DELIVERY_CHANNEL', 'VOICE', 'en', 'Voice notification', 'Voice notification channel scheduled after approval'),
        ('DELIVERY_CHANNEL', 'FAX', 'ko', '팩스', '운영이 종료되어 신규 업무에는 사용할 수 없는 채널'),
        ('DELIVERY_CHANNEL', 'FAX', 'en', 'Fax', 'Retired channel unavailable for new work')
)
INSERT INTO adm_reference_item_labels (
    tenant_id,
    reference_item_id,
    locale,
    label,
    description,
    created_by,
    updated_by
)
SELECT
    item.tenant_id,
    item.reference_item_id,
    labels.locale,
    labels.label,
    labels.description,
    1,
    1
FROM labels
JOIN adm_reference_sets reference_set
  ON reference_set.tenant_id = 1
 AND reference_set.set_key = labels.set_key
JOIN adm_reference_items item
  ON item.tenant_id = reference_set.tenant_id
 AND item.reference_set_id = reference_set.reference_set_id
 AND item.code = labels.code
ON CONFLICT (tenant_id, reference_item_id, locale) DO NOTHING;

INSERT INTO sys_platform_audit_events (
    audit_event_id,
    tenant_id,
    actor_type,
    actor_id,
    action,
    target_type,
    target_id,
    outcome,
    correlation_id,
    after_snapshot,
    occurred_at
)
SELECT
    md5('flyway-v22:reference-set:' || reference_set.set_key)::uuid,
    reference_set.tenant_id,
    'SERVICE',
    NULL,
    'reference-set.seeded',
    'REFERENCE_SET',
    reference_set.set_key,
    'SUCCESS',
    'flyway-v22-reference-catalog',
    jsonb_build_object(
        'setKey', reference_set.set_key,
        'lifecycleState', reference_set.lifecycle_state,
        'revision', reference_set.content_revision,
        'seededBy', 'platform-migration'
    )::text,
    CURRENT_TIMESTAMP
FROM adm_reference_sets reference_set
WHERE reference_set.tenant_id = 1
  AND reference_set.set_key IN (
      'WORK_PRIORITY', 'SERVICE_REGION', 'WORK_CATEGORY', 'DELIVERY_CHANNEL'
  )
ON CONFLICT (audit_event_id) DO NOTHING;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility)
VALUES
    ('PLATFORM.COMMUNICATION.CONTENT_TYPE', 'dwp-platform-server',
     'Communication content type',
     'Editorial format that drives newsroom rendering and governance behavior.',
     'SYSTEM', 'TYPED_CONTRACT', 'AnnouncementContentType', 'PROTOCOL', 'RUNTIME'),
    ('PLATFORM.COMMUNICATION.CATEGORY', 'dwp-platform-server',
     'Communication category',
     'Extensible editorial topic taxonomy used for discovery and presentation.',
     'EXTENSIBLE', 'DOMAIN_CATALOG',
     'sys_code_values:PLATFORM.COMMUNICATION.CATEGORY', 'REFERENCE', 'RUNTIME')
ON CONFLICT (code_set_key) DO UPDATE SET
    owner_service = EXCLUDED.owner_service,
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    configuration_level = EXCLUDED.configuration_level,
    validation_source = EXCLUDED.validation_source,
    source_reference = EXCLUDED.source_reference,
    contract_kind = EXCLUDED.contract_kind,
    runtime_visibility = EXCLUDED.runtime_visibility,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.COMMUNICATION.CONTENT_TYPE', 'ANNOUNCEMENT', 'Announcement',
     '{"ko":"안내","en":"Update"}', 10, '{"editorialWeight":"standard"}'),
    ('PLATFORM.COMMUNICATION.CONTENT_TYPE', 'NEWS', 'News',
     '{"ko":"뉴스","en":"News"}', 20, '{"editorialWeight":"story"}'),
    ('PLATFORM.COMMUNICATION.CONTENT_TYPE', 'EVENT', 'Event',
     '{"ko":"행사","en":"Event"}', 30, '{"editorialWeight":"event"}'),
    ('PLATFORM.COMMUNICATION.CONTENT_TYPE', 'POLICY_UPDATE', 'Policy update',
     '{"ko":"정책 업데이트","en":"Policy update"}', 40,
     '{"editorialWeight":"governed"}'),
    ('PLATFORM.COMMUNICATION.CATEGORY', 'COMPANY', 'Company',
     '{"ko":"회사","en":"Company"}', 10, '{"tone":"brand"}'),
    ('PLATFORM.COMMUNICATION.CATEGORY', 'INNOVATION', 'Innovation',
     '{"ko":"혁신","en":"Innovation"}', 20, '{"tone":"violet"}'),
    ('PLATFORM.COMMUNICATION.CATEGORY', 'CULTURE', 'Culture',
     '{"ko":"문화","en":"Culture"}', 30, '{"tone":"coral"}'),
    ('PLATFORM.COMMUNICATION.CATEGORY', 'SECURITY', 'Security',
     '{"ko":"보안","en":"Security"}', 40, '{"tone":"teal"}'),
    ('PLATFORM.COMMUNICATION.CATEGORY', 'LEADERSHIP', 'Leadership',
     '{"ko":"리더십","en":"Leadership"}', 50, '{"tone":"amber"}'),
    ('PLATFORM.COMMUNICATION.CATEGORY', 'GROWTH', 'Growth',
     '{"ko":"성장","en":"Growth"}', 60, '{"tone":"green"}')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    behavior_metadata = EXCLUDED.behavior_metadata,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PLATFORM.COMMUNICATION.CONTENT_TYPE', 'dwp-platform-server',
     'DATABASE_COLUMN', 'adm_announcements.content_type', 'CHECK'),
    ('PLATFORM.COMMUNICATION.CONTENT_TYPE', 'dwp-platform-server',
     'API_CONTRACT', 'AnnouncementContentType', 'TYPED_CONTRACT'),
    ('PLATFORM.COMMUNICATION.CATEGORY', 'dwp-frontend',
     'UI_SELECTION', 'AnnouncementManager.categoryKey', 'CATALOG_LOOKUP')
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference)
DO UPDATE SET
    enforcement_type = EXCLUDED.enforcement_type,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

ALTER TABLE adm_announcements
    ADD COLUMN content_type VARCHAR(24) NOT NULL DEFAULT 'ANNOUNCEMENT',
    ADD COLUMN category_key VARCHAR(40) NOT NULL DEFAULT 'COMPANY',
    ADD COLUMN body TEXT,
    ADD COLUMN cover_image_url VARCHAR(500),
    ADD COLUMN publisher_name VARCHAR(160) NOT NULL DEFAULT 'DWP Communications',
    ADD COLUMN featured BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN acknowledgement_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN acknowledgement_due_at TIMESTAMPTZ,
    ADD COLUMN dismissible BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN reading_minutes SMALLINT NOT NULL DEFAULT 2,
    ADD COLUMN source_locale VARCHAR(16) NOT NULL DEFAULT 'ko';

ALTER TABLE adm_announcements
    ADD CONSTRAINT ck_adm_announcements_content_type
        CHECK (content_type IN ('ANNOUNCEMENT', 'NEWS', 'EVENT', 'POLICY_UPDATE')),
    ADD CONSTRAINT ck_adm_announcements_category_key
        CHECK (category_key ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    ADD CONSTRAINT ck_adm_announcements_reading_minutes
        CHECK (reading_minutes BETWEEN 1 AND 60),
    ADD CONSTRAINT ck_adm_announcements_acknowledgement
        CHECK (
            (acknowledgement_required = FALSE AND acknowledgement_due_at IS NULL)
            OR acknowledgement_required = TRUE
        ),
    ADD CONSTRAINT ck_adm_announcements_required_dismissal
        CHECK (acknowledgement_required = FALSE OR dismissible = FALSE),
    ADD CONSTRAINT ck_adm_announcements_source_locale
        CHECK (source_locale ~ '^[a-z]{2}(-[A-Z]{2})?$');

ALTER TABLE sys_announcement_engagements
    ADD COLUMN first_opened_at TIMESTAMPTZ,
    ADD COLUMN last_opened_at TIMESTAMPTZ,
    ADD COLUMN open_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN saved_at TIMESTAMPTZ,
    ADD COLUMN acknowledged_at TIMESTAMPTZ,
    ADD COLUMN dismissed_at TIMESTAMPTZ;

ALTER TABLE sys_announcement_engagements
    ADD CONSTRAINT ck_announcement_engagement_open_count CHECK (open_count >= 0);

CREATE INDEX idx_announcement_engagement_user_state
    ON sys_announcement_engagements (
        tenant_id, user_id, acknowledged_at, saved_at, first_opened_at);

CREATE INDEX idx_adm_announcements_communications_feed
    ON adm_announcements (
        tenant_id, lifecycle_state, featured DESC, pinned DESC, published_at DESC);

CREATE TABLE adm_announcement_localizations (
    announcement_localization_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    announcement_id BIGINT NOT NULL
        REFERENCES adm_announcements(announcement_id) ON DELETE CASCADE,
    locale VARCHAR(16) NOT NULL,
    title VARCHAR(160) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    body TEXT,
    action_label VARCHAR(80),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_adm_announcement_localization
        UNIQUE (tenant_id, announcement_id, locale),
    CONSTRAINT ck_adm_announcement_localization_locale
        CHECK (locale ~ '^[a-z]{2}(-[A-Z]{2})?$')
);

COMMENT ON TABLE adm_announcement_localizations IS
    'Extensible localized editorial content. Base announcement columns remain the source-locale fallback.';
COMMENT ON COLUMN sys_announcement_engagements.first_seen_at IS
    'First measured impression. An impression never marks an item as opened.';
COMMENT ON COLUMN sys_announcement_engagements.first_opened_at IS
    'First intentional detail open and the source of unread state.';
COMMENT ON COLUMN sys_announcement_engagements.acknowledged_at IS
    'User acknowledgement evidence for mandatory communications.';

INSERT INTO adm_workspace_apps (
    tenant_id, app_key, name_ko, name_en, description_ko, description_en,
    owner_name, category, launch_mode, launch_target, icon_key, resource_key,
    health_state, sort_order, lifecycle_state, created_by, updated_by)
SELECT tenant_id, 'dwp-communications', '소식', 'Newsroom',
       '나에게 필요한 회사 소식, 행사 및 필수 확인 콘텐츠를 한곳에서 읽습니다.',
       'Read targeted company news, events, and required updates in one place.',
       'DWP Communications', 'PRODUCTIVITY', 'NATIVE', '/communications',
       'communications', 'APP.COMMUNICATIONS', 'HEALTHY', 35, 'ACTIVE', 1, 1
  FROM sys_service_tenants
ON CONFLICT (tenant_id, app_key) DO UPDATE SET
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description_ko = EXCLUDED.description_ko,
    description_en = EXCLUDED.description_en,
    owner_name = EXCLUDED.owner_name,
    category = EXCLUDED.category,
    launch_mode = EXCLUDED.launch_mode,
    launch_target = EXCLUDED.launch_target,
    icon_key = EXCLUDED.icon_key,
    resource_key = EXCLUDED.resource_key,
    health_state = EXCLUDED.health_state,
    sort_order = EXCLUDED.sort_order,
    lifecycle_state = 'ACTIVE',
    version = adm_workspace_apps.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO adm_announcements (
    tenant_id, title, message, body, severity, lifecycle_state,
    audience_type, starts_at, ends_at, pinned, action_label, action_url,
    published_at, published_by, content_type, category_key, cover_image_url,
    publisher_name, featured, acknowledgement_required,
    acknowledgement_due_at, dismissible, reading_minutes, source_locale,
    created_by, updated_by)
SELECT tenant_id,
       seed.title,
       seed.summary,
       seed.body,
       seed.severity,
       'PUBLISHED',
       'ALL',
       CURRENT_TIMESTAMP - INTERVAL '2 days',
       seed.ends_at,
       seed.pinned,
       seed.action_label,
       seed.action_url,
       CURRENT_TIMESTAMP - seed.published_ago,
       1,
       seed.content_type,
       seed.category_key,
       seed.cover_image_url,
       seed.publisher_name,
       seed.featured,
       seed.acknowledgement_required,
       seed.acknowledgement_due_at,
       seed.dismissible,
       seed.reading_minutes,
       'ko',
       1,
       1
  FROM sys_service_tenants tenant
 CROSS JOIN (VALUES
    (
        'AX 혁신 랩, 현업과 함께 여는 새로운 협업 방식',
        '작은 아이디어가 실제 업무 변화로 이어진 세 팀의 실험과 다음 참여 기회를 소개합니다.',
        E'이번 분기 AX 혁신 랩은 현업 구성원이 직접 문제를 제안하고, 제품·데이터·AI 전문가가 한 팀으로 해결하는 방식으로 운영됐습니다.\n\n첫 번째 실험에서는 반복 보고 업무를 줄였고, 두 번째 실험에서는 고객 문의 맥락을 더 빠르게 찾도록 지식 흐름을 재설계했습니다. 중요한 것은 기술 자체보다 구성원이 변화의 주체가 되었다는 점입니다.\n\n다음 랩은 고객 경험, 업무 자동화, 지속가능성 세 가지 트랙으로 열립니다. 관심 있는 구성원은 소속과 관계없이 아이디어를 제안할 수 있습니다.',
        'INFO', 'NEWS', 'INNOVATION',
        '/media/communications/innovation-lab.jpg', '디지털 워크플레이스팀',
        TRUE, FALSE, NULL::timestamptz, TRUE, FALSE,
        '참여 방법 보기', '/communications/all', INTERVAL '90 minutes',
        CURRENT_TIMESTAMP + INTERVAL '45 days', 4
    ),
    (
        '함께 만드는 그린 캠퍼스 데이',
        '지역사회와 연결되는 하루, 사내 자원봉사 프로그램의 일정과 참여 방법을 확인하세요.',
        E'그린 캠퍼스 데이는 일상 속 지속가능한 행동을 함께 실천하는 참여형 행사입니다. 캠퍼스 정원 조성, 다회용품 키트 제작, 지역 커뮤니티 지원 프로그램이 동시에 진행됩니다.\n\n참여자는 원하는 세션을 선택할 수 있으며 팀 단위 신청도 가능합니다. 모든 프로그램은 근무 시간 내 공식 활동으로 인정됩니다.',
        'SUCCESS', 'EVENT', 'CULTURE',
        '/media/communications/community-day.jpg', 'People & Culture',
        FALSE, FALSE, NULL::timestamptz, TRUE, FALSE,
        '행사 일정 확인', '/communications/all', INTERVAL '1 day',
        CURRENT_TIMESTAMP + INTERVAL '21 days', 3
    ),
    (
        '필수 확인: 하반기 보안 대응 원칙이 업데이트되었습니다',
        '업무 기기, 외부 협업, 생성형 AI 사용에 적용되는 변경 사항을 확인해 주세요.',
        E'업무 환경과 공격 방식의 변화에 맞춰 보안 대응 원칙이 개정되었습니다. 이번 개정은 외부 협업 공간의 자료 공유, 업무 기기 분실 대응, 생성형 AI에 입력할 수 있는 정보의 범위를 명확히 합니다.\n\n상세 내용을 읽은 뒤 확인 버튼을 선택해 주세요. 확인 기록은 정책 준수 증적으로 보관되며, 기한이 지난 뒤에도 완료할 수 있습니다.',
        'WARNING', 'POLICY_UPDATE', 'SECURITY',
        '/media/communications/security-readiness.jpg', '정보보호실',
        FALSE, TRUE, CURRENT_TIMESTAMP + INTERVAL '10 days', FALSE, TRUE,
        '정책 전문 열기', '/communications/required', INTERVAL '3 hours',
        CURRENT_TIMESTAMP + INTERVAL '60 days', 5
    ),
    (
        '리더십 타운홀 다시보기와 질문 모음',
        '이번 분기 전략, 고객 가치, 일하는 방식에 대한 주요 답변을 빠르게 확인할 수 있습니다.',
        E'타운홀에서 다룬 핵심 질문과 답변을 주제별로 정리했습니다. 전체 영상을 볼 시간이 없다면 각 섹션의 요약과 결정 사항부터 확인해 보세요.',
        'INFO', 'ANNOUNCEMENT', 'LEADERSHIP',
        NULL, 'CEO Office',
        FALSE, FALSE, NULL::timestamptz, TRUE, FALSE,
        NULL, NULL, INTERVAL '18 hours',
        CURRENT_TIMESTAMP + INTERVAL '30 days', 2
    ),
    (
        '사내 멘토링 커넥트, 두 번째 시즌을 시작합니다',
        '직무와 세대를 넘어 경험을 나누는 6주 프로그램의 멘토와 멘티를 모집합니다.',
        E'멘토링 커넥트는 정답을 전달하는 프로그램이 아니라 서로의 경험을 연결하는 자리입니다. 관심 주제와 성장 목표를 바탕으로 매칭하며, 6주 동안 세 번의 대화를 권장합니다.',
        'SUCCESS', 'EVENT', 'GROWTH',
        '/media/communications/innovation-lab.jpg', 'Talent Growth',
        FALSE, FALSE, NULL::timestamptz, TRUE, FALSE,
        '모집 안내 보기', '/communications/all', INTERVAL '30 hours',
        CURRENT_TIMESTAMP + INTERVAL '28 days', 3
    )
 ) AS seed(
    title, summary, body, severity, content_type, category_key,
    cover_image_url, publisher_name, featured, acknowledgement_required,
    acknowledgement_due_at, dismissible, pinned, action_label, action_url,
    published_ago, ends_at, reading_minutes)
WHERE NOT EXISTS (
    SELECT 1
      FROM adm_announcements existing
     WHERE existing.tenant_id = tenant.tenant_id
       AND existing.title = seed.title);

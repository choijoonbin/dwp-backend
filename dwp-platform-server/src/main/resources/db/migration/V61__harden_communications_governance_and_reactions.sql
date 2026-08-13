CREATE TABLE sys_announcement_reactions (
    announcement_reaction_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    announcement_id BIGINT NOT NULL
        REFERENCES adm_announcements(announcement_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    reaction_code VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_announcement_reaction_actor
        UNIQUE (tenant_id, announcement_id, user_id),
    CONSTRAINT ck_announcement_reaction_code
        CHECK (reaction_code IN ('CELEBRATE', 'INSIGHTFUL', 'SUPPORT'))
);

CREATE INDEX idx_announcement_reaction_summary
    ON sys_announcement_reactions (tenant_id, announcement_id, reaction_code);

COMMENT ON TABLE sys_announcement_reactions IS
    'One current lightweight reaction per reader and communication; comments remain outside this bounded context until moderation and retention controls exist.';

INSERT INTO adm_announcements (
    tenant_id, title, message, body, severity, lifecycle_state,
    audience_type, starts_at, ends_at, pinned, content_type, category_key,
    publisher_name, featured, acknowledgement_required, dismissible,
    reading_minutes, source_locale, created_by, updated_by)
SELECT tenant.tenant_id,
       '작성 중: 2027 업무 방식 아이디어 모집',
       '편집자 검토를 위한 초안 샘플입니다.',
       '이 콘텐츠는 게시 전 초안 상태와 편집 흐름을 검증하기 위한 운영 샘플입니다.',
       'INFO', 'DRAFT', 'ALL', NULL, NULL, FALSE, 'NEWS', 'INNOVATION',
       '디지털 워크플레이스팀', FALSE, FALSE, TRUE, 2, 'ko', 1, 1
  FROM sys_service_tenants tenant
 WHERE NOT EXISTS (
    SELECT 1 FROM adm_announcements existing
     WHERE existing.tenant_id = tenant.tenant_id
       AND existing.title = '작성 중: 2027 업무 방식 아이디어 모집');

INSERT INTO adm_announcements (
    tenant_id, title, message, body, severity, lifecycle_state,
    audience_type, starts_at, ends_at, pinned, published_at, published_by,
    content_type, category_key, publisher_name, featured,
    acknowledgement_required, dismissible, reading_minutes, source_locale,
    created_by, updated_by)
SELECT tenant.tenant_id,
       '예약: 다음 분기 사내 기술 포럼 안내',
       '예약 게시 흐름과 게시 창을 확인하기 위한 운영 샘플입니다.',
       '다음 분기 기술 포럼의 세부 일정은 게시 시작 시점에 구성원에게 공개됩니다.',
       'INFO', 'PUBLISHED', 'ALL', CURRENT_TIMESTAMP + INTERVAL '14 days',
       CURRENT_TIMESTAMP + INTERVAL '45 days', FALSE,
       CURRENT_TIMESTAMP, 1, 'EVENT', 'GROWTH', 'Technology Community', FALSE,
       FALSE, TRUE, 2, 'ko', 1, 1
  FROM sys_service_tenants tenant
 WHERE NOT EXISTS (
    SELECT 1 FROM adm_announcements existing
     WHERE existing.tenant_id = tenant.tenant_id
       AND existing.title = '예약: 다음 분기 사내 기술 포럼 안내');

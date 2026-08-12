INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('AUTH.ACCESS_REVIEW_ITEM.RECOMMENDATION', 'dwp-auth-server',
     'Access review recommendation', 'Immutable recommendation captured with access review evidence.',
     'SYSTEM', 'CHECK', 'com_access_review_items.recommendation', 'SECURITY'),
    ('AUTH.ACCESS_REVIEW_ITEM.RECOMMENDATION_REASON', 'dwp-auth-server',
     'Access review recommendation reason', 'Governed evidence reason used to explain an access recommendation.',
     'SYSTEM', 'CHECK', 'com_access_review_items.recommendation_reason', 'SECURITY'),
    ('PLATFORM.EXPERIENCE_REVISION.EXPERIENCE_TYPE', 'dwp-platform-server',
     'Experience revision type', 'Tenant experience surface represented by an immutable revision.',
     'SYSTEM', 'CHECK', 'adm_experience_revisions.experience_type', 'PROTOCOL'),
    ('PLATFORM.EXPERIENCE_REVISION.CHANGE_TYPE', 'dwp-platform-server',
     'Experience revision change', 'Published action represented by an immutable experience revision.',
     'SYSTEM', 'CHECK', 'adm_experience_revisions.change_type', 'STATE_MACHINE');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('AUTH.ACCESS_REVIEW_ITEM.RECOMMENDATION', 'KEEP', 'Keep',
     '{"ko":"유지 권고","en":"Keep"}', 10, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.RECOMMENDATION', 'REVIEW', 'Review',
     '{"ko":"검토 권고","en":"Review"}', 20, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.RECOMMENDATION', 'UNAVAILABLE', 'Unavailable',
     '{"ko":"권고 없음","en":"Unavailable"}', 30, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.RECOMMENDATION_REASON', 'RECENT_ACTIVITY', 'Recent activity',
     '{"ko":"최근 활동","en":"Recent activity"}', 10, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.RECOMMENDATION_REASON', 'PRIVILEGED_ROLE', 'Privileged role',
     '{"ko":"특권 역할","en":"Privileged role"}', 20, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.RECOMMENDATION_REASON', 'NEVER_SIGNED_IN', 'Never signed in',
     '{"ko":"로그인 이력 없음","en":"Never signed in"}', 30, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.RECOMMENDATION_REASON', 'INACTIVE_90_DAYS', 'Inactive for 90 days',
     '{"ko":"90일 이상 미사용","en":"Inactive for 90 days"}', 40, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.RECOMMENDATION_REASON', 'EVIDENCE_UNAVAILABLE', 'Evidence unavailable',
     '{"ko":"근거 없음","en":"Evidence unavailable"}', 50, '{}'),
    ('PLATFORM.EXPERIENCE_REVISION.EXPERIENCE_TYPE', 'BRANDING', 'Branding',
     '{"ko":"브랜딩","en":"Branding"}', 10, '{}'),
    ('PLATFORM.EXPERIENCE_REVISION.EXPERIENCE_TYPE', 'HOME', 'Home experience',
     '{"ko":"홈 경험","en":"Home experience"}', 20, '{}'),
    ('PLATFORM.EXPERIENCE_REVISION.CHANGE_TYPE', 'BASELINE', 'Baseline',
     '{"ko":"기준 버전","en":"Baseline"}', 10, '{}'),
    ('PLATFORM.EXPERIENCE_REVISION.CHANGE_TYPE', 'SETTINGS_PUBLISHED', 'Settings published',
     '{"ko":"설정 게시","en":"Settings published"}', 20, '{}'),
    ('PLATFORM.EXPERIENCE_REVISION.CHANGE_TYPE', 'ASSET_PUBLISHED', 'Asset published',
     '{"ko":"자산 게시","en":"Asset published"}', 30, '{}'),
    ('PLATFORM.EXPERIENCE_REVISION.CHANGE_TYPE', 'ASSET_RESET', 'Asset reset',
     '{"ko":"자산 초기화","en":"Asset reset"}', 40, '{}'),
    ('PLATFORM.EXPERIENCE_REVISION.CHANGE_TYPE', 'ROLLBACK', 'Rollback',
     '{"ko":"롤백","en":"Rollback"}', 50, '{}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
SELECT code_set_key, owner_service, 'DATABASE_COLUMN', source_reference, 'CHECK'
  FROM sys_code_sets
 WHERE code_set_key IN (
    'AUTH.ACCESS_REVIEW_ITEM.RECOMMENDATION',
    'AUTH.ACCESS_REVIEW_ITEM.RECOMMENDATION_REASON',
    'PLATFORM.EXPERIENCE_REVISION.EXPERIENCE_TYPE',
    'PLATFORM.EXPERIENCE_REVISION.CHANGE_TYPE'
 );

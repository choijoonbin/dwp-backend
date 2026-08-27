-- Deterministic integration-only fixture for saved-view custody tests.
-- These rows are never loaded by production Flyway.
INSERT INTO usr_saved_views (
    saved_view_id, tenant_id, surface_key, owner_user_id, owner_group_ref,
    name, scope, configuration, lifecycle_state, retention_until,
    version, created_by, updated_by)
VALUES
    ('10000000-0000-4000-8000-000000000001', 1, 'workspace.work', 900002, NULL,
     '퇴직자 개인 업무함', 'PERSONAL', '{"status":"OPEN"}'::jsonb,
     'ACTIVE', NULL, 0, 900002, 900002),
    ('10000000-0000-4000-8000-000000000002', 1, 'workspace.activity', 900002,
     '20000000-0000-4000-8000-000000000001', '운영팀 공유 현황', 'TEAM',
     '{"period":"LAST_30_DAYS"}'::jsonb, 'ACTIVE', NULL, 0, 900002, 900002),
    ('10000000-0000-4000-8000-000000000003', 1, 'people.workforce-directory', 900002, NULL,
     '조직 공용 인력 현황', 'TENANT', '{"employmentStatus":"ACTIVE"}'::jsonb,
     'ACTIVE', NULL, 0, 900002, 900002),
    ('10000000-0000-4000-8000-000000000004', 1, 'workspace.apps', NULL, NULL,
     '임시 보존 앱 보기', 'PERSONAL', '{"category":"OPERATIONS"}'::jsonb,
     'ORPHANED', CURRENT_TIMESTAMP + INTERVAL '7 days', 1, 900002, 900018);

INSERT INTO usr_saved_view_preferences (
    tenant_id, user_id, surface_key, saved_view_id, favorite, is_default, last_used_at)
VALUES
    (1, 900002, 'workspace.work',
     '10000000-0000-4000-8000-000000000001', TRUE, TRUE, CURRENT_TIMESTAMP),
    (1, 900099, 'workspace.activity',
     '10000000-0000-4000-8000-000000000002', TRUE, FALSE, CURRENT_TIMESTAMP),
    (1, 900099, 'people.workforce-directory',
     '10000000-0000-4000-8000-000000000003', FALSE, TRUE, CURRENT_TIMESTAMP);

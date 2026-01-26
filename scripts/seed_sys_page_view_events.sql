-- Seed data for sys_page_view_events (100 users x 10 events)
-- Range: 2026-01-01 ~ 2026-01-31
WITH menu(path, menu_key, name) AS (
    VALUES
        ('/sign-in', 'menu.signin', '로그인'),
        ('/admin/monitoring', 'menu.admin.monitoring', '모니터링'),
        ('/ai/workspace', 'menu.ai.workspace', '워크스페이스'),
        ('/admin/users', 'menu.admin.users', '사용자 관리'),
        ('/admin/roles', 'menu.admin.roles', '역할 관리'),
        ('/admin/resources', 'menu.admin.resources', '리소스'),
        ('/admin/menus', 'menu.admin.menus', '메뉴'),
        ('/admin/codes', 'menu.admin.codes', '코드'),
        ('/admin/code-usages', 'menu.admin.code-usages', '코드 사용'),
        ('/admin/audit-logs', 'menu.admin.audit', '감사 로그'),
        ('/main/dashboard', 'menu.main.dashboard', '대시보드'),
        ('/chat', 'menu.chat', '채팅')
),
users AS (
    SELECT generate_series(1, 100) AS user_id
),
events_raw AS (
    SELECT
        u.user_id,
        gs AS seq,
        (timestamp '2026-01-01' + (random() * (timestamp '2026-02-01' - timestamp '2026-01-01'))) AS created_at,
        m.path,
        m.menu_key,
        m.name
    FROM users u
    JOIN generate_series(1, 10) gs ON TRUE
    JOIN LATERAL (
        SELECT * FROM menu ORDER BY random() LIMIT 1
    ) m ON TRUE
),
events AS (
    SELECT
        user_id,
        created_at,
        path,
        menu_key,
        name,
        lag(path) OVER (PARTITION BY user_id ORDER BY created_at, seq) AS from_path,
        lag(menu_key) OVER (PARTITION BY user_id ORDER BY created_at, seq) AS from_page_key
    FROM events_raw
)
INSERT INTO sys_page_view_events (
    tenant_id,
    user_id,
    session_id,
    page_key,
    path,
    from_page_key,
    from_path,
    referrer,
    duration_ms,
    ip_address,
    user_agent,
    event_type,
    event_name,
    target_key,
    metadata_json,
    created_at,
    created_by,
    updated_at,
    updated_by
)
SELECT
    1 AS tenant_id,
    e.user_id,
    'sess-' || e.user_id AS session_id,
    e.menu_key AS page_key,
    e.path,
    e.from_page_key,
    e.from_path,
    e.from_path,
    (random() * 5000)::int AS duration_ms,
    '127.0.0.1' AS ip_address,
    'seed/1.0' AS user_agent,
    'PAGE_VIEW' AS event_type,
    e.name AS event_name,
    NULL AS target_key,
    NULL AS metadata_json,
    e.created_at,
    e.user_id,
    e.created_at,
    e.user_id
FROM events e;

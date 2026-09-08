-- Explicit local-only fixture for joonbin@sk.com. This is NOT a Flyway migration.
-- Run in a transaction after SET LOCAL dwp.activity.seed_profile = 'local-joonbin';
-- The caller must first verify IAM 900018 belongs to joonbin@sk.com in the default tenant.
-- Display also requires server-side DWP_ACTIVITY_LOCAL_FIXTURES_ENABLED=true. Production defaults false.
-- No credentials, user grants, existing work, audit records, or connector state are modified.
DO $$
DECLARE
    target_tenant BIGINT;
    fixture RECORD;
    work_id UUID;
    evidence_id UUID;
    event_id UUID;
    connector_id UUID;
    subject_id UUID;
    stream_id UUID;
BEGIN
    IF current_setting('dwp.activity.seed_profile', TRUE) IS DISTINCT FROM 'local-joonbin' THEN
        RAISE EXCEPTION 'Activity fixtures require explicit local-joonbin opt-in';
    END IF;
    SELECT tenant.tenant_id INTO STRICT target_tenant
      FROM sys_service_tenants tenant
      JOIN cal_identity_links identity_link ON identity_link.tenant_id = tenant.tenant_id
     WHERE tenant.tenant_key = 'default' AND identity_link.user_id = 900018
       AND identity_link.person_public_id = '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'::uuid;

    FOR fixture IN SELECT * FROM (VALUES
        ('ACTIVITY-LOCAL-01', '[개발 검증] 활동 사건 상세와 원본 이동', 'IN_PROGRESS'),
        ('ACTIVITY-LOCAL-02', '[개발 검증] 업무 대기 상태와 완료된 변경 이력', 'WAITING'),
        ('ACTIVITY-LOCAL-03', '[개발 검증] 완료된 업무의 감사 연결', 'COMPLETED')
    ) AS seeds(work_key, title, work_state)
    LOOP
        work_id := md5('activity-local-work:' || target_tenant || ':' || fixture.work_key)::uuid;
        evidence_id := md5('activity-local-audit:' || target_tenant || ':' || fixture.work_key)::uuid;
        event_id := md5('activity-local-event:' || target_tenant || ':' || fixture.work_key)::uuid;
        IF EXISTS (SELECT 1 FROM wrk_items
                    WHERE (work_item_id = work_id
                       OR (tenant_id = target_tenant AND work_key = fixture.work_key))
                      AND (work_item_id IS DISTINCT FROM work_id
                       OR tenant_id IS DISTINCT FROM target_tenant
                       OR work_key IS DISTINCT FROM fixture.work_key
                       OR assignee_user_id IS DISTINCT FROM 900018
                       OR source_system IS DISTINCT FROM 'DWP_WORKSPACE'
                       OR work_type IS DISTINCT FROM 'TASK')) THEN
            RAISE EXCEPTION 'Activity fixture key collides with an existing work item';
        END IF;
        IF EXISTS (SELECT 1 FROM sys_platform_audit_events WHERE audit_event_id = evidence_id
                   AND (tenant_id <> target_tenant OR actor_type <> 'USER'
                     OR actor_id IS DISTINCT FROM 900018
                     OR action <> 'workspace.activity.local-fixture-created'
                     OR target_type <> 'WORK_ITEM' OR target_id <> work_id::text
                     OR outcome <> 'SUCCESS'
                     OR correlation_id IS DISTINCT FROM 'activity-local-joonbin'))
           OR EXISTS (SELECT 1 FROM wrk_activity_events WHERE activity_event_id = event_id
                   AND (tenant_id <> target_tenant OR visible_to_user_id IS DISTINCT FROM 900018
                     OR object_type <> 'WORK_ITEM' OR source_system <> 'DWP_WORKSPACE'
                     OR event_kind <> 'CHANGE'
                     OR source_event_id IS DISTINCT FROM 'activity-local:' || fixture.work_key
                     OR object_id IS DISTINCT FROM work_id::text
                     OR correlation_id IS DISTINCT FROM 'activity-local-joonbin'
                     OR audit_record_id IS DISTINCT FROM evidence_id
                     OR data_provenance <> 'SAMPLE')) THEN
            RAISE EXCEPTION 'Activity fixture identity collides with an existing record';
        END IF;
        INSERT INTO wrk_items(work_item_id, tenant_id, work_key, title_ko, title_en,
            summary_ko, summary_en, work_type, priority, lifecycle_state, owner_name,
            assignee_user_id, source_system, source_route)
        VALUES (work_id, target_tenant, fixture.work_key, fixture.title, fixture.title,
            '로컬 화면 검증용으로 생성한 독립 업무입니다.', 'Independent work created for local UI verification.',
            'TASK', 'MEDIUM', fixture.work_state, '최준빈', 900018, 'DWP_WORKSPACE', '/work?item=' || fixture.work_key)
        ON CONFLICT (work_item_id) DO NOTHING;
        INSERT INTO sys_platform_audit_events(audit_event_id, tenant_id, actor_type, actor_id,
            action, target_type, target_id, outcome, correlation_id, after_snapshot)
        VALUES (evidence_id, target_tenant, 'USER', 900018, 'workspace.activity.local-fixture-created',
            'WORK_ITEM', work_id::text, 'SUCCESS', 'activity-local-joonbin',
            jsonb_build_object('workKey', fixture.work_key, 'localFixture', TRUE, 'status', fixture.work_state)::text)
        ON CONFLICT (audit_event_id) DO NOTHING;
        INSERT INTO wrk_activity_events(activity_event_id, tenant_id, visible_to_user_id, actor_kind,
            actor_name, event_state, title_ko, title_en, summary_ko, summary_en, object_type,
            object_label_ko, object_label_en, source_system, source_route, event_kind, source_event_id,
            object_id, work_status, correlation_id, audit_record_id, data_provenance, audit_reference)
        VALUES (event_id, target_tenant, 900018, 'PERSON', '최준빈', 'COMPLETED', fixture.title, fixture.title,
            '개발 검증용 업무 생성이 완료되었습니다. 실행 상태가 아닌 변경 사실입니다.',
            'Local verification work was created. This is a change fact, not an execution state.',
            'WORK_ITEM', fixture.title, fixture.title, 'DWP_WORKSPACE', '/work?item=' || fixture.work_key,
            'CHANGE', 'activity-local:' || fixture.work_key, work_id::text, fixture.work_state,
            'activity-local-joonbin', evidence_id, 'SAMPLE', 'LOCAL-FIXTURE-' || evidence_id)
        ON CONFLICT (activity_event_id) DO NOTHING;
    END LOOP;

    -- Independent, explicitly named UI fixtures. No production connector is reused;
    -- no client id, credential reference, refresh token, or provider tenant is installed.
    -- These stored observations are LOCAL_FIXTURE, never proof of an external sync.
    FOR fixture IN SELECT * FROM (VALUES
        ('primary', 'HEALTHY', 'APPROVED', 'MAIL', 'READY', INTERVAL '3 minutes', INTERVAL '3 minutes'),
        ('primary', 'HEALTHY', 'APPROVED', 'CALENDAR', 'STALE', INTERVAL '8 minutes', INTERVAL '2 hours'),
        ('attention', 'DEGRADED', 'BLOCKED', 'MAIL', 'READY', INTERVAL '2 minutes', INTERVAL '1 day')
    ) AS seeds(connector_key, health_state, policy_state, resource_kind, stream_state, attempt_age, success_age)
    LOOP
        connector_id := md5('activity-local-connector:' || target_tenant || ':' || fixture.connector_key)::uuid;
        subject_id := md5('activity-local-subject:' || target_tenant || ':' || fixture.connector_key)::uuid;
        stream_id := md5('activity-local-stream:' || target_tenant || ':' || fixture.connector_key || ':' || fixture.resource_kind)::uuid;
        IF EXISTS (SELECT 1 FROM int_productivity_connectors WHERE productivity_connector_id = connector_id
                   AND (tenant_id <> target_tenant
                     OR connector_key <> 'activity-local-joonbin-' || fixture.connector_key
                     OR display_name <> '[개발 검증] 개인 연동 ' || fixture.connector_key
                     OR provider_type <> 'MICROSOFT_GRAPH' OR auth_mode <> 'DELEGATED'
                     OR provider_tenant_id IS NOT NULL OR client_id IS NOT NULL
                     OR credential_reference IS NOT NULL OR redirect_uri IS NOT NULL))
           OR EXISTS (SELECT 1 FROM int_productivity_subjects WHERE productivity_subject_id = subject_id
                   AND (tenant_id <> target_tenant OR user_id <> 900018
                     OR productivity_connector_id <> connector_id
                     OR provider_subject_ref_hash IS NOT NULL OR encrypted_refresh_token IS NOT NULL))
           OR EXISTS (SELECT 1 FROM int_productivity_sync_streams WHERE productivity_sync_stream_id = stream_id
                   AND (tenant_id <> target_tenant OR productivity_subject_id <> subject_id
                     OR resource_kind <> fixture.resource_kind OR encrypted_cursor IS NOT NULL
                     OR cursor_fingerprint IS NOT NULL)) THEN
            RAISE EXCEPTION 'Activity connector fixture collides with an existing record';
        END IF;
        INSERT INTO int_productivity_connectors(productivity_connector_id, tenant_id, connector_key,
            display_name, provider_type, auth_mode, capabilities, lifecycle_state, health_state, policy_state)
        VALUES (connector_id, target_tenant, 'activity-local-joonbin-' || fixture.connector_key,
            '[개발 검증] 개인 연동 ' || fixture.connector_key, 'MICROSOFT_GRAPH', 'DELEGATED',
            '["MAIL", "CALENDAR"]'::jsonb, 'ACTIVE', fixture.health_state, fixture.policy_state)
        ON CONFLICT (productivity_connector_id) DO NOTHING;
        INSERT INTO int_productivity_subjects(productivity_subject_id, tenant_id,
            productivity_connector_id, user_id, consent_state)
        VALUES (subject_id, target_tenant, connector_id, 900018, 'CONNECTED')
        ON CONFLICT (productivity_subject_id) DO NOTHING;
        INSERT INTO int_productivity_sync_streams(productivity_sync_stream_id, tenant_id,
            productivity_subject_id, resource_kind, stream_state, last_attempt_at, last_success_at,
            calendar_window_start, calendar_window_end)
        VALUES (stream_id, target_tenant, subject_id, fixture.resource_kind, fixture.stream_state,
            CURRENT_TIMESTAMP - fixture.attempt_age, CURRENT_TIMESTAMP - fixture.success_age,
            CASE WHEN fixture.resource_kind = 'CALENDAR' THEN CURRENT_TIMESTAMP - INTERVAL '7 days' ELSE NULL END,
            CASE WHEN fixture.resource_kind = 'CALENDAR' THEN CURRENT_TIMESTAMP + INTERVAL '30 days' ELSE NULL END)
        ON CONFLICT (productivity_sync_stream_id) DO NOTHING;
    END LOOP;
END $$;

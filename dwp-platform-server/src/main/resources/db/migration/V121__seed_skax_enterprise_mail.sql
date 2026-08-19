INSERT INTO mail_provider_connections (
    connection_id, tenant_id, connection_key, display_name, provider_type,
    authentication_mode, mail_domain, credential_ref, connection_state,
    capabilities, last_synchronized_at, created_by, updated_by)
SELECT md5('mail:connection:' || tenant.tenant_id || ':' || provider.connection_key)::uuid,
       tenant.tenant_id, provider.connection_key, provider.display_name,
       provider.provider_type, provider.authentication_mode, provider.mail_domain,
       provider.credential_ref, provider.connection_state,
       provider.capabilities::jsonb,
       CASE WHEN provider.connection_state = 'ACTIVE' THEN CURRENT_TIMESTAMP END,
       1, 1
  FROM sys_service_tenants tenant
 CROSS JOIN (VALUES
    ('dwp-sandbox', 'DWP 개발 메일', 'DWP_SANDBOX', 'NONE', 'sk.com',
     NULL, 'ACTIVE', '["READ","SEND","THREADS","PUSH","SHARED_INBOX","COMMENTS"]'),
    ('microsoft-graph', 'Microsoft 365', 'MICROSOFT_GRAPH', 'OAUTH2', NULL,
     NULL, 'CONFIGURATION_REQUIRED', '["READ","SEND","THREADS","DELTA_SYNC","PUSH","CALENDAR_CONTEXT"]'),
    ('google-gmail', 'Google Workspace', 'GOOGLE_GMAIL', 'OAUTH2', NULL,
     NULL, 'CONFIGURATION_REQUIRED', '["READ","SEND","THREADS","HISTORY_SYNC","PUSH","LABELS"]'),
    ('naver-works', 'NAVER WORKS', 'NAVER_WORKS', 'OAUTH2', NULL,
     NULL, 'CONFIGURATION_REQUIRED', '["READ","SEND","THREADS","FOLDERS"]'),
    ('jmap', 'JMAP 메일 서버', 'JMAP', 'OAUTH2', NULL,
     NULL, 'CONFIGURATION_REQUIRED', '["READ","SEND","THREADS","PUSH"]'),
    ('imap-smtp', '표준 회사 메일 서버', 'IMAP_SMTP', 'PASSWORD', NULL,
     NULL, 'CONFIGURATION_REQUIRED', '["READ","SEND","FOLDERS","IDLE"]')
 ) provider(
     connection_key, display_name, provider_type, authentication_mode,
     mail_domain, credential_ref, connection_state, capabilities)
 WHERE tenant.tenant_key = 'default'
ON CONFLICT (tenant_id, connection_key) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    provider_type = EXCLUDED.provider_type,
    authentication_mode = EXCLUDED.authentication_mode,
    connection_state = EXCLUDED.connection_state,
    capabilities = EXCLUDED.capabilities,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

WITH workforce AS (
    SELECT identity.tenant_id, identity.user_id, identity.person_public_id,
           COALESCE(
               MAX(event.organizer_email) FILTER (
                   WHERE event.organizer_email IS NOT NULL
                     AND event.organizer_email LIKE '%@%'),
               'member' || identity.user_id || '@sk.com') AS email,
           COALESCE(
               MAX(event.organizer_name) FILTER (
                   WHERE event.organizer_name IS NOT NULL
                     AND event.organizer_name <> '나'),
               'SKAX 구성원 ' || identity.user_id) AS display_name
      FROM cal_identity_links identity
      LEFT JOIN cal_events event
        ON event.tenant_id = identity.tenant_id
       AND event.organizer_user_id = identity.user_id
     GROUP BY identity.tenant_id, identity.user_id, identity.person_public_id
), sandbox AS (
    SELECT connection_id, tenant_id
      FROM mail_provider_connections
     WHERE connection_key = 'dwp-sandbox'
)
INSERT INTO mail_accounts (
    account_id, tenant_id, connection_id, owner_user_id,
    owner_person_public_id, email_address, display_name, account_kind,
    connection_state, synchronization_state, provider_account_ref,
    is_default, created_by, updated_by)
SELECT md5('mail:account:' || workforce.tenant_id || ':' || workforce.user_id)::uuid,
       workforce.tenant_id, sandbox.connection_id, workforce.user_id,
       workforce.person_public_id, LOWER(workforce.email), workforce.display_name,
       'PERSONAL', 'ACTIVE', 'READY', 'sandbox:user:' || workforce.user_id,
       TRUE, workforce.user_id, workforce.user_id
  FROM workforce
  JOIN sandbox ON sandbox.tenant_id = workforce.tenant_id
ON CONFLICT (tenant_id, email_address) DO UPDATE SET
    owner_user_id = EXCLUDED.owner_user_id,
    owner_person_public_id = EXCLUDED.owner_person_public_id,
    display_name = EXCLUDED.display_name,
    connection_state = 'ACTIVE',
    synchronization_state = 'READY',
    is_default = TRUE,
    version = mail_accounts.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

WITH sandbox AS (
    SELECT connection_id, tenant_id
      FROM mail_provider_connections
     WHERE connection_key = 'dwp-sandbox'
)
INSERT INTO mail_accounts (
    account_id, tenant_id, connection_id, email_address, display_name,
    account_kind, connection_state, synchronization_state,
    provider_account_ref, is_default, created_by, updated_by)
SELECT md5('mail:shared-account:' || sandbox.tenant_id || ':' || seed.inbox_key)::uuid,
       sandbox.tenant_id, sandbox.connection_id, seed.email, seed.display_name,
       'SHARED', 'ACTIVE', 'READY', 'sandbox:shared:' || seed.inbox_key,
       FALSE, 1, 1
  FROM sandbox
 CROSS JOIN (VALUES
    ('people-help', 'people@sk.com', 'People Help'),
    ('digital-workplace', 'dwp-help@sk.com', 'Digital Workplace Help')
 ) seed(inbox_key, email, display_name)
ON CONFLICT (tenant_id, email_address) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    connection_state = 'ACTIVE',
    synchronization_state = 'READY',
    version = mail_accounts.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO mail_folders (
    folder_id, tenant_id, account_id, provider_folder_ref,
    folder_key, display_name, folder_type, sort_order)
SELECT md5('mail:folder:' || account.account_id || ':' || folder.folder_key)::uuid,
       account.tenant_id, account.account_id,
       'sandbox:folder:' || folder.folder_key,
       folder.folder_key, folder.display_name, folder.folder_type, folder.sort_order
  FROM mail_accounts account
 CROSS JOIN (VALUES
    ('inbox', '받은 메일', 'INBOX', 10),
    ('sent', '보낸 메일', 'SENT', 20),
    ('drafts', '임시 보관함', 'DRAFTS', 30),
    ('archive', '보관함', 'ARCHIVE', 40),
    ('spam', '스팸', 'SPAM', 50),
    ('trash', '휴지통', 'TRASH', 60)
 ) folder(folder_key, display_name, folder_type, sort_order)
ON CONFLICT (account_id, folder_key) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    folder_type = EXCLUDED.folder_type,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO mail_shared_inboxes (
    shared_inbox_id, tenant_id, account_id, inbox_key, display_name,
    purpose, service_target_minutes, created_by, updated_by)
SELECT md5('mail:shared-inbox:' || account.tenant_id || ':' || seed.inbox_key)::uuid,
       account.tenant_id, account.account_id, seed.inbox_key,
       seed.display_name, seed.purpose, seed.target_minutes, 1, 1
  FROM (VALUES
    ('people-help', 'People Help', '인사 제도와 구성원 문의를 함께 처리합니다.', 240),
    ('digital-workplace', 'Digital Workplace Help', 'DWP 사용 문의와 접근 문제를 담당자에게 연결합니다.', 120)
  ) seed(inbox_key, display_name, purpose, target_minutes)
  JOIN mail_accounts account ON account.email_address = CASE seed.inbox_key
      WHEN 'people-help' THEN 'people@sk.com' ELSE 'dwp-help@sk.com' END
ON CONFLICT (tenant_id, inbox_key) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    purpose = EXCLUDED.purpose,
    service_target_minutes = EXCLUDED.service_target_minutes,
    lifecycle_state = 'ACTIVE',
    version = mail_shared_inboxes.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO mail_shared_inbox_members (
    tenant_id, shared_inbox_id, user_id, member_role,
    lifecycle_state, created_by, updated_by)
SELECT inbox.tenant_id, inbox.shared_inbox_id, member.user_id,
       member.member_role, 'ACTIVE', 1, 1
  FROM mail_shared_inboxes inbox
  JOIN (VALUES
    ('people-help', 9::BIGINT, 'MANAGER'),
    ('people-help', 11::BIGINT, 'MEMBER'),
    ('people-help', 16::BIGINT, 'MEMBER'),
    ('people-help', 37::BIGINT, 'MEMBER'),
    ('digital-workplace', 6::BIGINT, 'MEMBER'),
    ('digital-workplace', 7::BIGINT, 'MEMBER'),
    ('digital-workplace', 10::BIGINT, 'MANAGER'),
    ('digital-workplace', 16::BIGINT, 'MEMBER'),
    ('digital-workplace', 900018::BIGINT, 'MEMBER')
  ) member(inbox_key, user_id, member_role)
    ON member.inbox_key = inbox.inbox_key
ON CONFLICT (tenant_id, shared_inbox_id, user_id) DO UPDATE SET
    member_role = EXCLUDED.member_role,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

WITH personal_accounts AS (
    SELECT account.*, folder.folder_id
      FROM mail_accounts account
      JOIN mail_folders folder
        ON folder.account_id = account.account_id
       AND folder.folder_key = 'inbox'
     WHERE account.account_kind = 'PERSONAL'
), templates AS (
    SELECT * FROM (VALUES
      ('customer-reply', '고객 AX 전환 제안서 검토 요청',
       '내일 고객 미팅 전에 보안 운영 모델과 전환 일정에 대한 의견을 부탁드립니다.',
       1, 'URGENT', 'NEEDS_REPLY', TRUE, TRUE, 'CONFIDENTIAL'),
      ('meeting-plan', '프로젝트 킥오프 일정 조율',
       '이번 주 목요일 오후 3시에 킥오프를 진행할 수 있을까요? 참석자는 메일 본문에 정리했습니다.',
       3, 'HIGH', 'PRIORITY', TRUE, FALSE, 'INTERNAL'),
      ('leave-plan', '8월 워크숍 이후 휴가 계획 확인',
       '워크숍 다음 날 휴가를 계획하고 있다면 오늘 안에 신청해 주세요.',
       5, 'NORMAL', 'UPDATES', FALSE, FALSE, 'INTERNAL'),
      ('approval-followup', '서비스 계약 검토 후속 조치',
       '법무 검토 의견이 반영되었습니다. 담당 업무를 확인하고 다음 단계로 이동해 주세요.',
       9, 'HIGH', 'ASSIGNED', TRUE, TRUE, 'CONFIDENTIAL'),
      ('security-alert', '조치 필요: 외부 공유 링크 만료 예정',
       '고객 자료가 포함된 외부 공유 링크가 오늘 오후 만료됩니다. 연장이 필요한지 확인해 주세요.',
       14, 'URGENT', 'PRIORITY', TRUE, FALSE, 'RESTRICTED'),
      ('product-update', 'DWP 8월 제품 업데이트',
       '메일, 캘린더와 업무 흐름이 더 자연스럽게 연결됩니다. 이번 달 변경 사항을 확인하세요.',
       32, 'LOW', 'NEWSLETTERS', FALSE, FALSE, 'INTERNAL')
    ) value(
        seed_key, subject, preview, hours_ago, importance, triage_lane,
        unread, has_attachments, classification)
)
INSERT INTO mail_threads (
    thread_id, tenant_id, account_id, folder_id, provider_thread_ref,
    subject, preview, participants, latest_message_at, unread, starred,
    importance, triage_lane, workflow_state, has_attachments,
    external_sender, classification, message_count, created_by, updated_by)
SELECT md5('mail:thread:' || account.tenant_id || ':' || account.owner_user_id
           || ':' || template.seed_key)::uuid,
       account.tenant_id, account.account_id, account.folder_id,
       'sandbox:thread:' || account.owner_user_id || ':' || template.seed_key,
       template.subject, template.preview,
       jsonb_build_array(jsonb_build_object(
           'name', CASE template.seed_key
               WHEN 'customer-reply' THEN '이현정 고객성공 리드'
               WHEN 'meeting-plan' THEN '박현우'
               WHEN 'leave-plan' THEN 'People Operations'
               WHEN 'approval-followup' THEN 'DWP Decision Hub'
               WHEN 'security-alert' THEN 'Security Operations'
               ELSE 'DWP Product Team' END,
           'email', CASE template.seed_key
               WHEN 'customer-reply' THEN 'customer.success@example.com'
               WHEN 'meeting-plan' THEN 'hyunwoo.park@sk.com'
               WHEN 'leave-plan' THEN 'people@sk.com'
               WHEN 'approval-followup' THEN 'approvals@sk.com'
               WHEN 'security-alert' THEN 'security@sk.com'
               ELSE 'dwp-product@sk.com' END)),
       CURRENT_TIMESTAMP - template.hours_ago * INTERVAL '1 hour',
       template.unread, template.seed_key = 'meeting-plan',
       template.importance, template.triage_lane, 'OPEN',
       template.has_attachments,
       template.seed_key = 'customer-reply', template.classification,
       1, account.owner_user_id, account.owner_user_id
  FROM personal_accounts account
 CROSS JOIN templates template
ON CONFLICT (account_id, provider_thread_ref) DO UPDATE SET
    subject = EXCLUDED.subject,
    preview = EXCLUDED.preview,
    participants = EXCLUDED.participants,
    latest_message_at = EXCLUDED.latest_message_at,
    importance = EXCLUDED.importance,
    triage_lane = EXCLUDED.triage_lane,
    classification = EXCLUDED.classification,
    version = mail_threads.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

WITH shared_accounts AS (
    SELECT account.*, folder.folder_id, inbox.shared_inbox_id, inbox.inbox_key
      FROM mail_accounts account
      JOIN mail_folders folder
        ON folder.account_id = account.account_id AND folder.folder_key = 'inbox'
      JOIN mail_shared_inboxes inbox ON inbox.account_id = account.account_id
), shared_members AS (
    SELECT inbox.tenant_id, inbox.shared_inbox_id,
           member.user_id AS owner_user_id, account.display_name,
           ROW_NUMBER() OVER (
               PARTITION BY inbox.shared_inbox_id
               ORDER BY CASE member.member_role WHEN 'MANAGER' THEN 0 ELSE 1 END,
                        member.user_id) AS member_rank
      FROM mail_shared_inboxes inbox
      JOIN mail_shared_inbox_members member
        ON member.tenant_id = inbox.tenant_id
       AND member.shared_inbox_id = inbox.shared_inbox_id
       AND member.lifecycle_state = 'ACTIVE'
      JOIN mail_accounts account
        ON account.tenant_id = inbox.tenant_id
       AND account.owner_user_id = member.user_id
       AND account.account_kind = 'PERSONAL'
), templates AS (
    SELECT * FROM (VALUES
      ('benefit-question', '가족 의료비 지원 대상 문의',
       '신규 등록한 가족도 이번 달 의료비 지원 대상에 포함되는지 확인 부탁드립니다.', 2, 'people-help', 1),
      ('access-help', '프로젝트 워크스페이스 접근 요청',
       '고객 프로젝트 워크스페이스가 목록에 보이지 않습니다. 접근 상태를 확인해 주세요.', 4, 'digital-workplace', 1),
      ('payroll-document', '급여 증빙 발급 일정 문의',
       '해외 비자 제출용 영문 급여 증빙을 금요일까지 받을 수 있을까요?', 7, 'people-help', 2),
      ('calendar-sync', '모바일 캘린더 동기화 지연',
       '휴대폰 캘린더에 오늘 일정이 늦게 표시됩니다. 계정 동기화 상태를 확인해 주세요.', 11, 'digital-workplace', 2)
    ) value(seed_key, subject, preview, hours_ago, inbox_key, member_rank)
)
INSERT INTO mail_threads (
    thread_id, tenant_id, account_id, folder_id, shared_inbox_id,
    provider_thread_ref, subject, preview, participants, latest_message_at,
    unread, importance, triage_lane, workflow_state, assigned_user_id,
    assigned_name, external_sender, classification, message_count,
    created_by, updated_by)
SELECT md5('mail:shared-thread:' || shared.tenant_id || ':' || template.seed_key)::uuid,
       shared.tenant_id, shared.account_id, shared.folder_id, shared.shared_inbox_id,
       'sandbox:shared-thread:' || template.seed_key,
       template.subject, template.preview,
       jsonb_build_array(jsonb_build_object(
           'name', 'SKAX 구성원', 'email', 'member.request@sk.com')),
       CURRENT_TIMESTAMP - template.hours_ago * INTERVAL '1 hour',
       TRUE, CASE WHEN template.hours_ago <= 4 THEN 'HIGH' ELSE 'NORMAL' END,
       'ASSIGNED', 'OPEN', assignee.owner_user_id, assignee.display_name,
       FALSE, 'INTERNAL', 1, assignee.owner_user_id, assignee.owner_user_id
  FROM templates template
  JOIN shared_accounts shared ON shared.inbox_key = template.inbox_key
  JOIN shared_members assignee
    ON assignee.tenant_id = shared.tenant_id
   AND assignee.shared_inbox_id = shared.shared_inbox_id
   AND assignee.member_rank = template.member_rank
ON CONFLICT (account_id, provider_thread_ref) DO UPDATE SET
    preview = EXCLUDED.preview,
    assigned_user_id = EXCLUDED.assigned_user_id,
    assigned_name = EXCLUDED.assigned_name,
    version = mail_threads.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

WITH personal_folders AS (
    SELECT account.tenant_id, account.account_id, account.owner_user_id,
           folder.folder_id, folder.folder_type
      FROM mail_accounts account
      JOIN mail_folders folder ON folder.account_id = account.account_id
     WHERE account.account_kind = 'PERSONAL'
       AND folder.folder_type IN ('SENT', 'DRAFTS')
), templates AS (
    SELECT * FROM (VALUES
      ('customer-followup', 'SENT', '고객 AX 전환 검토 의견 전달',
       '보안 운영 모델과 단계별 전환 일정에 대한 검토 의견을 전달드립니다.',
       18, '이현정 고객성공 리드', 'customer.success@example.com'),
      ('weekly-note', 'DRAFTS', '이번 주 업무 공유 초안',
       '이번 주 주요 진행 상황과 다음 주 협업 요청 사항을 정리하고 있습니다.',
       1, 'Digital Workplace Team', 'dwp-team@sk.com')
    ) value(seed_key, folder_type, subject, preview, hours_ago, recipient_name, recipient_email)
)
INSERT INTO mail_threads (
    thread_id, tenant_id, account_id, folder_id, provider_thread_ref,
    subject, preview, participants, latest_message_at, unread, starred,
    importance, triage_lane, workflow_state, has_attachments,
    external_sender, classification, message_count, created_by, updated_by)
SELECT md5('mail:thread:' || folder.tenant_id || ':' || folder.owner_user_id
           || ':' || template.seed_key)::uuid,
       folder.tenant_id, folder.account_id, folder.folder_id,
       'sandbox:thread:' || folder.owner_user_id || ':' || template.seed_key,
       template.subject, template.preview,
       jsonb_build_array(jsonb_build_object(
           'name', template.recipient_name, 'email', template.recipient_email)),
       CURRENT_TIMESTAMP - template.hours_ago * INTERVAL '1 hour',
       FALSE, FALSE, 'NORMAL', 'UPDATES',
       CASE template.folder_type WHEN 'DRAFTS' THEN 'DRAFT' ELSE 'OPEN' END,
       FALSE, template.recipient_email NOT LIKE '%@sk.com', 'INTERNAL',
       1, folder.owner_user_id, folder.owner_user_id
  FROM personal_folders folder
  JOIN templates template ON template.folder_type = folder.folder_type
ON CONFLICT (account_id, provider_thread_ref) DO UPDATE SET
    subject = EXCLUDED.subject,
    preview = EXCLUDED.preview,
    participants = EXCLUDED.participants,
    latest_message_at = EXCLUDED.latest_message_at,
    workflow_state = EXCLUDED.workflow_state,
    version = mail_threads.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO mail_messages (
    message_id, tenant_id, thread_id, provider_message_ref,
    sender_email, sender_name, recipients, message_direction,
    body_format, body_content, attachments, sent_at, created_by)
SELECT md5('mail:message:' || thread.thread_id)::uuid,
       thread.tenant_id, thread.thread_id,
       'sandbox:message:' || thread.thread_id,
       CASE WHEN folder.folder_type = 'INBOX'
           THEN COALESCE(thread.participants -> 0 ->> 'email', 'sender@sk.com')
           ELSE account.email_address END,
       CASE WHEN folder.folder_type = 'INBOX'
           THEN COALESCE(thread.participants -> 0 ->> 'name', '보낸 사람')
           ELSE account.display_name END,
       CASE WHEN folder.folder_type = 'INBOX' THEN
           jsonb_build_array(jsonb_build_object(
               'email', account.email_address, 'name', account.display_name,
               'type', 'TO'))
           ELSE thread.participants END,
       CASE folder.folder_type
           WHEN 'SENT' THEN 'OUTBOUND'
           WHEN 'DRAFTS' THEN 'DRAFT'
           ELSE 'INBOUND' END,
       'TEXT',
       thread.preview || E'\n\n' || CASE
           WHEN thread.provider_thread_ref LIKE '%customer-reply' THEN
               '제안서의 보안 운영 모델, 단계별 전환 일정과 책임 구분을 중심으로 검토 의견을 부탁드립니다. 내일 오전 고객 회의 자료에 반영하겠습니다.'
           WHEN thread.provider_thread_ref LIKE '%meeting-plan' THEN
               '가능한 시간을 회신해 주시면 참석자의 기존 일정을 확인한 뒤 회의실과 온라인 링크를 함께 예약하겠습니다.'
           WHEN thread.provider_thread_ref LIKE '%leave-plan' THEN
               '팀 업무 공백을 미리 확인할 수 있도록 휴가 일정이 정해졌다면 DWP에서 신청해 주세요.'
           WHEN thread.provider_thread_ref LIKE '%security-alert' THEN
               '공유 링크의 소유자와 접근 대상을 검토한 후 연장하거나 즉시 종료할 수 있습니다.'
           WHEN thread.shared_inbox_id IS NOT NULL THEN
               '필요한 추가 정보가 있다면 알려주세요. 담당자의 답변을 기다리겠습니다.'
           ELSE '관련 문서와 다음 행동을 메일에서 바로 확인할 수 있습니다.' END,
       CASE WHEN thread.has_attachments THEN
           '[{"name":"검토자료.pdf","sizeBytes":842112,"contentType":"application/pdf"}]'::jsonb
           ELSE '[]'::jsonb END,
       thread.latest_message_at, thread.created_by
  FROM mail_threads thread
  JOIN mail_accounts account ON account.account_id = thread.account_id
  JOIN mail_folders folder ON folder.folder_id = thread.folder_id
ON CONFLICT (thread_id, provider_message_ref) DO NOTHING;

WITH proposed AS (
    SELECT thread.*, account.owner_user_id
      FROM mail_threads thread
      JOIN mail_accounts account ON account.account_id = thread.account_id
     WHERE account.account_kind = 'PERSONAL'
)
INSERT INTO mail_action_proposals (
    proposal_id, tenant_id, account_id, thread_id, proposal_type,
    proposal_status, title, summary, evidence, proposed_payload,
    confidence, risk_level, required_resource_key,
    required_permission_code, target_route, expires_at,
    created_by, updated_by)
SELECT md5('mail:proposal:' || thread.thread_id || ':' || proposal.proposal_type)::uuid,
       thread.tenant_id, thread.account_id, thread.thread_id,
       proposal.proposal_type, 'PROPOSED', proposal.title, proposal.summary,
       jsonb_build_array(jsonb_build_object(
           'messageId', 'sandbox:message:' || thread.thread_id,
           'excerpt', thread.preview,
           'observedAt', thread.latest_message_at)),
       proposal.payload::jsonb, proposal.confidence, proposal.risk_level,
       proposal.resource_key, proposal.permission_code, proposal.target_route,
       CURRENT_TIMESTAMP + INTERVAL '7 days',
       thread.owner_user_id, thread.owner_user_id
  FROM proposed thread
  JOIN (VALUES
      ('customer-reply', 'DRAFT_REPLY', '회신 초안 준비',
       '고객이 요청한 세 가지 검토 항목을 기준으로 답변 초안을 준비할 수 있습니다.',
       '{"tone":"PROFESSIONAL","language":"ko","requiresConfirmation":true}',
       0.9300, 'LOW', 'APP.MAIL', 'CREATE', '/mail/inbox'),
      ('meeting-plan', 'CREATE_CALENDAR_EVENT', '킥오프 일정 제안',
       '메일에 언급된 목요일 오후 3시를 참석자 일정과 비교해 캘린더에 제안합니다.',
       '{"durationMinutes":60,"timeZone":"Asia/Seoul","requiresConfirmation":true}',
       0.9100, 'MEDIUM', 'APP.CALENDAR', 'CREATE', '/calendar/schedule?action=create'),
      ('leave-plan', 'CREATE_LEAVE_REQUEST', '휴가 신청 준비',
       '메일 맥락을 바탕으로 휴가 신청 화면에 기간과 사유를 미리 채울 수 있습니다.',
       '{"durationDays":1,"requiresConfirmation":true}',
       0.8700, 'HIGH', 'APP.HCM', 'VIEW', '/hr/absence?request=open')
  ) proposal(
      thread_suffix, proposal_type, title, summary, payload, confidence,
      risk_level, resource_key, permission_code, target_route)
    ON thread.provider_thread_ref LIKE '%:' || proposal.thread_suffix
ON CONFLICT (proposal_id) DO NOTHING;

INSERT INTO mail_domain_events (
    domain_event_id, tenant_id, aggregate_type, aggregate_id,
    event_type, payload, correlation_id)
SELECT md5('mail:seed-event:' || account.account_id)::uuid,
       account.tenant_id, 'MAIL_ACCOUNT', account.account_id,
       'mail.account.seeded', jsonb_build_object(
           'accountId', account.account_id,
           'providerType', connection.provider_type,
           'ownerUserId', account.owner_user_id),
       'seed:skax-mail'
  FROM mail_accounts account
  JOIN mail_provider_connections connection
    ON connection.connection_id = account.connection_id
ON CONFLICT (domain_event_id) DO NOTHING;

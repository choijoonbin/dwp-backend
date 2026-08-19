CREATE TEMP TABLE tmp_mail_skax_roster (
    user_id BIGINT PRIMARY KEY,
    email_address VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_mail_skax_roster (user_id, email_address, display_name) VALUES
    (3, 'seoyeon.lee@sk.com', '이서연'),
    (4, 'hyunwoo.park@sk.com', '박현우'),
    (5, 'yujin.choi@sk.com', '최유진'),
    (8, 'doyun.kim@sk.com', '김도윤'),
    (9, 'seojin.yoon@sk.com', '윤서진'),
    (10, 'minseok.jang@sk.com', '장민석'),
    (14, 'subin.oh@sk.com', '오수빈'),
    (15, 'taehoon.kang@sk.com', '강태훈'),
    (16, 'yerin.moon@sk.com', '문예린'),
    (18, 'jiwoo.bae@sk.com', '배지우'),
    (20, 'minseo.kim@sk.com', '김민서'),
    (23, 'chaewon.kim@sk.com', '김채원'),
    (26, 'seowoo.jung@sk.com', '정서우'),
    (27, 'dohyun.lee@sk.com', '이도현'),
    (29, 'gunwoo.choi@sk.com', '최건우'),
    (31, 'jisoo.hong@sk.com', '홍지수'),
    (32, 'doyoon.nam@sk.com', '남도윤'),
    (34, 'taeyeon.kim@sk.com', '김태연'),
    (35, 'seungmin.yoo@sk.com', '유승민'),
    (36, 'james.wilson@sk.com', 'James Wilson'),
    (900018, 'joonbin@sk.com', '최준빈');

UPDATE mail_accounts account
   SET email_address = roster.email_address,
       display_name = roster.display_name,
       connection_state = 'ACTIVE',
       synchronization_state = 'READY',
       is_default = TRUE,
       version = account.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = roster.user_id
  FROM tmp_mail_skax_roster roster
 WHERE account.tenant_id = 1
   AND account.account_kind = 'PERSONAL'
   AND account.owner_user_id = roster.user_id;

WITH tenant AS (
    SELECT tenant_id
      FROM sys_service_tenants
     WHERE tenant_key = 'default'
), sandbox AS (
    SELECT connection_id, tenant_id
      FROM mail_provider_connections
     WHERE connection_key = 'dwp-sandbox'
)
INSERT INTO mail_accounts (
    account_id, tenant_id, connection_id, owner_user_id,
    email_address, display_name, account_kind,
    connection_state, synchronization_state, provider_account_ref,
    is_default, created_by, updated_by)
SELECT md5('mail:account:' || tenant.tenant_id || ':' || roster.user_id)::uuid,
       tenant.tenant_id, sandbox.connection_id, roster.user_id,
       roster.email_address, roster.display_name, 'PERSONAL',
       'ACTIVE', 'READY', 'sandbox:user:' || roster.user_id,
       TRUE, roster.user_id, roster.user_id
  FROM tenant
  JOIN sandbox ON sandbox.tenant_id = tenant.tenant_id
 CROSS JOIN tmp_mail_skax_roster roster
ON CONFLICT (tenant_id, email_address) DO UPDATE SET
    owner_user_id = EXCLUDED.owner_user_id,
    display_name = EXCLUDED.display_name,
    connection_state = 'ACTIVE',
    synchronization_state = 'READY',
    is_default = TRUE,
    version = mail_accounts.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

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
 WHERE account.tenant_id = 1
   AND account.account_kind = 'PERSONAL'
ON CONFLICT (account_id, folder_key) DO NOTHING;

CREATE TEMP TABLE tmp_mail_shared_members (
    inbox_key VARCHAR(100) NOT NULL,
    user_id BIGINT NOT NULL,
    member_role VARCHAR(20) NOT NULL,
    PRIMARY KEY (inbox_key, user_id)
) ON COMMIT DROP;

INSERT INTO tmp_mail_shared_members (inbox_key, user_id, member_role) VALUES
    ('people-help', 31, 'MANAGER'),
    ('people-help', 32, 'MEMBER'),
    ('people-help', 9, 'MEMBER'),
    ('people-help', 18, 'MEMBER'),
    ('digital-workplace', 10, 'MANAGER'),
    ('digital-workplace', 5, 'MEMBER'),
    ('digital-workplace', 23, 'MEMBER'),
    ('digital-workplace', 900018, 'MEMBER');

UPDATE mail_shared_inbox_members member
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM mail_shared_inboxes inbox
 WHERE inbox.shared_inbox_id = member.shared_inbox_id
   AND inbox.tenant_id = 1
   AND NOT EXISTS (
       SELECT 1
         FROM tmp_mail_shared_members expected
        WHERE expected.inbox_key = inbox.inbox_key
          AND expected.user_id = member.user_id);

INSERT INTO mail_shared_inbox_members (
    tenant_id, shared_inbox_id, user_id, member_role,
    lifecycle_state, created_by, updated_by)
SELECT inbox.tenant_id, inbox.shared_inbox_id, expected.user_id,
       expected.member_role, 'ACTIVE', 1, 1
  FROM mail_shared_inboxes inbox
  JOIN tmp_mail_shared_members expected ON expected.inbox_key = inbox.inbox_key
 WHERE inbox.tenant_id = 1
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
     WHERE account.tenant_id = 1
       AND account.account_kind = 'PERSONAL'
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
       CURRENT_TIMESTAMP
           - (template.hours_ago + account.owner_user_id % 4) * INTERVAL '1 hour',
       template.unread, template.seed_key = 'meeting-plan',
       template.importance, template.triage_lane, 'OPEN',
       template.has_attachments,
       template.seed_key = 'customer-reply', template.classification,
       1, account.owner_user_id, account.owner_user_id
  FROM personal_accounts account
 CROSS JOIN templates template
ON CONFLICT (account_id, provider_thread_ref) DO NOTHING;

WITH personal_folders AS (
    SELECT account.tenant_id, account.account_id, account.owner_user_id,
           folder.folder_id, folder.folder_type
      FROM mail_accounts account
      JOIN mail_folders folder ON folder.account_id = account.account_id
     WHERE account.tenant_id = 1
       AND account.account_kind = 'PERSONAL'
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
       CURRENT_TIMESTAMP
           - (template.hours_ago + folder.owner_user_id % 3) * INTERVAL '1 hour',
       FALSE, FALSE, 'NORMAL', 'UPDATES',
       CASE template.folder_type WHEN 'DRAFTS' THEN 'DRAFT' ELSE 'OPEN' END,
       FALSE, template.recipient_email NOT LIKE '%@sk.com', 'INTERNAL',
       1, folder.owner_user_id, folder.owner_user_id
  FROM personal_folders folder
  JOIN templates template ON template.folder_type = folder.folder_type
ON CONFLICT (account_id, provider_thread_ref) DO NOTHING;

UPDATE mail_threads thread
   SET assigned_user_id = assignment.user_id,
       assigned_name = assignment.display_name,
       version = thread.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = assignment.user_id
  FROM mail_shared_inboxes inbox
  JOIN (VALUES
    ('people-help', 'benefit-question', 31::BIGINT, '홍지수'),
    ('people-help', 'payroll-document', 32::BIGINT, '남도윤'),
    ('digital-workplace', 'access-help', 10::BIGINT, '장민석'),
    ('digital-workplace', 'calendar-sync', 5::BIGINT, '최유진')
  ) assignment(inbox_key, thread_key, user_id, display_name)
    ON assignment.inbox_key = inbox.inbox_key
 WHERE thread.shared_inbox_id = inbox.shared_inbox_id
   AND thread.provider_thread_ref LIKE '%:' || assignment.thread_key;

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
       'TEXT', thread.preview,
       CASE WHEN thread.has_attachments THEN
           '[{"name":"검토자료.pdf","sizeBytes":842112,"contentType":"application/pdf"}]'::jsonb
           ELSE '[]'::jsonb END,
       thread.latest_message_at, thread.created_by
  FROM mail_threads thread
  JOIN mail_accounts account ON account.account_id = thread.account_id
  JOIN mail_folders folder ON folder.folder_id = thread.folder_id
 WHERE thread.tenant_id = 1
ON CONFLICT (thread_id, provider_message_ref) DO NOTHING;

WITH proposed AS (
    SELECT thread.*, account.owner_user_id
      FROM mail_threads thread
      JOIN mail_accounts account ON account.account_id = thread.account_id
     WHERE account.tenant_id = 1
       AND account.account_kind = 'PERSONAL'
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
           'messageId', md5('mail:message:' || thread.thread_id)::uuid,
           'observedAt', thread.latest_message_at,
           'rationale', proposal.rationale,
           'excerptSha256', encode(sha256(convert_to(thread.preview, 'UTF8')), 'hex'))),
       proposal.payload::jsonb, proposal.confidence, proposal.risk_level,
       proposal.resource_key, proposal.permission_code, proposal.target_route,
       CURRENT_TIMESTAMP + INTERVAL '7 days',
       thread.owner_user_id, thread.owner_user_id
  FROM proposed thread
  JOIN (VALUES
      ('customer-reply', 'DRAFT_REPLY', '회신 초안 준비',
       '고객이 요청한 세 가지 검토 항목을 기준으로 답변 초안을 준비할 수 있습니다.',
       '고객이 명시적으로 검토 의견과 회신을 요청했습니다.',
       '{"tone":"PROFESSIONAL","language":"ko","requiresConfirmation":true}',
       0.9300, 'LOW', 'APP.MAIL', 'CREATE', '/mail/inbox'),
      ('meeting-plan', 'CREATE_CALENDAR_EVENT', '킥오프 일정 제안',
       '메일에 언급된 목요일 오후 3시를 참석자 일정과 비교해 캘린더에 제안합니다.',
       '발신자가 구체적인 회의 시각을 제안했습니다.',
       '{"durationMinutes":60,"timeZone":"Asia/Seoul","requiresConfirmation":true}',
       0.9100, 'MEDIUM', 'APP.CALENDAR', 'CREATE', '/calendar/schedule?action=create'),
      ('leave-plan', 'CREATE_LEAVE_REQUEST', '휴가 신청 준비',
       '메일 맥락을 바탕으로 휴가 신청 화면에 기간과 사유를 미리 채울 수 있습니다.',
       '조직 안내가 워크숍 다음 날 휴가 신청을 요청했습니다.',
       '{"durationDays":1,"requiresConfirmation":true}',
       0.8700, 'HIGH', 'APP.HCM', 'VIEW', '/hr/absence?request=open'),
      ('approval-followup', 'CREATE_TASK', '후속 업무 만들기',
       '계약 검토의 다음 단계를 업무로 만들고 기한을 확인할 수 있습니다.',
       '메일이 담당 업무 확인과 다음 단계 진행을 요청했습니다.',
       '{"priority":"HIGH","requiresConfirmation":true}',
       0.8900, 'MEDIUM', 'APP.WORK', 'UPDATE', '/work?action=create'),
      ('security-alert', 'ESCALATE_NOTIFICATION', '긴급 메일 알림 제안',
       '민감 자료의 외부 공유 링크가 곧 만료되어 즉시 확인 알림을 제안합니다.',
       '제한 등급 메일에 오늘 만료되는 외부 공유 링크가 포함되었습니다.',
       '{"channel":"IN_APP","urgency":"URGENT","requiresConfirmation":true}',
       0.9600, 'LOW', 'APP.MAIL', 'UPDATE', '/mail/inbox')
  ) proposal(
      thread_suffix, proposal_type, title, summary, rationale, payload, confidence,
      risk_level, resource_key, permission_code, target_route)
    ON thread.provider_thread_ref LIKE '%:' || proposal.thread_suffix
ON CONFLICT (proposal_id) DO UPDATE SET
    title = EXCLUDED.title,
    summary = EXCLUDED.summary,
    evidence = EXCLUDED.evidence,
    proposed_payload = EXCLUDED.proposed_payload,
    confidence = EXCLUDED.confidence,
    risk_level = EXCLUDED.risk_level,
    required_resource_key = EXCLUDED.required_resource_key,
    required_permission_code = EXCLUDED.required_permission_code,
    target_route = EXCLUDED.target_route,
    expires_at = EXCLUDED.expires_at,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO mail_domain_events (
    domain_event_id, tenant_id, aggregate_type, aggregate_id,
    event_type, payload, correlation_id)
SELECT md5('mail:seed-event:' || account.account_id)::uuid,
       account.tenant_id, 'MAIL_ACCOUNT', account.account_id,
       'mail.account.seeded', jsonb_build_object(
           'accountId', account.account_id,
           'providerType', connection.provider_type,
           'ownerUserId', account.owner_user_id),
       'seed:skax-mail-v124'
  FROM mail_accounts account
  JOIN mail_provider_connections connection
    ON connection.connection_id = account.connection_id
 WHERE account.tenant_id = 1
ON CONFLICT (domain_event_id) DO NOTHING;

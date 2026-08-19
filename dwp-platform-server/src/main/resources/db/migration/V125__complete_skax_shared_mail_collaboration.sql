WITH shared_accounts AS (
    SELECT account.tenant_id, account.account_id, folder.folder_id,
           inbox.shared_inbox_id, inbox.inbox_key
      FROM mail_accounts account
      JOIN mail_folders folder
        ON folder.account_id = account.account_id
       AND folder.folder_key = 'inbox'
      JOIN mail_shared_inboxes inbox ON inbox.account_id = account.account_id
     WHERE account.tenant_id = 1
), templates AS (
    SELECT * FROM (VALUES
      ('benefit-question', 'people-help', '가족 의료비 지원 대상 문의',
       '신규 등록한 가족도 이번 달 의료비 지원 대상에 포함되는지 확인 부탁드립니다.',
       2, 31::BIGINT, '홍지수'),
      ('payroll-document', 'people-help', '급여 증빙 발급 일정 문의',
       '해외 비자 제출용 영문 급여 증빙을 금요일까지 받을 수 있을까요?',
       7, 32::BIGINT, '남도윤'),
      ('access-help', 'digital-workplace', '프로젝트 워크스페이스 접근 요청',
       '고객 프로젝트 워크스페이스가 목록에 보이지 않습니다. 접근 상태를 확인해 주세요.',
       4, 10::BIGINT, '장민석'),
      ('calendar-sync', 'digital-workplace', '모바일 캘린더 동기화 지연',
       '휴대폰 캘린더에 오늘 일정이 늦게 표시됩니다. 계정 동기화 상태를 확인해 주세요.',
       11, 5::BIGINT, '최유진')
    ) value(
        seed_key, inbox_key, subject, preview, hours_ago,
        assigned_user_id, assigned_name)
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
       'ASSIGNED', 'OPEN', template.assigned_user_id, template.assigned_name,
       FALSE, 'INTERNAL', 1, template.assigned_user_id, template.assigned_user_id
  FROM templates template
  JOIN shared_accounts shared ON shared.inbox_key = template.inbox_key
ON CONFLICT (account_id, provider_thread_ref) DO UPDATE SET
    subject = EXCLUDED.subject,
    preview = EXCLUDED.preview,
    assigned_user_id = EXCLUDED.assigned_user_id,
    assigned_name = EXCLUDED.assigned_name,
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
       'member.request@sk.com', 'SKAX 구성원',
       jsonb_build_array(jsonb_build_object(
           'email', account.email_address, 'name', account.display_name,
           'type', 'TO')),
       'INBOUND', 'TEXT',
       thread.preview || E'\n\n필요한 추가 정보가 있다면 알려주세요. 담당자의 답변을 기다리겠습니다.',
       '[]'::jsonb, thread.latest_message_at, thread.created_by
  FROM mail_threads thread
  JOIN mail_accounts account ON account.account_id = thread.account_id
 WHERE thread.tenant_id = 1
   AND thread.shared_inbox_id IS NOT NULL
ON CONFLICT (thread_id, provider_message_ref) DO NOTHING;

INSERT INTO mail_internal_comments (
    comment_id, tenant_id, thread_id, author_user_id,
    author_name, body, mentioned_user_ids)
SELECT md5('mail:comment:' || thread.thread_id || ':triage')::uuid,
       thread.tenant_id, thread.thread_id, thread.assigned_user_id,
       thread.assigned_name,
       CASE
           WHEN inbox.inbox_key = 'people-help'
               THEN '인사 기준과 발급 일정을 확인한 뒤 오늘 중 답변하겠습니다.'
           ELSE '계정 및 동기화 상태를 먼저 점검하고 처리 결과를 공유하겠습니다.'
       END,
       jsonb_build_array(thread.assigned_user_id)
  FROM mail_threads thread
  JOIN mail_shared_inboxes inbox ON inbox.shared_inbox_id = thread.shared_inbox_id
 WHERE thread.tenant_id = 1
   AND thread.assigned_user_id IS NOT NULL
ON CONFLICT (comment_id) DO NOTHING;

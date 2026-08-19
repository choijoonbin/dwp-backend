INSERT INTO msg_tenant_policies (
    tenant_id, direct_messages_enabled, space_messaging_enabled,
    allow_message_edit, allow_message_delete, ai_assistance_enabled,
    retention_days, maximum_attachment_mb, created_by, updated_by)
VALUES (1, TRUE, TRUE, TRUE, TRUE, TRUE, 1095, 100, 1, 1)
ON CONFLICT (tenant_id) DO UPDATE SET
    direct_messages_enabled = EXCLUDED.direct_messages_enabled,
    space_messaging_enabled = EXCLUDED.space_messaging_enabled,
    allow_message_edit = EXCLUDED.allow_message_edit,
    allow_message_delete = EXCLUDED.allow_message_delete,
    ai_assistance_enabled = EXCLUDED.ai_assistance_enabled,
    retention_days = EXCLUDED.retention_days,
    maximum_attachment_mb = EXCLUDED.maximum_attachment_mb,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO msg_people_snapshot (
    tenant_id, user_id, person_public_id, email_address, display_name,
    job_title, organization_name, presence_state, lifecycle_state)
VALUES
    (1, 3, '34f5e51a-2ca6-c6f6-6627-b44f08f31d1d'::uuid, 'seoyeon.lee@sk.com', '이서연', 'Employee Experience Lead', 'People Experience', 'AVAILABLE', 'ACTIVE'),
    (1, 4, '5af80da3-0dd8-b3bc-2f44-22d90eecaac4'::uuid, 'hyunwoo.park@sk.com', '박현우', 'Company administrator', 'Digital Platform', 'BUSY', 'ACTIVE'),
    (1, 5, '00ba0853-02a8-7499-b6d8-009251e6a464'::uuid, 'yujin.choi@sk.com', '최유진', 'Space Governance Lead', 'Platform Governance', 'FOCUS', 'ACTIVE'),
    (1, 8, '57d240ea-bc2c-a562-d4cf-03448dec4d22'::uuid, 'doyun.kim@sk.com', '김도윤', 'Service Desk Manager', 'Shared Services', 'AVAILABLE', 'ACTIVE'),
    (1, 9, 'e96089af-ead6-2f6a-6111-1d2e15058b1d'::uuid, 'seojin.yoon@sk.com', '윤서진', 'People Operations Manager', 'People Operations', 'AVAILABLE', 'ACTIVE'),
    (1, 10, 'bda29b83-7a8f-ded4-083b-244f055bd6c4'::uuid, 'minseok.jang@sk.com', '장민석', 'Mail Platform Admin', 'Digital Workplace', 'AWAY', 'ACTIVE'),
    (1, 14, '71ed1904-1405-e7ce-3f27-0845298ba1e2'::uuid, 'subin.oh@sk.com', '오수빈', 'Access Manager', 'Security Operations', 'AVAILABLE', 'ACTIVE'),
    (1, 15, '94d55a4f-96de-09fd-5454-bbd64b60ccb3'::uuid, 'taehoon.kang@sk.com', '강태훈', 'Access Approver', 'Security Operations', 'BUSY', 'ACTIVE'),
    (1, 16, '457477f1-ee4a-9b12-3668-ec7663989ee5'::uuid, 'yerin.moon@sk.com', '문예린', 'Access Reviewer', 'Risk & Compliance', 'AVAILABLE', 'ACTIVE'),
    (1, 18, 'a3e07946-57b1-4441-ae00-d14ad9eb284c'::uuid, 'jiwoo.bae@sk.com', '배지우', 'Service Agent', 'Shared Services', 'AVAILABLE', 'ACTIVE'),
    (1, 20, '3edde887-9716-8950-e7a0-045998101987'::uuid, 'minseo.kim@sk.com', '김민서', 'Network Operations Lead', 'Network Operations', 'FOCUS', 'ACTIVE'),
    (1, 23, 'd4bc013d-8c7a-fbcb-be2a-7d83286e0b18'::uuid, 'chaewon.kim@sk.com', '김채원', 'Product Designer', 'Digital Workplace', 'AVAILABLE', 'ACTIVE'),
    (1, 26, '6edd429e-6650-00a3-d68a-2bd4cc954551'::uuid, 'seowoo.jung@sk.com', '정서우', 'Frontend Engineer', 'Digital Workplace', 'AVAILABLE', 'ACTIVE'),
    (1, 27, '63aedd96-0fe7-afbc-a2e4-3d18c53708f2'::uuid, 'dohyun.lee@sk.com', '이도현', 'Backend Engineer', 'Digital Workplace', 'BUSY', 'ACTIVE'),
    (1, 29, '0a1400f8-c80e-e06a-263a-ae18528c1a58'::uuid, 'gunwoo.choi@sk.com', '최건우', 'Communications Editor', 'Corporate Communications', 'AVAILABLE', 'ACTIVE'),
    (1, 900018, '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'::uuid, 'joonbin@sk.com', '최준빈', 'SKAX integrated verification administrator', 'Tenant Control Plane', 'AVAILABLE', 'ACTIVE')
ON CONFLICT (tenant_id, user_id) DO UPDATE SET
    person_public_id = EXCLUDED.person_public_id,
    email_address = EXCLUDED.email_address,
    display_name = EXCLUDED.display_name,
    job_title = EXCLUDED.job_title,
    organization_name = EXCLUDED.organization_name,
    presence_state = EXCLUDED.presence_state,
    lifecycle_state = EXCLUDED.lifecycle_state,
    updated_at = CURRENT_TIMESTAMP;

WITH seed AS (
    SELECT *
      FROM (VALUES
        ('channel:dwp-product-room', 'CHANNEL', 'DWP Product Room',
         '홈, 메일, 캘린더, Space가 만나는 제품 경험을 빠르게 결정합니다.',
         'PRIVATE', 'CONFIDENTIAL', NULL, NULL, 900018),
        ('channel:operations-bridge', 'INCIDENT', 'Operations Bridge',
         '장애, 접근 정책, 운영 알림을 짧고 명확하게 처리합니다.',
         'PRIVATE', 'RESTRICTED', NULL, NULL, 4),
        ('space:ai-governance', 'CHANNEL', 'AI Governance Space',
         '통제된 AI 사용 정책과 심사 근거를 Space 문맥에서 논의합니다.',
         'SPACE', 'CONFIDENTIAL', 'ai-governance', 'AI Governance', 5),
        ('space:employee-experience', 'CHANNEL', 'Employee Experience Space',
         '구성원 경험, 가이드, 온보딩 개선을 People/Service와 연결합니다.',
         'SPACE', 'INTERNAL', 'employee-experience', 'Employee Experience', 3),
        ('announcement:dwp-news', 'ANNOUNCEMENT', 'DWP 운영 공지',
         '필수 확인 공지와 서비스 변경 사항을 전달합니다.',
         'ANNOUNCEMENT', 'INTERNAL', NULL, NULL, 29)
      ) value(
        conversation_key, conversation_type, name, topic, visibility,
        data_classification, linked_space_key, linked_space_name, created_by)
)
INSERT INTO msg_conversations (
    conversation_id, tenant_id, conversation_key, conversation_type, name, topic,
    visibility, data_classification, linked_space_key, linked_space_name,
    lifecycle_state, last_message_at, created_by, updated_by)
SELECT md5('msg:conversation:1:' || seed.conversation_key)::uuid,
       1, seed.conversation_key, seed.conversation_type, seed.name, seed.topic,
       seed.visibility, seed.data_classification, seed.linked_space_key,
       seed.linked_space_name, 'ACTIVE',
       CURRENT_TIMESTAMP - interval '20 minutes',
       seed.created_by, seed.created_by
  FROM seed
ON CONFLICT (tenant_id, conversation_key) DO UPDATE SET
    name = EXCLUDED.name,
    topic = EXCLUDED.topic,
    visibility = EXCLUDED.visibility,
    data_classification = EXCLUDED.data_classification,
    linked_space_key = EXCLUDED.linked_space_key,
    linked_space_name = EXCLUDED.linked_space_name,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

WITH memberships AS (
    SELECT *
      FROM (VALUES
        ('channel:dwp-product-room', 900018, 'OWNER', 'DIRECT', TRUE, TRUE),
        ('channel:dwp-product-room', 4, 'OWNER', 'DIRECT', TRUE, FALSE),
        ('channel:dwp-product-room', 10, 'MODERATOR', 'DIRECT', FALSE, FALSE),
        ('channel:dwp-product-room', 23, 'MEMBER', 'DIRECT', FALSE, FALSE),
        ('channel:dwp-product-room', 26, 'MEMBER', 'DIRECT', FALSE, FALSE),
        ('channel:dwp-product-room', 27, 'MEMBER', 'DIRECT', FALSE, FALSE),
        ('channel:operations-bridge', 900018, 'OWNER', 'DIRECT', TRUE, TRUE),
        ('channel:operations-bridge', 4, 'OWNER', 'DIRECT', TRUE, TRUE),
        ('channel:operations-bridge', 14, 'MODERATOR', 'DIRECT', FALSE, FALSE),
        ('channel:operations-bridge', 15, 'MEMBER', 'DIRECT', FALSE, FALSE),
        ('channel:operations-bridge', 16, 'MEMBER', 'DIRECT', FALSE, FALSE),
        ('space:ai-governance', 900018, 'OWNER', 'SPACE_MIRRORED', TRUE, FALSE),
        ('space:ai-governance', 5, 'OWNER', 'SPACE_MIRRORED', TRUE, FALSE),
        ('space:ai-governance', 10, 'MEMBER', 'SPACE_MIRRORED', FALSE, FALSE),
        ('space:ai-governance', 14, 'MEMBER', 'SPACE_MIRRORED', FALSE, FALSE),
        ('space:employee-experience', 3, 'OWNER', 'SPACE_MIRRORED', TRUE, FALSE),
        ('space:employee-experience', 8, 'MEMBER', 'SPACE_MIRRORED', FALSE, FALSE),
        ('space:employee-experience', 9, 'MEMBER', 'SPACE_MIRRORED', FALSE, FALSE),
        ('space:employee-experience', 18, 'MEMBER', 'SPACE_MIRRORED', FALSE, FALSE),
        ('space:employee-experience', 20, 'MEMBER', 'SPACE_MIRRORED', FALSE, FALSE),
        ('announcement:dwp-news', 900018, 'OWNER', 'SYSTEM', TRUE, FALSE),
        ('announcement:dwp-news', 29, 'MODERATOR', 'SYSTEM', TRUE, FALSE),
        ('announcement:dwp-news', 3, 'VIEWER', 'SYSTEM', FALSE, FALSE),
        ('announcement:dwp-news', 4, 'VIEWER', 'SYSTEM', FALSE, FALSE),
        ('announcement:dwp-news', 5, 'VIEWER', 'SYSTEM', FALSE, FALSE),
        ('announcement:dwp-news', 20, 'VIEWER', 'SYSTEM', FALSE, FALSE)
      ) value(conversation_key, user_id, member_role, membership_source, favorite, pinned)
)
INSERT INTO msg_conversation_members (
    tenant_id, conversation_id, user_id, person_public_id, member_role,
    membership_source, notification_level, favorite, pinned, lifecycle_state,
    created_by, updated_by)
SELECT 1, conversation.conversation_id, memberships.user_id,
       person.person_public_id, memberships.member_role,
       memberships.membership_source, 'DEFAULT', memberships.favorite,
       memberships.pinned, 'ACTIVE', memberships.user_id, memberships.user_id
  FROM memberships
  JOIN msg_conversations conversation
    ON conversation.tenant_id = 1
   AND conversation.conversation_key = memberships.conversation_key
  JOIN msg_people_snapshot person
    ON person.tenant_id = 1
   AND person.user_id = memberships.user_id
ON CONFLICT (tenant_id, conversation_id, user_id) DO UPDATE SET
    person_public_id = EXCLUDED.person_public_id,
    member_role = EXCLUDED.member_role,
    membership_source = EXCLUDED.membership_source,
    favorite = EXCLUDED.favorite,
    pinned = EXCLUDED.pinned,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

WITH direct_pairs AS (
    SELECT * FROM (VALUES
        (900018, 4, '최준빈 / 박현우'),
        (900018, 20, '최준빈 / 김민서'),
        (4, 10, '박현우 / 장민석'),
        (20, 14, '김민서 / 오수빈')
    ) value(left_user_id, right_user_id, name)
), created AS (
    INSERT INTO msg_conversations (
        conversation_id, tenant_id, conversation_key, conversation_type, name,
        topic, visibility, data_classification, lifecycle_state,
        last_message_at, created_by, updated_by)
    SELECT md5('msg:dm:1:' || LEAST(left_user_id, right_user_id) || ':' ||
               GREATEST(left_user_id, right_user_id))::uuid,
           1,
           'dm:' || LEAST(left_user_id, right_user_id) || ':' ||
           GREATEST(left_user_id, right_user_id),
           'DIRECT', name, '1:1 업무 대화', 'PRIVATE', 'INTERNAL',
           'ACTIVE', CURRENT_TIMESTAMP - interval '10 minutes',
           left_user_id, left_user_id
      FROM direct_pairs
    ON CONFLICT (tenant_id, conversation_key) DO UPDATE SET
        name = EXCLUDED.name,
        lifecycle_state = 'ACTIVE',
        updated_at = CURRENT_TIMESTAMP,
        updated_by = EXCLUDED.updated_by
    RETURNING conversation_id, conversation_key
), members AS (
    SELECT 'dm:' || LEAST(left_user_id, right_user_id) || ':' ||
           GREATEST(left_user_id, right_user_id) AS conversation_key,
           left_user_id AS user_id
      FROM direct_pairs
    UNION ALL
    SELECT 'dm:' || LEAST(left_user_id, right_user_id) || ':' ||
           GREATEST(left_user_id, right_user_id) AS conversation_key,
           right_user_id AS user_id
      FROM direct_pairs
)
INSERT INTO msg_conversation_members (
    tenant_id, conversation_id, user_id, person_public_id, member_role,
    membership_source, notification_level, favorite, pinned, lifecycle_state,
    created_by, updated_by)
SELECT 1, conversation.conversation_id, members.user_id, person.person_public_id,
       'MEMBER', 'DIRECT', 'DEFAULT', FALSE, FALSE, 'ACTIVE',
       members.user_id, members.user_id
  FROM members
  JOIN msg_conversations conversation
    ON conversation.tenant_id = 1
   AND conversation.conversation_key = members.conversation_key
  JOIN msg_people_snapshot person
    ON person.tenant_id = 1
   AND person.user_id = members.user_id
ON CONFLICT (tenant_id, conversation_id, user_id) DO UPDATE SET
    person_public_id = EXCLUDED.person_public_id,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

WITH message_seed AS (
    SELECT *
      FROM (VALUES
        ('channel:dwp-product-room', 'm1', 900018, '오늘 홈 편집 모드 흔들림 효과는 앱 아이콘과 위젯이 같은 방향으로 움직이지 않도록 감쇠값을 분리해서 검증하겠습니다.', 120),
        ('channel:dwp-product-room', 'm2', 23, '위젯은 전체 카드가 흔들리기보다 상단 핸들과 그림자만 반응하는 편이 더 고급스럽습니다.', 96),
        ('channel:dwp-product-room', 'm3', 26, '프론트에서는 모션 토큰을 공통화하고 reduce motion에서도 위치가 튀지 않게 처리하겠습니다.', 72),
        ('channel:operations-bridge', 'm1', 4, '접근 정책 변경 후 세션 무효화와 메시지 권한 반영 시간은 15초 목표로 보겠습니다.', 110),
        ('channel:operations-bridge', 'm2', 14, '권한 부여 화면은 테넌트 관리자와 기능 관리자 범위를 분리해야 합니다. 메신저 관리자도 본문 열람 권한은 주지 않겠습니다.', 74),
        ('space:ai-governance', 'm1', 5, 'AI가 메일과 메신저를 분석하더라도 실행은 사용자 승인 후 대상 앱에서 수행하는 원칙을 유지합니다.', 88),
        ('space:ai-governance', 'm2', 10, '메일 회신 초안, 회의 일정 제안, 휴가 신청 제안은 메시지 액션 카드로 연결할 수 있습니다.', 42),
        ('space:employee-experience', 'm1', 3, '이용 가이드와 담당자 연락처는 오른쪽 레일의 접이식 센터로 합치면 홈 화면을 밀지 않고 확장할 수 있습니다.', 64),
        ('space:employee-experience', 'm2', 20, '일반 구성원 기준으로는 “지금 해야 할 일”과 “도움 받을 곳”이 먼저 보여야 합니다.', 31),
        ('announcement:dwp-news', 'm1', 29, 'DWP 메신저 베타 채널이 열렸습니다. Space와 조직 검색에서 바로 대화를 시작할 수 있습니다.', 55),
        ('dm:4:900018', 'm1', 4, '최준빈님, 메신저 R1은 Space 연동 채널과 관리자 정책 화면까지 같이 확인 부탁드립니다.', 29),
        ('dm:4:900018', 'm2', 900018, '확인했습니다. 실제 SKAX 구성원 데이터 기준으로 홈에서 바로 진입되는지 보겠습니다.', 15),
        ('dm:20:900018', 'm1', 20, '네트워크 운영팀 쪽 알림은 급한 메시지로 분류되도록 이후 AI 규칙에 연결하면 좋겠습니다.', 18),
        ('dm:4:10', 'm1', 10, '메일 관리자 관점에서 메신저 알림과 메일 후속 조치가 중복되지 않게 정책을 정리하겠습니다.', 25),
        ('dm:14:20', 'm1', 14, '접근 권한 변경이 대화방 멤버십에도 바로 반영되는지 테스트 케이스를 잡겠습니다.', 20)
      ) value(conversation_key, message_key, sender_user_id, body, minutes_ago)
)
INSERT INTO msg_messages (
    message_id, tenant_id, conversation_id, sender_user_id,
    sender_person_public_id, sender_name, body, content_type, message_kind,
    created_at)
SELECT md5('msg:message:1:' || seed.conversation_key || ':' || seed.message_key)::uuid,
       1, conversation.conversation_id, seed.sender_user_id,
       person.person_public_id, person.display_name, seed.body,
       'TEXT', 'USER', CURRENT_TIMESTAMP - seed.minutes_ago * INTERVAL '1 minute'
  FROM message_seed seed
  JOIN msg_conversations conversation
    ON conversation.tenant_id = 1
   AND conversation.conversation_key = seed.conversation_key
  JOIN msg_people_snapshot person
    ON person.tenant_id = 1
   AND person.user_id = seed.sender_user_id
ON CONFLICT (message_id) DO UPDATE SET
    body = EXCLUDED.body,
    sender_name = EXCLUDED.sender_name,
    created_at = EXCLUDED.created_at;

UPDATE msg_conversations conversation
   SET last_message_id = latest.message_id,
       last_message_at = latest.created_at,
       updated_at = CURRENT_TIMESTAMP
  FROM (
      SELECT DISTINCT ON (message.conversation_id)
             message.conversation_id, message.message_id, message.created_at
        FROM msg_messages message
       WHERE message.tenant_id = 1
       ORDER BY message.conversation_id, message.created_at DESC, message.message_id DESC
  ) latest
 WHERE conversation.tenant_id = 1
   AND conversation.conversation_id = latest.conversation_id;

WITH readable AS (
    SELECT member.tenant_id, member.conversation_id, member.user_id,
           conversation.last_message_id, conversation.last_message_at
      FROM msg_conversation_members member
      JOIN msg_conversations conversation
        ON conversation.conversation_id = member.conversation_id
       AND conversation.tenant_id = member.tenant_id
     WHERE member.tenant_id = 1
       AND member.lifecycle_state = 'ACTIVE'
       AND member.user_id IN (4, 10, 14)
)
UPDATE msg_conversation_members member
   SET last_read_message_id = readable.last_message_id,
       last_read_at = readable.last_message_at,
       updated_at = CURRENT_TIMESTAMP
  FROM readable
 WHERE member.tenant_id = readable.tenant_id
   AND member.conversation_id = readable.conversation_id
   AND member.user_id = readable.user_id;

INSERT INTO msg_message_reactions (tenant_id, message_id, user_id, emoji)
SELECT 1, message.message_id, reaction.user_id, reaction.emoji
  FROM (VALUES
    ('channel:dwp-product-room', 'm2', 900018, '👍'),
    ('channel:dwp-product-room', 'm3', 23, '✨'),
    ('space:ai-governance', 'm1', 900018, '🛡'),
    ('dm:4:900018', 'm1', 900018, '✅')
  ) reaction(conversation_key, message_key, user_id, emoji)
  JOIN msg_messages message
    ON message.message_id =
       md5('msg:message:1:' || reaction.conversation_key || ':' || reaction.message_key)::uuid
ON CONFLICT (tenant_id, message_id, user_id, emoji) DO NOTHING;

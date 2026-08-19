-- Local SKAX development projection. Runtime delivery keeps this table aligned from
-- People/Auth lifecycle events; the migration makes existing developer databases
-- converge to the same stable person identifiers without cross-database queries.
WITH workforce (
    user_id, person_public_id, email_address, display_name,
    job_title, organization_name, presence_state
) AS (
    VALUES
        (3, '34f5e51a-2ca6-c6f6-6627-b44f08f31d1d'::uuid, 'seoyeon.lee@sk.com', '이서연', 'Executive Strategy Officer', 'CEO Staff', 'AVAILABLE'),
        (4, '5af80da3-0dd8-b3bc-2f44-22d90eecaac4'::uuid, 'hyunwoo.park@sk.com', '박현우', 'Digital Platform 부문장', 'Digital Platform 부문', 'AVAILABLE'),
        (5, '00ba0853-02a8-7499-b6d8-009251e6a464'::uuid, 'yujin.choi@sk.com', '최유진', 'Enterprise Transformation 부문장', 'Enterprise Transformation 부문', 'AVAILABLE'),
        (8, '306b543f-741f-6fd3-36bf-48325f3e7e20'::uuid, 'doyun.kim@sk.com', '김도윤', 'AI Platform 본부장', 'AI Platform 본부', 'AVAILABLE'),
        (9, 'e96089af-ead6-2f6a-6111-1d2e15058b1d'::uuid, 'seojin.yoon@sk.com', '윤서진', 'Cloud & Infra 본부장', 'Cloud & Infra 본부', 'AVAILABLE'),
        (10, 'bda29b83-7a8f-ded4-083b-244f055bd6c4'::uuid, 'minseok.jang@sk.com', '장민석', 'GenAI Engineering 팀장', 'GenAI Engineering 팀', 'AVAILABLE'),
        (14, '71ed1904-1405-e7ce-3f27-0845298ba1e2'::uuid, 'subin.oh@sk.com', '오수빈', 'Data Platform 팀장', 'Data Platform 팀', 'AVAILABLE'),
        (15, '94d55a4f-96de-09fd-5454-bbd64b60ccb3'::uuid, 'taehoon.kang@sk.com', '강태훈', 'Data Architect', 'Data Platform 팀', 'AVAILABLE'),
        (16, '457477f1-ee4a-9b12-3668-ec7663989ee5'::uuid, 'yerin.moon@sk.com', '문예린', 'Analytics Engineer', 'Data Platform 팀', 'AVAILABLE'),
        (18, 'a3e07946-57b1-4441-ae00-d14ad9eb284c'::uuid, 'jiwoo.bae@sk.com', '배지우', 'Site Reliability Engineer', 'Cloud Platform 팀', 'AVAILABLE'),
        (20, '3edde887-9716-8950-e7a0-045998101987'::uuid, 'minseo.kim@sk.com', '김민서', 'Network Operations Lead', 'Network Operations 팀', 'AVAILABLE'),
        (23, 'd4bc013d-8c7a-fbcb-be2a-7d83286e0b18'::uuid, 'chaewon.kim@sk.com', '김채원', 'SAP Transformation Consultant', 'ERP Innovation 본부', 'AVAILABLE'),
        (26, '6edd429e-6650-00a3-d68a-2bd4cc954551'::uuid, 'seowoo.jung@sk.com', '정서우', 'Business Consultant', 'Digital Consulting 본부', 'AVAILABLE'),
        (27, 'd1b648ab-318d-824d-50f6-11c418b75f9a'::uuid, 'dohyun.lee@sk.com', '이도현', 'Change Management Lead', 'Digital Consulting 본부', 'AVAILABLE'),
        (29, '6625e4a8-eaa9-c5d7-20bc-47f2029677b3'::uuid, 'gunwoo.choi@sk.com', '최건우', 'UX Strategist', 'Customer Experience 팀', 'AVAILABLE'),
        (31, '073c6aef-f778-94ac-4bb3-0e355fa41dbc'::uuid, 'jisoo.hong@sk.com', '홍지수', 'People & Culture 팀장', 'People & Culture 팀', 'AVAILABLE'),
        (32, '3490c134-c01b-d32d-eda2-f257c94496f2'::uuid, 'doyoon.nam@sk.com', '남도윤', 'HR Business Partner', 'People & Culture 팀', 'AWAY'),
        (34, '6dddb2e7-e311-0455-2c15-55d1ff0e2379'::uuid, 'taeyeon.kim@sk.com', '김태연', 'Finance & Risk 팀장', 'Finance & Risk 팀', 'AVAILABLE'),
        (35, 'cc4804fd-f65a-998f-b162-4c2d594ec767'::uuid, 'seungmin.yoo@sk.com', '유승민', 'Financial Controller', 'Finance & Risk 팀', 'AVAILABLE'),
        (36, 'aaf32653-4578-46a9-c679-7302615e84cc'::uuid, 'james.wilson@sk.com', 'James Wilson', 'Risk Analyst', 'Finance & Risk 팀', 'AVAILABLE'),
        (900018, '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'::uuid, 'joonbin@sk.com', '최준빈', 'SKAX integrated verification administrator', 'Tenant Control Plane', 'AVAILABLE')
)
INSERT INTO msg_people_snapshot (
    tenant_id, user_id, person_public_id, email_address, display_name,
    job_title, organization_name, presence_state, lifecycle_state
)
SELECT 1, user_id, person_public_id, email_address, display_name,
       job_title, organization_name, presence_state, 'ACTIVE'
  FROM workforce
ON CONFLICT (tenant_id, user_id) DO UPDATE SET
    person_public_id = EXCLUDED.person_public_id,
    email_address = EXCLUDED.email_address,
    display_name = EXCLUDED.display_name,
    job_title = EXCLUDED.job_title,
    organization_name = EXCLUDED.organization_name,
    presence_state = EXCLUDED.presence_state,
    lifecycle_state = EXCLUDED.lifecycle_state,
    updated_at = CURRENT_TIMESTAMP;

-- A tenant-wide announcement is the safe initial landing conversation for every
-- active workforce account. Posting remains restricted to owners/moderators.
INSERT INTO msg_conversation_members (
    tenant_id, conversation_id, user_id, person_public_id, member_role,
    membership_source, notification_level, favorite, pinned, lifecycle_state,
    created_by, updated_by
)
SELECT person.tenant_id, conversation.conversation_id, person.user_id,
       person.person_public_id,
       CASE
           WHEN person.user_id = 900018 THEN 'OWNER'
           WHEN person.user_id = 29 THEN 'MODERATOR'
           ELSE 'VIEWER'
       END,
       'SYSTEM', 'DEFAULT', FALSE, FALSE, 'ACTIVE',
       900018, 900018
  FROM msg_people_snapshot person
  JOIN msg_conversations conversation
    ON conversation.tenant_id = person.tenant_id
   AND conversation.conversation_key = 'announcement:dwp-news'
 WHERE person.tenant_id = 1
   AND person.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, conversation_id, user_id) DO UPDATE SET
    person_public_id = EXCLUDED.person_public_id,
    member_role = EXCLUDED.member_role,
    membership_source = EXCLUDED.membership_source,
    lifecycle_state = EXCLUDED.lifecycle_state,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

-- Existing memberships must follow the authoritative person identifier as well.
UPDATE msg_conversation_members member
   SET person_public_id = person.person_public_id,
       updated_at = CURRENT_TIMESTAMP
  FROM msg_people_snapshot person
 WHERE member.tenant_id = person.tenant_id
   AND member.user_id = person.user_id
   AND member.person_public_id IS DISTINCT FROM person.person_public_id;

UPDATE msg_messages message
   SET sender_person_public_id = person.person_public_id,
       sender_name = person.display_name,
       version = message.version + 1
  FROM msg_people_snapshot person
 WHERE message.tenant_id = person.tenant_id
   AND message.sender_user_id = person.user_id
   AND (message.sender_person_public_id IS DISTINCT FROM person.person_public_id
        OR message.sender_name IS DISTINCT FROM person.display_name);

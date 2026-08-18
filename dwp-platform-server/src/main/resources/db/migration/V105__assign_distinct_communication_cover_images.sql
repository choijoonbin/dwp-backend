-- Every active employee communication needs a distinct, topic-specific cover so readers can
-- recognize stories before reading the title. V104 is already released, so image corrections
-- are applied forward without changing its Flyway checksum.
WITH cover_mapping (title, cover_image_url) AS (
    VALUES
        (
            '리더십 타운홀 다시보기와 질문 모음',
            '/media/communications/leadership-townhall.png'
        ),
        (
            '사내 멘토링 커넥트, 두 번째 시즌을 시작합니다',
            '/media/communications/mentoring-connect.png'
        ),
        (
            '이번 주 DWP 업데이트: 더 빠른 업무 흐름',
            '/media/communications/dwp-product-update.png'
        ),
        (
            'AI 업무 활용 클리닉, 실전 질문을 받습니다',
            '/media/communications/ai-work-clinic.png'
        ),
        (
            '고객 성공 사례: 프로젝트 인사이트를 공유합니다',
            '/media/communications/customer-success-insights.png'
        ),
        (
            '사내 기술 커뮤니티 오픈 데이',
            '/media/communications/engineering-open-day.png'
        ),
        (
            '8월 웰니스 챌린지 참여 안내',
            '/media/communications/wellness-challenge.png'
        ),
        (
            '업무 공간 개선 제안 결과를 공개합니다',
            '/media/communications/workplace-improvement.png'
        )
)
UPDATE adm_announcements announcement
   SET cover_image_url = cover_mapping.cover_image_url,
       version = announcement.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM cover_mapping
 WHERE announcement.title = cover_mapping.title
   AND announcement.cover_image_url IS DISTINCT FROM cover_mapping.cover_image_url;

DO $$
BEGIN
    IF EXISTS (
        SELECT announcement.cover_image_url
          FROM adm_announcements announcement
         WHERE announcement.lifecycle_state = 'PUBLISHED'
           AND (announcement.starts_at IS NULL OR announcement.starts_at <= CURRENT_TIMESTAMP)
           AND (announcement.ends_at IS NULL OR announcement.ends_at > CURRENT_TIMESTAMP)
         GROUP BY announcement.tenant_id, announcement.cover_image_url
        HAVING announcement.cover_image_url IS NULL OR COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Active communications must have distinct cover images per tenant';
    END IF;
END
$$;

-- Keep generated editorial covers lightweight enough for the employee home feed.
-- V105 introduced the distinct PNG assets; this forward migration points records to
-- visually equivalent, optimized JPEG derivatives without changing released history.
WITH cover_mapping (png_url, jpg_url) AS (
    VALUES
        (
            '/media/communications/leadership-townhall.png',
            '/media/communications/leadership-townhall.jpg'
        ),
        (
            '/media/communications/mentoring-connect.png',
            '/media/communications/mentoring-connect.jpg'
        ),
        (
            '/media/communications/dwp-product-update.png',
            '/media/communications/dwp-product-update.jpg'
        ),
        (
            '/media/communications/ai-work-clinic.png',
            '/media/communications/ai-work-clinic.jpg'
        ),
        (
            '/media/communications/customer-success-insights.png',
            '/media/communications/customer-success-insights.jpg'
        ),
        (
            '/media/communications/engineering-open-day.png',
            '/media/communications/engineering-open-day.jpg'
        ),
        (
            '/media/communications/wellness-challenge.png',
            '/media/communications/wellness-challenge.jpg'
        ),
        (
            '/media/communications/workplace-improvement.png',
            '/media/communications/workplace-improvement.jpg'
        )
)
UPDATE adm_announcements announcement
   SET cover_image_url = cover_mapping.jpg_url,
       version = announcement.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM cover_mapping
 WHERE announcement.cover_image_url = cover_mapping.png_url;

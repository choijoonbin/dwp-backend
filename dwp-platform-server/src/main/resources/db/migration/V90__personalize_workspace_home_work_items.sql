-- Home work is an actionable, user-owned projection. A shared nullable assignee caused one
-- user's status update to mutate every user's home, so seed rows are expanded per identity.
WITH templates AS (
    SELECT *, ROW_NUMBER() OVER (ORDER BY work_key) AS template_rank
      FROM wrk_items
     WHERE assignee_user_id IS NULL
), members AS (
    SELECT tenant_id, user_id,
           ROW_NUMBER() OVER (PARTITION BY tenant_id ORDER BY user_id) AS member_rank
      FROM cal_identity_links
)
INSERT INTO wrk_items (
    work_item_id, tenant_id, work_key, title_ko, title_en, summary_ko, summary_en,
    work_type, priority, lifecycle_state, owner_name, assignee_user_id, due_at,
    source_system, source_reference, source_route,
    reason_ko, reason_en, recommended_next_ko, recommended_next_en,
    latest_activity_ko, latest_activity_en, created_by, updated_by)
SELECT md5('workspace-home:' || member.tenant_id || ':' || member.user_id || ':' || template.work_key)::uuid,
       member.tenant_id,
       'HOME-' || member.user_id || '-' || template.template_rank,
       template.title_ko, template.title_en, template.summary_ko, template.summary_en,
       template.work_type, template.priority, template.lifecycle_state, template.owner_name,
       member.user_id,
       CURRENT_TIMESTAMP
           + CASE template.template_rank
               WHEN 1 THEN INTERVAL '45 minutes'
               WHEN 2 THEN INTERVAL '90 minutes'
               WHEN 3 THEN INTERVAL '8 hours'
               WHEN 4 THEN INTERVAL '1 day'
               ELSE INTERVAL '2 days'
             END,
       template.source_system,
       COALESCE(template.source_reference, template.work_key) || '-U' || member.user_id,
       template.source_route,
       template.reason_ko, template.reason_en,
       template.recommended_next_ko, template.recommended_next_en,
       template.latest_activity_ko, template.latest_activity_en, 1, 1
  FROM members member
  JOIN templates template
    ON template.tenant_id = member.tenant_id
   AND MOD(template.template_rank + member.member_rank, 5) IN (0, 1, 3)
ON CONFLICT (tenant_id, work_key) DO NOTHING;

DELETE FROM wrk_items WHERE assignee_user_id IS NULL;

ALTER TABLE wrk_items
    ALTER COLUMN assignee_user_id SET NOT NULL;

COMMENT ON COLUMN wrk_items.assignee_user_id IS
    'IAM user that owns this actionable home projection; tenant-wide messages belong in communications.';

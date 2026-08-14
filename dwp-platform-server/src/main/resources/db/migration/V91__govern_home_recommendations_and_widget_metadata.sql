CREATE TABLE usr_home_recommendation_feedback (
    feedback_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    recommendation_key VARCHAR(80) NOT NULL,
    feedback_type VARCHAR(24) NOT NULL,
    source VARCHAR(80) NOT NULL,
    rule_version VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_usr_home_recommendation_feedback
        UNIQUE (tenant_id, user_id, recommendation_key),
    CONSTRAINT ck_usr_home_recommendation_feedback_key
        CHECK (recommendation_key ~ '^[a-z][a-z0-9-]{1,79}$'),
    CONSTRAINT ck_usr_home_recommendation_feedback_type
        CHECK (feedback_type IN ('HELPFUL', 'NOT_RELEVANT', 'DISMISSED'))
);

CREATE INDEX idx_usr_home_recommendation_feedback_user
    ON usr_home_recommendation_feedback (tenant_id, user_id, feedback_type, updated_at DESC);

COMMENT ON TABLE usr_home_recommendation_feedback IS
    'Explicit user feedback for explainable home recommendations. No work payload or inferred behavior is retained.';
COMMENT ON COLUMN usr_home_recommendation_feedback.rule_version IS
    'Bounded recommendation rule version shown to the user when feedback was recorded.';

UPDATE sys_code_values
   SET behavior_metadata = CASE code
       WHEN 'announcements' THEN
           '{"canHide":false,"defaultSize":"full","allowedSizes":["full"],"owner":"Employee Communications","dataSource":"DWP_COMMUNICATIONS","freshnessSeconds":60,"privacyClass":"INTERNAL","retention":"NONE","analyticsKey":"home.announcements"}'::jsonb
       WHEN 'daily-brief' THEN
           '{"canHide":true,"defaultSize":"full","allowedSizes":["large","full"],"owner":"Digital Workplace Product","dataSource":"DWP_HOME_OVERVIEW","freshnessSeconds":30,"privacyClass":"INTERNAL","retention":"NONE","analyticsKey":"home.workday-insights"}'::jsonb
       WHEN 'focus' THEN
           '{"canHide":true,"defaultSize":"large","allowedSizes":["medium","large","full"],"owner":"Digital Workplace Product","dataSource":"DWP_WORKSPACE","freshnessSeconds":30,"privacyClass":"CONFIDENTIAL","retention":"NONE","analyticsKey":"home.focus"}'::jsonb
       WHEN 'schedule' THEN
           '{"canHide":true,"defaultSize":"compact","allowedSizes":["compact","medium"],"owner":"Calendar Product","dataSource":"DWP_CALENDAR","freshnessSeconds":30,"privacyClass":"CONFIDENTIAL","retention":"NONE","analyticsKey":"home.schedule"}'::jsonb
       WHEN 'activity' THEN
           '{"canHide":true,"defaultSize":"compact","allowedSizes":["compact","medium"],"owner":"Digital Workplace Product","dataSource":"DWP_ACTIVITY","freshnessSeconds":30,"privacyClass":"INTERNAL","retention":"NONE","analyticsKey":"home.activity"}'::jsonb
       ELSE behavior_metadata
       END,
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_WIDGET';

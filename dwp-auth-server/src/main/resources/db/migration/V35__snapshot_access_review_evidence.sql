ALTER TABLE com_access_review_items
    ADD COLUMN source_key VARCHAR(160),
    ADD COLUMN source_display_name VARCHAR(200),
    ADD COLUMN assignment_created_at TIMESTAMPTZ,
    ADD COLUMN subject_last_sign_in_at TIMESTAMPTZ,
    ADD COLUMN privileged BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN recommendation VARCHAR(20) NOT NULL DEFAULT 'UNAVAILABLE',
    ADD COLUMN recommendation_reason VARCHAR(40) NOT NULL DEFAULT 'EVIDENCE_UNAVAILABLE',
    ADD CONSTRAINT ck_access_review_item_recommendation
        CHECK (recommendation IN ('KEEP', 'REVIEW', 'UNAVAILABLE')),
    ADD CONSTRAINT ck_access_review_item_recommendation_reason
        CHECK (recommendation_reason IN (
            'RECENT_ACTIVITY', 'PRIVILEGED_ROLE', 'NEVER_SIGNED_IN',
            'INACTIVE_90_DAYS', 'EVIDENCE_UNAVAILABLE'));

CREATE INDEX idx_access_review_item_recommendation
    ON com_access_review_items(
        access_review_campaign_id,
        recommendation,
        privileged,
        decision);

ALTER TABLE adm_home_experiences
    ADD COLUMN background_focal_x INTEGER NOT NULL DEFAULT 50,
    ADD COLUMN background_focal_y INTEGER NOT NULL DEFAULT 50,
    ADD COLUMN mobile_background_focal_x INTEGER NOT NULL DEFAULT 50,
    ADD COLUMN mobile_background_focal_y INTEGER NOT NULL DEFAULT 50,
    ADD COLUMN content_alignment VARCHAR(16) NOT NULL DEFAULT 'LEFT';

-- Preserve the visible crop chosen by the legacy three-position control. New rows and
-- explicit focal-point edits still use the neutral 50/50 default.
UPDATE adm_home_experiences
   SET background_focal_x = CASE background_position
           WHEN 'LEFT' THEN 0
           WHEN 'RIGHT' THEN 100
           ELSE 50
       END,
       mobile_background_focal_x = CASE background_position
           WHEN 'LEFT' THEN 0
           WHEN 'RIGHT' THEN 100
           ELSE 50
       END;

ALTER TABLE adm_home_experiences
    ADD CONSTRAINT ck_adm_home_experiences_background_focal_point
        CHECK (
            background_focal_x BETWEEN 0 AND 100
            AND background_focal_y BETWEEN 0 AND 100
            AND mobile_background_focal_x BETWEEN 0 AND 100
            AND mobile_background_focal_y BETWEEN 0 AND 100
        ),
    ADD CONSTRAINT ck_adm_home_experiences_content_alignment
        CHECK (content_alignment IN ('LEFT', 'CENTER', 'RIGHT'));

COMMENT ON COLUMN adm_home_experiences.background_focal_x IS
    'Desktop and wide-screen background focal point X percentage.';
COMMENT ON COLUMN adm_home_experiences.background_focal_y IS
    'Desktop and wide-screen background focal point Y percentage.';
COMMENT ON COLUMN adm_home_experiences.mobile_background_focal_x IS
    'Mobile background focal point X percentage.';
COMMENT ON COLUMN adm_home_experiences.mobile_background_focal_y IS
    'Mobile background focal point Y percentage.';
COMMENT ON COLUMN adm_home_experiences.content_alignment IS
    'Home hero content alignment, independent from background image focal point.';

ALTER TABLE adm_experience_revisions
    DROP CONSTRAINT ck_adm_experience_revisions_change;

ALTER TABLE adm_experience_revisions
    ADD CONSTRAINT ck_adm_experience_revisions_change
        CHECK (change_type IN (
            'BASELINE',
            'SETTINGS_PUBLISHED',
            'EXPERIENCE_PUBLISHED',
            'ASSET_PUBLISHED',
            'ASSET_RESET',
            'ROLLBACK'
        ));

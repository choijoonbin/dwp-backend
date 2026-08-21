ALTER TABLE ntf_user_delivery_profiles
    ADD COLUMN experience_preferences JSONB NOT NULL
        DEFAULT '{"bannerMode":"SMART","previewMode":"FULL"}'::jsonb;

ALTER TABLE ntf_user_delivery_profiles
    ADD CONSTRAINT ck_ntf_delivery_profile_experience_object
        CHECK (jsonb_typeof(experience_preferences) = 'object'),
    ADD CONSTRAINT ck_ntf_delivery_profile_banner_mode
        CHECK (COALESCE(experience_preferences ->> 'bannerMode', 'SMART')
            IN ('SMART', 'HIGH_PRIORITY_ONLY', 'OFF')),
    ADD CONSTRAINT ck_ntf_delivery_profile_preview_mode
        CHECK (COALESCE(experience_preferences ->> 'previewMode', 'FULL')
            IN ('FULL', 'TITLE_ONLY', 'HIDDEN'));

COMMENT ON COLUMN ntf_user_delivery_profiles.experience_preferences IS
    'Extensible user-owned presentation controls. Content-free delivery infrastructure remains governed separately.';

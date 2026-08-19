ALTER TABLE ntf_user_delivery_profiles
    ADD COLUMN IF NOT EXISTS digest_day_of_week SMALLINT
        CHECK (digest_day_of_week BETWEEN 1 AND 7);

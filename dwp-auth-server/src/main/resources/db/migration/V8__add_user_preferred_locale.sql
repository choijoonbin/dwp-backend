ALTER TABLE com_users
    ADD COLUMN preferred_locale VARCHAR(35);

ALTER TABLE com_users
    ADD CONSTRAINT ck_com_users_preferred_locale
        CHECK (
            preferred_locale IS NULL
            OR preferred_locale ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'
        );

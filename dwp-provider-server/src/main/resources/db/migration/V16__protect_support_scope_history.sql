ALTER TABLE prv_support_session_scopes
    DROP CONSTRAINT prv_support_session_scopes_support_session_id_fkey,
    ADD CONSTRAINT fk_prv_support_session_scopes_session
        FOREIGN KEY (support_session_id)
        REFERENCES prv_support_sessions(support_session_id)
        ON DELETE RESTRICT;

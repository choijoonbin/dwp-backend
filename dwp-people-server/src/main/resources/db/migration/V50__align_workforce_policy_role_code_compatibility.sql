-- Preserve every historical V34 role reference (up to 80 uppercase/underscore
-- characters), while admitting the native Auth role-code vocabulary (up to 50,
-- including a single letter, dots and hyphens). This is storage compatibility,
-- not a role grant: current tenant-owned Auth identity/role evidence is still
-- mandatory for native authorization. New owner API writes must validate that
-- native role vocabulary and current role ownership; legacy rows remain readable
-- for inspection/revocation without being relabelled as current native grants.
ALTER TABLE public.ppl_workforce_access_policies
    DROP CONSTRAINT ck_workforce_access_policy_subject_ref;

ALTER TABLE public.ppl_workforce_access_policies
    ADD CONSTRAINT ck_workforce_access_policy_subject_ref
    CHECK ((subject_type = 'ROLE'
            AND (subject_ref ~ '^[A-Z][A-Z0-9_]{1,79}$'
                OR subject_ref ~ '^[A-Z][A-Z0-9_.-]{0,49}$'))
        OR (subject_type = 'USER' AND subject_ref ~ '^[1-9][0-9]{0,18}$'));

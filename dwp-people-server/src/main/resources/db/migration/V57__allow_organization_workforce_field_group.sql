ALTER TABLE public.ppl_workforce_access_policies
    DROP CONSTRAINT ck_workforce_access_policy_fields;

ALTER TABLE public.ppl_workforce_access_policies
    ADD CONSTRAINT ck_workforce_access_policy_fields
        CHECK (field_groups <@ ARRAY[
            'DIRECTORY', 'WORKER_IDENTIFIERS', 'EMPLOYMENT', 'JOB_GRADE',
            'ORGANIZATION'
        ]::VARCHAR[] AND cardinality(field_groups) > 0);

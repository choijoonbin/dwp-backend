-- Home must never infer the sensitivity of projected work titles. Existing rows are the
-- bounded, user-owned seed projection and retain the prior CONFIDENTIAL handling contract;
-- any future writer that omits an explicit classification fails closed as RESTRICTED.
ALTER TABLE wrk_items
    ADD COLUMN data_classification VARCHAR(24) NOT NULL DEFAULT 'RESTRICTED';

UPDATE wrk_items
   SET data_classification = 'CONFIDENTIAL';

ALTER TABLE wrk_items
    ADD CONSTRAINT ck_wrk_items_data_classification
        CHECK (data_classification IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'));

COMMENT ON COLUMN wrk_items.data_classification IS
    'Sensitivity carried with the user-owned work projection; unknown writers default to RESTRICTED.';

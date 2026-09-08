-- The task version owns ordered checklist and source identity arrays atomically.
ALTER TABLE personal_work_tasks
    ADD COLUMN checklist JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN source_references JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_personal_work_checklist CHECK
        (jsonb_typeof(checklist) = 'array' AND jsonb_array_length(checklist) <= 100),
    ADD CONSTRAINT ck_personal_work_sources CHECK
        (jsonb_typeof(source_references) = 'array' AND jsonb_array_length(source_references) <= 10);
UPDATE personal_work_tasks
   SET source_references = jsonb_build_array(jsonb_strip_nulls(jsonb_build_object(
       'sourceSystem', source_system, 'sourceReference', source_reference, 'obligationKey', obligation_key)))
 WHERE source_system IS NOT NULL;
CREATE INDEX idx_personal_work_active_owner_queue
    ON personal_work_tasks (tenant_id, owner_user_id, updated_at DESC, task_id)
    WHERE deleted_at IS NULL;

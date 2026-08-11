ALTER TABLE ppl_job_grades
    ADD COLUMN description VARCHAR(1000),
    ADD COLUMN label_i18n JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT ck_ppl_job_grades_labels CHECK (jsonb_typeof(label_i18n) = 'object');

UPDATE ppl_job_grades
   SET label_i18n = jsonb_build_object('en', name, 'ko', name)
 WHERE label_i18n = '{}'::jsonb;

COMMENT ON COLUMN ppl_job_grades.label_i18n IS
    'Locale-keyed labels. Locale additions do not require schema changes.';

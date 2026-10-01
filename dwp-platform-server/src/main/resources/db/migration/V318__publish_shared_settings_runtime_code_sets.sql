DO $$
DECLARE
    active_code_set_count INTEGER;
BEGIN
    SELECT COUNT(*)
      INTO active_code_set_count
      FROM sys_code_sets
     WHERE lifecycle_state = 'ACTIVE'
       AND code_set_key IN (
           'PLATFORM.PREFERENCE.COLOR_MODE',
           'PLATFORM.PREFERENCE.DENSITY',
           'PLATFORM.PREFERENCE.TIME_ZONE',
           'PLATFORM.PREFERENCE.DATE_FORMAT',
           'PLATFORM.PREFERENCE.TIME_FORMAT',
           'PLATFORM.PREFERENCE.FIRST_DAY_OF_WEEK',
           'PLATFORM.PREFERENCE.NUMBER_FORMAT',
           'PLATFORM.AUDIT.WINDOW',
           'PLATFORM.AUDIT.CATEGORY_FILTER',
           'PLATFORM.AUDIT.SEVERITY_FILTER',
           'PLATFORM.AUDIT.OUTCOME_FILTER',
           'PLATFORM.EVENT_ENVELOPE.DOMAIN',
           'PLATFORM.EVENT_ENVELOPE.CLASSIFICATION',
           'PLATFORM.SYS_AUDIT_EXPORT_JOBS.FORMAT'
       );

    IF active_code_set_count <> 14 THEN
        RAISE EXCEPTION
            'Expected all 14 shared settings runtime code sets, found %',
            active_code_set_count;
    END IF;

    UPDATE sys_code_sets
       SET runtime_visibility = 'RUNTIME'
     WHERE lifecycle_state = 'ACTIVE'
       AND runtime_visibility <> 'RUNTIME'
       AND code_set_key IN (
           'PLATFORM.PREFERENCE.COLOR_MODE',
           'PLATFORM.PREFERENCE.DENSITY',
           'PLATFORM.PREFERENCE.TIME_ZONE',
           'PLATFORM.PREFERENCE.DATE_FORMAT',
           'PLATFORM.PREFERENCE.TIME_FORMAT',
           'PLATFORM.PREFERENCE.FIRST_DAY_OF_WEEK',
           'PLATFORM.PREFERENCE.NUMBER_FORMAT',
           'PLATFORM.AUDIT.WINDOW',
           'PLATFORM.AUDIT.CATEGORY_FILTER',
           'PLATFORM.AUDIT.SEVERITY_FILTER',
           'PLATFORM.AUDIT.OUTCOME_FILTER',
           'PLATFORM.EVENT_ENVELOPE.DOMAIN',
           'PLATFORM.EVENT_ENVELOPE.CLASSIFICATION',
           'PLATFORM.SYS_AUDIT_EXPORT_JOBS.FORMAT'
       );
END
$$;

COMMENT ON COLUMN sys_code_sets.runtime_visibility IS
    'ADMIN_ONLY keeps source and values on the administrator surface; RUNTIME allows an explicitly authorized reduced catalog projection.';

ALTER TABLE wp_experience_connector_configurations
    DROP CONSTRAINT ck_wp_experience_connector_kind;

ALTER TABLE wp_experience_connector_configurations
    ADD CONSTRAINT ck_wp_experience_connector_kind CHECK
        (connector_kind IN (
            'CALENDAR',
            'ACTUAL_PRESENCE',
            'ACCESS_CONTROL',
            'SIGNAGE',
            'VISITOR',
            'VEHICLE',
            'FACILITY_WORK_ORDER'
        ));

COMMENT ON TABLE wp_experience_connector_configurations IS
    'Provider-neutral Workplace data-source ownership and configuration metadata. '
    'A saved configuration never asserts a healthy connection or verified external signal; '
    'an approved provider adapter must publish that evidence separately.';

ALTER TABLE apr_form_v3_lifecycle_events
    DROP CONSTRAINT ck_apr_form_v3_event_action,
    ADD CONSTRAINT ck_apr_form_v3_event_action CHECK (
        action IN ('INSTALL_TEMPLATE', 'CLONE_DRAFT', 'UPDATE_DRAFT', 'ARCHIVE_DRAFT',
                   'ADD_FIELD', 'DELETE_FIELD', 'CLONE_FIELD', 'REORDER_FIELDS',
                   'UPDATE_FIELD_PROPERTIES'));

ALTER TABLE apr_form_v3_command_receipts
    DROP CONSTRAINT ck_apr_form_v3_receipt_kind,
    ADD CONSTRAINT ck_apr_form_v3_receipt_kind CHECK (
        command_kind IN ('INSTALL_TEMPLATE', 'CLONE_DRAFT', 'UPDATE_DRAFT', 'ARCHIVE_DRAFT',
                         'ADD_FIELD', 'DELETE_FIELD', 'CLONE_FIELD', 'REORDER_FIELDS',
                         'UPDATE_FIELD_PROPERTIES'));

COMMENT ON TABLE apr_form_v3_lifecycle_events IS
    'Append-only Form V3 workspace lifecycle and structured editor command evidence.';

ALTER TABLE plt_widget_command_receipts
    ADD COLUMN request_audit_payload JSONB;

ALTER TABLE plt_widget_command_receipts
    ADD CONSTRAINT ck_widget_command_receipt_audit_payload
    CHECK (request_audit_payload IS NULL OR jsonb_typeof(request_audit_payload) = 'object');

ALTER TABLE plt_widget_runtime_controls
    DROP CONSTRAINT ck_widget_control_state;

ALTER TABLE plt_widget_runtime_controls
    ADD CONSTRAINT ck_widget_control_state
    CHECK (control_state IN ('DISABLED', 'ENABLED', 'EXPIRED'));

COMMENT ON COLUMN plt_widget_command_receipts.request_audit_payload IS
    'Immutable internal command evidence. Never serialized by member-facing Widget Catalog APIs.';

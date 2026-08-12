INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('PEOPLE.INT_CONNECTOR_CURSORS.CURSOR_TYPE', 'dwp-people-server',
     'HRIS cursor type', 'Checkpoint representation committed after a successful HRIS projection.',
     'SYSTEM', 'CHECK', 'int_connector_cursors.cursor_type', 'PROTOCOL'),
    ('PEOPLE.INT_SYNC_ERRORS.LIFECYCLE_STATE', 'dwp-people-server',
     'HRIS sync error state', 'Operator-visible lifecycle for an HRIS synchronization error.',
     'SYSTEM', 'CHECK', 'int_sync_errors.lifecycle_state', 'STATE_MACHINE'),
    ('PEOPLE.INT_RECONCILIATION_RUNS.LIFECYCLE_STATE', 'dwp-people-server',
     'HRIS reconciliation run state', 'Execution lifecycle for an HRIS reconciliation run.',
     'SYSTEM', 'CHECK', 'int_reconciliation_runs.lifecycle_state', 'STATE_MACHINE'),
    ('PEOPLE.INT_RECONCILIATION_ISSUES.SEVERITY', 'dwp-people-server',
     'HRIS reconciliation severity', 'Business impact assigned to detected workforce drift.',
     'SYSTEM', 'CHECK', 'int_reconciliation_issues.severity', 'OBSERVABILITY'),
    ('PEOPLE.INT_RECONCILIATION_ISSUES.LIFECYCLE_STATE', 'dwp-people-server',
     'HRIS reconciliation issue state', 'Human-review lifecycle for detected workforce drift.',
     'SYSTEM', 'CHECK', 'int_reconciliation_issues.lifecycle_state', 'STATE_MACHINE');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PEOPLE.INT_CONNECTOR_CURSORS.CURSOR_TYPE', 'WATERMARK', 'Watermark',
     '{"ko":"워터마크","en":"Watermark"}', 10, '{"opaque":false}'),
    ('PEOPLE.INT_CONNECTOR_CURSORS.CURSOR_TYPE', 'OPAQUE_TOKEN', 'Opaque token',
     '{"ko":"불투명 토큰","en":"Opaque token"}', 20, '{"opaque":true}'),
    ('PEOPLE.INT_CONNECTOR_CURSORS.CURSOR_TYPE', 'OFFSET', 'Offset',
     '{"ko":"오프셋","en":"Offset"}', 30, '{"opaque":false}'),

    ('PEOPLE.INT_SYNC_ERRORS.LIFECYCLE_STATE', 'OPEN', 'Open',
     '{"ko":"미해결","en":"Open"}', 10, '{"terminal":false}'),
    ('PEOPLE.INT_SYNC_ERRORS.LIFECYCLE_STATE', 'RETRYING', 'Retrying',
     '{"ko":"재시도 중","en":"Retrying"}', 20, '{"terminal":false}'),
    ('PEOPLE.INT_SYNC_ERRORS.LIFECYCLE_STATE', 'RESOLVED', 'Resolved',
     '{"ko":"해결됨","en":"Resolved"}', 30, '{"terminal":true}'),
    ('PEOPLE.INT_SYNC_ERRORS.LIFECYCLE_STATE', 'IGNORED', 'Ignored',
     '{"ko":"무시됨","en":"Ignored"}', 40, '{"terminal":true}'),

    ('PEOPLE.INT_RECONCILIATION_RUNS.LIFECYCLE_STATE', 'RUNNING', 'Running',
     '{"ko":"실행 중","en":"Running"}', 10, '{"terminal":false}'),
    ('PEOPLE.INT_RECONCILIATION_RUNS.LIFECYCLE_STATE', 'SUCCEEDED', 'Succeeded',
     '{"ko":"성공","en":"Succeeded"}', 20, '{"terminal":true,"successful":true}'),
    ('PEOPLE.INT_RECONCILIATION_RUNS.LIFECYCLE_STATE', 'FAILED', 'Failed',
     '{"ko":"실패","en":"Failed"}', 30, '{"terminal":true,"successful":false}'),

    ('PEOPLE.INT_RECONCILIATION_ISSUES.SEVERITY', 'INFO', 'Information',
     '{"ko":"정보","en":"Information"}', 10, '{"weight":1}'),
    ('PEOPLE.INT_RECONCILIATION_ISSUES.SEVERITY', 'WARNING', 'Warning',
     '{"ko":"경고","en":"Warning"}', 20, '{"weight":2}'),
    ('PEOPLE.INT_RECONCILIATION_ISSUES.SEVERITY', 'CRITICAL', 'Critical',
     '{"ko":"치명적","en":"Critical"}', 30, '{"weight":4}'),

    ('PEOPLE.INT_RECONCILIATION_ISSUES.LIFECYCLE_STATE', 'OPEN', 'Open',
     '{"ko":"검토 필요","en":"Open"}', 10, '{"terminal":false}'),
    ('PEOPLE.INT_RECONCILIATION_ISSUES.LIFECYCLE_STATE', 'RESOLVED', 'Resolved',
     '{"ko":"해결됨","en":"Resolved"}', 20, '{"terminal":true}'),
    ('PEOPLE.INT_RECONCILIATION_ISSUES.LIFECYCLE_STATE', 'ACCEPTED', 'Accepted',
     '{"ko":"허용됨","en":"Accepted"}', 30, '{"terminal":true}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PEOPLE.INT_CONNECTOR_CURSORS.CURSOR_TYPE', 'dwp-people-server', 'DATABASE_COLUMN',
     'int_connector_cursors.cursor_type', 'CHECK'),
    ('PEOPLE.INT_SYNC_ERRORS.LIFECYCLE_STATE', 'dwp-people-server', 'DATABASE_COLUMN',
     'int_sync_errors.lifecycle_state', 'CHECK'),
    ('PEOPLE.INT_RECONCILIATION_RUNS.LIFECYCLE_STATE', 'dwp-people-server', 'DATABASE_COLUMN',
     'int_reconciliation_runs.lifecycle_state', 'CHECK'),
    ('PEOPLE.INT_RECONCILIATION_ISSUES.SEVERITY', 'dwp-people-server', 'DATABASE_COLUMN',
     'int_reconciliation_issues.severity', 'CHECK'),
    ('PEOPLE.INT_RECONCILIATION_ISSUES.LIFECYCLE_STATE', 'dwp-people-server', 'DATABASE_COLUMN',
     'int_reconciliation_issues.lifecycle_state', 'CHECK');

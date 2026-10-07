-- Project every Auth-owned permission action introduced after the original
-- Platform registry seed. Auth's canonical table supplies only code and name,
-- so both labels intentionally use that name rather than inventing translations
-- or behavior metadata that the owner does not define.
WITH permission_actions(code, display_name, sort_order) AS (
    VALUES
        ('AUDIT', 'Audit', 100),
        ('AUDIT_READ', 'Read Mail delivery audit', 110),
        ('AUDIT_REVEAL', 'Reveal sensitive Mail delivery audit evidence', 120),
        ('CONNECTION_MANAGE', 'Manage Mail connections', 130),
        ('DECIDE', 'Decide Mail proposals', 140),
        ('DELIVERY_CANCEL', 'Cancel Mail delivery', 150),
        ('DELIVERY_RECONCILE', 'Reconcile Mail delivery', 160),
        ('DELIVERY_RETRY', 'Retry Mail delivery', 170),
        ('EVIDENCE_EXPORT', 'Export Mail evidence', 180),
        ('EXPLAIN', 'Explain', 190),
        ('HOLD_MANAGE', 'Manage Mail legal holds', 200),
        ('POLICY_MANAGE', 'Manage Mail policy', 210),
        ('PURGE_AUTHORIZE', 'Authorize Mail purge', 220),
        ('PURGE_EXECUTE', 'Execute Mail purge', 230),
        ('PURGE_PREVIEW', 'Preview Mail purge candidates', 240),
        ('RECONCILE', 'Reconcile', 250),
        ('RECOVERY', 'Recover Mail delivery', 260),
        ('REVERSE', 'Reverse', 270),
        ('SEND', 'Send Mail', 280),
        ('SHARED_INBOX_MANAGE', 'Manage shared inbox access', 290),
        ('SIGN', 'Internal self-attestation', 300),
        ('SIMULATE', 'Simulate', 310),
        ('WRITING_ASSET_APPROVE', 'Approve Mail organization writing assets', 320),
        ('WRITING_ASSET_EDIT', 'Edit Mail organization writing assets', 330),
        ('WRITING_ASSET_PUBLISH', 'Publish Mail organization writing assets', 340),
        ('WRITING_ASSET_RETIRE', 'Retire Mail organization writing assets', 350),
        ('WRITING_ASSET_SUBMIT', 'Submit Mail organization writing assets', 360)
)
INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, behavior_metadata, predefined, lifecycle_state)
SELECT 'AUTH.PERMISSION_ACTION',
       permission.code,
       permission.display_name,
       jsonb_build_object(
           'ko', permission.display_name,
           'en', permission.display_name),
       permission.sort_order,
       '{}'::jsonb,
       TRUE,
       'ACTIVE'
  FROM permission_actions permission
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    behavior_metadata = EXCLUDED.behavior_metadata,
    predefined = TRUE,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE ROW(
          sys_code_values.display_name,
          sys_code_values.label_i18n,
          sys_code_values.sort_order,
          sys_code_values.behavior_metadata,
          sys_code_values.predefined,
          sys_code_values.lifecycle_state)
      IS DISTINCT FROM ROW(
          EXCLUDED.display_name,
          EXCLUDED.label_i18n,
          EXCLUDED.sort_order,
          EXCLUDED.behavior_metadata,
          TRUE,
          'ACTIVE');

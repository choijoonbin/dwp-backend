ALTER TABLE wp_service_line_adjustments
    ADD COLUMN origin_command_id UUID,
    ADD COLUMN reconciliation_command_id UUID,
    ADD COLUMN provider_operation_id UUID;

-- Existing rows already have one receipt command. Bind them before enforcing the invariant.
UPDATE wp_service_line_adjustments adjustment
   SET origin_command_id = command.command_id
  FROM wp_service_order_commands command
 WHERE command.tenant_id = adjustment.tenant_id
   AND command.actor_user_id = adjustment.actor_user_id
   AND command.service_order_id = adjustment.service_order_id
   AND command.command_scope = 'SERVICE_LINE_CANCEL:'
       || adjustment.service_order_line_id::text
   AND command.status_href LIKE '%/line-adjustments/' || adjustment.line_adjustment_id::text;

INSERT INTO wp_service_order_commands (
    command_id, tenant_id, actor_user_id, command_scope, idempotency_key,
    request_fingerprint, service_order_id, command_state, status_href,
    correlation_id, created_at, updated_at)
SELECT gen_random_uuid(), adjustment.tenant_id, adjustment.actor_user_id,
       'SERVICE_LINE_CANCEL:' || adjustment.service_order_line_id::text,
       'legacy-adjustment:' || adjustment.line_adjustment_id::text,
       encode(digest('legacy-adjustment:' || adjustment.line_adjustment_id::text, 'sha256'), 'hex'),
       adjustment.service_order_id,
       CASE adjustment.adjustment_state
         WHEN 'CANCELLATION_SUCCEEDED' THEN 'SUCCEEDED'
         WHEN 'REFUNDED' THEN 'SUCCEEDED'
         WHEN 'RESULT_UNKNOWN' THEN 'RESULT_UNKNOWN'
         ELSE 'FAILED'
       END,
       '/v1/workplace/service-orders/' || adjustment.service_order_id::text
           || '/line-adjustments/' || adjustment.line_adjustment_id::text,
       NULL, adjustment.created_at, adjustment.updated_at
  FROM wp_service_line_adjustments adjustment
 WHERE adjustment.origin_command_id IS NULL;

UPDATE wp_service_line_adjustments adjustment
   SET origin_command_id = command.command_id
  FROM wp_service_order_commands command
 WHERE adjustment.origin_command_id IS NULL
   AND command.tenant_id = adjustment.tenant_id
   AND command.actor_user_id = adjustment.actor_user_id
   AND command.service_order_id = adjustment.service_order_id
   AND command.idempotency_key =
       'legacy-adjustment:' || adjustment.line_adjustment_id::text;

UPDATE wp_service_line_adjustments
   SET provider_operation_id = line_adjustment_id;

ALTER TABLE wp_service_line_adjustments
    ALTER COLUMN origin_command_id SET NOT NULL,
    ALTER COLUMN provider_operation_id SET NOT NULL,
    ADD CONSTRAINT fk_wp_service_line_adjustment_origin_command
        FOREIGN KEY (tenant_id, origin_command_id)
        REFERENCES wp_service_order_commands(tenant_id, command_id),
    ADD CONSTRAINT fk_wp_service_line_adjustment_reconciliation_command
        FOREIGN KEY (tenant_id, reconciliation_command_id)
        REFERENCES wp_service_order_commands(tenant_id, command_id),
    ADD CONSTRAINT uq_wp_service_line_adjustment_provider_operation
        UNIQUE (tenant_id, provider_operation_id),
    DROP CONSTRAINT ck_wp_service_line_adjustment_state,
    ADD CONSTRAINT ck_wp_service_line_adjustment_state CHECK
        (adjustment_state IN ('CANCELLATION_PENDING', 'RECONCILIATION_PENDING',
         'CANCELLATION_SUCCEEDED', 'REFUNDED', 'REFUND_NOT_CONFIGURED',
         'FAILED', 'RESULT_UNKNOWN'));

DROP INDEX uq_wp_service_line_unresolved_adjustment;
CREATE UNIQUE INDEX uq_wp_service_line_unresolved_adjustment
    ON wp_service_line_adjustments(tenant_id, service_order_id, service_order_line_id)
    WHERE adjustment_state IN ('CANCELLATION_PENDING', 'RECONCILIATION_PENDING',
        'RESULT_UNKNOWN', 'REFUND_NOT_CONFIGURED');

COMMENT ON COLUMN wp_service_line_adjustments.origin_command_id IS
    'Committed ACCEPTED command that owns the one provider mutation for this adjustment.';
COMMENT ON COLUMN wp_service_line_adjustments.provider_operation_id IS
    'Stable provider idempotency identity; retries use lookup only and never issue another mutation.';

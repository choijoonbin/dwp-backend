ALTER TABLE wp_service_catalog_items
    ADD COLUMN sla_response_minutes INTEGER NOT NULL DEFAULT 30,
    ADD COLUMN sla_fulfillment_lead_minutes INTEGER NOT NULL DEFAULT 30;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM wp_service_catalog_items
         WHERE unit_price < 0 OR unit_price > 499999999999.99
            OR currency !~ '^[A-Z]{3}$'
            OR minimum_quantity < 1 OR minimum_quantity > 1000
            OR maximum_quantity < minimum_quantity OR maximum_quantity > 1000
            OR order_cutoff_minutes < 0 OR order_cutoff_minutes > 525600
            OR cancellation_cutoff_minutes < 0
            OR cancellation_cutoff_minutes > 525600
            OR sla_response_minutes < 1 OR sla_response_minutes > 525600
            OR sla_fulfillment_lead_minutes < 0
            OR sla_fulfillment_lead_minutes > 525600
    ) THEN
        RAISE EXCEPTION 'Workplace service catalog contains unsupported amount, currency, quantity, cutoff, or SLA values';
    END IF;
END $$;

ALTER TABLE wp_service_catalog_items
    DROP CONSTRAINT ck_wp_service_catalog_amount,
    DROP CONSTRAINT ck_wp_service_catalog_cutoff,
    ADD CONSTRAINT ck_wp_service_catalog_amount CHECK
        (unit_price >= 0 AND unit_price <= 499999999999.99
         AND minimum_quantity > 0 AND minimum_quantity <= 1000
         AND maximum_quantity >= minimum_quantity AND maximum_quantity <= 1000),
    ADD CONSTRAINT ck_wp_service_catalog_cutoff CHECK
        (order_cutoff_minutes BETWEEN 0 AND 525600
         AND cancellation_cutoff_minutes BETWEEN 0 AND 525600),
    ADD CONSTRAINT ck_wp_service_catalog_currency CHECK
        (currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT ck_wp_service_catalog_sla CHECK
        (sla_response_minutes BETWEEN 1 AND 525600
         AND sla_fulfillment_lead_minutes BETWEEN 0 AND 525600);

ALTER TABLE wp_service_order_lines
    ADD COLUMN catalog_version BIGINT,
    ADD COLUMN provider_configuration_version BIGINT,
    ADD COLUMN currency CHAR(3),
    ADD COLUMN cancellation_cutoff_minutes INTEGER,
    ADD COLUMN cancellation_policy_ko VARCHAR(1000),
    ADD COLUMN cancellation_policy_en VARCHAR(1000),
    ADD COLUMN sla_response_minutes INTEGER,
    ADD COLUMN sla_fulfillment_lead_minutes INTEGER,
    ADD COLUMN cancelled_quantity INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN refunded_amount NUMERIC(18,2) NOT NULL DEFAULT 0;

UPDATE wp_service_order_lines line
   SET catalog_version = item.version,
       provider_configuration_version = truth.configuration_version,
       currency = item.currency,
       cancellation_cutoff_minutes = item.cancellation_cutoff_minutes,
       cancellation_policy_ko = item.cancellation_policy_ko,
       cancellation_policy_en = item.cancellation_policy_en,
       sla_response_minutes = item.sla_response_minutes,
       sla_fulfillment_lead_minutes = item.sla_fulfillment_lead_minutes
  FROM wp_service_catalog_items item
  JOIN wp_service_provider_truth truth
    ON truth.tenant_id = item.tenant_id AND truth.provider_code = item.provider_code
 WHERE item.tenant_id = line.tenant_id AND item.catalog_item_id = line.catalog_item_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM wp_service_order_lines
         WHERE catalog_version IS NULL OR provider_configuration_version IS NULL
            OR currency IS NULL OR cancellation_cutoff_minutes IS NULL
            OR cancellation_policy_ko IS NULL OR cancellation_policy_en IS NULL
            OR sla_response_minutes IS NULL OR sla_fulfillment_lead_minutes IS NULL
    ) THEN
        RAISE EXCEPTION 'Workplace service line policy snapshot backfill is incomplete';
    END IF;
    IF EXISTS (
        SELECT 1 FROM wp_service_order_lines
         WHERE quantity < 1 OR quantity > 1000
            OR unit_price < 0 OR unit_price > 499999999999.99
            OR estimated_cost <> unit_price * quantity
            OR estimated_cost > 9999999999999999.99
            OR currency !~ '^[A-Z]{3}$'
            OR cancellation_cutoff_minutes > 525600
            OR sla_response_minutes > 525600
            OR sla_fulfillment_lead_minutes > 525600
    ) THEN
        RAISE EXCEPTION 'Workplace service line snapshot contains unsupported amount, currency, quantity, cutoff, or SLA values';
    END IF;
END $$;

ALTER TABLE wp_service_order_lines
    ALTER COLUMN catalog_version SET NOT NULL,
    ALTER COLUMN provider_configuration_version SET NOT NULL,
    ALTER COLUMN currency SET NOT NULL,
    ALTER COLUMN cancellation_cutoff_minutes SET NOT NULL,
    ALTER COLUMN cancellation_policy_ko SET NOT NULL,
    ALTER COLUMN cancellation_policy_en SET NOT NULL,
    ALTER COLUMN sla_response_minutes SET NOT NULL,
    ALTER COLUMN sla_fulfillment_lead_minutes SET NOT NULL,
    ADD CONSTRAINT uq_wp_service_line_order_identity
        UNIQUE (tenant_id, service_order_id, service_order_line_id),
    DROP CONSTRAINT ck_wp_service_line_quantity,
    ADD CONSTRAINT ck_wp_service_line_quantity CHECK
        (quantity BETWEEN 1 AND 1000
         AND fulfilled_quantity >= 0 AND cancelled_quantity >= 0
         AND fulfilled_quantity + cancelled_quantity <= quantity
         AND unit_price BETWEEN 0 AND 499999999999.99
         AND estimated_cost = unit_price * quantity
         AND estimated_cost BETWEEN 0 AND 9999999999999999.99
         AND refunded_amount >= 0
         AND refunded_amount <= estimated_cost),
    ADD CONSTRAINT ck_wp_service_line_snapshot CHECK
        (catalog_version > 0 AND provider_configuration_version >= 0
         AND currency ~ '^[A-Z]{3}$'
         AND cancellation_cutoff_minutes BETWEEN 0 AND 525600
         AND sla_response_minutes BETWEEN 1 AND 525600
         AND sla_fulfillment_lead_minutes BETWEEN 0 AND 525600);

ALTER TABLE wp_service_fulfillment_tasks
    ADD COLUMN response_due_at TIMESTAMPTZ,
    ADD COLUMN provider_receipt_at TIMESTAMPTZ;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM wp_service_fulfillment_tasks task
          JOIN wp_service_order_lines line
            ON line.tenant_id = task.tenant_id
           AND line.service_order_line_id = task.service_order_line_id
         WHERE line.service_order_id <> task.service_order_id
    ) THEN
        RAISE EXCEPTION 'Workplace service fulfillment task order/line pairing is inconsistent';
    END IF;
END $$;

UPDATE wp_service_fulfillment_tasks task
   SET response_due_at = task.created_at
       + make_interval(mins => line.sla_response_minutes),
       due_at = order_row.reservation_starts_at
       - make_interval(mins => line.sla_fulfillment_lead_minutes)
  FROM wp_service_order_lines line
  JOIN wp_service_orders order_row
    ON order_row.tenant_id = line.tenant_id
   AND order_row.service_order_id = line.service_order_id
 WHERE line.tenant_id = task.tenant_id
   AND line.service_order_id = task.service_order_id
   AND line.service_order_line_id = task.service_order_line_id;

ALTER TABLE wp_service_fulfillment_tasks
    ALTER COLUMN response_due_at SET NOT NULL,
    ADD CONSTRAINT fk_wp_service_task_order_line
        FOREIGN KEY (tenant_id, service_order_id, service_order_line_id)
        REFERENCES wp_service_order_lines(
            tenant_id, service_order_id, service_order_line_id) ON DELETE CASCADE;

ALTER TABLE wp_service_order_events
    DROP CONSTRAINT ck_wp_service_event_type,
    ADD CONSTRAINT ck_wp_service_event_type CHECK
        (event_type IN ('SUBMITTED', 'ACCEPTED', 'PREPARATION_STARTED',
         'PARTIALLY_FULFILLED', 'FULFILLED', 'BLOCKED', 'DELAYED',
         'CANCELLED', 'RESULT_UNKNOWN', 'RESERVATION_IMPACT_CHANGED',
         'MESSAGE_ADDED', 'ATTACHMENT_LINKED', 'ATTACHMENT_SCAN_UPDATED',
         'LINE_CANCELLED', 'LINE_CANCELLATION_FAILED',
         'LINE_CANCELLATION_REQUIRES_REVIEW',
         'LINE_CANCELLATION_RESULT_UNKNOWN', 'LINE_ADJUSTMENT_RECONCILED'));

ALTER TABLE wp_service_order_attachments
    ADD COLUMN scan_state VARCHAR(24) NOT NULL DEFAULT 'NOT_CONFIGURED',
    ADD COLUMN scan_version BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN scanner_evidence_reference VARCHAR(320),
    ADD COLUMN scan_detail VARCHAR(1000),
    ADD COLUMN scanned_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_wp_service_attachment_scan_state CHECK
        (scan_state IN ('NOT_CONFIGURED', 'QUARANTINED', 'CLEAN', 'INFECTED', 'ERROR')),
    ADD CONSTRAINT ck_wp_service_attachment_scan_version CHECK (scan_version > 0),
    ADD CONSTRAINT ck_wp_service_attachment_scan_evidence CHECK
        ((scan_state IN ('NOT_CONFIGURED', 'QUARANTINED')
          AND scanner_evidence_reference IS NULL AND scanned_at IS NULL)
         OR (scan_state IN ('CLEAN', 'INFECTED', 'ERROR')
          AND scanner_evidence_reference IS NOT NULL AND scanned_at IS NOT NULL));

CREATE TABLE wp_service_line_cancellation_previews (
    cancellation_preview_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    service_order_id UUID NOT NULL,
    service_order_line_id UUID NOT NULL,
    order_version BIGINT NOT NULL,
    line_version BIGINT NOT NULL,
    cancel_quantity INTEGER NOT NULL,
    fulfilled_quantity INTEGER NOT NULL,
    previously_cancelled_quantity INTEGER NOT NULL,
    catalog_version BIGINT NOT NULL,
    provider_configuration_version BIGINT NOT NULL,
    unit_price NUMERIC(18,2) NOT NULL,
    currency CHAR(3) NOT NULL,
    cancellation_cutoff_minutes INTEGER NOT NULL,
    cancellation_policy_ko VARCHAR(1000) NOT NULL,
    cancellation_policy_en VARCHAR(1000) NOT NULL,
    refund_scope VARCHAR(20) NOT NULL,
    refundable_amount NUMERIC(18,2) NOT NULL,
    eligible BOOLEAN NOT NULL,
    reason VARCHAR(500) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, cancellation_preview_id),
    UNIQUE (tenant_id, cancellation_preview_id, actor_user_id,
        service_order_id, service_order_line_id),
    FOREIGN KEY (tenant_id, service_order_id)
        REFERENCES wp_service_orders(tenant_id, service_order_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, service_order_id, service_order_line_id)
        REFERENCES wp_service_order_lines(
            tenant_id, service_order_id, service_order_line_id) ON DELETE CASCADE,
    CONSTRAINT ck_wp_service_cancel_preview_scope CHECK
        (refund_scope IN ('FULL', 'PARTIAL', 'NONE')),
    CONSTRAINT ck_wp_service_cancel_preview_values CHECK
        (order_version > 0 AND line_version > 0 AND cancel_quantity > 0
         AND fulfilled_quantity >= 0 AND previously_cancelled_quantity >= 0
         AND catalog_version > 0 AND provider_configuration_version >= 0
         AND unit_price >= 0 AND cancellation_cutoff_minutes >= 0
         AND refundable_amount >= 0
         AND refundable_amount <= unit_price * cancel_quantity
         AND expires_at > created_at)
);

CREATE TABLE wp_service_line_adjustments (
    line_adjustment_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    service_order_id UUID NOT NULL,
    service_order_line_id UUID NOT NULL,
    cancellation_preview_id UUID NOT NULL,
    adjustment_type VARCHAR(24) NOT NULL,
    cancel_quantity INTEGER NOT NULL,
    refund_scope VARCHAR(20) NOT NULL,
    refundable_amount NUMERIC(18,2) NOT NULL,
    refunded_amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL,
    adjustment_state VARCHAR(32) NOT NULL,
    provider_operation_reference VARCHAR(320),
    refund_receipt_reference VARCHAR(320),
    result_detail VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, line_adjustment_id),
    UNIQUE (tenant_id, cancellation_preview_id),
    FOREIGN KEY (tenant_id, service_order_id)
        REFERENCES wp_service_orders(tenant_id, service_order_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, service_order_id, service_order_line_id)
        REFERENCES wp_service_order_lines(
            tenant_id, service_order_id, service_order_line_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, cancellation_preview_id)
        REFERENCES wp_service_line_cancellation_previews(tenant_id, cancellation_preview_id),
    FOREIGN KEY (tenant_id, cancellation_preview_id, actor_user_id,
        service_order_id, service_order_line_id)
        REFERENCES wp_service_line_cancellation_previews(
            tenant_id, cancellation_preview_id, actor_user_id,
            service_order_id, service_order_line_id),
    CONSTRAINT ck_wp_service_line_adjustment_type CHECK
        (adjustment_type = 'CANCEL_REFUND'),
    CONSTRAINT ck_wp_service_line_adjustment_scope CHECK
        (refund_scope IN ('FULL', 'PARTIAL', 'NONE')),
    CONSTRAINT ck_wp_service_line_adjustment_state CHECK
        (adjustment_state IN ('CANCELLATION_SUCCEEDED', 'REFUNDED',
         'REFUND_NOT_CONFIGURED', 'FAILED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_service_line_adjustment_values CHECK
        (cancel_quantity > 0 AND refundable_amount >= 0 AND refunded_amount >= 0
         AND refunded_amount <= refundable_amount AND version > 0),
    CONSTRAINT ck_wp_service_line_adjustment_receipt CHECK
        ((adjustment_state = 'REFUNDED' AND refund_receipt_reference IS NOT NULL
          AND refunded_amount = refundable_amount)
         OR (adjustment_state <> 'REFUNDED' AND refunded_amount = 0
          AND refund_receipt_reference IS NULL))
);

CREATE UNIQUE INDEX uq_wp_service_line_unresolved_adjustment
    ON wp_service_line_adjustments(tenant_id, service_order_id, service_order_line_id)
    WHERE adjustment_state IN ('RESULT_UNKNOWN', 'REFUND_NOT_CONFIGURED');

DROP INDEX idx_wp_service_orders_requester;
CREATE INDEX idx_wp_service_orders_requester
    ON wp_service_orders(tenant_id, requester_user_id,
        created_at DESC, service_order_id DESC);
DROP INDEX idx_wp_service_orders_fulfillment;
CREATE INDEX idx_wp_service_orders_fulfillment
    ON wp_service_orders(tenant_id, order_state,
        created_at DESC, service_order_id DESC);
CREATE INDEX idx_wp_service_order_events_page
    ON wp_service_order_events(tenant_id, service_order_id,
        occurred_at DESC, service_order_event_id DESC);
CREATE INDEX idx_wp_service_order_messages_page
    ON wp_service_order_messages(tenant_id, service_order_id,
        created_at DESC, message_id DESC);
CREATE INDEX idx_wp_service_order_attachments_page
    ON wp_service_order_attachments(tenant_id, service_order_id,
        created_at DESC, attachment_id DESC);

COMMENT ON TABLE wp_service_line_adjustments IS
    'Per-line cancellation and refund truth. REFUNDED requires an authoritative provider receipt; estimates never imply settlement.';
COMMENT ON COLUMN wp_service_order_attachments.scan_state IS
    'Downloads are fail-closed unless the authoritative scan state is CLEAN.';

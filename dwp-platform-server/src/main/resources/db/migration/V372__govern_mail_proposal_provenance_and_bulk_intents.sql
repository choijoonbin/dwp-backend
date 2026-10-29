-- Bind every runtime-produced Mail proposal to immutable producer and source
-- evidence, and give client-side bulk triage an item-level idempotency ledger.

ALTER TABLE mail_action_proposals
    ADD COLUMN source_message_id UUID,
    ADD COLUMN producer_identity VARCHAR(120),
    ADD COLUMN producer_event_id UUID,
    ADD COLUMN producer_model VARCHAR(160),
    ADD COLUMN model_revision VARCHAR(160),
    ADD COLUMN prompt_revision VARCHAR(160),
    ADD COLUMN policy_revision VARCHAR(160),
    ADD COLUMN source_evidence_hashes JSONB,
    ADD COLUMN producer_fingerprint CHAR(64);

ALTER TABLE mail_action_proposals
    ADD CONSTRAINT fk_mail_proposal_source_message
        FOREIGN KEY (tenant_id, thread_id, source_message_id)
        REFERENCES mail_messages (tenant_id, thread_id, message_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_mail_proposal_producer_contract CHECK (
        (producer_identity IS NULL
            AND producer_event_id IS NULL
            AND producer_model IS NULL
            AND model_revision IS NULL
            AND prompt_revision IS NULL
            AND policy_revision IS NULL
            AND source_evidence_hashes IS NULL
            AND producer_fingerprint IS NULL)
        OR (source_message_id IS NOT NULL
            AND producer_identity ~ '^[a-z][a-z0-9.-]{2,119}$'
            AND producer_event_id IS NOT NULL
            AND length(btrim(producer_model)) BETWEEN 1 AND 160
            AND length(btrim(model_revision)) BETWEEN 1 AND 160
            AND length(btrim(prompt_revision)) BETWEEN 1 AND 160
            AND length(btrim(policy_revision)) BETWEEN 1 AND 160
            AND jsonb_typeof(source_evidence_hashes) = 'array'
            AND jsonb_array_length(source_evidence_hashes) BETWEEN 1 AND 32
            AND producer_fingerprint ~ '^[a-f0-9]{64}$'));

CREATE UNIQUE INDEX uk_mail_proposal_producer_event
    ON mail_action_proposals (tenant_id, producer_identity, producer_event_id)
    WHERE producer_identity IS NOT NULL;

CREATE INDEX idx_mail_proposal_source_message
    ON mail_action_proposals (tenant_id, source_message_id, created_at DESC)
    WHERE source_message_id IS NOT NULL;

CREATE TABLE mail_bulk_mutation_receipts (
    receipt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    intent_id UUID NOT NULL,
    item_idempotency_key UUID NOT NULL,
    thread_id UUID NOT NULL,
    action_kind VARCHAR(32) NOT NULL,
    expected_version BIGINT NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    result_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    resulting_version BIGINT,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_mail_bulk_mutation_item
        UNIQUE (tenant_id, actor_user_id, item_idempotency_key),
    CONSTRAINT fk_mail_bulk_mutation_thread
        FOREIGN KEY (tenant_id, thread_id)
        REFERENCES mail_threads (tenant_id, thread_id) ON DELETE CASCADE,
    CONSTRAINT ck_mail_bulk_mutation_action CHECK (action_kind IN (
        'MARK_READ', 'STAR', 'ARCHIVE')),
    CONSTRAINT ck_mail_bulk_mutation_version CHECK (expected_version >= 0),
    CONSTRAINT ck_mail_bulk_mutation_fingerprint CHECK (
        request_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_mail_bulk_mutation_result CHECK (
        (result_state = 'PENDING'
            AND resulting_version IS NULL AND completed_at IS NULL)
        OR (result_state = 'SUCCEEDED'
            AND resulting_version IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE INDEX idx_mail_bulk_mutation_intent
    ON mail_bulk_mutation_receipts (
        tenant_id, actor_user_id, intent_id, created_at, receipt_id);

COMMENT ON COLUMN mail_action_proposals.producer_fingerprint IS
    'Canonical SHA-256 of the bounded producer request. Exact event replays must match it.';
COMMENT ON TABLE mail_bulk_mutation_receipts IS
    'Atomic item-level success receipts for one reviewed bulk triage intent.';

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE msg_conversation_creation_requests (
    tenant_id BIGINT NOT NULL,
    requester_user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    conversation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, requester_user_id, idempotency_key),
    CONSTRAINT fk_msg_creation_request_conversation
        FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES msg_conversations (tenant_id, conversation_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_msg_creation_request_key
        CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 120),
    CONSTRAINT ck_msg_creation_request_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_msg_creation_request_conversation
    ON msg_conversation_creation_requests (tenant_id, conversation_id);

CREATE INDEX ix_msg_conversation_name_search
    ON msg_conversations USING GIN (lower(name) gin_trgm_ops)
    WHERE lifecycle_state = 'ACTIVE' AND name IS NOT NULL;

CREATE INDEX ix_msg_conversation_topic_search
    ON msg_conversations USING GIN (lower(topic) gin_trgm_ops)
    WHERE lifecycle_state = 'ACTIVE' AND topic IS NOT NULL;

CREATE INDEX ix_msg_message_body_search
    ON msg_messages USING GIN (lower(body) gin_trgm_ops)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_msg_people_display_name_search
    ON msg_people_snapshot USING GIN (lower(display_name) gin_trgm_ops)
    WHERE lifecycle_state = 'ACTIVE';

CREATE INDEX ix_msg_people_email_search
    ON msg_people_snapshot USING GIN (lower(email_address) gin_trgm_ops)
    WHERE lifecycle_state = 'ACTIVE';

COMMENT ON TABLE msg_conversation_creation_requests IS
    'Conversation-creation idempotency ledger scoped to one tenant and requesting user.';
COMMENT ON INDEX ix_msg_message_body_search IS
    'ACL-safe SQL fallback search accelerator; OpenSearch remains a replaceable projection.';

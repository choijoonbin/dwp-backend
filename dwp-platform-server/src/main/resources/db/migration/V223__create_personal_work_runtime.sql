-- Native personal tasks and daily selection have independent versions and lifecycles.
CREATE TABLE personal_work_tasks (
    task_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    owner_user_id BIGINT NOT NULL CHECK (owner_user_id > 0),
    title VARCHAR(500) NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 500),
    description VARCHAR(10000),
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'IN_PROGRESS', 'WAITING', 'COMPLETED', 'ARCHIVED')),
    priority VARCHAR(10) NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    due_at TIMESTAMPTZ,
    source_system VARCHAR(64),
    source_reference VARCHAR(256),
    obligation_key VARCHAR(160),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_personal_work_owner UNIQUE (tenant_id, owner_user_id, task_id),
    CONSTRAINT ck_personal_work_source CHECK (
        (source_system IS NULL AND source_reference IS NULL AND obligation_key IS NULL)
        OR (source_system IS NOT NULL AND source_reference IS NOT NULL)),
    CONSTRAINT ck_personal_work_completion CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (status <> 'COMPLETED' AND completed_at IS NULL))
);
CREATE INDEX idx_personal_work_owner_queue
    ON personal_work_tasks (tenant_id, owner_user_id, updated_at DESC, task_id);
CREATE INDEX idx_personal_work_owner_due
    ON personal_work_tasks (tenant_id, owner_user_id, due_at)
    WHERE status IN ('OPEN', 'IN_PROGRESS', 'WAITING');

CREATE TABLE personal_work_day_plans (
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    owner_user_id BIGINT NOT NULL CHECK (owner_user_id > 0),
    plan_date DATE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, owner_user_id, plan_date)
);
CREATE TABLE personal_work_day_plan_items (
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    plan_date DATE NOT NULL,
    position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 99),
    source_system VARCHAR(64) NOT NULL,
    source_reference VARCHAR(256) NOT NULL,
    obligation_key VARCHAR(160) NOT NULL DEFAULT '',
    PRIMARY KEY (tenant_id, owner_user_id, plan_date, position),
    UNIQUE (tenant_id, owner_user_id, plan_date, source_system, source_reference, obligation_key),
    FOREIGN KEY (tenant_id, owner_user_id, plan_date)
        REFERENCES personal_work_day_plans (tenant_id, owner_user_id, plan_date) ON DELETE CASCADE
);

CREATE TABLE personal_work_timeline (
    event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    task_id UUID NOT NULL,
    action VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 0),
    audit_record_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id, owner_user_id, task_id)
        REFERENCES personal_work_tasks (tenant_id, owner_user_id, task_id) ON DELETE RESTRICT,
    UNIQUE (tenant_id, owner_user_id, task_id, version)
);
CREATE INDEX idx_personal_work_timeline
    ON personal_work_timeline (tenant_id, owner_user_id, task_id, occurred_at DESC, event_id);

-- Receipts contain caller-owned task data and reference identities, never resolved source content.
CREATE TABLE personal_work_command_receipts (
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    owner_user_id BIGINT NOT NULL CHECK (owner_user_id > 0),
    command_id UUID NOT NULL,
    operation VARCHAR(32) NOT NULL,
    target_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    response_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, owner_user_id, command_id)
);

CREATE FUNCTION reject_personal_work_timeline_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'personal work timeline is append-only';
END;
$$;
CREATE TRIGGER trg_personal_work_timeline_append_only
    BEFORE UPDATE OR DELETE ON personal_work_timeline
    FOR EACH ROW EXECUTE FUNCTION reject_personal_work_timeline_mutation();

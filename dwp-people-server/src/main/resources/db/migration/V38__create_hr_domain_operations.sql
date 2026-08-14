-- Core HR (ppl_*) remains the effective-dated workforce projection. The
-- following bounded contexts own transactional HR operations and reference
-- workers through tenant-scoped foreign keys instead of duplicating identity.

CREATE TABLE tme_work_schedule_profiles (
    schedule_profile_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    profile_key VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    time_zone VARCHAR(80) NOT NULL,
    weekly_minutes INTEGER NOT NULL,
    daily_pattern JSONB NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_tme_schedule_profile_key UNIQUE (tenant_id, profile_key),
    CONSTRAINT uk_tme_schedule_profile_id UNIQUE (tenant_id, schedule_profile_id),
    CONSTRAINT ck_tme_schedule_profile_minutes CHECK (weekly_minutes BETWEEN 1 AND 10080),
    CONSTRAINT ck_tme_schedule_profile_pattern CHECK (jsonb_typeof(daily_pattern) = 'object'),
    CONSTRAINT ck_tme_schedule_profile_state CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED'))
);

CREATE TABLE tme_worker_schedule_assignments (
    worker_schedule_assignment_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    schedule_profile_id BIGINT NOT NULL,
    effective_start_date DATE NOT NULL,
    effective_end_date DATE,
    data_origin VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_tme_worker_schedule_start UNIQUE (tenant_id, worker_id, effective_start_date),
    CONSTRAINT fk_tme_worker_schedule_worker FOREIGN KEY (tenant_id, worker_id)
        REFERENCES ppl_workers(tenant_id, worker_id),
    CONSTRAINT fk_tme_worker_schedule_profile FOREIGN KEY (tenant_id, schedule_profile_id)
        REFERENCES tme_work_schedule_profiles(tenant_id, schedule_profile_id),
    CONSTRAINT ck_tme_worker_schedule_dates CHECK (effective_end_date IS NULL OR effective_end_date >= effective_start_date),
    CONSTRAINT ck_tme_worker_schedule_origin CHECK (data_origin IN ('SOURCE', 'MANUAL', 'REFERENCE'))
);

CREATE TABLE tme_time_cards (
    time_card_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    period_start_date DATE NOT NULL,
    period_end_date DATE NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    scheduled_minutes INTEGER NOT NULL DEFAULT 0,
    recorded_minutes INTEGER NOT NULL DEFAULT 0,
    exception_count INTEGER NOT NULL DEFAULT 0,
    submitted_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    decided_by BIGINT,
    decision_note VARCHAR(1000),
    data_origin VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_tme_time_card_period UNIQUE (tenant_id, worker_id, period_start_date),
    CONSTRAINT uk_tme_time_card_id UNIQUE (tenant_id, time_card_id),
    CONSTRAINT fk_tme_time_card_worker FOREIGN KEY (tenant_id, worker_id)
        REFERENCES ppl_workers(tenant_id, worker_id),
    CONSTRAINT ck_tme_time_card_period CHECK (period_end_date >= period_start_date),
    CONSTRAINT ck_tme_time_card_minutes CHECK (scheduled_minutes >= 0 AND recorded_minutes >= 0),
    CONSTRAINT ck_tme_time_card_exceptions CHECK (exception_count >= 0),
    CONSTRAINT ck_tme_time_card_status CHECK (status IN ('OPEN', 'SUBMITTED', 'APPROVED', 'REJECTED', 'LOCKED')),
    CONSTRAINT ck_tme_time_card_origin CHECK (data_origin IN ('SOURCE', 'MANUAL', 'REFERENCE'))
);

CREATE TABLE tme_time_entries (
    time_entry_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    time_card_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    entry_type VARCHAR(24) NOT NULL,
    start_at TIMESTAMPTZ,
    end_at TIMESTAMPTZ,
    minutes INTEGER NOT NULL,
    work_mode VARCHAR(24),
    note VARCHAR(1000),
    source_reference VARCHAR(255),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_tme_time_entry_id UNIQUE (tenant_id, time_entry_id),
    CONSTRAINT fk_tme_time_entry_card FOREIGN KEY (tenant_id, time_card_id)
        REFERENCES tme_time_cards(tenant_id, time_card_id),
    CONSTRAINT fk_tme_time_entry_worker FOREIGN KEY (tenant_id, worker_id)
        REFERENCES ppl_workers(tenant_id, worker_id),
    CONSTRAINT ck_tme_time_entry_type CHECK (entry_type IN ('WORK', 'BREAK', 'ON_CALL', 'TRAINING', 'CORRECTION')),
    CONSTRAINT ck_tme_time_entry_minutes CHECK (minutes BETWEEN 1 AND 1440),
    CONSTRAINT ck_tme_time_entry_range CHECK (end_at IS NULL OR start_at IS NULL OR end_at > start_at),
    CONSTRAINT ck_tme_time_entry_mode CHECK (work_mode IS NULL OR work_mode IN ('OFFICE', 'REMOTE', 'FIELD', 'HYBRID')),
    CONSTRAINT ck_tme_time_entry_state CHECK (lifecycle_state IN ('ACTIVE', 'VOID'))
);

CREATE INDEX idx_tme_time_entries_worker_date
    ON tme_time_entries(tenant_id, worker_id, work_date DESC)
    WHERE lifecycle_state = 'ACTIVE';

CREATE UNIQUE INDEX uk_tme_time_entries_card_day_type
    ON tme_time_entries(tenant_id, time_card_id, work_date, entry_type)
    WHERE lifecycle_state = 'ACTIVE';

CREATE TABLE tme_time_exceptions (
    time_exception_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    time_card_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    exception_code VARCHAR(80) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    occurred_on DATE NOT NULL,
    message VARCHAR(500) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    resolution_note VARCHAR(1000),
    resolved_at TIMESTAMPTZ,
    resolved_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_tme_exception_card FOREIGN KEY (tenant_id, time_card_id)
        REFERENCES tme_time_cards(tenant_id, time_card_id),
    CONSTRAINT fk_tme_exception_worker FOREIGN KEY (tenant_id, worker_id)
        REFERENCES ppl_workers(tenant_id, worker_id),
    CONSTRAINT ck_tme_exception_severity CHECK (severity IN ('INFO', 'WARNING', 'BLOCKING')),
    CONSTRAINT ck_tme_exception_state CHECK (lifecycle_state IN ('OPEN', 'RESOLVED', 'WAIVED'))
);

CREATE TABLE abs_leave_plans (
    leave_plan_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    plan_key VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    unit VARCHAR(20) NOT NULL DEFAULT 'MINUTE',
    accrual_method VARCHAR(24) NOT NULL,
    approval_policy VARCHAR(24) NOT NULL,
    negative_balance_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    plan_rules JSONB NOT NULL DEFAULT '{}'::jsonb,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_abs_leave_plan_key UNIQUE (tenant_id, plan_key),
    CONSTRAINT uk_abs_leave_plan_id UNIQUE (tenant_id, leave_plan_id),
    CONSTRAINT ck_abs_leave_plan_unit CHECK (unit IN ('MINUTE', 'DAY')),
    CONSTRAINT ck_abs_leave_plan_accrual CHECK (accrual_method IN ('ANNUAL', 'MONTHLY', 'EVENT', 'NONE')),
    CONSTRAINT ck_abs_leave_plan_approval CHECK (approval_policy IN ('AUTO', 'MANAGER', 'HR')),
    CONSTRAINT ck_abs_leave_plan_rules CHECK (jsonb_typeof(plan_rules) = 'object'),
    CONSTRAINT ck_abs_leave_plan_state CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED'))
);

CREATE TABLE abs_worker_plan_enrollments (
    worker_plan_enrollment_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    leave_plan_id BIGINT NOT NULL,
    effective_start_date DATE NOT NULL,
    effective_end_date DATE,
    eligibility_reason VARCHAR(200),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_abs_worker_plan_start UNIQUE (tenant_id, worker_id, leave_plan_id, effective_start_date),
    CONSTRAINT fk_abs_worker_plan_worker FOREIGN KEY (tenant_id, worker_id)
        REFERENCES ppl_workers(tenant_id, worker_id),
    CONSTRAINT fk_abs_worker_plan_plan FOREIGN KEY (tenant_id, leave_plan_id)
        REFERENCES abs_leave_plans(tenant_id, leave_plan_id),
    CONSTRAINT ck_abs_worker_plan_dates CHECK (effective_end_date IS NULL OR effective_end_date >= effective_start_date),
    CONSTRAINT ck_abs_worker_plan_state CHECK (lifecycle_state IN ('ACTIVE', 'ENDED'))
);

CREATE TABLE abs_leave_balances (
    leave_balance_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    leave_plan_id BIGINT NOT NULL,
    balance_year INTEGER NOT NULL,
    granted_minutes INTEGER NOT NULL DEFAULT 0,
    used_minutes INTEGER NOT NULL DEFAULT 0,
    pending_minutes INTEGER NOT NULL DEFAULT 0,
    adjustment_minutes INTEGER NOT NULL DEFAULT 0,
    as_of_date DATE NOT NULL,
    data_origin VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_abs_leave_balance_year UNIQUE (tenant_id, worker_id, leave_plan_id, balance_year),
    CONSTRAINT fk_abs_leave_balance_worker FOREIGN KEY (tenant_id, worker_id)
        REFERENCES ppl_workers(tenant_id, worker_id),
    CONSTRAINT fk_abs_leave_balance_plan FOREIGN KEY (tenant_id, leave_plan_id)
        REFERENCES abs_leave_plans(tenant_id, leave_plan_id),
    CONSTRAINT ck_abs_leave_balance_values CHECK (granted_minutes >= 0 AND used_minutes >= 0 AND pending_minutes >= 0),
    CONSTRAINT ck_abs_leave_balance_origin CHECK (data_origin IN ('SOURCE', 'MANUAL', 'REFERENCE'))
);

CREATE TABLE abs_leave_requests (
    leave_request_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    leave_plan_id BIGINT NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    requested_minutes INTEGER NOT NULL,
    reason VARCHAR(1000),
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    submitted_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    decided_by BIGINT,
    decision_note VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_abs_leave_request_id UNIQUE (tenant_id, leave_request_id),
    CONSTRAINT fk_abs_leave_request_worker FOREIGN KEY (tenant_id, worker_id)
        REFERENCES ppl_workers(tenant_id, worker_id),
    CONSTRAINT fk_abs_leave_request_plan FOREIGN KEY (tenant_id, leave_plan_id)
        REFERENCES abs_leave_plans(tenant_id, leave_plan_id),
    CONSTRAINT ck_abs_leave_request_range CHECK (end_at > start_at),
    CONSTRAINT ck_abs_leave_request_minutes CHECK (requested_minutes > 0),
    CONSTRAINT ck_abs_leave_request_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED'))
);

CREATE INDEX idx_abs_leave_requests_team_queue
    ON abs_leave_requests(tenant_id, status, start_at)
    WHERE status = 'SUBMITTED';

CREATE TABLE bnf_benefit_programs (
    benefit_program_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    program_key VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    program_year INTEGER NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_bnf_program_key UNIQUE (tenant_id, program_key, program_year),
    CONSTRAINT uk_bnf_program_id UNIQUE (tenant_id, benefit_program_id),
    CONSTRAINT ck_bnf_program_year CHECK (program_year BETWEEN 2000 AND 2200),
    CONSTRAINT ck_bnf_program_state CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'CLOSED', 'RETIRED'))
);

CREATE TABLE bnf_benefit_plans (
    benefit_plan_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    benefit_program_id BIGINT NOT NULL,
    plan_key VARCHAR(100) NOT NULL,
    plan_type VARCHAR(30) NOT NULL,
    name VARCHAR(200) NOT NULL,
    provider_name VARCHAR(200),
    plan_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_bnf_plan_key UNIQUE (tenant_id, plan_key),
    CONSTRAINT uk_bnf_plan_id UNIQUE (tenant_id, benefit_plan_id),
    CONSTRAINT fk_bnf_plan_program FOREIGN KEY (tenant_id, benefit_program_id)
        REFERENCES bnf_benefit_programs(tenant_id, benefit_program_id),
    CONSTRAINT ck_bnf_plan_type CHECK (plan_type IN ('HEALTH', 'WELLNESS', 'LIFE', 'RETIREMENT', 'ALLOWANCE', 'OTHER')),
    CONSTRAINT ck_bnf_plan_summary CHECK (jsonb_typeof(plan_summary) = 'object'),
    CONSTRAINT ck_bnf_plan_state CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED'))
);

CREATE TABLE bnf_enrollment_windows (
    enrollment_window_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    benefit_program_id BIGINT NOT NULL,
    window_type VARCHAR(24) NOT NULL,
    name VARCHAR(200) NOT NULL,
    opens_at TIMESTAMPTZ NOT NULL,
    closes_at TIMESTAMPTZ NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_bnf_window_program FOREIGN KEY (tenant_id, benefit_program_id)
        REFERENCES bnf_benefit_programs(tenant_id, benefit_program_id),
    CONSTRAINT ck_bnf_window_range CHECK (closes_at > opens_at),
    CONSTRAINT ck_bnf_window_type CHECK (window_type IN ('OPEN_ENROLLMENT', 'NEW_HIRE', 'LIFE_EVENT', 'CORRECTION')),
    CONSTRAINT ck_bnf_window_state CHECK (lifecycle_state IN ('SCHEDULED', 'OPEN', 'CLOSED', 'CANCELLED'))
);

CREATE TABLE bnf_enrollments (
    enrollment_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    benefit_plan_id BIGINT NOT NULL,
    coverage_level VARCHAR(24) NOT NULL,
    effective_start_date DATE NOT NULL,
    effective_end_date DATE,
    status VARCHAR(24) NOT NULL DEFAULT 'ELECTED',
    source_reference VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_bnf_enrollment_slice UNIQUE (tenant_id, worker_id, benefit_plan_id, effective_start_date),
    CONSTRAINT fk_bnf_enrollment_worker FOREIGN KEY (tenant_id, worker_id)
        REFERENCES ppl_workers(tenant_id, worker_id),
    CONSTRAINT fk_bnf_enrollment_plan FOREIGN KEY (tenant_id, benefit_plan_id)
        REFERENCES bnf_benefit_plans(tenant_id, benefit_plan_id),
    CONSTRAINT ck_bnf_enrollment_dates CHECK (effective_end_date IS NULL OR effective_end_date >= effective_start_date),
    CONSTRAINT ck_bnf_enrollment_coverage CHECK (coverage_level IN ('EMPLOYEE', 'EMPLOYEE_SPOUSE', 'EMPLOYEE_CHILDREN', 'FAMILY', 'WAIVED')),
    CONSTRAINT ck_bnf_enrollment_status CHECK (status IN ('DRAFT', 'ELECTED', 'ACTIVE', 'WAIVED', 'ENDED'))
);

CREATE TABLE pay_pay_cycles (
    pay_cycle_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    cycle_key VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    period_start_date DATE NOT NULL,
    period_end_date DATE NOT NULL,
    pay_date DATE NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PLANNED',
    readiness JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_pay_cycle_key UNIQUE (tenant_id, cycle_key),
    CONSTRAINT uk_pay_cycle_id UNIQUE (tenant_id, pay_cycle_id),
    CONSTRAINT ck_pay_cycle_dates CHECK (period_end_date >= period_start_date AND pay_date >= period_end_date),
    CONSTRAINT ck_pay_cycle_status CHECK (status IN ('PLANNED', 'COLLECTING', 'VALIDATING', 'APPROVED', 'PAID', 'CANCELLED')),
    CONSTRAINT ck_pay_cycle_readiness CHECK (jsonb_typeof(readiness) = 'object')
);

CREATE TABLE pay_statement_references (
    pay_statement_reference_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    pay_cycle_id BIGINT NOT NULL,
    statement_period_label VARCHAR(100) NOT NULL,
    document_reference VARCHAR(500) NOT NULL,
    document_checksum_sha256 CHAR(64),
    availability_state VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE',
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_pay_statement_worker_cycle UNIQUE (tenant_id, worker_id, pay_cycle_id),
    CONSTRAINT fk_pay_statement_worker FOREIGN KEY (tenant_id, worker_id)
        REFERENCES ppl_workers(tenant_id, worker_id),
    CONSTRAINT fk_pay_statement_cycle FOREIGN KEY (tenant_id, pay_cycle_id)
        REFERENCES pay_pay_cycles(tenant_id, pay_cycle_id),
    CONSTRAINT ck_pay_statement_state CHECK (availability_state IN ('PENDING', 'AVAILABLE', 'WITHHELD', 'RETIRED'))
);

CREATE TABLE tal_journey_templates (
    journey_template_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    template_key VARCHAR(100) NOT NULL,
    journey_type VARCHAR(24) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    task_blueprint JSONB NOT NULL DEFAULT '[]'::jsonb,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_tal_journey_template_key UNIQUE (tenant_id, template_key),
    CONSTRAINT uk_tal_journey_template_id UNIQUE (tenant_id, journey_template_id),
    CONSTRAINT ck_tal_journey_type CHECK (journey_type IN ('ONBOARDING', 'MOBILITY', 'GROWTH', 'RETURN', 'OFFBOARDING')),
    CONSTRAINT ck_tal_journey_blueprint CHECK (jsonb_typeof(task_blueprint) = 'array'),
    CONSTRAINT ck_tal_journey_state CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED'))
);

CREATE TABLE tal_journey_instances (
    journey_instance_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    journey_template_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    starts_on DATE NOT NULL,
    target_date DATE,
    progress_percent INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_tal_journey_worker FOREIGN KEY (tenant_id, worker_id)
        REFERENCES ppl_workers(tenant_id, worker_id),
    CONSTRAINT fk_tal_journey_template FOREIGN KEY (tenant_id, journey_template_id)
        REFERENCES tal_journey_templates(tenant_id, journey_template_id),
    CONSTRAINT ck_tal_journey_progress CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_tal_journey_dates CHECK (target_date IS NULL OR target_date >= starts_on),
    CONSTRAINT ck_tal_journey_instance_state CHECK (status IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED'))
);

CREATE TABLE tal_goals (
    goal_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    goal_key VARCHAR(160) NOT NULL,
    title VARCHAR(300) NOT NULL,
    description VARCHAR(2000),
    goal_type VARCHAR(24) NOT NULL,
    progress_percent INTEGER NOT NULL DEFAULT 0,
    due_date DATE,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    visibility VARCHAR(20) NOT NULL DEFAULT 'MANAGER',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_tal_goal_worker_key UNIQUE (tenant_id, worker_id, goal_key),
    CONSTRAINT fk_tal_goal_worker FOREIGN KEY (tenant_id, worker_id)
        REFERENCES ppl_workers(tenant_id, worker_id),
    CONSTRAINT ck_tal_goal_progress CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_tal_goal_type CHECK (goal_type IN ('PERFORMANCE', 'DEVELOPMENT', 'TEAM', 'ORGANIZATION')),
    CONSTRAINT ck_tal_goal_status CHECK (status IN ('DRAFT', 'ACTIVE', 'AT_RISK', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_tal_goal_visibility CHECK (visibility IN ('PRIVATE', 'MANAGER', 'TEAM', 'TENANT'))
);

CREATE TABLE tal_learning_assignments (
    learning_assignment_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    learning_key VARCHAR(160) NOT NULL,
    title VARCHAR(300) NOT NULL,
    provider_name VARCHAR(200),
    required BOOLEAN NOT NULL DEFAULT FALSE,
    progress_percent INTEGER NOT NULL DEFAULT 0,
    due_date DATE,
    status VARCHAR(24) NOT NULL DEFAULT 'ASSIGNED',
    source_reference VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_tal_learning_worker_key UNIQUE (tenant_id, worker_id, learning_key),
    CONSTRAINT fk_tal_learning_worker FOREIGN KEY (tenant_id, worker_id)
        REFERENCES ppl_workers(tenant_id, worker_id),
    CONSTRAINT ck_tal_learning_progress CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_tal_learning_state CHECK (status IN ('ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'WAIVED', 'EXPIRED'))
);

-- Tenant-safe reference foundation. Values are explicitly marked REFERENCE so
-- demonstrations cannot be confused with authoritative customer transactions.
CREATE OR REPLACE FUNCTION seed_hr_domain_foundation(p_tenant_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_schedule_id BIGINT;
    v_leave_plan_id BIGINT;
    v_program_id BIGINT;
    v_health_plan_id BIGINT;
    v_cycle_id BIGINT;
    v_journey_template_id BIGINT;
    v_year INTEGER := EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER;
BEGIN
    INSERT INTO tme_work_schedule_profiles (
        tenant_id, profile_key, name, time_zone, weekly_minutes,
        daily_pattern, created_by, updated_by)
    VALUES (
        p_tenant_id, 'STANDARD_40H', 'Standard 40-hour week', 'Asia/Seoul', 2400,
        '{"monday":480,"tuesday":480,"wednesday":480,"thursday":480,"friday":480,"saturday":0,"sunday":0}'::jsonb,
        1, 1)
    ON CONFLICT (tenant_id, profile_key) DO UPDATE SET
        lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
    RETURNING schedule_profile_id INTO v_schedule_id;

    INSERT INTO abs_leave_plans (
        tenant_id, plan_key, name, accrual_method, approval_policy,
        plan_rules, created_by, updated_by)
    VALUES (
        p_tenant_id, 'ANNUAL_LEAVE', 'Annual leave', 'ANNUAL', 'MANAGER',
        '{"referenceGrantMinutes":7200,"partialDay":true,"minimumMinutes":60}'::jsonb,
        1, 1)
    ON CONFLICT (tenant_id, plan_key) DO UPDATE SET
        lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
    RETURNING leave_plan_id INTO v_leave_plan_id;

    INSERT INTO bnf_benefit_programs (
        tenant_id, program_key, name, description, program_year,
        created_by, updated_by)
    VALUES (
        p_tenant_id, 'CORE_BENEFITS', 'Core benefits',
        'Reference benefit program awaiting tenant policy confirmation.', v_year, 1, 1)
    ON CONFLICT (tenant_id, program_key, program_year) DO UPDATE SET
        lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
    RETURNING benefit_program_id INTO v_program_id;

    INSERT INTO bnf_benefit_plans (
        tenant_id, benefit_program_id, plan_key, plan_type, name,
        provider_name, plan_summary, created_by, updated_by)
    VALUES (
        p_tenant_id, v_program_id, 'WELLNESS_CORE', 'WELLNESS',
        'Wellbeing support', NULL,
        '{"dataOrigin":"REFERENCE","coverage":"Employee wellbeing and preventive care"}'::jsonb,
        1, 1)
    ON CONFLICT (tenant_id, plan_key) DO UPDATE SET
        benefit_program_id = EXCLUDED.benefit_program_id,
        lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
    RETURNING benefit_plan_id INTO v_health_plan_id;

    INSERT INTO pay_pay_cycles (
        tenant_id, cycle_key, name, period_start_date, period_end_date,
        pay_date, status, readiness, created_by, updated_by)
    VALUES (
        p_tenant_id, TO_CHAR(CURRENT_DATE, 'YYYY-MM'),
        TO_CHAR(CURRENT_DATE, 'YYYY-MM') || ' monthly payroll',
        DATE_TRUNC('month', CURRENT_DATE)::DATE,
        (DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month - 1 day')::DATE,
        (DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month - 1 day')::DATE,
        'COLLECTING',
        '{"dataOrigin":"REFERENCE","timeValidated":false,"absenceValidated":false,"sourceConfirmed":false}'::jsonb,
        1, 1)
    ON CONFLICT (tenant_id, cycle_key) DO UPDATE SET
        updated_at = CURRENT_TIMESTAMP
    RETURNING pay_cycle_id INTO v_cycle_id;

    INSERT INTO tal_journey_templates (
        tenant_id, template_key, journey_type, name, description,
        task_blueprint, created_by, updated_by)
    VALUES (
        p_tenant_id, 'GROWTH_FOUNDATION', 'GROWTH', 'Growth foundation',
        'A role-aware employee growth journey.',
        '[{"key":"profile","title":"Review your career profile"},{"key":"goal","title":"Confirm one growth goal"},{"key":"learning","title":"Choose a learning activity"}]'::jsonb,
        1, 1)
    ON CONFLICT (tenant_id, template_key) DO UPDATE SET
        lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
    RETURNING journey_template_id INTO v_journey_template_id;

    INSERT INTO tme_worker_schedule_assignments (
        tenant_id, worker_id, schedule_profile_id, effective_start_date,
        data_origin, created_by, updated_by)
    SELECT p_tenant_id, worker.worker_id, v_schedule_id,
           COALESCE(worker.original_hire_date, CURRENT_DATE), 'REFERENCE', 1, 1
      FROM ppl_workers worker
     WHERE worker.tenant_id = p_tenant_id
       AND worker.worker_status IN ('ACTIVE', 'LEAVE')
    ON CONFLICT (tenant_id, worker_id, effective_start_date) DO NOTHING;

    INSERT INTO tme_time_cards (
        tenant_id, worker_id, period_start_date, period_end_date,
        status, scheduled_minutes, recorded_minutes, exception_count,
        data_origin, created_by, updated_by)
    SELECT p_tenant_id, worker.worker_id,
           DATE_TRUNC('week', CURRENT_DATE)::DATE,
           (DATE_TRUNC('week', CURRENT_DATE) + INTERVAL '6 days')::DATE,
           'OPEN', 2400, 0, 0, 'REFERENCE', 1, 1
      FROM ppl_workers worker
     WHERE worker.tenant_id = p_tenant_id
       AND worker.worker_status = 'ACTIVE'
    ON CONFLICT (tenant_id, worker_id, period_start_date) DO NOTHING;

    INSERT INTO tme_time_entries (
        tenant_id, time_card_id, worker_id, work_date, entry_type,
        minutes, work_mode, note, source_reference, created_by, updated_by)
    SELECT p_tenant_id, card.time_card_id, card.worker_id, work_day::DATE,
           'WORK', 480, 'HYBRID',
           'Reference attendance entry', 'reference:time-foundation', 1, 1
      FROM tme_time_cards card
      CROSS JOIN LATERAL GENERATE_SERIES(
          card.period_start_date::TIMESTAMP,
          LEAST(CURRENT_DATE - 1, card.period_start_date + 4)::TIMESTAMP,
          INTERVAL '1 day') work_day
     WHERE card.tenant_id = p_tenant_id
       AND card.period_start_date = DATE_TRUNC('week', CURRENT_DATE)::DATE
       AND CURRENT_DATE > card.period_start_date
    ON CONFLICT (tenant_id, time_card_id, work_date, entry_type)
        WHERE lifecycle_state = 'ACTIVE'
    DO NOTHING;

    UPDATE tme_time_cards card
       SET recorded_minutes = summary.recorded_minutes,
           updated_at = CURRENT_TIMESTAMP
      FROM (
          SELECT entry.tenant_id, entry.time_card_id, SUM(entry.minutes)::INTEGER recorded_minutes
            FROM tme_time_entries entry
           WHERE entry.tenant_id = p_tenant_id
             AND entry.lifecycle_state = 'ACTIVE'
           GROUP BY entry.tenant_id, entry.time_card_id
      ) summary
     WHERE card.tenant_id = summary.tenant_id
       AND card.time_card_id = summary.time_card_id;

    INSERT INTO abs_worker_plan_enrollments (
        tenant_id, worker_id, leave_plan_id, effective_start_date,
        eligibility_reason, created_by, updated_by)
    SELECT p_tenant_id, worker.worker_id, v_leave_plan_id,
           COALESCE(worker.original_hire_date, CURRENT_DATE),
           'Reference employee eligibility', 1, 1
      FROM ppl_workers worker
     WHERE worker.tenant_id = p_tenant_id
       AND worker.worker_type = 'EMPLOYEE'
       AND worker.worker_status IN ('ACTIVE', 'LEAVE')
    ON CONFLICT (tenant_id, worker_id, leave_plan_id, effective_start_date) DO NOTHING;

    INSERT INTO abs_leave_balances (
        tenant_id, worker_id, leave_plan_id, balance_year,
        granted_minutes, used_minutes, pending_minutes, adjustment_minutes,
        as_of_date, data_origin, created_by, updated_by)
    SELECT p_tenant_id, worker.worker_id, v_leave_plan_id, v_year,
           7200, 0, 0, 0, CURRENT_DATE, 'REFERENCE', 1, 1
      FROM ppl_workers worker
     WHERE worker.tenant_id = p_tenant_id
       AND worker.worker_type = 'EMPLOYEE'
       AND worker.worker_status IN ('ACTIVE', 'LEAVE')
    ON CONFLICT (tenant_id, worker_id, leave_plan_id, balance_year) DO NOTHING;

    INSERT INTO bnf_enrollments (
        tenant_id, worker_id, benefit_plan_id, coverage_level,
        effective_start_date, status, source_reference, created_by, updated_by)
    SELECT p_tenant_id, worker.worker_id, v_health_plan_id, 'EMPLOYEE',
           DATE_TRUNC('year', CURRENT_DATE)::DATE, 'ACTIVE',
           'reference:benefits-foundation', 1, 1
      FROM ppl_workers worker
     WHERE worker.tenant_id = p_tenant_id
       AND worker.worker_type = 'EMPLOYEE'
       AND worker.worker_status IN ('ACTIVE', 'LEAVE')
    ON CONFLICT (tenant_id, worker_id, benefit_plan_id, effective_start_date) DO NOTHING;

    INSERT INTO pay_statement_references (
        tenant_id, worker_id, pay_cycle_id, statement_period_label,
        document_reference, availability_state, created_by, updated_by)
    SELECT p_tenant_id, worker.worker_id, v_cycle_id,
           TO_CHAR(CURRENT_DATE, 'YYYY-MM'),
           'reference://pay-statement/' || p_tenant_id || '/' || worker.worker_id || '/' || TO_CHAR(CURRENT_DATE, 'YYYY-MM'),
           'PENDING', 1, 1
      FROM ppl_workers worker
     WHERE worker.tenant_id = p_tenant_id
       AND worker.worker_type = 'EMPLOYEE'
       AND worker.worker_status IN ('ACTIVE', 'LEAVE')
    ON CONFLICT (tenant_id, worker_id, pay_cycle_id) DO NOTHING;

    INSERT INTO tal_journey_instances (
        tenant_id, journey_template_id, worker_id, starts_on,
        target_date, progress_percent, status, created_by, updated_by)
    SELECT p_tenant_id, v_journey_template_id, worker.worker_id,
           CURRENT_DATE, CURRENT_DATE + 90, 0, 'ACTIVE', 1, 1
      FROM ppl_workers worker
     WHERE worker.tenant_id = p_tenant_id
       AND worker.worker_status = 'ACTIVE'
       AND NOT EXISTS (
           SELECT 1 FROM tal_journey_instances instance
            WHERE instance.tenant_id = p_tenant_id
              AND instance.journey_template_id = v_journey_template_id
              AND instance.worker_id = worker.worker_id
              AND instance.status = 'ACTIVE');

    INSERT INTO tal_goals (
        tenant_id, worker_id, goal_key, title, description, goal_type,
        progress_percent, due_date, status, visibility, created_by, updated_by)
    SELECT p_tenant_id, worker.worker_id, 'REFERENCE_GROWTH_' || v_year,
           'Define a growth outcome',
           'Reference goal. Replace it with a manager-aligned employee goal.',
           'DEVELOPMENT', 20, CURRENT_DATE + 90, 'ACTIVE', 'MANAGER', 1, 1
      FROM ppl_workers worker
     WHERE worker.tenant_id = p_tenant_id AND worker.worker_status = 'ACTIVE'
    ON CONFLICT (tenant_id, worker_id, goal_key) DO NOTHING;

    INSERT INTO tal_learning_assignments (
        tenant_id, worker_id, learning_key, title, provider_name,
        required, progress_percent, due_date, status, source_reference,
        created_by, updated_by)
    SELECT p_tenant_id, worker.worker_id, 'REFERENCE_DATA_LITERACY_' || v_year,
           'Responsible data and AI foundations', 'DWP Learning',
           TRUE, 0, CURRENT_DATE + 45, 'ASSIGNED',
           'reference:learning-foundation', 1, 1
      FROM ppl_workers worker
     WHERE worker.tenant_id = p_tenant_id AND worker.worker_status = 'ACTIVE'
    ON CONFLICT (tenant_id, worker_id, learning_key) DO NOTHING;
END;
$$;

SELECT seed_hr_domain_foundation(tenant_id)
  FROM sys_service_tenants
 WHERE lifecycle_state IN ('PROVISIONING', 'ACTIVE');

COMMENT ON TABLE ppl_persons IS 'Core HR person projection; not a time, leave, benefits, payroll, or talent transaction store.';
COMMENT ON TABLE tme_time_cards IS 'Tenant-scoped time-card workflow and period decision state.';
COMMENT ON TABLE abs_leave_requests IS 'Employee absence request workflow; balance changes are applied only after approval.';
COMMENT ON TABLE pay_statement_references IS 'Opaque references to encrypted payroll statements. Monetary payloads remain in the payroll system of record.';
COMMENT ON FUNCTION seed_hr_domain_foundation(BIGINT) IS 'Idempotently creates tenant HR domain reference foundations without authoritative customer transactions.';

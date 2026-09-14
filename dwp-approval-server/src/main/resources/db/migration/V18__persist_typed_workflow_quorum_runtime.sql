-- Only explicitly tagged workflow definitions use this runtime. Legacy ANY stays unchanged.
ALTER TABLE apr_steps DROP CONSTRAINT ck_apr_step_mode,
    ADD CONSTRAINT ck_apr_step_mode CHECK (approval_mode IN ('ANY', 'ALL', 'SEQUENTIAL', 'COUNT', 'PERCENT'));
ALTER TABLE apr_tasks ADD CONSTRAINT uk_apr_task_exact_stage
    UNIQUE (tenant_id, request_id, step_id, task_id);

CREATE TABLE apr_quorum_stage_runtime (
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    step_id UUID NOT NULL,
    generation BIGINT NOT NULL CHECK (generation > 0),
    stage_key VARCHAR(100) NOT NULL,
    context JSONB NOT NULL CHECK (jsonb_typeof(context) = 'object'),
    definition_canonical TEXT NOT NULL,
    definition_sha256 CHAR(64) NOT NULL CHECK (definition_sha256 ~ '^[a-f0-9]{64}$'),
    snapshot JSONB CHECK (jsonb_typeof(snapshot) = 'object'),
    candidate_sha256 CHAR(64) CHECK (candidate_sha256 ~ '^[a-f0-9]{64}$'),
    eligible_count INTEGER CHECK (eligible_count BETWEEN 1 AND 1000),
    threshold INTEGER CHECK (threshold BETWEEN 1 AND eligible_count),
    status VARCHAR(24) NOT NULL CHECK (status IN ('WAITING', 'IN_PROGRESS', 'APPROVED', 'REJECTED', 'CANCELLED', 'SKIPPED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    opened_at TIMESTAMPTZ,
    due_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, request_id, step_id, generation),
    UNIQUE (tenant_id, request_id, stage_key, generation),
    FOREIGN KEY (tenant_id, request_id, step_id) REFERENCES apr_steps(tenant_id, request_id, step_id),
    CHECK (encode(sha256(convert_to(definition_canonical, 'UTF8')), 'hex') = definition_sha256),
    CHECK ((context->'pins'->>'tenantId')::bigint = tenant_id),
    CHECK (snapshot IS NULL OR (
        (snapshot->>'requestId')::uuid=request_id AND (snapshot->>'stepId')::uuid=step_id
        AND (snapshot->>'generation')::bigint=generation AND snapshot->'pins'=context->'pins'
        AND jsonb_array_length(snapshot->'candidates')=eligible_count
        AND threshold=CASE snapshot->'rule'->>'mode'
            WHEN 'ANY' THEN 1 WHEN 'ALL' THEN eligible_count
            WHEN 'COUNT' THEN (snapshot->'rule'->>'value')::int
            WHEN 'PERCENT' THEN ceil(eligible_count::numeric*(snapshot->'rule'->>'value')::int/100)::int
            ELSE 0 END)),
    CHECK ((snapshot IS NULL AND eligible_count IS NULL AND threshold IS NULL AND candidate_sha256 IS NULL
            AND opened_at IS NULL AND due_at IS NULL AND status IN ('WAITING', 'CANCELLED', 'SKIPPED'))
        OR (snapshot IS NOT NULL AND eligible_count IS NOT NULL AND threshold IS NOT NULL
            AND candidate_sha256 IS NOT NULL AND opened_at IS NOT NULL AND due_at > opened_at
            AND status <> 'WAITING')),
    CHECK (completed_at IS NULL OR completed_at >= opened_at)
);

CREATE TABLE apr_quorum_candidates (
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    step_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    principal_user_id BIGINT NOT NULL CHECK (principal_user_id > 0),
    principal_person_id UUID NOT NULL,
    task_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, request_id, step_id, generation, principal_user_id),
    UNIQUE (tenant_id, request_id, step_id, generation, principal_person_id),
    UNIQUE (tenant_id, request_id, step_id, generation, principal_user_id, principal_person_id, task_id),
    FOREIGN KEY (tenant_id, request_id, step_id, generation)
        REFERENCES apr_quorum_stage_runtime(tenant_id, request_id, step_id, generation),
    FOREIGN KEY (tenant_id, request_id, step_id, task_id)
        REFERENCES apr_tasks(tenant_id, request_id, step_id, task_id)
);

CREATE TABLE apr_quorum_votes (
    vote_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    step_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    stage_version BIGINT NOT NULL CHECK (stage_version > 0),
    actor_user_id BIGINT NOT NULL CHECK (actor_user_id > 0),
    actor_person_id UUID NOT NULL,
    principal_user_id BIGINT NOT NULL,
    principal_person_id UUID NOT NULL,
    task_id UUID NOT NULL,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    evidence JSONB NOT NULL CHECK (jsonb_typeof(evidence) = 'object'),
    accepted_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, request_id, step_id, generation, stage_version),
    UNIQUE (tenant_id, request_id, step_id, generation, actor_user_id),
    UNIQUE (tenant_id, request_id, step_id, generation, actor_person_id),
    UNIQUE (tenant_id, request_id, step_id, generation, principal_user_id),
    FOREIGN KEY (tenant_id, request_id, step_id, generation, principal_user_id, principal_person_id, task_id)
        REFERENCES apr_quorum_candidates(tenant_id, request_id, step_id, generation,
            principal_user_id, principal_person_id, task_id)
);

CREATE TABLE apr_quorum_prerequisites (
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    step_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    predecessor_step_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, request_id, step_id, generation, predecessor_step_id),
    CHECK (step_id <> predecessor_step_id),
    FOREIGN KEY (tenant_id, request_id, step_id, generation)
        REFERENCES apr_quorum_stage_runtime(tenant_id, request_id, step_id, generation),
    FOREIGN KEY (tenant_id, request_id, predecessor_step_id, generation)
        REFERENCES apr_quorum_stage_runtime(tenant_id, request_id, step_id, generation)
);

CREATE TABLE apr_quorum_sla_timers (
    timer_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    step_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('WARNING', 'BREACH')),
    due_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'CLAIMED', 'COMPLETED', 'CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    lease_epoch BIGINT NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
    lease_owner VARCHAR(160),
    lease_until TIMESTAMPTZ,
    event_id UUID,
    UNIQUE (tenant_id, request_id, step_id, generation, policy_version, kind),
    FOREIGN KEY (tenant_id, request_id, step_id, generation)
        REFERENCES apr_quorum_stage_runtime(tenant_id, request_id, step_id, generation),
    CHECK ((status = 'CLAIMED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'CLAIMED' AND lease_owner IS NULL AND lease_until IS NULL)),
    CHECK ((status = 'COMPLETED') = (event_id IS NOT NULL))
);
CREATE INDEX idx_apr_quorum_timer_claim ON apr_quorum_sla_timers(due_at, lease_until)
    WHERE status IN ('PENDING', 'CLAIMED');

CREATE FUNCTION protect_apr_quorum_immutable_row() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Quorum candidates, votes and prerequisites are immutable';
END;
$$;
CREATE TRIGGER trg_apr_quorum_candidates_immutable BEFORE UPDATE OR DELETE ON apr_quorum_candidates
    FOR EACH ROW EXECUTE FUNCTION protect_apr_quorum_immutable_row();
CREATE TRIGGER trg_apr_quorum_votes_immutable BEFORE UPDATE OR DELETE ON apr_quorum_votes
    FOR EACH ROW EXECUTE FUNCTION protect_apr_quorum_immutable_row();
CREATE TRIGGER trg_apr_quorum_prerequisites_immutable BEFORE UPDATE OR DELETE ON apr_quorum_prerequisites
    FOR EACH ROW EXECUTE FUNCTION protect_apr_quorum_immutable_row();

CREATE FUNCTION protect_apr_quorum_stage() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Quorum stage evidence is immutable'; END IF;
    IF (NEW.tenant_id, NEW.request_id, NEW.step_id, NEW.generation, NEW.stage_key, NEW.context,
        NEW.definition_canonical, NEW.definition_sha256) IS DISTINCT FROM
       (OLD.tenant_id, OLD.request_id, OLD.step_id, OLD.generation, OLD.stage_key, OLD.context,
        OLD.definition_canonical, OLD.definition_sha256) THEN
        RAISE EXCEPTION 'Quorum stage pins are immutable';
    END IF;
    IF OLD.snapshot IS NOT NULL AND
       (NEW.snapshot, NEW.candidate_sha256, NEW.eligible_count, NEW.threshold, NEW.opened_at, NEW.due_at)
       IS DISTINCT FROM
       (OLD.snapshot, OLD.candidate_sha256, OLD.eligible_count, OLD.threshold, OLD.opened_at, OLD.due_at) THEN
        RAISE EXCEPTION 'Activated quorum candidate pool is frozen';
    END IF;
    IF NEW.version <> OLD.version + 1 OR
       NOT ((OLD.status = 'WAITING' AND NEW.status IN ('IN_PROGRESS', 'CANCELLED', 'SKIPPED')) OR
            (OLD.status = 'IN_PROGRESS' AND NEW.status IN ('IN_PROGRESS', 'APPROVED', 'REJECTED', 'CANCELLED'))) THEN
        RAISE EXCEPTION 'Invalid quorum stage transition or CAS version';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_apr_quorum_stage_frozen BEFORE UPDATE OR DELETE ON apr_quorum_stage_runtime
    FOR EACH ROW EXECUTE FUNCTION protect_apr_quorum_stage();

CREATE FUNCTION validate_apr_quorum_candidate() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE frozen JSONB;
BEGIN
    SELECT snapshot INTO frozen FROM apr_quorum_stage_runtime
     WHERE tenant_id=NEW.tenant_id AND request_id=NEW.request_id AND step_id=NEW.step_id
       AND generation=NEW.generation AND status='IN_PROGRESS' FOR SHARE;
    IF frozen IS NULL OR NOT EXISTS (
        SELECT 1 FROM jsonb_array_elements(frozen->'candidates') candidate
         WHERE (candidate->>'userId')::bigint=NEW.principal_user_id
           AND (candidate->>'personPublicId')::uuid=NEW.principal_person_id
    ) OR NOT EXISTS (
        SELECT 1 FROM apr_tasks task WHERE task.tenant_id=NEW.tenant_id AND task.request_id=NEW.request_id
         AND task.step_id=NEW.step_id AND task.task_id=NEW.task_id AND task.assignee_user_id=NEW.principal_user_id
         AND task.assignee_person_public_id=NEW.principal_person_id AND task.status='CLAIMED'
    ) THEN RAISE EXCEPTION 'Quorum candidate does not match the frozen pool and exact task'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_apr_quorum_candidate_exact BEFORE INSERT ON apr_quorum_candidates
    FOR EACH ROW EXECUTE FUNCTION validate_apr_quorum_candidate();

CREATE FUNCTION validate_apr_quorum_vote() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE runtime apr_quorum_stage_runtime; evidence JSONB;
BEGIN
    SELECT * INTO runtime FROM apr_quorum_stage_runtime
     WHERE tenant_id=NEW.tenant_id AND request_id=NEW.request_id AND step_id=NEW.step_id
       AND generation=NEW.generation FOR SHARE;
    evidence := NEW.evidence;
    IF runtime.status IS DISTINCT FROM 'IN_PROGRESS' OR NEW.stage_version<>runtime.version+1
       OR evidence->'pins' IS DISTINCT FROM runtime.snapshot->'pins'
       OR (evidence->>'requestId')::uuid IS DISTINCT FROM NEW.request_id
       OR (evidence->>'stepId')::uuid IS DISTINCT FROM NEW.step_id
       OR (evidence->>'generation')::bigint IS DISTINCT FROM NEW.generation
       OR (evidence->>'stageVersion')::bigint IS DISTINCT FROM NEW.stage_version
       OR (evidence->>'actorUserId')::bigint IS DISTINCT FROM NEW.actor_user_id
       OR (evidence->>'actorPersonPublicId')::uuid IS DISTINCT FROM NEW.actor_person_id
       OR (evidence->>'principalUserId')::bigint IS DISTINCT FROM NEW.principal_user_id
       OR (evidence->>'principalPersonPublicId')::uuid IS DISTINCT FROM NEW.principal_person_id
       OR evidence->>'decision' IS DISTINCT FROM NEW.decision
       OR (evidence->>'acceptedAt')::timestamptz IS DISTINCT FROM NEW.accepted_at
       OR evidence->'payloadRevision' IS DISTINCT FROM runtime.snapshot->'payloadRevision'
       OR evidence->'payloadSha256' IS DISTINCT FROM runtime.snapshot->'payloadSha256'
       OR NEW.accepted_at<runtime.opened_at
       OR NEW.actor_user_id=(runtime.snapshot->>'requesterUserId')::bigint
       OR NEW.principal_user_id=(runtime.snapshot->>'requesterUserId')::bigint
       OR NEW.actor_person_id=(runtime.snapshot->>'requesterPersonPublicId')::uuid
       OR NEW.principal_person_id=(runtime.snapshot->>'requesterPersonPublicId')::uuid
       OR evidence->>'activeAccessMode' IS NULL OR evidence->>'activeAccessMode' NOT IN ('NORMAL','ELEVATED')
       OR evidence->>'authorityRevision' IS NULL OR evidence->>'authorityRevision' !~ '^[A-Za-z0-9._:-]{1,120}$'
       OR jsonb_typeof(evidence->'reason') IS DISTINCT FROM 'string'
       OR length(evidence->>'reason')>2000 OR btrim(evidence->>'reason') IS DISTINCT FROM evidence->>'reason'
       OR ((NEW.actor_user_id=NEW.principal_user_id) IS DISTINCT FROM (evidence->>'delegationId' IS NULL))
       OR (NEW.decision='REJECT' AND length(evidence->>'reason')<(runtime.snapshot->>'minimumRejectReasonLength')::int)
    THEN RAISE EXCEPTION 'Quorum vote does not match the exact frozen runtime evidence'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_apr_quorum_vote_exact BEFORE INSERT ON apr_quorum_votes
    FOR EACH ROW EXECUTE FUNCTION validate_apr_quorum_vote();

CREATE FUNCTION approval_quorum_canonical_json(value JSONB) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE result TEXT;
BEGIN
    CASE jsonb_typeof(value)
        WHEN 'object' THEN SELECT '{' || COALESCE(string_agg(to_json(key)::text || ':' ||
            approval_quorum_canonical_json(item), ',' ORDER BY key COLLATE "C"), '') || '}'
            INTO result FROM jsonb_each(value) AS entry(key, item);
        WHEN 'array' THEN SELECT '[' || COALESCE(string_agg(approval_quorum_canonical_json(item),
            ',' ORDER BY ordinal), '') || ']' INTO result
            FROM jsonb_array_elements(value) WITH ORDINALITY AS entry(item, ordinal);
        ELSE result := value::text;
    END CASE;
    RETURN result;
END;
$$;
CREATE FUNCTION protect_published_quorum_workflow() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' AND OLD.definition->>'schemaContract' = 'DWP_APPROVAL_WORKFLOW_QUORUM_V2'
       AND OLD.lifecycle_state = 'PUBLISHED' THEN
        IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Published quorum workflow is immutable'; END IF;
        IF NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'Published quorum workflow is immutable'; END IF;
    END IF;
    IF TG_OP <> 'DELETE' AND NEW.definition->>'schemaContract' = 'DWP_APPROVAL_WORKFLOW_QUORUM_V2'
       AND (NEW.definition->>'schemaVersion' IS DISTINCT FROM '2' OR
            NEW.definition_sha256::text IS DISTINCT FROM encode(sha256(convert_to(
                approval_quorum_canonical_json(NEW.definition), 'UTF8')), 'hex')) THEN
        RAISE EXCEPTION 'Quorum workflow canonical hash is invalid';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM apr_workflow_versions
        WHERE definition->>'schemaContract'='DWP_APPROVAL_WORKFLOW_QUORUM_V2'
          AND (definition->>'schemaVersion' IS DISTINCT FROM '2' OR definition_sha256::text IS DISTINCT FROM
              encode(sha256(convert_to(approval_quorum_canonical_json(definition),'UTF8')),'hex'))) THEN
        RAISE EXCEPTION 'Existing quorum workflow canonical hash is invalid';
    END IF;
END;
$$;
CREATE TRIGGER trg_published_quorum_workflow BEFORE INSERT OR UPDATE OR DELETE ON apr_workflow_versions
    FOR EACH ROW EXECUTE FUNCTION protect_published_quorum_workflow();

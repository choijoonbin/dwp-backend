-- Managed execution is opt-in and remains unreachable without real Auth and owner ports.
-- Existing V24/V28 deletion permits and the V33 exact 39-table inventory are unchanged.
CREATE TABLE public.apr_retention_managed_executions (
    tenant_id BIGINT NOT NULL,
    intent_id UUID PRIMARY KEY,
    stage VARCHAR(24) NOT NULL CHECK(stage IN('AUTHORITY','OBJECTS','FOREIGN','LOCAL_DB','FINALIZE','COMPLETE')),
    state VARCHAR(16) NOT NULL CHECK(state IN('READY','RUNNING','UNKNOWN','BLOCKED','COMPLETE')),
    version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
    generation BIGINT NOT NULL DEFAULT 0 CHECK(generation>=0),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count BETWEEN 0 AND 1000),
    lease_token UUID,
    lease_owner VARCHAR(120),
    lease_until TIMESTAMPTZ,
    last_reason VARCHAR(120) NOT NULL,
    outcome_sha256 CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,intent_id),
    FOREIGN KEY(tenant_id,intent_id) REFERENCES public.apr_retention_dispatch_intents(tenant_id,intent_id),
    CHECK(lease_owner IS NULL OR lease_owner ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$'),
    CHECK((lease_token IS NULL AND lease_owner IS NULL AND lease_until IS NULL)
       OR (lease_token IS NOT NULL AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK((state='RUNNING')=(lease_token IS NOT NULL)),
    CHECK(outcome_sha256 IS NULL OR outcome_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK(state<>'COMPLETE' OR (stage='COMPLETE' AND outcome_sha256 IS NOT NULL))
);

CREATE TABLE public.apr_retention_managed_execution_events (
    event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    intent_id UUID NOT NULL,
    generation BIGINT NOT NULL CHECK(generation>=0),
    stage VARCHAR(24) NOT NULL CHECK(stage IN('AUTHORITY','OBJECTS','FOREIGN','LOCAL_DB','FINALIZE','COMPLETE')),
    outcome VARCHAR(24) NOT NULL CHECK(outcome IN('CLAIMED','SUCCEEDED','WAITING','UNKNOWN','BLOCKED','RECOVERY_REQUESTED')),
    reason_code VARCHAR(120) NOT NULL CHECK(reason_code ~ '^[A-Z][A-Z0-9_]{2,119}$'),
    evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[a-f0-9]{64}$'),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(tenant_id,intent_id) REFERENCES public.apr_retention_managed_executions(tenant_id,intent_id)
);
CREATE INDEX ix_retention_managed_events_intent
    ON public.apr_retention_managed_execution_events(tenant_id,intent_id,occurred_at,event_id);

CREATE TABLE public.apr_retention_foreign_delivery_states (
    deletion_request_id UUID PRIMARY KEY REFERENCES public.apr_retention_foreign_requests(deletion_request_id),
    tenant_id BIGINT NOT NULL,
    intent_id UUID NOT NULL,
    consumer_service VARCHAR(24) NOT NULL CHECK(consumer_service IN('AUDIT','NOTIFICATION')),
    request_sha256 CHAR(64) NOT NULL CHECK(request_sha256 ~ '^[a-f0-9]{64}$'),
    state VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK(state IN('READY','DISPATCHING','UNKNOWN','ACKNOWLEDGED','BLOCKED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
    generation BIGINT NOT NULL DEFAULT 0 CHECK(generation>=0),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count BETWEEN 0 AND 100),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_reason VARCHAR(120) NOT NULL DEFAULT 'OWNER_DISPATCH_PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,intent_id,consumer_service,deletion_request_id),
    FOREIGN KEY(deletion_request_id,tenant_id,consumer_service,request_sha256)
        REFERENCES public.apr_retention_foreign_requests(deletion_request_id,tenant_id,consumer_service,request_sha256),
    FOREIGN KEY(tenant_id,intent_id) REFERENCES public.apr_retention_managed_executions(tenant_id,intent_id),
    CHECK((state='DISPATCHING')=(lease_token IS NOT NULL)),
    CHECK((lease_token IS NULL AND lease_until IS NULL) OR (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);

CREATE FUNCTION apr_retention_internal.guard_managed_execution() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF TG_OP='DELETE' OR NEW.tenant_id<>OLD.tenant_id OR NEW.intent_id<>OLD.intent_id
       OR NEW.created_at<>OLD.created_at OR NEW.version<>OLD.version+1
       OR (OLD.state='COMPLETE' AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Managed retention execution identity or fence changed' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_retention_managed_execution_guard BEFORE UPDATE OR DELETE
    ON public.apr_retention_managed_executions FOR EACH ROW
    EXECUTE FUNCTION apr_retention_internal.guard_managed_execution();

CREATE FUNCTION apr_retention_internal.guard_foreign_delivery_state() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF TG_OP='DELETE' OR NEW.deletion_request_id<>OLD.deletion_request_id
       OR NEW.tenant_id<>OLD.tenant_id OR NEW.intent_id<>OLD.intent_id
       OR NEW.consumer_service<>OLD.consumer_service OR NEW.request_sha256<>OLD.request_sha256
       OR NEW.created_at<>OLD.created_at OR NEW.version<>OLD.version+1
       OR (OLD.state='ACKNOWLEDGED' AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Foreign retention delivery identity or fence changed' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_retention_foreign_delivery_guard BEFORE UPDATE OR DELETE
    ON public.apr_retention_foreign_delivery_states FOR EACH ROW
    EXECUTE FUNCTION apr_retention_internal.guard_foreign_delivery_state();
CREATE TRIGGER trg_retention_managed_events_immutable BEFORE UPDATE OR DELETE
    ON public.apr_retention_managed_execution_events FOR EACH ROW
    EXECUTE FUNCTION apr_retention_internal.immutable();

CREATE FUNCTION apr_retention_internal.managed_event(p_tenant BIGINT,p_intent UUID,p_generation BIGINT,
    p_stage TEXT,p_outcome TEXT,p_reason TEXT,p_material TEXT) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    IF p_reason IS NULL OR p_reason!~'^[A-Z][A-Z0-9_]{2,119}$' THEN
        RAISE EXCEPTION 'Closed managed retention reason required' USING ERRCODE='23514';
    END IF;
    INSERT INTO public.apr_retention_managed_execution_events(event_id,tenant_id,intent_id,generation,stage,outcome,reason_code,evidence_sha256)
    VALUES(gen_random_uuid(),p_tenant,p_intent,p_generation,p_stage,p_outcome,p_reason,
        encode(sha256(convert_to(COALESCE(p_material,'')||':'||p_intent||':'||p_generation||':'||p_stage||':'||p_outcome||':'||p_reason,'UTF8')),'hex'));
END $$;

CREATE FUNCTION apr_retention_internal.assert_managed_lease(p_intent UUID,p_generation BIGINT,p_token UUID)
RETURNS public.apr_retention_managed_executions
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE execution public.apr_retention_managed_executions;
BEGIN
    PERFORM apr_retention_internal.require_executor();
    SELECT * INTO execution FROM public.apr_retention_managed_executions
      WHERE intent_id=p_intent FOR UPDATE;
    IF execution.intent_id IS NULL OR execution.state<>'RUNNING'
       OR execution.generation<>p_generation OR execution.lease_token IS DISTINCT FROM p_token
       OR execution.lease_until<=clock_timestamp() THEN
        RAISE EXCEPTION 'Managed retention lease changed or expired' USING ERRCODE='40001';
    END IF;
    RETURN execution;
END $$;

CREATE FUNCTION apr_retention_internal.assert_exact_foreign_request_set(p_intent UUID)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE valid BOOLEAN;
BEGIN
    PERFORM apr_retention_internal.require_executor();
    SELECT count(*)=2 AND bool_and(owner_set.actual_count=owner_set.declared_min
            AND owner_set.declared_min=owner_set.declared_max
            AND owner_set.first_index=0 AND owner_set.last_index=owner_set.declared_min-1)
      INTO valid
      FROM (SELECT f.consumer_service,count(*) actual_count,min(f.chunk_count) declared_min,
                   max(f.chunk_count) declared_max,min(f.chunk_index) first_index,max(f.chunk_index) last_index
              FROM public.apr_retention_foreign_requests f WHERE f.intent_id=p_intent
             GROUP BY f.consumer_service) owner_set;
    IF NOT COALESCE(valid,false) THEN
        RAISE EXCEPTION 'Exact foreign request inventory changed' USING ERRCODE='40001';
    END IF;
END $$;

CREATE FUNCTION apr_retention_internal.expire_managed_retention_leases(p_limit INTEGER DEFAULT 100)
RETURNS INTEGER LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE execution public.apr_retention_managed_executions; changed INTEGER:=0;
BEGIN
    PERFORM apr_retention_internal.require_executor();
    IF p_limit NOT BETWEEN 1 AND 100 THEN RAISE EXCEPTION 'Invalid recovery bound' USING ERRCODE='23514'; END IF;
    FOR execution IN SELECT * FROM public.apr_retention_managed_executions
        WHERE state='RUNNING' AND lease_until<=clock_timestamp()
        ORDER BY lease_until,intent_id FOR UPDATE SKIP LOCKED LIMIT p_limit
    LOOP
        UPDATE public.apr_retention_foreign_delivery_states SET state='UNKNOWN',version=version+1,
            lease_token=NULL,lease_until=NULL,last_reason='WORKER_LEASE_EXPIRED',updated_at=clock_timestamp()
          WHERE intent_id=execution.intent_id AND state='DISPATCHING'
            AND lease_token=execution.lease_token;
        UPDATE public.apr_retention_managed_executions SET state='UNKNOWN',version=version+1,
            lease_token=NULL,lease_owner=NULL,lease_until=NULL,last_reason='WORKER_LEASE_EXPIRED',updated_at=clock_timestamp()
          WHERE intent_id=execution.intent_id;
        PERFORM apr_retention_internal.managed_event(execution.tenant_id,execution.intent_id,execution.generation,
            execution.stage,'UNKNOWN','WORKER_LEASE_EXPIRED',execution.lease_token::text);
        changed:=changed+1;
    END LOOP;
    RETURN changed;
END $$;

CREATE FUNCTION apr_retention_internal.claim_managed_retention_execution(p_intent UUID,p_expected_intent_version BIGINT,
    p_worker TEXT,p_lease_seconds INTEGER)
RETURNS TABLE(intent_id UUID,tenant_id BIGINT,actor_user_id BIGINT,request_id UUID,resource_set_key TEXT,
    request_version BIGINT,policy_id UUID,policy_version BIGINT,hold_version BIGINT,inventory_sha256 TEXT,
    command_fingerprint TEXT,intent_version BIGINT,execution_claim_id UUID,stage TEXT,execution_version BIGINT,
    generation BIGINT,lease_token UUID)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE target public.apr_retention_dispatch_intents; execution public.apr_retention_managed_executions;
    token UUID:=gen_random_uuid(); next_stage TEXT; source_ready BOOLEAN;
BEGIN
    PERFORM apr_retention_internal.require_executor();
    IF p_worker IS NULL OR p_worker!~'^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$'
       OR p_lease_seconds NOT BETWEEN 30 AND 300 THEN
        RAISE EXCEPTION 'Invalid managed retention worker lease' USING ERRCODE='23514';
    END IF;
    SELECT i.* INTO target FROM public.apr_retention_dispatch_intents i
      LEFT JOIN public.apr_retention_managed_executions e ON e.intent_id=i.intent_id
      WHERE (p_intent IS NULL OR i.intent_id=p_intent)
        AND i.state IN('QUEUED','IRREVERSIBLE','LOCAL_DB_PURGED')
        AND (e.intent_id IS NULL OR e.state='READY')
      ORDER BY i.created_at,i.intent_id FOR UPDATE OF i SKIP LOCKED LIMIT 1;
    IF target.intent_id IS NULL THEN RETURN; END IF;
    IF p_expected_intent_version IS NOT NULL AND target.version<>p_expected_intent_version THEN
        RAISE EXCEPTION 'Intent version changed' USING ERRCODE='40001';
    END IF;
    IF target.state='QUEUED' THEN
        SELECT EXISTS(SELECT 1 FROM public.apr_retention_inventory_closures c
            JOIN public.apr_retention_original_command_witnesses w
              ON w.tenant_id=c.tenant_id AND w.command_id=c.command_id
            WHERE c.tenant_id=target.tenant_id AND c.intent_id=target.intent_id
              AND target.receipt_command_id=c.command_id
              AND c.dispatch_inventory_sha256=target.inventory_sha256
              AND c.witness_sha256=encode(sha256(convert_to(to_jsonb(w)::text,'UTF8')),'hex')
              AND w.retain_until<=clock_timestamp()) INTO source_ready;
        IF NOT source_ready THEN
            INSERT INTO public.apr_retention_managed_executions(tenant_id,intent_id,stage,state,last_reason)
            VALUES(target.tenant_id,target.intent_id,'AUTHORITY','READY','SOURCE_RETENTION_DEADLINE_PENDING')
            ON CONFLICT ON CONSTRAINT apr_retention_managed_executions_pkey DO NOTHING;
            RETURN;
        END IF;
    END IF;
    next_stage:=CASE target.state WHEN 'QUEUED' THEN 'AUTHORITY'
        WHEN 'IRREVERSIBLE' THEN 'OBJECTS' ELSE 'FINALIZE' END;
    INSERT INTO public.apr_retention_managed_executions(tenant_id,intent_id,stage,state,last_reason)
    VALUES(target.tenant_id,target.intent_id,next_stage,'READY','MANAGED_EXECUTION_READY')
    ON CONFLICT ON CONSTRAINT apr_retention_managed_executions_pkey DO NOTHING;
    SELECT * INTO execution FROM public.apr_retention_managed_executions
      WHERE public.apr_retention_managed_executions.intent_id=target.intent_id FOR UPDATE;
    IF execution.state<>'READY' THEN RETURN; END IF;
    IF execution.stage='AUTHORITY' AND target.state='IRREVERSIBLE' THEN execution.stage:='OBJECTS'; END IF;
    IF target.state='LOCAL_DB_PURGED' THEN execution.stage:='FINALIZE'; END IF;
    UPDATE public.apr_retention_managed_executions AS managed SET stage=execution.stage,state='RUNNING',version=managed.version+1,
        generation=managed.generation+1,attempt_count=managed.attempt_count+1,lease_token=token,lease_owner=p_worker,
        lease_until=clock_timestamp()+make_interval(secs=>p_lease_seconds),last_reason='LEASE_CLAIMED',updated_at=clock_timestamp()
      WHERE managed.intent_id=target.intent_id
      RETURNING managed.* INTO execution;
    PERFORM apr_retention_internal.managed_event(execution.tenant_id,execution.intent_id,execution.generation,
        execution.stage,'CLAIMED','LEASE_CLAIMED',execution.lease_owner||':'||execution.lease_token);
    RETURN QUERY SELECT target.intent_id,target.tenant_id,target.actor_user_id,target.request_id,target.resource_set_key::text,
        target.request_version,target.policy_id,target.policy_version,target.hold_version,target.inventory_sha256::text,
        target.command_fingerprint::text,target.version,target.execution_claim_id,execution.stage::text,execution.version,
        execution.generation,execution.lease_token;
END $$;

CREATE FUNCTION apr_retention_internal.recover_managed_retention_execution(p_intent UUID,p_expected_version BIGINT)
RETURNS BIGINT LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE execution public.apr_retention_managed_executions;
BEGIN
    PERFORM apr_retention_internal.require_executor();
    SELECT * INTO execution FROM public.apr_retention_managed_executions WHERE intent_id=p_intent FOR UPDATE;
    IF execution.intent_id IS NULL OR execution.state<>'UNKNOWN' OR execution.version<>p_expected_version
       OR execution.lease_token IS NOT NULL THEN
        RAISE EXCEPTION 'Unknown execution recovery version changed' USING ERRCODE='40001';
    END IF;
    UPDATE public.apr_retention_managed_executions SET state='READY',version=version+1,
        last_reason='EXPLICIT_RECOVERY_REQUESTED',updated_at=clock_timestamp()
      WHERE intent_id=p_intent RETURNING * INTO execution;
    PERFORM apr_retention_internal.managed_event(execution.tenant_id,execution.intent_id,execution.generation,
        execution.stage,'RECOVERY_REQUESTED','EXPLICIT_RECOVERY_REQUESTED',p_expected_version::text);
    RETURN execution.version;
END $$;

CREATE FUNCTION apr_retention_internal.managed_retention_execution_status(p_intent UUID)
RETURNS TABLE(stage TEXT,state TEXT,version BIGINT,generation BIGINT,last_reason TEXT,outcome_sha256 TEXT)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    PERFORM apr_retention_internal.require_executor();
    RETURN QUERY SELECT e.stage::text,e.state::text,e.version,e.generation,e.last_reason::text,e.outcome_sha256::text
      FROM public.apr_retention_managed_executions e WHERE e.intent_id=p_intent;
END $$;

CREATE FUNCTION apr_retention_internal.mark_managed_retention_unknown(p_intent UUID,p_generation BIGINT,p_token UUID,p_reason TEXT)
RETURNS BIGINT LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE execution public.apr_retention_managed_executions;
BEGIN
    execution:=apr_retention_internal.assert_managed_lease(p_intent,p_generation,p_token);
    IF p_reason IS NULL OR p_reason!~'^[A-Z][A-Z0-9_]{2,119}$' THEN
        RAISE EXCEPTION 'Closed unknown reason required' USING ERRCODE='23514'; END IF;
    UPDATE public.apr_retention_foreign_delivery_states AS delivery SET state='UNKNOWN',version=delivery.version+1,
        lease_token=NULL,lease_until=NULL,last_reason=p_reason,updated_at=clock_timestamp()
      WHERE delivery.intent_id=p_intent AND delivery.state='DISPATCHING' AND delivery.lease_token=p_token;
    UPDATE public.apr_retention_managed_executions SET state='UNKNOWN',version=version+1,
        lease_token=NULL,lease_owner=NULL,lease_until=NULL,last_reason=p_reason,updated_at=clock_timestamp()
      WHERE intent_id=p_intent RETURNING * INTO execution;
    PERFORM apr_retention_internal.managed_event(execution.tenant_id,p_intent,p_generation,execution.stage,
        'UNKNOWN',p_reason,p_token::text);
    RETURN execution.version;
END $$;

CREATE FUNCTION apr_retention_internal.block_managed_retention_execution(p_intent UUID,p_generation BIGINT,p_token UUID,p_reason TEXT)
RETURNS BIGINT LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE execution public.apr_retention_managed_executions; intent public.apr_retention_dispatch_intents;
BEGIN
    execution:=apr_retention_internal.assert_managed_lease(p_intent,p_generation,p_token);
    IF p_reason IS NULL OR p_reason!~'^[A-Z][A-Z0-9_]{2,119}$' THEN
        RAISE EXCEPTION 'Closed blocked reason required' USING ERRCODE='23514'; END IF;
    UPDATE public.apr_retention_foreign_delivery_states AS delivery SET state='BLOCKED',version=delivery.version+1,
        lease_token=NULL,lease_until=NULL,last_reason=p_reason,updated_at=clock_timestamp()
      WHERE delivery.intent_id=p_intent AND delivery.state IN('READY','UNKNOWN','DISPATCHING');
    UPDATE public.apr_retention_managed_executions SET state='BLOCKED',version=version+1,
        lease_token=NULL,lease_owner=NULL,lease_until=NULL,last_reason=p_reason,updated_at=clock_timestamp()
      WHERE intent_id=p_intent RETURNING * INTO execution;
    SELECT * INTO intent FROM public.apr_retention_dispatch_intents WHERE intent_id=p_intent FOR UPDATE;
    IF intent.state='QUEUED' THEN
        UPDATE public.apr_retention_dispatch_intents SET state='BLOCKED',version=version+1,reason_code=p_reason
          WHERE intent_id=p_intent;
    END IF;
    PERFORM apr_retention_internal.managed_event(execution.tenant_id,p_intent,p_generation,execution.stage,
        'BLOCKED',p_reason,p_token::text);
    RETURN execution.version;
END $$;

CREATE FUNCTION apr_retention_internal.dispatch_managed_retention_record(p_intent UUID,p_intent_version BIGINT,
    p_generation BIGINT,p_token UUID,p_authority_sha256 TEXT)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE execution public.apr_retention_managed_executions; claim UUID;
BEGIN
    execution:=apr_retention_internal.assert_managed_lease(p_intent,p_generation,p_token);
    IF execution.stage<>'AUTHORITY' THEN RAISE EXCEPTION 'Authority stage changed' USING ERRCODE='40001'; END IF;
    claim:=apr_retention_internal.dispatch_record(p_intent,p_intent_version,p_authority_sha256);
    IF claim IS NULL THEN RAISE EXCEPTION 'Exact execution claim required' USING ERRCODE='55000'; END IF;
    UPDATE public.apr_retention_managed_executions SET stage='OBJECTS',state='READY',version=version+1,
        lease_token=NULL,lease_owner=NULL,lease_until=NULL,last_reason='CURRENT_AUTHORITY_AND_INVENTORY_VERIFIED',updated_at=clock_timestamp()
      WHERE intent_id=p_intent RETURNING * INTO execution;
    PERFORM apr_retention_internal.managed_event(execution.tenant_id,p_intent,p_generation,'AUTHORITY',
        'SUCCEEDED','CURRENT_AUTHORITY_AND_INVENTORY_VERIFIED',p_authority_sha256||':'||claim);
    RETURN claim;
END $$;

CREATE FUNCTION apr_retention_internal.checkpoint_managed_retention_objects(p_intent UUID,p_generation BIGINT,p_token UUID)
RETURNS TEXT LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE execution public.apr_retention_managed_executions; claim UUID; pending INTEGER; exhausted INTEGER; unknown_count INTEGER;
    next_stage TEXT; outcome TEXT; reason TEXT;
BEGIN
    execution:=apr_retention_internal.assert_managed_lease(p_intent,p_generation,p_token);
    IF execution.stage<>'OBJECTS' THEN RAISE EXCEPTION 'Object stage changed' USING ERRCODE='40001'; END IF;
    SELECT execution_claim_id INTO claim FROM public.apr_retention_dispatch_intents WHERE intent_id=p_intent;
    IF claim IS NULL THEN RAISE EXCEPTION 'Execution claim unavailable' USING ERRCODE='55000'; END IF;
    SELECT count(*) FILTER(WHERE state<>'CONFIRMED'),count(*) FILTER(WHERE state<>'CONFIRMED' AND attempts>=100),
        count(*) FILTER(WHERE state='UNKNOWN')
      INTO pending,exhausted,unknown_count FROM public.apr_record_purge_objects WHERE claim_id=claim;
    IF exhausted>0 THEN
        UPDATE public.apr_retention_managed_executions SET state='BLOCKED',version=version+1,
            lease_token=NULL,lease_owner=NULL,lease_until=NULL,last_reason='OBJECT_ATTEMPTS_EXHAUSTED',updated_at=clock_timestamp()
          WHERE intent_id=p_intent RETURNING * INTO execution;
        PERFORM apr_retention_internal.managed_event(execution.tenant_id,p_intent,p_generation,'OBJECTS',
            'BLOCKED','OBJECT_ATTEMPTS_EXHAUSTED',pending||':'||exhausted);
        RETURN 'BLOCKED';
    END IF;
    IF unknown_count>0 THEN
        UPDATE public.apr_retention_managed_executions SET state='UNKNOWN',version=version+1,
            lease_token=NULL,lease_owner=NULL,lease_until=NULL,last_reason='OBJECT_RESULT_UNKNOWN',updated_at=clock_timestamp()
          WHERE intent_id=p_intent RETURNING * INTO execution;
        PERFORM apr_retention_internal.managed_event(execution.tenant_id,p_intent,p_generation,'OBJECTS',
            'UNKNOWN','OBJECT_RESULT_UNKNOWN',pending||':'||unknown_count);
        RETURN 'UNKNOWN';
    END IF;
    next_stage:=CASE WHEN pending=0 THEN 'FOREIGN' ELSE 'OBJECTS' END;
    outcome:=CASE WHEN pending=0 THEN 'SUCCEEDED' ELSE 'WAITING' END;
    reason:=CASE WHEN pending=0 THEN 'EXACT_OBJECTS_CONFIRMED' ELSE 'OBJECT_RECONCILIATION_PENDING' END;
    UPDATE public.apr_retention_managed_executions SET stage=next_stage,state='READY',version=version+1,
        lease_token=NULL,lease_owner=NULL,lease_until=NULL,last_reason=reason,updated_at=clock_timestamp()
      WHERE intent_id=p_intent RETURNING * INTO execution;
    PERFORM apr_retention_internal.managed_event(execution.tenant_id,p_intent,p_generation,'OBJECTS',outcome,reason,pending::text);
    RETURN next_stage;
END $$;

CREATE FUNCTION apr_retention_internal.claim_managed_retention_foreign(p_intent UUID,p_generation BIGINT,p_token UUID)
RETURNS TABLE(deletion_request_id UUID,tenant_id BIGINT,consumer_service TEXT,recovery_mode TEXT)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE execution public.apr_retention_managed_executions; request public.apr_retention_foreign_requests;
    delivery public.apr_retention_foreign_delivery_states; mode TEXT;
BEGIN
    execution:=apr_retention_internal.assert_managed_lease(p_intent,p_generation,p_token);
    IF execution.stage<>'FOREIGN' THEN RAISE EXCEPTION 'Foreign stage changed' USING ERRCODE='40001'; END IF;
    PERFORM apr_retention_internal.assert_exact_foreign_request_set(p_intent);
    UPDATE public.apr_retention_foreign_delivery_states d SET state='ACKNOWLEDGED',version=d.version+1,
        lease_token=NULL,lease_until=NULL,last_reason='SIGNED_OWNER_ACK_VERIFIED',updated_at=clock_timestamp()
      FROM public.apr_retention_foreign_acknowledgements a
      WHERE d.intent_id=p_intent AND d.deletion_request_id=a.deletion_request_id
        AND d.tenant_id=a.tenant_id AND d.consumer_service=a.consumer_service
        AND d.request_sha256=a.request_sha256 AND d.state<>'ACKNOWLEDGED';
    SELECT f.* INTO request FROM public.apr_retention_foreign_requests f
      WHERE f.intent_id=p_intent AND NOT EXISTS(SELECT 1 FROM public.apr_retention_foreign_acknowledgements a
        WHERE a.deletion_request_id=f.deletion_request_id AND a.tenant_id=f.tenant_id
          AND a.consumer_service=f.consumer_service AND a.request_sha256=f.request_sha256)
      ORDER BY f.consumer_service,f.chunk_index LIMIT 1;
    IF request.deletion_request_id IS NULL THEN RETURN; END IF;
    INSERT INTO public.apr_retention_foreign_delivery_states(deletion_request_id,tenant_id,intent_id,consumer_service,request_sha256)
    VALUES(request.deletion_request_id,request.tenant_id,request.intent_id,request.consumer_service,request.request_sha256)
    ON CONFLICT ON CONSTRAINT apr_retention_foreign_delivery_states_pkey DO NOTHING;
    SELECT * INTO delivery FROM public.apr_retention_foreign_delivery_states
      WHERE public.apr_retention_foreign_delivery_states.deletion_request_id=request.deletion_request_id FOR UPDATE;
    IF delivery.state='DISPATCHING' OR delivery.state IN('ACKNOWLEDGED','BLOCKED') OR delivery.attempt_count>=100 THEN
        RAISE EXCEPTION 'Foreign delivery state unavailable' USING ERRCODE='40001';
    END IF;
    mode:=CASE WHEN delivery.state='READY' AND delivery.attempt_count=0 THEN 'DISPATCH' ELSE 'RECONCILE' END;
    UPDATE public.apr_retention_foreign_delivery_states AS delivery_target SET state='DISPATCHING',version=delivery_target.version+1,
        generation=delivery_target.generation+1,attempt_count=delivery_target.attempt_count+1,lease_token=p_token,lease_until=execution.lease_until,
        last_reason=CASE WHEN mode='DISPATCH' THEN 'OWNER_DISPATCH_STARTED' ELSE 'UNKNOWN_OUTCOME_RECONCILIATION_STARTED' END,
        updated_at=clock_timestamp() WHERE delivery_target.deletion_request_id=request.deletion_request_id;
    RETURN QUERY SELECT request.deletion_request_id,request.tenant_id,request.consumer_service::text,mode;
END $$;

CREATE FUNCTION apr_retention_internal.finish_managed_retention_foreign(p_intent UUID,p_generation BIGINT,p_token UUID,p_request UUID)
RETURNS BIGINT LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE execution public.apr_retention_managed_executions; delivery public.apr_retention_foreign_delivery_states; proof TEXT;
BEGIN
    execution:=apr_retention_internal.assert_managed_lease(p_intent,p_generation,p_token);
    SELECT * INTO delivery FROM public.apr_retention_foreign_delivery_states
      WHERE deletion_request_id=p_request AND intent_id=p_intent FOR UPDATE;
    SELECT a.signed_proof_sha256 INTO proof FROM public.apr_retention_foreign_acknowledgements a
      WHERE a.deletion_request_id=delivery.deletion_request_id AND a.tenant_id=delivery.tenant_id
        AND a.consumer_service=delivery.consumer_service AND a.request_sha256=delivery.request_sha256;
    IF execution.stage<>'FOREIGN' OR delivery.state<>'DISPATCHING'
       OR delivery.lease_token IS DISTINCT FROM p_token OR proof IS NULL THEN
        RAISE EXCEPTION 'Signed owner acknowledgement fence changed' USING ERRCODE='40001';
    END IF;
    UPDATE public.apr_retention_foreign_delivery_states SET state='ACKNOWLEDGED',version=version+1,
        lease_token=NULL,lease_until=NULL,last_reason='SIGNED_OWNER_ACK_VERIFIED',updated_at=clock_timestamp()
      WHERE deletion_request_id=p_request;
    UPDATE public.apr_retention_managed_executions SET state='READY',version=version+1,
        lease_token=NULL,lease_owner=NULL,lease_until=NULL,last_reason='SIGNED_OWNER_ACK_VERIFIED',updated_at=clock_timestamp()
      WHERE intent_id=p_intent RETURNING * INTO execution;
    PERFORM apr_retention_internal.managed_event(execution.tenant_id,p_intent,p_generation,'FOREIGN',
        'SUCCEEDED','SIGNED_OWNER_ACK_VERIFIED',proof||':'||p_request);
    RETURN execution.version;
END $$;

CREATE FUNCTION apr_retention_internal.unknown_managed_retention_foreign(p_intent UUID,p_generation BIGINT,p_token UUID,
    p_request UUID,p_reason TEXT)
RETURNS BIGINT LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE execution public.apr_retention_managed_executions; delivery public.apr_retention_foreign_delivery_states;
BEGIN
    execution:=apr_retention_internal.assert_managed_lease(p_intent,p_generation,p_token);
    IF p_reason IS NULL OR p_reason!~'^[A-Z][A-Z0-9_]{2,119}$' THEN
        RAISE EXCEPTION 'Closed foreign unknown reason required' USING ERRCODE='23514'; END IF;
    SELECT * INTO delivery FROM public.apr_retention_foreign_delivery_states
      WHERE deletion_request_id=p_request AND intent_id=p_intent FOR UPDATE;
    IF execution.stage<>'FOREIGN' OR delivery.state<>'DISPATCHING' OR delivery.lease_token IS DISTINCT FROM p_token THEN
        RAISE EXCEPTION 'Foreign unknown fence changed' USING ERRCODE='40001'; END IF;
    UPDATE public.apr_retention_foreign_delivery_states SET state='UNKNOWN',version=version+1,
        lease_token=NULL,lease_until=NULL,last_reason=p_reason,updated_at=clock_timestamp()
      WHERE deletion_request_id=p_request;
    UPDATE public.apr_retention_managed_executions SET state='UNKNOWN',version=version+1,
        lease_token=NULL,lease_owner=NULL,lease_until=NULL,last_reason=p_reason,updated_at=clock_timestamp()
      WHERE intent_id=p_intent RETURNING * INTO execution;
    PERFORM apr_retention_internal.managed_event(execution.tenant_id,p_intent,p_generation,'FOREIGN',
        'UNKNOWN',p_reason,p_request::text);
    RETURN execution.version;
END $$;

CREATE FUNCTION apr_retention_internal.checkpoint_managed_retention_foreign(p_intent UUID,p_generation BIGINT,p_token UUID)
RETURNS TEXT LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE execution public.apr_retention_managed_executions; valid BOOLEAN; missing INTEGER;
BEGIN
    execution:=apr_retention_internal.assert_managed_lease(p_intent,p_generation,p_token);
    IF execution.stage<>'FOREIGN' THEN RAISE EXCEPTION 'Foreign stage changed' USING ERRCODE='40001'; END IF;
    PERFORM apr_retention_internal.assert_exact_foreign_request_set(p_intent);
    SELECT count(*) FILTER(WHERE a.deletion_request_id IS NULL),count(*)=count(a.deletion_request_id)
      INTO missing,valid
      FROM public.apr_retention_foreign_requests f
      LEFT JOIN public.apr_retention_foreign_acknowledgements a
        ON a.deletion_request_id=f.deletion_request_id AND a.tenant_id=f.tenant_id
       AND a.consumer_service=f.consumer_service AND a.request_sha256=f.request_sha256
      WHERE f.intent_id=p_intent;
    IF missing<>0 OR NOT COALESCE(valid,false) THEN
        RAISE EXCEPTION 'Exact foreign acknowledgement set incomplete' USING ERRCODE='40001'; END IF;
    UPDATE public.apr_retention_managed_executions SET stage='LOCAL_DB',state='READY',version=version+1,
        lease_token=NULL,lease_owner=NULL,lease_until=NULL,last_reason='DECLARED_FOREIGN_COPIES_CONFIRMED',updated_at=clock_timestamp()
      WHERE intent_id=p_intent RETURNING * INTO execution;
    PERFORM apr_retention_internal.managed_event(execution.tenant_id,p_intent,p_generation,'FOREIGN',
        'SUCCEEDED','DECLARED_FOREIGN_COPIES_CONFIRMED',missing::text);
    RETURN 'LOCAL_DB';
END $$;

CREATE FUNCTION apr_retention_internal.purge_managed_retention_local(p_intent UUID,p_generation BIGINT,p_token UUID)
RETURNS TEXT LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE execution public.apr_retention_managed_executions; intent public.apr_retention_dispatch_intents;
    head public.apr_record_retention_heads; result TEXT;
BEGIN
    execution:=apr_retention_internal.assert_managed_lease(p_intent,p_generation,p_token);
    IF execution.stage<>'LOCAL_DB' THEN RAISE EXCEPTION 'Local purge stage changed' USING ERRCODE='40001'; END IF;
    SELECT * INTO intent FROM public.apr_retention_dispatch_intents WHERE intent_id=p_intent FOR UPDATE;
    SELECT * INTO head FROM public.apr_record_retention_heads WHERE claim_id=intent.execution_claim_id FOR UPDATE;
    IF intent.state<>'IRREVERSIBLE' OR head.state NOT IN('IRREVERSIBLE','OBJECTS_CONFIRMED','LOCAL_DB_PURGED') THEN
        RAISE EXCEPTION 'Local purge source state changed' USING ERRCODE='40001'; END IF;
    result:=apr_retention_internal.purge_local_record(intent.execution_claim_id,head.version);
    IF result<>'LOCAL_DB_PURGED' THEN RAISE EXCEPTION 'Exact local purge proof unavailable' USING ERRCODE='55000'; END IF;
    UPDATE public.apr_retention_dispatch_intents SET state='LOCAL_DB_PURGED',version=version+1,
        reason_code='EXACT_LOCAL_RECORD_PURGED' WHERE intent_id=p_intent;
    UPDATE public.apr_retention_managed_executions SET stage='FINALIZE',state='READY',version=version+1,
        lease_token=NULL,lease_owner=NULL,lease_until=NULL,last_reason='EXACT_LOCAL_RECORD_PURGED',updated_at=clock_timestamp()
      WHERE intent_id=p_intent RETURNING * INTO execution;
    PERFORM apr_retention_internal.managed_event(execution.tenant_id,p_intent,p_generation,'LOCAL_DB',
        'SUCCEEDED','EXACT_LOCAL_RECORD_PURGED',intent.execution_claim_id::text);
    RETURN result;
END $$;

CREATE FUNCTION apr_retention_internal.finalize_managed_retention_execution(p_intent UUID,p_generation BIGINT,p_token UUID)
RETURNS TEXT LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE execution public.apr_retention_managed_executions; intent public.apr_retention_dispatch_intents;
    head public.apr_record_retention_heads; outcome TEXT; foreign_proof TEXT; valid BOOLEAN;
BEGIN
    execution:=apr_retention_internal.assert_managed_lease(p_intent,p_generation,p_token);
    IF execution.stage<>'FINALIZE' THEN RAISE EXCEPTION 'Finalization stage changed' USING ERRCODE='40001'; END IF;
    PERFORM apr_retention_internal.assert_exact_foreign_request_set(p_intent);
    SELECT * INTO intent FROM public.apr_retention_dispatch_intents WHERE intent_id=p_intent FOR UPDATE;
    SELECT * INTO head FROM public.apr_record_retention_heads WHERE claim_id=intent.execution_claim_id FOR UPDATE;
    SELECT count(DISTINCT f.consumer_service)=2
      AND count(*)=count(a.deletion_request_id)
      AND count(*)=sum(CASE WHEN f.chunk_index=0 THEN f.chunk_count ELSE 0 END),
      string_agg(a.signed_proof_sha256,'|' ORDER BY f.consumer_service,f.chunk_index)
      INTO valid,foreign_proof
      FROM public.apr_retention_foreign_requests f
      LEFT JOIN public.apr_retention_foreign_acknowledgements a
        ON a.deletion_request_id=f.deletion_request_id AND a.tenant_id=f.tenant_id
       AND a.consumer_service=f.consumer_service AND a.request_sha256=f.request_sha256
      WHERE f.intent_id=p_intent;
    IF intent.state<>'LOCAL_DB_PURGED' OR head.state<>'LOCAL_DB_PURGED'
       OR head.inventory_sha256 IS DISTINCT FROM intent.inventory_sha256 OR NOT COALESCE(valid,false)
       OR EXISTS(SELECT 1 FROM public.apr_record_purge_objects WHERE claim_id=intent.execution_claim_id AND state<>'CONFIRMED')
       OR NOT EXISTS(SELECT 1 FROM public.apr_record_tombstones t WHERE t.tenant_id=intent.tenant_id
          AND t.request_id=intent.request_id AND t.claim_id=intent.execution_claim_id
          AND t.inventory_sha256=intent.inventory_sha256) THEN
        RAISE EXCEPTION 'Managed retention completion evidence incomplete' USING ERRCODE='40001'; END IF;
    outcome:=encode(sha256(convert_to(intent.intent_id::text||':'||intent.execution_claim_id::text||':'||intent.inventory_sha256||':'||foreign_proof,'UTF8')),'hex');
    UPDATE public.apr_record_retention_heads SET state='COMPLETE',version=version+1
      WHERE claim_id=intent.execution_claim_id AND state='LOCAL_DB_PURGED' AND version=head.version;
    IF NOT FOUND THEN RAISE EXCEPTION 'Retention completion CAS changed' USING ERRCODE='40001'; END IF;
    UPDATE public.apr_retention_dispatch_intents SET version=version+1,reason_code='MANAGED_RETENTION_COMPLETE'
      WHERE intent_id=p_intent;
    INSERT INTO public.apr_record_purge_journal(entry_id,claim_id,state,reason_code,proof_sha256)
      VALUES(gen_random_uuid(),intent.execution_claim_id,'COMPLETE','LOCAL_AND_DECLARED_FOREIGN_COPIES_CONFIRMED',outcome);
    UPDATE public.apr_retention_managed_executions SET stage='COMPLETE',state='COMPLETE',version=version+1,
        lease_token=NULL,lease_owner=NULL,lease_until=NULL,last_reason='MANAGED_RETENTION_COMPLETE',
        outcome_sha256=outcome,updated_at=clock_timestamp()
      WHERE intent_id=p_intent RETURNING * INTO execution;
    PERFORM apr_retention_internal.managed_event(execution.tenant_id,p_intent,p_generation,'COMPLETE',
        'SUCCEEDED','MANAGED_RETENTION_COMPLETE',outcome);
    RETURN outcome;
END $$;

ALTER TABLE public.apr_retention_managed_executions OWNER TO dwp_approval_retention_owner;
ALTER TABLE public.apr_retention_managed_execution_events OWNER TO dwp_approval_retention_owner;
ALTER TABLE public.apr_retention_foreign_delivery_states OWNER TO dwp_approval_retention_owner;
GRANT SELECT ON public.apr_retention_foreign_requests,public.apr_retention_foreign_acknowledgements
    TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.guard_managed_execution() OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.guard_foreign_delivery_state() OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.managed_event(bigint,uuid,bigint,text,text,text,text) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.assert_managed_lease(uuid,bigint,uuid) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.assert_exact_foreign_request_set(uuid) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.expire_managed_retention_leases(integer) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.claim_managed_retention_execution(uuid,bigint,text,integer) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.recover_managed_retention_execution(uuid,bigint) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.managed_retention_execution_status(uuid) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.mark_managed_retention_unknown(uuid,bigint,uuid,text) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.block_managed_retention_execution(uuid,bigint,uuid,text) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.dispatch_managed_retention_record(uuid,bigint,bigint,uuid,text) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.checkpoint_managed_retention_objects(uuid,bigint,uuid) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.claim_managed_retention_foreign(uuid,bigint,uuid) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.finish_managed_retention_foreign(uuid,bigint,uuid,uuid) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.unknown_managed_retention_foreign(uuid,bigint,uuid,uuid,text) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.checkpoint_managed_retention_foreign(uuid,bigint,uuid) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.purge_managed_retention_local(uuid,bigint,uuid) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.finalize_managed_retention_execution(uuid,bigint,uuid) OWNER TO dwp_approval_retention_owner;

REVOKE ALL ON public.apr_retention_managed_executions,public.apr_retention_managed_execution_events,
    public.apr_retention_foreign_delivery_states FROM PUBLIC,dwp_approval_retention_executor;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA apr_retention_internal FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION apr_retention_internal.dispatch_record(uuid,bigint,text) FROM dwp_approval_retention_executor;
GRANT EXECUTE ON FUNCTION apr_retention_internal.expire_managed_retention_leases(integer),
    apr_retention_internal.claim_managed_retention_execution(uuid,bigint,text,integer),
    apr_retention_internal.recover_managed_retention_execution(uuid,bigint),
    apr_retention_internal.managed_retention_execution_status(uuid),
    apr_retention_internal.mark_managed_retention_unknown(uuid,bigint,uuid,text),
    apr_retention_internal.block_managed_retention_execution(uuid,bigint,uuid,text),
    apr_retention_internal.dispatch_managed_retention_record(uuid,bigint,bigint,uuid,text),
    apr_retention_internal.checkpoint_managed_retention_objects(uuid,bigint,uuid),
    apr_retention_internal.claim_managed_retention_foreign(uuid,bigint,uuid),
    apr_retention_internal.finish_managed_retention_foreign(uuid,bigint,uuid,uuid),
    apr_retention_internal.unknown_managed_retention_foreign(uuid,bigint,uuid,uuid,text),
    apr_retention_internal.checkpoint_managed_retention_foreign(uuid,bigint,uuid),
    apr_retention_internal.purge_managed_retention_local(uuid,bigint,uuid),
    apr_retention_internal.finalize_managed_retention_execution(uuid,bigint,uuid)
  TO dwp_approval_retention_executor;

COMMENT ON TABLE public.apr_retention_managed_executions IS
  'Content-free leased state machine. UNKNOWN is explicit and requires versioned recovery; no automatic command replay.';
COMMENT ON TABLE public.apr_retention_managed_execution_events IS
  'Immutable content-free execution evidence; success never implies undeclared client or provider copies.';
COMMENT ON TABLE public.apr_retention_foreign_delivery_states IS
  'Per-owner dispatch versus reconciliation state. A lost response is never converted to success or a fresh delete command.';

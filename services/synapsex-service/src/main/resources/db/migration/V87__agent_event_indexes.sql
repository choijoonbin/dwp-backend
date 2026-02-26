-- AGENT_EVENT 조회/중복 억제 성능 보강

CREATE INDEX IF NOT EXISTS ix_agent_activity_log_tenant_case_occurred
ON dwp_aura.agent_activity_log (tenant_id, resource_type, resource_id, occurred_at);

CREATE INDEX IF NOT EXISTS ix_agent_activity_log_tenant_meta_run_id
ON dwp_aura.agent_activity_log (tenant_id, (metadata_json->>'run_id'));

CREATE INDEX IF NOT EXISTS ix_agent_activity_log_tenant_meta_event_type
ON dwp_aura.agent_activity_log (tenant_id, (metadata_json->>'event_type'));

CREATE INDEX IF NOT EXISTS ix_agent_activity_log_tenant_meta_input_hash
ON dwp_aura.agent_activity_log (tenant_id, (metadata_json->>'input_hash'));

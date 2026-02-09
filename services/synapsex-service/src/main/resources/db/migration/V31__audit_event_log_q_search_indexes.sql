-- V31: 감사로그 q 검색 인덱스 (trace_id, gateway_request_id, span_id, resource_id)
-- 목적: GET /api/synapse/audit/logs?q=... OR 매칭 시 1~2초 내 응답

SET search_path TO dwp_aura, public;

-- q 대상 컬럼: tenant scope + prefix 검색 지원
CREATE INDEX IF NOT EXISTS ix_audit_event_log_tenant_trace_id
ON dwp_aura.audit_event_log(tenant_id, trace_id)
WHERE trace_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_audit_event_log_tenant_gateway_request_id
ON dwp_aura.audit_event_log(tenant_id, gateway_request_id)
WHERE gateway_request_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_audit_event_log_tenant_span_id
ON dwp_aura.audit_event_log(tenant_id, span_id)
WHERE span_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_audit_event_log_tenant_resource_id
ON dwp_aura.audit_event_log(tenant_id, resource_id)
WHERE resource_id IS NOT NULL;

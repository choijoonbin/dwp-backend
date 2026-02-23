-- V67: 미사용 테이블 synapse_audit_event_log 제거 (SoT는 audit_event_log)
SET search_path TO dwp_aura, public;

DROP TABLE IF EXISTS dwp_aura.synapse_audit_event_log CASCADE;

-- Complete the source-level contract inventory without duplicating code sets
-- that already govern the same values in an owning service.
INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('CORE.ERROR_CODE', 'dwp-core', 'API error code',
     'Stable wire codes returned by the common API error envelope.',
     'SYSTEM', 'TYPED_CONTRACT', 'ErrorCode.code', 'PROTOCOL'),
    ('AUDIT.DELIVERY_RESULT', 'dwp-audit', 'Audit delivery result',
     'Outbox publisher outcomes that control acknowledgement, rejection, and retry.',
     'SYSTEM', 'TYPED_CONTRACT', 'AuditEventPublisher.DeliveryResult', 'STATE_MACHINE'),
    ('PLATFORM.CONNECTOR.CAPABILITY', 'dwp-platform-contracts', 'Connector capability',
     'Provider-neutral capabilities declared by connector manifests.',
     'SYSTEM', 'TYPED_CONTRACT', 'ConnectorPort.Capability', 'PROTOCOL'),
    ('PLATFORM.CONNECTOR.HEALTH_STATE', 'dwp-platform-contracts', 'Connector health state',
     'Provider-neutral connector health outcomes.',
     'SYSTEM', 'TYPED_CONTRACT', 'ConnectorPort.HealthState', 'OBSERVABILITY');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('CORE.ERROR_CODE', 'E1000', 'Internal server error',
     '{"ko":"내부 서버 오류","en":"Internal server error"}', 10,
     '{"enumName":"INTERNAL_SERVER_ERROR","httpStatus":500}'),
    ('CORE.ERROR_CODE', 'E1001', 'Invalid input value',
     '{"ko":"잘못된 입력값","en":"Invalid input value"}', 20,
     '{"enumName":"INVALID_INPUT_VALUE","httpStatus":400}'),
    ('CORE.ERROR_CODE', 'E1004', 'Not found',
     '{"ko":"리소스를 찾을 수 없음","en":"Not found"}', 30,
     '{"enumName":"NOT_FOUND","httpStatus":404}'),
    ('CORE.ERROR_CODE', 'E1009', 'Resource conflict',
     '{"ko":"리소스 상태 충돌","en":"Resource conflict"}', 40,
     '{"enumName":"RESOURCE_CONFLICT","httpStatus":409}'),
    ('CORE.ERROR_CODE', 'E2000', 'Unauthorized',
     '{"ko":"인증 필요","en":"Unauthorized"}', 50,
     '{"enumName":"UNAUTHORIZED","httpStatus":401}'),
    ('CORE.ERROR_CODE', 'E2001', 'Forbidden',
     '{"ko":"권한 없음","en":"Forbidden"}', 60,
     '{"enumName":"FORBIDDEN","httpStatus":403}'),
    ('CORE.ERROR_CODE', 'E2003', 'Invalid token',
     '{"ko":"유효하지 않은 토큰","en":"Invalid token"}', 70,
     '{"enumName":"TOKEN_INVALID","httpStatus":401}'),
    ('CORE.ERROR_CODE', 'E2004', 'Invalid credentials',
     '{"ko":"잘못된 인증 정보","en":"Invalid credentials"}', 80,
     '{"enumName":"AUTH_INVALID_CREDENTIALS","httpStatus":401}'),
    ('CORE.ERROR_CODE', 'E2005', 'Authentication required',
     '{"ko":"인증 필요","en":"Authentication required"}', 90,
     '{"enumName":"AUTH_REQUIRED","httpStatus":401}'),
    ('CORE.ERROR_CODE', 'E2006', 'Tenant missing',
     '{"ko":"테넌트 정보 누락","en":"Tenant missing"}', 100,
     '{"enumName":"TENANT_MISSING","httpStatus":400}'),
    ('CORE.ERROR_CODE', 'E2007', 'Tenant mismatch',
     '{"ko":"테넌트 불일치","en":"Tenant mismatch"}', 110,
     '{"enumName":"TENANT_MISMATCH","httpStatus":403}'),
    ('CORE.ERROR_CODE', 'E3000', 'Entity not found',
     '{"ko":"엔터티를 찾을 수 없음","en":"Entity not found"}', 120,
     '{"enumName":"ENTITY_NOT_FOUND","httpStatus":404}'),
    ('CORE.ERROR_CODE', 'E3002', 'Invalid state',
     '{"ko":"잘못된 상태","en":"Invalid state"}', 130,
     '{"enumName":"INVALID_STATE","httpStatus":400}'),
    ('CORE.ERROR_CODE', 'E4000', 'Validation error',
     '{"ko":"입력 검증 오류","en":"Validation error"}', 140,
     '{"enumName":"VALIDATION_ERROR","httpStatus":400}'),
    ('CORE.ERROR_CODE', 'E4002', 'Invalid format',
     '{"ko":"잘못된 형식","en":"Invalid format"}', 150,
     '{"enumName":"INVALID_FORMAT","httpStatus":400}'),
    ('CORE.ERROR_CODE', 'E5000', 'External service error',
     '{"ko":"외부 서비스 오류","en":"External service error"}', 160,
     '{"enumName":"EXTERNAL_SERVICE_ERROR","httpStatus":502}'),
    ('AUDIT.DELIVERY_RESULT', 'ACCEPTED', 'Accepted',
     '{"ko":"수락됨","en":"Accepted"}', 10, '{"terminal":true,"retryable":false}'),
    ('AUDIT.DELIVERY_RESULT', 'REJECTED', 'Rejected',
     '{"ko":"거부됨","en":"Rejected"}', 20, '{"terminal":true,"retryable":false}'),
    ('AUDIT.DELIVERY_RESULT', 'RETRYABLE_FAILURE', 'Retryable failure',
     '{"ko":"재시도 가능한 실패","en":"Retryable failure"}', 30,
     '{"terminal":false,"retryable":true}'),
    ('PLATFORM.CONNECTOR.CAPABILITY', 'PRODUCTIVITY_READ', 'Productivity read',
     '{"ko":"생산성 데이터 조회","en":"Productivity read"}', 10, '{}'),
    ('PLATFORM.CONNECTOR.CAPABILITY', 'KNOWLEDGE_READ', 'Knowledge read',
     '{"ko":"지식 데이터 조회","en":"Knowledge read"}', 20, '{}'),
    ('PLATFORM.CONNECTOR.CAPABILITY', 'SERVICE_REQUEST_CREATE', 'Service request create',
     '{"ko":"서비스 요청 생성","en":"Service request create"}', 30, '{}'),
    ('PLATFORM.CONNECTOR.CAPABILITY', 'LOW_RISK_ACTION', 'Low-risk action',
     '{"ko":"저위험 작업","en":"Low-risk action"}', 40, '{}'),
    ('PLATFORM.CONNECTOR.HEALTH_STATE', 'HEALTHY', 'Healthy',
     '{"ko":"정상","en":"Healthy"}', 10, '{}'),
    ('PLATFORM.CONNECTOR.HEALTH_STATE', 'DEGRADED', 'Degraded',
     '{"ko":"성능 저하","en":"Degraded"}', 20, '{}'),
    ('PLATFORM.CONNECTOR.HEALTH_STATE', 'AUTHENTICATION_REQUIRED', 'Authentication required',
     '{"ko":"재인증 필요","en":"Authentication required"}', 30, '{}'),
    ('PLATFORM.CONNECTOR.HEALTH_STATE', 'UNAVAILABLE', 'Unavailable',
     '{"ko":"사용 불가","en":"Unavailable"}', 40, '{}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('CORE.ERROR_CODE', 'dwp-core', 'API_CONTRACT',
     'ApiResponse.error.code / ErrorCode.code', 'TYPED_CONTRACT'),
    ('AUDIT.DELIVERY_RESULT', 'dwp-audit', 'API_CONTRACT',
     'AuditEventPublisher.DeliveryResult', 'TYPED_CONTRACT'),
    ('AUDIT.DELIVERY_RESULT', 'dwp-core', 'BEHAVIOR',
     'AuditOutboxRelay delivery decision', 'TYPED_CONTRACT'),
    ('PLATFORM.CONNECTOR.CAPABILITY', 'dwp-platform-contracts', 'API_CONTRACT',
     'ConnectorPort.Capability', 'TYPED_CONTRACT'),
    ('PLATFORM.CONNECTOR.HEALTH_STATE', 'dwp-platform-contracts', 'API_CONTRACT',
     'ConnectorPort.HealthState', 'TYPED_CONTRACT'),
    ('PEOPLE.PPL_ATTRIBUTE_DEFINITIONS.DATA_CLASSIFICATION',
     'dwp-platform-contracts', 'API_CONTRACT', 'DataClassification', 'TYPED_CONTRACT'),
    ('PLATFORM.SYS_ADMIN_COMMAND_REQUESTS.RISK_TIER',
     'dwp-platform-contracts', 'API_CONTRACT', 'RiskTier', 'TYPED_CONTRACT'),
    ('PLATFORM.ADM_ANNOUNCEMENTS.LIFECYCLE_STATE',
     'dwp-platform-server', 'API_CONTRACT', 'AnnouncementLifecycle', 'TYPED_CONTRACT');

-- schema_version is a monotonic revision used by runtime clients. A statement
-- touching values or bindings increments every affected set once.
CREATE OR REPLACE FUNCTION sys_bump_code_set_revision_from_rows()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE sys_code_sets code_set
       SET schema_version = code_set.schema_version + 1,
           updated_at = CURRENT_TIMESTAMP
     WHERE code_set.code_set_key IN (
           SELECT DISTINCT code_set_key FROM changed_code_rows);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION sys_bump_code_set_revision_from_update()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE sys_code_sets code_set
       SET schema_version = code_set.schema_version + 1,
           updated_at = CURRENT_TIMESTAMP
     WHERE code_set.code_set_key IN (
           SELECT code_set_key FROM old_code_rows
           UNION
           SELECT code_set_key FROM new_code_rows);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION sys_guard_code_set_revision()
RETURNS TRIGGER AS $$
DECLARE
    metadata_changed BOOLEAN;
BEGIN
    IF NEW.schema_version < OLD.schema_version THEN
        RAISE EXCEPTION 'System code schema version cannot decrease'
            USING ERRCODE = '23514';
    END IF;

    metadata_changed := ROW(
        NEW.owner_service, NEW.display_name, NEW.description,
        NEW.configuration_level, NEW.validation_source,
        NEW.source_reference, NEW.lifecycle_state, NEW.contract_kind)
        IS DISTINCT FROM ROW(
        OLD.owner_service, OLD.display_name, OLD.description,
        OLD.configuration_level, OLD.validation_source,
        OLD.source_reference, OLD.lifecycle_state, OLD.contract_kind);

    IF metadata_changed AND NEW.schema_version = OLD.schema_version THEN
        NEW.schema_version := OLD.schema_version + 1;
    END IF;
    IF metadata_changed OR NEW.schema_version <> OLD.schema_version THEN
        NEW.updated_at := CURRENT_TIMESTAMP;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sys_code_sets_revision_guard
BEFORE UPDATE ON sys_code_sets
FOR EACH ROW EXECUTE FUNCTION sys_guard_code_set_revision();

CREATE TRIGGER trg_sys_code_values_revision_insert
AFTER INSERT ON sys_code_values
REFERENCING NEW TABLE AS changed_code_rows
FOR EACH STATEMENT EXECUTE FUNCTION sys_bump_code_set_revision_from_rows();

CREATE TRIGGER trg_sys_code_values_revision_delete
AFTER DELETE ON sys_code_values
REFERENCING OLD TABLE AS changed_code_rows
FOR EACH STATEMENT EXECUTE FUNCTION sys_bump_code_set_revision_from_rows();

CREATE TRIGGER trg_sys_code_values_revision_update
AFTER UPDATE ON sys_code_values
REFERENCING OLD TABLE AS old_code_rows NEW TABLE AS new_code_rows
FOR EACH STATEMENT EXECUTE FUNCTION sys_bump_code_set_revision_from_update();

CREATE TRIGGER trg_sys_code_bindings_revision_insert
AFTER INSERT ON sys_code_bindings
REFERENCING NEW TABLE AS changed_code_rows
FOR EACH STATEMENT EXECUTE FUNCTION sys_bump_code_set_revision_from_rows();

CREATE TRIGGER trg_sys_code_bindings_revision_delete
AFTER DELETE ON sys_code_bindings
REFERENCING OLD TABLE AS changed_code_rows
FOR EACH STATEMENT EXECUTE FUNCTION sys_bump_code_set_revision_from_rows();

CREATE TRIGGER trg_sys_code_bindings_revision_update
AFTER UPDATE ON sys_code_bindings
REFERENCING OLD TABLE AS old_code_rows NEW TABLE AS new_code_rows
FOR EACH STATEMENT EXECUTE FUNCTION sys_bump_code_set_revision_from_update();

UPDATE sys_code_sets
   SET schema_version = 2
 WHERE code_set_key IN (
       'PLATFORM.PREFERENCE.COLOR_MODE',
       'PLATFORM.PREFERENCE.DENSITY')
   AND schema_version < 2;

COMMENT ON COLUMN sys_code_sets.schema_version IS
    'Monotonic contract revision; incremented for metadata, value, or binding changes.';

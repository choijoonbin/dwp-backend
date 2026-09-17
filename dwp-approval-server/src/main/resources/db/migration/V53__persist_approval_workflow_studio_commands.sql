ALTER TABLE apr_routing_directory_commands
    DROP CONSTRAINT apr_routing_directory_commands_operation_check,
    ADD CONSTRAINT apr_routing_directory_commands_operation_check CHECK (
        operation IN ('SAVE_RESOLVER', 'OBSERVE_RESOLVER', 'SAVE_GROUP',
                      'ACTIVATE_GROUP', 'RECORD_USAGE', 'RETIRE_GROUP',
                      'SAVE_WORKFLOW_CANVAS', 'DRY_RUN_WORKFLOW', 'RETIRE_WORKFLOW'));

CREATE TABLE apr_workflow_studio_events (
    event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    management_resource_set_key VARCHAR(80) NOT NULL,
    workflow_id UUID NOT NULL,
    workflow_version_id UUID NOT NULL,
    action VARCHAR(32) NOT NULL,
    workflow_revision BIGINT NOT NULL,
    definition_sha256 CHAR(64) NOT NULL,
    command_sha256 CHAR(64) NOT NULL,
    material JSONB NOT NULL,
    actor_user_id BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (tenant_id, workflow_id)
        REFERENCES apr_workflow_definitions(tenant_id, workflow_id),
    FOREIGN KEY (tenant_id, workflow_version_id)
        REFERENCES apr_workflow_versions(tenant_id, workflow_version_id),
    CONSTRAINT ck_apr_workflow_studio_event_scope CHECK (
        management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_apr_workflow_studio_event_action CHECK (
        action IN ('SAVE_DRAFT', 'DRY_RUN', 'RETIRE')),
    CONSTRAINT ck_apr_workflow_studio_event_revision CHECK (
        workflow_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_apr_workflow_studio_event_hashes CHECK (
        definition_sha256 ~ '^[a-f0-9]{64}$'
        AND command_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_apr_workflow_studio_event_material CHECK (
        jsonb_typeof(material) = 'object' AND octet_length(material::text) <= 262144),
    CONSTRAINT ck_apr_workflow_studio_event_actor CHECK (actor_user_id > 0)
);

CREATE INDEX idx_apr_workflow_studio_event_history
    ON apr_workflow_studio_events(
        tenant_id, management_resource_set_key, workflow_id, occurred_at DESC, event_id);

CREATE FUNCTION validate_apr_workflow_studio_event_binding()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    bound_scope VARCHAR(80);
    bound_workflow UUID;
BEGIN
    SELECT definition.management_resource_set_key
      INTO bound_scope
      FROM apr_workflow_definitions definition
     WHERE definition.tenant_id = NEW.tenant_id
       AND definition.workflow_id = NEW.workflow_id;
    SELECT version.workflow_id
      INTO bound_workflow
      FROM apr_workflow_versions version
     WHERE version.tenant_id = NEW.tenant_id
       AND version.workflow_version_id = NEW.workflow_version_id;
    IF bound_scope IS DISTINCT FROM NEW.management_resource_set_key
       OR bound_workflow IS DISTINCT FROM NEW.workflow_id THEN
        RAISE EXCEPTION 'Workflow Studio evidence is not bound to the exact workflow scope';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_apr_workflow_studio_event_binding
    BEFORE INSERT ON apr_workflow_studio_events
    FOR EACH ROW EXECUTE FUNCTION validate_apr_workflow_studio_event_binding();

CREATE FUNCTION reject_apr_workflow_studio_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Workflow Studio evidence is append-only';
END;
$$;

CREATE TRIGGER trg_apr_workflow_studio_event_append_only
    BEFORE UPDATE OR DELETE ON apr_workflow_studio_events
    FOR EACH ROW EXECUTE FUNCTION reject_apr_workflow_studio_event_mutation();

COMMENT ON TABLE apr_workflow_studio_events IS
    'Append-only workflow canvas edit, dry-run, and retirement evidence over canonical workflow versions.';

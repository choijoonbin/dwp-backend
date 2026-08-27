ALTER TABLE usr_saved_view_lifecycle_commands
    ADD COLUMN saved_view_name VARCHAR(160),
    ADD COLUMN surface_key VARCHAR(80),
    ADD COLUMN scope VARCHAR(16);

-- The append-only trigger protects application traffic. This one-time forward migration
-- backfills pre-existing commands from their still-referenced saved view.
ALTER TABLE usr_saved_view_lifecycle_commands
    DISABLE TRIGGER trg_saved_view_lifecycle_command_immutable;

UPDATE usr_saved_view_lifecycle_commands command
   SET saved_view_name = view.name,
       surface_key = view.surface_key,
       scope = view.scope
  FROM usr_saved_views view
 WHERE view.tenant_id = command.tenant_id
   AND view.saved_view_id = command.saved_view_id;

ALTER TABLE usr_saved_view_lifecycle_commands
    ENABLE TRIGGER trg_saved_view_lifecycle_command_immutable;

ALTER TABLE usr_saved_view_lifecycle_commands
    ALTER COLUMN saved_view_name SET NOT NULL,
    ALTER COLUMN surface_key SET NOT NULL,
    ALTER COLUMN scope SET NOT NULL,
    ADD CONSTRAINT ck_usr_saved_view_lifecycle_name
        CHECK (BTRIM(saved_view_name) <> ''),
    ADD CONSTRAINT ck_usr_saved_view_lifecycle_surface
        CHECK (surface_key ~ '^[a-z0-9][a-z0-9._-]{2,79}$'),
    ADD CONSTRAINT ck_usr_saved_view_lifecycle_scope
        CHECK (scope IN ('PERSONAL', 'TEAM', 'TENANT'));

COMMENT ON COLUMN usr_saved_view_lifecycle_commands.saved_view_name IS
    'Immutable saved-view name snapshot captured when the lifecycle command executes.';
COMMENT ON COLUMN usr_saved_view_lifecycle_commands.surface_key IS
    'Immutable product-surface snapshot captured when the lifecycle command executes.';
COMMENT ON COLUMN usr_saved_view_lifecycle_commands.scope IS
    'Immutable sharing-scope snapshot captured when the lifecycle command executes.';

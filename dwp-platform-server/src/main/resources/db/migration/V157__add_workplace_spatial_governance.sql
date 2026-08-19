CREATE TABLE wp_campuses (
    campus_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    campus_code VARCHAR(80) NOT NULL,
    name_ko VARCHAR(160) NOT NULL,
    name_en VARCHAR(160) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_wp_campuses_tenant_id UNIQUE (tenant_id, campus_id),
    CONSTRAINT uk_wp_campuses_code UNIQUE (tenant_id, campus_code),
    CONSTRAINT fk_wp_campuses_tenant FOREIGN KEY (tenant_id)
        REFERENCES sys_service_tenants(tenant_id),
    CONSTRAINT ck_wp_campuses_code CHECK (
        campus_code ~ '^[A-Z0-9][A-Z0-9_-]{2,79}$'),
    CONSTRAINT ck_wp_campuses_state CHECK (
        lifecycle_state IN ('ACTIVE', 'MAINTENANCE', 'CLOSED'))
);

INSERT INTO wp_campuses (
    campus_id, tenant_id, campus_code, name_ko, name_en, lifecycle_state,
    created_by, updated_by)
SELECT md5('workplace:campus:' || tenant_id || ':' || site_code)::uuid,
       tenant_id,
       CASE WHEN site_code ~ '^[A-Z0-9][A-Z0-9_-]{2,79}$' THEN site_code
            ELSE 'LEGACY_' || UPPER(SUBSTRING(md5(site_id::TEXT), 1, 16)) END,
       name_ko, name_en,
       CASE lifecycle_state
           WHEN 'CLOSED' THEN 'CLOSED'
           WHEN 'MAINTENANCE' THEN 'MAINTENANCE'
           ELSE 'ACTIVE'
       END,
       COALESCE(created_by, 1), COALESCE(updated_by, 1)
 FROM wp_sites
ON CONFLICT (tenant_id, campus_code) DO NOTHING;

ALTER TABLE wp_sites ADD COLUMN campus_id UUID;

UPDATE wp_sites site
   SET campus_id = campus.campus_id
 FROM wp_campuses campus
 WHERE campus.tenant_id = site.tenant_id
   AND campus.campus_id =
       md5('workplace:campus:' || site.tenant_id || ':' || site.site_code)::uuid;

ALTER TABLE wp_sites
    ALTER COLUMN campus_id SET NOT NULL,
    ADD CONSTRAINT uk_wp_sites_tenant_campus_site
        UNIQUE (tenant_id, campus_id, site_id),
    ADD CONSTRAINT fk_wp_sites_tenant_campus
        FOREIGN KEY (tenant_id, campus_id)
        REFERENCES wp_campuses(tenant_id, campus_id);

CREATE OR REPLACE FUNCTION wp_apply_site_compatibility_campus()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    compatibility_code VARCHAR(80);
BEGIN
    IF NEW.campus_id IS NOT NULL THEN
        RETURN NEW;
    END IF;
    compatibility_code := CASE
        WHEN NEW.site_code ~ '^[A-Z0-9][A-Z0-9_-]{2,79}$' THEN NEW.site_code
        ELSE 'LEGACY_' || UPPER(SUBSTRING(md5(NEW.site_id::TEXT), 1, 16))
    END;
    INSERT INTO wp_campuses (
        campus_id, tenant_id, campus_code, name_ko, name_en, lifecycle_state,
        created_by, updated_by)
    VALUES (
        md5('workplace:campus:' || NEW.tenant_id || ':' || NEW.site_id)::uuid,
        NEW.tenant_id, compatibility_code, NEW.name_ko, NEW.name_en,
        CASE NEW.lifecycle_state
            WHEN 'CLOSED' THEN 'CLOSED'
            WHEN 'MAINTENANCE' THEN 'MAINTENANCE'
            ELSE 'ACTIVE'
        END,
        COALESCE(NEW.created_by, 1), COALESCE(NEW.updated_by, 1))
    ON CONFLICT (tenant_id, campus_code) DO NOTHING;

    SELECT campus_id INTO NEW.campus_id
      FROM wp_campuses
     WHERE tenant_id = NEW.tenant_id
       AND campus_code = compatibility_code;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wp_sites_compatibility_campus
BEFORE INSERT ON wp_sites
FOR EACH ROW EXECUTE FUNCTION wp_apply_site_compatibility_campus();

CREATE TABLE wp_zones (
    zone_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    floor_id UUID NOT NULL,
    zone_code VARCHAR(80) NOT NULL,
    name_ko VARCHAR(160) NOT NULL,
    name_en VARCHAR(160) NOT NULL,
    zone_type VARCHAR(24) NOT NULL DEFAULT 'GENERAL',
    boundary JSONB NOT NULL DEFAULT '{}'::jsonb,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_wp_zones_tenant_id UNIQUE (tenant_id, zone_id),
    CONSTRAINT uk_wp_zones_floor_id UNIQUE (tenant_id, floor_id, zone_id),
    CONSTRAINT uk_wp_zones_code UNIQUE (tenant_id, floor_id, zone_code),
    CONSTRAINT fk_wp_zones_tenant_floor
        FOREIGN KEY (tenant_id, floor_id)
        REFERENCES wp_floors(tenant_id, floor_id),
    CONSTRAINT ck_wp_zones_code CHECK (
        zone_code ~ '^[A-Z0-9][A-Z0-9_-]{2,79}$'),
    CONSTRAINT ck_wp_zones_type CHECK (zone_type IN (
        'GENERAL', 'WORK_AREA', 'COLLABORATION', 'QUIET', 'SERVICE', 'RESTRICTED')),
    CONSTRAINT ck_wp_zones_boundary CHECK (jsonb_typeof(boundary) = 'object'),
    CONSTRAINT ck_wp_zones_state CHECK (
        lifecycle_state IN ('ACTIVE', 'MAINTENANCE', 'CLOSED'))
);

CREATE TABLE wp_sections (
    section_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    floor_id UUID NOT NULL,
    zone_id UUID NOT NULL,
    section_code VARCHAR(80) NOT NULL,
    name_ko VARCHAR(160) NOT NULL,
    name_en VARCHAR(160) NOT NULL,
    boundary JSONB NOT NULL DEFAULT '{}'::jsonb,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_wp_sections_tenant_id UNIQUE (tenant_id, section_id),
    CONSTRAINT uk_wp_sections_zone_id
        UNIQUE (tenant_id, floor_id, zone_id, section_id),
    CONSTRAINT uk_wp_sections_code
        UNIQUE (tenant_id, floor_id, zone_id, section_code),
    CONSTRAINT fk_wp_sections_tenant_zone
        FOREIGN KEY (tenant_id, floor_id, zone_id)
        REFERENCES wp_zones(tenant_id, floor_id, zone_id),
    CONSTRAINT ck_wp_sections_code CHECK (
        section_code ~ '^[A-Z0-9][A-Z0-9_-]{2,79}$'),
    CONSTRAINT ck_wp_sections_boundary CHECK (jsonb_typeof(boundary) = 'object'),
    CONSTRAINT ck_wp_sections_state CHECK (
        lifecycle_state IN ('ACTIVE', 'MAINTENANCE', 'CLOSED'))
);

CREATE OR REPLACE FUNCTION wp_create_default_zone_for_floor()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO wp_zones (
        zone_id, tenant_id, floor_id, zone_code, name_ko, name_en, zone_type,
        created_by, updated_by)
    VALUES (
        md5('workplace:zone:' || NEW.tenant_id || ':' || NEW.floor_id || ':DEFAULT')::uuid,
        NEW.tenant_id, NEW.floor_id, 'DEFAULT', '기본 구역', 'Default zone', 'GENERAL',
        COALESCE(NEW.created_by, 1), COALESCE(NEW.updated_by, 1))
    ON CONFLICT (tenant_id, floor_id, zone_code) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wp_floors_default_zone
AFTER INSERT ON wp_floors
FOR EACH ROW EXECUTE FUNCTION wp_create_default_zone_for_floor();

INSERT INTO wp_zones (
    zone_id, tenant_id, floor_id, zone_code, name_ko, name_en, zone_type,
    created_by, updated_by)
SELECT md5('workplace:zone:' || tenant_id || ':' || floor_id || ':DEFAULT')::uuid,
       tenant_id, floor_id, 'DEFAULT', '기본 구역', 'Default zone', 'GENERAL',
       COALESCE(created_by, 1), COALESCE(updated_by, 1)
  FROM wp_floors
ON CONFLICT (tenant_id, floor_id, zone_code) DO NOTHING;

ALTER TABLE wp_resources
    ADD COLUMN zone_id UUID,
    ADD COLUMN section_id UUID,
    ADD CONSTRAINT uk_wp_resources_floor_resource
        UNIQUE (tenant_id, floor_id, resource_id);

UPDATE wp_resources resource
   SET zone_id = zone.zone_id
  FROM wp_zones zone
 WHERE zone.tenant_id = resource.tenant_id
   AND zone.floor_id = resource.floor_id
   AND zone.zone_code = 'DEFAULT';

ALTER TABLE wp_resources
    ALTER COLUMN zone_id SET NOT NULL,
    ADD CONSTRAINT fk_wp_resources_tenant_zone
        FOREIGN KEY (tenant_id, floor_id, zone_id)
        REFERENCES wp_zones(tenant_id, floor_id, zone_id),
    ADD CONSTRAINT fk_wp_resources_tenant_section
        FOREIGN KEY (tenant_id, floor_id, zone_id, section_id)
        REFERENCES wp_sections(tenant_id, floor_id, zone_id, section_id);

CREATE OR REPLACE FUNCTION wp_apply_default_resource_zone()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.zone_id IS NULL THEN
        SELECT zone_id INTO NEW.zone_id
          FROM wp_zones
         WHERE tenant_id = NEW.tenant_id
           AND floor_id = NEW.floor_id
           AND zone_code = 'DEFAULT';
    END IF;
    IF NEW.zone_id IS NULL THEN
        RAISE EXCEPTION 'A workplace resource requires a governed floor zone';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wp_resources_default_zone
BEFORE INSERT ON wp_resources
FOR EACH ROW EXECUTE FUNCTION wp_apply_default_resource_zone();

CREATE TABLE wp_site_access_rules (
    access_rule_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    site_id UUID NOT NULL,
    subject_type VARCHAR(20) NOT NULL,
    subject_user_id BIGINT,
    subject_group_ref UUID,
    permission_code VARCHAR(20) NOT NULL,
    effect VARCHAR(10) NOT NULL,
    valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_wp_site_access_rules_tenant_id
        UNIQUE (tenant_id, access_rule_id),
    CONSTRAINT fk_wp_site_access_rules_tenant_site
        FOREIGN KEY (tenant_id, site_id)
        REFERENCES wp_sites(tenant_id, site_id),
    CONSTRAINT ck_wp_site_access_rules_subject CHECK (
        (subject_type = 'USER' AND subject_user_id IS NOT NULL AND subject_group_ref IS NULL)
        OR (subject_type = 'GROUP_REF' AND subject_user_id IS NULL AND subject_group_ref IS NOT NULL)),
    CONSTRAINT ck_wp_site_access_rules_permission CHECK (
        permission_code IN ('VIEW', 'BOOK', 'MANAGE')),
    CONSTRAINT ck_wp_site_access_rules_effect CHECK (effect IN ('ALLOW', 'DENY')),
    CONSTRAINT ck_wp_site_access_rules_validity CHECK (
        valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_wp_site_access_rules_state CHECK (
        lifecycle_state IN ('ACTIVE', 'INACTIVE'))
);

CREATE UNIQUE INDEX uk_wp_site_access_rules_user
    ON wp_site_access_rules (tenant_id, site_id, subject_user_id, permission_code)
    WHERE subject_type = 'USER';
CREATE UNIQUE INDEX uk_wp_site_access_rules_group
    ON wp_site_access_rules (tenant_id, site_id, subject_group_ref, permission_code)
    WHERE subject_type = 'GROUP_REF';
CREATE INDEX idx_wp_site_access_rules_evaluation
    ON wp_site_access_rules (tenant_id, site_id, lifecycle_state, permission_code);

CREATE TABLE wp_policy_overrides (
    policy_override_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    campus_id UUID,
    site_id UUID,
    floor_id UUID,
    zone_id UUID,
    resource_id UUID,
    policy_patch JSONB NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_wp_policy_overrides_tenant_id
        UNIQUE (tenant_id, policy_override_id),
    CONSTRAINT fk_wp_policy_overrides_tenant_campus
        FOREIGN KEY (tenant_id, campus_id)
        REFERENCES wp_campuses(tenant_id, campus_id),
    CONSTRAINT fk_wp_policy_overrides_tenant_site
        FOREIGN KEY (tenant_id, site_id)
        REFERENCES wp_sites(tenant_id, site_id),
    CONSTRAINT fk_wp_policy_overrides_tenant_floor
        FOREIGN KEY (tenant_id, floor_id)
        REFERENCES wp_floors(tenant_id, floor_id),
    CONSTRAINT fk_wp_policy_overrides_tenant_zone
        FOREIGN KEY (tenant_id, zone_id)
        REFERENCES wp_zones(tenant_id, zone_id),
    CONSTRAINT fk_wp_policy_overrides_tenant_resource
        FOREIGN KEY (tenant_id, resource_id)
        REFERENCES wp_resources(tenant_id, resource_id),
    CONSTRAINT ck_wp_policy_overrides_scope CHECK (
        (scope_type = 'TENANT' AND campus_id IS NULL AND site_id IS NULL
            AND floor_id IS NULL AND zone_id IS NULL AND resource_id IS NULL)
        OR (scope_type = 'CAMPUS' AND campus_id IS NOT NULL AND site_id IS NULL
            AND floor_id IS NULL AND zone_id IS NULL AND resource_id IS NULL)
        OR (scope_type = 'SITE' AND campus_id IS NULL AND site_id IS NOT NULL
            AND floor_id IS NULL AND zone_id IS NULL AND resource_id IS NULL)
        OR (scope_type = 'FLOOR' AND campus_id IS NULL AND site_id IS NULL
            AND floor_id IS NOT NULL AND zone_id IS NULL AND resource_id IS NULL)
        OR (scope_type = 'ZONE' AND campus_id IS NULL AND site_id IS NULL
            AND floor_id IS NULL AND zone_id IS NOT NULL AND resource_id IS NULL)
        OR (scope_type = 'RESOURCE' AND campus_id IS NULL AND site_id IS NULL
            AND floor_id IS NULL AND zone_id IS NULL AND resource_id IS NOT NULL)),
    CONSTRAINT ck_wp_policy_overrides_patch CHECK (
        jsonb_typeof(policy_patch) = 'object'
        AND policy_patch - ARRAY[
            'bookingWindowDays', 'maximumActiveBookings', 'minimumBookingMinutes',
            'maximumBookingMinutes', 'maximumConsecutiveDays', 'workingDayStart',
            'workingDayEnd', 'allowRecurring', 'requireCheckIn',
            'checkInLeadMinutes', 'autoReleaseMinutes',
            'allowAssignedDeskLending', 'showColleagueNames']::TEXT[] = '{}'::jsonb),
    CONSTRAINT ck_wp_policy_overrides_state CHECK (
        lifecycle_state IN ('ACTIVE', 'INACTIVE'))
);

CREATE UNIQUE INDEX uk_wp_policy_override_tenant
    ON wp_policy_overrides (tenant_id) WHERE scope_type = 'TENANT';
CREATE UNIQUE INDEX uk_wp_policy_override_campus
    ON wp_policy_overrides (tenant_id, campus_id) WHERE scope_type = 'CAMPUS';
CREATE UNIQUE INDEX uk_wp_policy_override_site
    ON wp_policy_overrides (tenant_id, site_id) WHERE scope_type = 'SITE';
CREATE UNIQUE INDEX uk_wp_policy_override_floor
    ON wp_policy_overrides (tenant_id, floor_id) WHERE scope_type = 'FLOOR';
CREATE UNIQUE INDEX uk_wp_policy_override_zone
    ON wp_policy_overrides (tenant_id, zone_id) WHERE scope_type = 'ZONE';
CREATE UNIQUE INDEX uk_wp_policy_override_resource
    ON wp_policy_overrides (tenant_id, resource_id) WHERE scope_type = 'RESOURCE';

CREATE TABLE wp_floor_plan_revisions (
    floor_plan_revision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    floor_id UUID NOT NULL,
    revision_number BIGINT NOT NULL,
    based_on_revision_id UUID,
    restore_source_revision_id UUID,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    plan_width INTEGER NOT NULL,
    plan_height INTEGER NOT NULL,
    background_asset_path VARCHAR(1000),
    background_asset_key VARCHAR(320),
    background_content_type VARCHAR(80),
    background_size_bytes BIGINT,
    background_sha256 CHAR(64),
    change_summary VARCHAR(500) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    submitted_at TIMESTAMPTZ,
    submitted_by BIGINT,
    published_at TIMESTAMPTZ,
    published_by BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_wp_floor_plan_revisions_tenant_id
        UNIQUE (tenant_id, floor_plan_revision_id),
    CONSTRAINT uk_wp_floor_plan_revisions_floor_id
        UNIQUE (tenant_id, floor_id, floor_plan_revision_id),
    CONSTRAINT uk_wp_floor_plan_revisions_number
        UNIQUE (tenant_id, floor_id, revision_number),
    CONSTRAINT fk_wp_floor_plan_revisions_tenant_floor
        FOREIGN KEY (tenant_id, floor_id)
        REFERENCES wp_floors(tenant_id, floor_id),
    CONSTRAINT fk_wp_floor_plan_revisions_tenant_baseline
        FOREIGN KEY (tenant_id, based_on_revision_id)
        REFERENCES wp_floor_plan_revisions(tenant_id, floor_plan_revision_id),
    CONSTRAINT fk_wp_floor_plan_revisions_tenant_restore
        FOREIGN KEY (tenant_id, restore_source_revision_id)
        REFERENCES wp_floor_plan_revisions(tenant_id, floor_plan_revision_id),
    CONSTRAINT ck_wp_floor_plan_revisions_number CHECK (revision_number > 0),
    CONSTRAINT ck_wp_floor_plan_revisions_state CHECK (
        lifecycle_state IN ('DRAFT', 'REVIEW', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_wp_floor_plan_revisions_plan CHECK (
        plan_width BETWEEN 400 AND 5000 AND plan_height BETWEEN 300 AND 5000),
    CONSTRAINT ck_wp_floor_plan_revisions_summary CHECK (BTRIM(change_summary) <> ''),
    CONSTRAINT ck_wp_floor_plan_revisions_hash CHECK (
        content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_floor_plan_revisions_submission CHECK (
        (submitted_at IS NULL AND submitted_by IS NULL)
        OR (submitted_at IS NOT NULL AND submitted_by IS NOT NULL)),
    CONSTRAINT ck_wp_floor_plan_revisions_publish CHECK (
        (published_at IS NULL AND published_by IS NULL)
        OR (published_at IS NOT NULL AND published_by IS NOT NULL)),
    CONSTRAINT ck_wp_floor_plan_revisions_published CHECK (
        lifecycle_state <> 'PUBLISHED'
        OR (submitted_at IS NOT NULL AND published_at IS NOT NULL))
);

CREATE UNIQUE INDEX uk_wp_floor_plan_revision_open
    ON wp_floor_plan_revisions (tenant_id, floor_id)
    WHERE lifecycle_state IN ('DRAFT', 'REVIEW');
CREATE UNIQUE INDEX uk_wp_floor_plan_revision_published
    ON wp_floor_plan_revisions (tenant_id, floor_id)
    WHERE lifecycle_state = 'PUBLISHED';
CREATE INDEX idx_wp_floor_plan_revision_history
    ON wp_floor_plan_revisions (tenant_id, floor_id, revision_number DESC);

CREATE TABLE wp_floor_plan_revision_placements (
    placement_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    floor_id UUID NOT NULL,
    floor_plan_revision_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    resource_version BIGINT NOT NULL,
    zone_id UUID NOT NULL,
    section_id UUID,
    position_x NUMERIC(6,2) NOT NULL,
    position_y NUMERIC(6,2) NOT NULL,
    width_percent NUMERIC(6,2) NOT NULL,
    height_percent NUMERIC(6,2) NOT NULL,
    rotation_degrees INTEGER NOT NULL DEFAULT 0,
    placement_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_wp_floor_plan_placements_tenant_id
        UNIQUE (tenant_id, placement_id),
    CONSTRAINT uk_wp_floor_plan_placements_resource
        UNIQUE (tenant_id, floor_plan_revision_id, resource_id),
    CONSTRAINT fk_wp_floor_plan_placements_tenant_revision
        FOREIGN KEY (tenant_id, floor_id, floor_plan_revision_id)
        REFERENCES wp_floor_plan_revisions(tenant_id, floor_id, floor_plan_revision_id),
    CONSTRAINT fk_wp_floor_plan_placements_tenant_resource
        FOREIGN KEY (tenant_id, floor_id, resource_id)
        REFERENCES wp_resources(tenant_id, floor_id, resource_id),
    CONSTRAINT fk_wp_floor_plan_placements_tenant_zone
        FOREIGN KEY (tenant_id, floor_id, zone_id)
        REFERENCES wp_zones(tenant_id, floor_id, zone_id),
    CONSTRAINT fk_wp_floor_plan_placements_tenant_section
        FOREIGN KEY (tenant_id, floor_id, zone_id, section_id)
        REFERENCES wp_sections(tenant_id, floor_id, zone_id, section_id),
    CONSTRAINT ck_wp_floor_plan_placements_position CHECK (
        position_x BETWEEN 0 AND 99.99 AND position_y BETWEEN 0 AND 99.99
        AND width_percent BETWEEN 1 AND 100 AND height_percent BETWEEN 1 AND 100
        AND position_x + width_percent <= 100
        AND position_y + height_percent <= 100
        AND rotation_degrees BETWEEN -359 AND 359),
    CONSTRAINT ck_wp_floor_plan_placements_metadata CHECK (
        jsonb_typeof(placement_metadata) = 'object')
);

INSERT INTO wp_floor_plan_revisions (
    floor_plan_revision_id, tenant_id, floor_id, revision_number,
    lifecycle_state, plan_width, plan_height, background_asset_path,
    background_asset_key, background_content_type, background_size_bytes,
    background_sha256, change_summary, content_hash, submitted_at,
    submitted_by, published_at, published_by, created_by, updated_by)
SELECT md5('workplace:floor-plan:' || tenant_id || ':' || floor_id || ':1')::uuid,
       tenant_id, floor_id, 1, 'PUBLISHED', plan_width, plan_height,
       background_asset_path, background_asset_key, background_content_type,
       background_size_bytes, background_sha256, 'V157 baseline migration',
       md5('workplace:floor-plan:' || tenant_id || ':' || floor_id || ':1')
           || md5('workplace:floor-plan:' || tenant_id || ':' || floor_id || ':1:content'),
       CURRENT_TIMESTAMP, COALESCE(updated_by, 1), CURRENT_TIMESTAMP,
       COALESCE(updated_by, 1), COALESCE(created_by, 1), COALESCE(updated_by, 1)
  FROM wp_floors;

INSERT INTO wp_floor_plan_revision_placements (
    placement_id, tenant_id, floor_id, floor_plan_revision_id, resource_id,
    resource_version, zone_id, section_id, position_x, position_y, width_percent, height_percent,
    rotation_degrees, created_by, updated_by)
SELECT md5('workplace:placement:' || resource.tenant_id || ':'
           || revision.floor_plan_revision_id || ':' || resource.resource_id)::uuid,
       resource.tenant_id, resource.floor_id, revision.floor_plan_revision_id,
       resource.resource_id, resource.version, resource.zone_id, resource.section_id,
       resource.position_x, resource.position_y, resource.width_percent,
       resource.height_percent, resource.rotation_degrees,
       COALESCE(resource.created_by, 1), COALESCE(resource.updated_by, 1)
  FROM wp_resources resource
  JOIN wp_floor_plan_revisions revision
    ON revision.tenant_id = resource.tenant_id
   AND revision.floor_id = resource.floor_id
   AND revision.revision_number = 1;

ALTER TABLE wp_floors ADD COLUMN published_plan_revision_id UUID;

UPDATE wp_floors floor
   SET published_plan_revision_id = revision.floor_plan_revision_id
  FROM wp_floor_plan_revisions revision
 WHERE revision.tenant_id = floor.tenant_id
   AND revision.floor_id = floor.floor_id
   AND revision.lifecycle_state = 'PUBLISHED';

ALTER TABLE wp_floors
    ADD CONSTRAINT fk_wp_floors_tenant_published_plan
        FOREIGN KEY (tenant_id, floor_id, published_plan_revision_id)
        REFERENCES wp_floor_plan_revisions(
            tenant_id, floor_id, floor_plan_revision_id);

CREATE TABLE wp_delegated_admin_scopes (
    delegation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    delegate_type VARCHAR(20) NOT NULL,
    delegate_user_id BIGINT,
    delegate_group_ref UUID,
    scope_type VARCHAR(20) NOT NULL,
    site_id UUID,
    managed_group_ref UUID,
    permission_codes VARCHAR(40)[] NOT NULL,
    valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_wp_delegated_admin_scopes_tenant_id
        UNIQUE (tenant_id, delegation_id),
    CONSTRAINT fk_wp_delegated_admin_scopes_tenant_site
        FOREIGN KEY (tenant_id, site_id)
        REFERENCES wp_sites(tenant_id, site_id),
    CONSTRAINT ck_wp_delegated_admin_scopes_delegate CHECK (
        (delegate_type = 'USER' AND delegate_user_id IS NOT NULL
            AND delegate_group_ref IS NULL)
        OR (delegate_type = 'GROUP_REF' AND delegate_user_id IS NULL
            AND delegate_group_ref IS NOT NULL)),
    CONSTRAINT ck_wp_delegated_admin_scopes_scope CHECK (
        (scope_type = 'SITE' AND site_id IS NOT NULL AND managed_group_ref IS NULL)
        OR (scope_type = 'GROUP_REF' AND site_id IS NULL
            AND managed_group_ref IS NOT NULL)),
    CONSTRAINT ck_wp_delegated_admin_scopes_permissions CHECK (
        cardinality(permission_codes) > 0
        AND permission_codes <@ ARRAY[
            'CATALOG_VIEW', 'CATALOG_MANAGE', 'ACCESS_MANAGE',
            'POLICY_MANAGE', 'FLOOR_PLAN_MANAGE', 'DELEGATION_VIEW']::VARCHAR[]),
    CONSTRAINT ck_wp_delegated_admin_scopes_validity CHECK (
        valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_wp_delegated_admin_scopes_state CHECK (
        lifecycle_state IN ('ACTIVE', 'REVOKED'))
);

CREATE UNIQUE INDEX uk_wp_delegated_admin_site_user
    ON wp_delegated_admin_scopes (
        tenant_id, delegate_user_id, site_id)
    WHERE lifecycle_state = 'ACTIVE' AND delegate_type = 'USER' AND scope_type = 'SITE';
CREATE UNIQUE INDEX uk_wp_delegated_admin_site_group
    ON wp_delegated_admin_scopes (
        tenant_id, delegate_group_ref, site_id)
    WHERE lifecycle_state = 'ACTIVE' AND delegate_type = 'GROUP_REF' AND scope_type = 'SITE';
CREATE UNIQUE INDEX uk_wp_delegated_admin_group_user
    ON wp_delegated_admin_scopes (
        tenant_id, delegate_user_id, managed_group_ref)
    WHERE lifecycle_state = 'ACTIVE' AND delegate_type = 'USER' AND scope_type = 'GROUP_REF';
CREATE UNIQUE INDEX uk_wp_delegated_admin_group_group
    ON wp_delegated_admin_scopes (
        tenant_id, delegate_group_ref, managed_group_ref)
    WHERE lifecycle_state = 'ACTIVE' AND delegate_type = 'GROUP_REF' AND scope_type = 'GROUP_REF';
CREATE INDEX idx_wp_delegated_admin_effective
    ON wp_delegated_admin_scopes (tenant_id, lifecycle_state, valid_from, valid_until);

CREATE OR REPLACE FUNCTION sys_guard_wp_floor_plan_revision()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.lifecycle_state <> 'DRAFT' THEN
        RAISE EXCEPTION 'Published workplace floor-plan history is immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF OLD.lifecycle_state <> NEW.lifecycle_state AND NOT (
        (OLD.lifecycle_state = 'DRAFT' AND NEW.lifecycle_state = 'REVIEW')
        OR (OLD.lifecycle_state = 'REVIEW' AND NEW.lifecycle_state = 'DRAFT')
        OR (OLD.lifecycle_state = 'REVIEW' AND NEW.lifecycle_state = 'PUBLISHED')
        OR (OLD.lifecycle_state = 'PUBLISHED' AND NEW.lifecycle_state = 'ARCHIVED')) THEN
        RAISE EXCEPTION 'Invalid workplace floor-plan lifecycle transition';
    END IF;
    IF OLD.lifecycle_state <> 'DRAFT' AND (
        NEW.plan_width IS DISTINCT FROM OLD.plan_width
        OR NEW.plan_height IS DISTINCT FROM OLD.plan_height
        OR NEW.background_asset_path IS DISTINCT FROM OLD.background_asset_path
        OR NEW.background_asset_key IS DISTINCT FROM OLD.background_asset_key
        OR NEW.background_content_type IS DISTINCT FROM OLD.background_content_type
        OR NEW.background_size_bytes IS DISTINCT FROM OLD.background_size_bytes
        OR NEW.background_sha256 IS DISTINCT FROM OLD.background_sha256
        OR NEW.change_summary IS DISTINCT FROM OLD.change_summary
        OR NEW.content_hash IS DISTINCT FROM OLD.content_hash) THEN
        RAISE EXCEPTION 'Reviewed workplace floor-plan content is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wp_floor_plan_revision_guard
BEFORE UPDATE OR DELETE ON wp_floor_plan_revisions
FOR EACH ROW EXECUTE FUNCTION sys_guard_wp_floor_plan_revision();

CREATE OR REPLACE FUNCTION sys_guard_wp_floor_plan_placement()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    revision_tenant BIGINT;
    revision_id UUID;
    revision_state VARCHAR(20);
BEGIN
    revision_tenant := CASE WHEN TG_OP = 'DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
    revision_id := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.floor_plan_revision_id ELSE NEW.floor_plan_revision_id END;
    SELECT lifecycle_state INTO revision_state
      FROM wp_floor_plan_revisions
     WHERE tenant_id = revision_tenant
       AND floor_plan_revision_id = revision_id;
    IF revision_state IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'Only draft workplace floor-plan placements are mutable';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER trg_wp_floor_plan_placement_guard
BEFORE INSERT OR UPDATE OR DELETE ON wp_floor_plan_revision_placements
FOR EACH ROW EXECUTE FUNCTION sys_guard_wp_floor_plan_placement();

CREATE OR REPLACE FUNCTION sys_reject_wp_audit_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Workplace audit events are append-only';
END;
$$;

CREATE TRIGGER trg_wp_audit_events_immutable
BEFORE UPDATE OR DELETE ON wp_audit_events
FOR EACH ROW EXECUTE FUNCTION sys_reject_wp_audit_mutation();

COMMENT ON TABLE wp_campuses IS
    'Tenant campus portfolio. Existing wp_sites remain API-compatible building aggregates.';
COMMENT ON COLUMN wp_sites.campus_id IS
    'Parent campus for the site/building compatibility aggregate.';
COMMENT ON TABLE wp_zones IS
    'Governed floor zones addressed by immutable identifiers rather than display names.';
COMMENT ON TABLE wp_sections IS
    'Optional subdivisions of a floor zone for finer spatial governance.';
COMMENT ON TABLE wp_policy_overrides IS
    'Partial Workplace policy documents resolved from tenant through resource scope.';
COMMENT ON TABLE wp_floor_plan_revisions IS
    'Governed floor-plan snapshots; only PUBLISHED revisions may feed runtime projection.';
COMMENT ON TABLE wp_floor_plan_revision_placements IS
    'Full resource placement snapshot owned by a floor-plan revision.';
COMMENT ON TABLE wp_delegated_admin_scopes IS
    'Identifier-based delegated Workplace administration for site or verified group scope.';

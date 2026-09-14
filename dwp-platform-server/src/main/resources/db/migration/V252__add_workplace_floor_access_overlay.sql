-- Optional floors restrict an already-authorized SITE; they never grant SITE access.
ALTER TABLE wp_floors ADD CONSTRAINT uk_wp_floors_tenant_site_floor UNIQUE (tenant_id, site_id, floor_id);
ALTER TABLE wp_site_access_rules ADD COLUMN floor_id UUID;
ALTER TABLE wp_site_access_rules ADD CONSTRAINT fk_wp_access_rules_tenant_site_floor
    FOREIGN KEY (tenant_id, site_id, floor_id) REFERENCES wp_floors (tenant_id, site_id, floor_id);
DROP INDEX uk_wp_site_access_rules_user;
DROP INDEX uk_wp_site_access_rules_group;
CREATE UNIQUE INDEX uk_wp_site_access_rules_user
    ON wp_site_access_rules (tenant_id, site_id, subject_user_id, permission_code)
    WHERE subject_type = 'USER' AND floor_id IS NULL;
CREATE UNIQUE INDEX uk_wp_site_access_rules_group
    ON wp_site_access_rules (tenant_id, site_id, subject_group_ref, permission_code)
    WHERE subject_type = 'GROUP_REF' AND floor_id IS NULL;
CREATE UNIQUE INDEX uk_wp_floor_access_rules_user
    ON wp_site_access_rules (tenant_id, site_id, floor_id, subject_user_id, permission_code)
    WHERE subject_type = 'USER' AND floor_id IS NOT NULL;
CREATE UNIQUE INDEX uk_wp_floor_access_rules_group
    ON wp_site_access_rules (tenant_id, site_id, floor_id, subject_group_ref, permission_code)
    WHERE subject_type = 'GROUP_REF' AND floor_id IS NOT NULL;
CREATE INDEX idx_wp_access_rules_floor_evaluation
    ON wp_site_access_rules (tenant_id, site_id, floor_id, lifecycle_state, permission_code);
COMMENT ON COLUMN wp_site_access_rules.floor_id IS 'NULL preserves SITE access semantics. Configured floor rules only restrict an allowed SITE; floor rules never open SITE navigation.';

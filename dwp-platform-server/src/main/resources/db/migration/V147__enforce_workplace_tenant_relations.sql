ALTER TABLE wp_sites
    ADD CONSTRAINT uk_wp_sites_tenant_site UNIQUE (tenant_id, site_id);

ALTER TABLE wp_floors
    ADD CONSTRAINT uk_wp_floors_tenant_floor UNIQUE (tenant_id, floor_id),
    ADD CONSTRAINT fk_wp_floors_tenant_site
        FOREIGN KEY (tenant_id, site_id)
        REFERENCES wp_sites (tenant_id, site_id);

ALTER TABLE cal_resources
    ADD CONSTRAINT uk_cal_resources_tenant_resource UNIQUE (tenant_id, resource_id);

ALTER TABLE wp_resources
    ADD CONSTRAINT uk_wp_resources_tenant_resource UNIQUE (tenant_id, resource_id),
    ADD CONSTRAINT fk_wp_resources_tenant_floor
        FOREIGN KEY (tenant_id, floor_id)
        REFERENCES wp_floors (tenant_id, floor_id),
    ADD CONSTRAINT fk_wp_resources_tenant_calendar_resource
        FOREIGN KEY (tenant_id, calendar_resource_id)
        REFERENCES cal_resources (tenant_id, resource_id);

ALTER TABLE wp_bookings
    ADD CONSTRAINT fk_wp_bookings_tenant_resource
        FOREIGN KEY (tenant_id, resource_id)
        REFERENCES wp_resources (tenant_id, resource_id);

ALTER TABLE wp_tenant_policies
    ADD CONSTRAINT ck_wp_policy_consecutive_window
        CHECK (maximum_consecutive_days <= booking_window_days);

COMMENT ON CONSTRAINT fk_wp_floors_tenant_site ON wp_floors IS
    'Database-level tenant isolation for the site-to-floor hierarchy.';
COMMENT ON CONSTRAINT fk_wp_resources_tenant_floor ON wp_resources IS
    'Database-level tenant isolation for the floor-to-resource hierarchy.';
COMMENT ON CONSTRAINT fk_wp_bookings_tenant_resource ON wp_bookings IS
    'Database-level tenant isolation for Workplace reservations.';

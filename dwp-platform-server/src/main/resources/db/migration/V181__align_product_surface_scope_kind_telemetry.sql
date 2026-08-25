-- Keep privacy-minimized UX telemetry aligned with the canonical Product Surface scope taxonomy.
ALTER TABLE plt_product_surface_ux_event
    DROP CONSTRAINT IF EXISTS plt_product_surface_ux_event_scope_kind_check;

ALTER TABLE plt_product_surface_ux_event
    ADD CONSTRAINT ck_plt_product_surface_ux_event_scope_kind
        CHECK (scope_kind IS NULL OR scope_kind IN (
            'TENANT', 'SELF', 'TEAM', 'ORG_UNIT', 'LEGAL_ENTITY', 'DOMAIN',
            'RESOURCE_SET', 'RESOURCE', 'POLICY_NODE', 'TARGET_POPULATION',
            'SUPPORT_SESSION'));

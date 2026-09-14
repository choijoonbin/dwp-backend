ALTER TABLE wp_delegated_admin_scopes
    ADD COLUMN floor_scope_restricted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT uk_wp_delegated_scope_tenant_site UNIQUE (tenant_id, delegation_id, site_id),
    ADD CONSTRAINT ck_wp_delegated_floor_scope_site CHECK (NOT floor_scope_restricted OR scope_type = 'SITE');

CREATE TABLE wp_delegated_admin_scope_floors (
    tenant_id BIGINT NOT NULL,
    delegation_id UUID NOT NULL,
    site_id UUID NOT NULL,
    floor_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, delegation_id, floor_id),
    FOREIGN KEY (tenant_id, delegation_id, site_id)
        REFERENCES wp_delegated_admin_scopes (tenant_id, delegation_id, site_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, site_id, floor_id)
        REFERENCES wp_floors (tenant_id, site_id, floor_id)
);

CREATE FUNCTION wp_assert_delegated_floor_scope() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    scope_tenant BIGINT;
    scope_id UUID;
    restricted BOOLEAN;
    floor_count BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        scope_tenant := OLD.tenant_id; scope_id := OLD.delegation_id;
    ELSE
        scope_tenant := NEW.tenant_id; scope_id := NEW.delegation_id;
    END IF;
    SELECT floor_scope_restricted INTO restricted FROM wp_delegated_admin_scopes
        WHERE tenant_id = scope_tenant AND delegation_id = scope_id;
    IF FOUND THEN
        SELECT COUNT(*) INTO floor_count FROM wp_delegated_admin_scope_floors
            WHERE tenant_id = scope_tenant AND delegation_id = scope_id;
        IF (restricted AND floor_count = 0) OR (NOT restricted AND floor_count <> 0) THEN
            RAISE EXCEPTION 'Delegated floor scope must be nonempty exactly when restricted'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER wp_delegated_parent_floor_scope_consistent
    AFTER INSERT OR UPDATE ON wp_delegated_admin_scopes
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wp_assert_delegated_floor_scope();
CREATE CONSTRAINT TRIGGER wp_delegated_child_floor_scope_consistent
    AFTER INSERT OR UPDATE OR DELETE ON wp_delegated_admin_scope_floors
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wp_assert_delegated_floor_scope();

CREATE FUNCTION wp_preserve_restricted_delegation_scope() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.floor_scope_restricted <> NEW.floor_scope_restricted
       OR (OLD.floor_scope_restricted AND
           (OLD.delegate_type, OLD.delegate_user_id, OLD.delegate_group_ref, OLD.scope_type, OLD.site_id, OLD.managed_group_ref)
           IS DISTINCT FROM
           (NEW.delegate_type, NEW.delegate_user_id, NEW.delegate_group_ref, NEW.scope_type, NEW.site_id, NEW.managed_group_ref)) THEN
        RAISE EXCEPTION 'Restricted delegation scope is immutable; revoke and create a new delegation'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER wp_delegated_restricted_scope_immutable
    BEFORE UPDATE ON wp_delegated_admin_scopes
    FOR EACH ROW EXECUTE FUNCTION wp_preserve_restricted_delegation_scope();

CREATE FUNCTION wp_preserve_delegated_floor_identity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.tenant_id, OLD.delegation_id, OLD.site_id, OLD.floor_id)
       IS DISTINCT FROM (NEW.tenant_id, NEW.delegation_id, NEW.site_id, NEW.floor_id) THEN
        RAISE EXCEPTION 'Delegated floor membership identity is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER wp_delegated_floor_identity_immutable
    BEFORE UPDATE ON wp_delegated_admin_scope_floors
    FOR EACH ROW EXECUTE FUNCTION wp_preserve_delegated_floor_identity();

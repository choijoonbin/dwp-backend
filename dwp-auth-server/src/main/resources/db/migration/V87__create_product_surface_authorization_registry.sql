-- CORE-006 authorization contracts are immutable, versioned snapshots.
-- Loading a DRAFT does not approve or activate it. The active pointer is the
-- sole runtime authority and is changed with compare-and-swap semantics.

CREATE TABLE auth_product_authorization_bundle (
    bundle_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bundle_key VARCHAR(80) NOT NULL,
    version BIGINT NOT NULL,
    bundle_status VARCHAR(20) NOT NULL,
    schema_version INTEGER NOT NULL,
    checksum_algorithm VARCHAR(20) NOT NULL DEFAULT 'SHA-256',
    checksum VARCHAR(64) NOT NULL,
    owner VARCHAR(200) NOT NULL,
    approved_by VARCHAR(160),
    approved_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_authorization_bundle_key_version
        UNIQUE (bundle_key, version),
    CONSTRAINT uk_product_authorization_bundle_id_key
        UNIQUE (bundle_id, bundle_key),
    CONSTRAINT ck_product_authorization_bundle_key
        CHECK (bundle_key ~ '^[a-z][a-z0-9-]{2,79}$'),
    CONSTRAINT ck_product_authorization_bundle_version CHECK (version > 0),
    CONSTRAINT ck_product_authorization_bundle_schema CHECK (schema_version > 0),
    CONSTRAINT ck_product_authorization_bundle_status
        CHECK (bundle_status IN ('DRAFT', 'APPROVED', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_product_authorization_bundle_checksum_algorithm
        CHECK (checksum_algorithm = 'SHA-256'),
    CONSTRAINT ck_product_authorization_bundle_checksum
        CHECK (checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_product_authorization_bundle_approval
        CHECK ((bundle_status = 'DRAFT' AND approved_by IS NULL AND approved_at IS NULL)
            OR (bundle_status <> 'DRAFT' AND approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT ck_product_authorization_bundle_activation
        CHECK (bundle_status <> 'ACTIVE' OR activated_at IS NOT NULL)
);

CREATE TABLE auth_product_capability_contract (
    bundle_id UUID NOT NULL
        REFERENCES auth_product_authorization_bundle(bundle_id),
    contract_key VARCHAR(180) NOT NULL,
    product_key VARCHAR(80) NOT NULL,
    surface_key VARCHAR(120) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL,
    descriptor JSONB NOT NULL,
    PRIMARY KEY (bundle_id, contract_key),
    CONSTRAINT ck_product_capability_contract_key
        CHECK (contract_key ~ '^[a-z][a-z0-9.-]{2,179}$'),
    CONSTRAINT ck_product_capability_lifecycle
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_product_capability_descriptor CHECK (jsonb_typeof(descriptor) = 'object')
);

CREATE TABLE auth_product_access_policy (
    bundle_id UUID NOT NULL
        REFERENCES auth_product_authorization_bundle(bundle_id),
    access_policy_key VARCHAR(180) NOT NULL,
    navigation_context_id VARCHAR(160) NOT NULL,
    product_key VARCHAR(80),
    surface_key VARCHAR(120),
    lifecycle_state VARCHAR(20) NOT NULL,
    descriptor JSONB NOT NULL,
    PRIMARY KEY (bundle_id, access_policy_key),
    CONSTRAINT ck_product_access_policy_key
        CHECK (access_policy_key ~ '^[A-Za-z][A-Za-z0-9._-]{2,179}$'),
    CONSTRAINT ck_product_access_policy_subject
        CHECK ((product_key IS NULL) = (surface_key IS NULL)),
    CONSTRAINT ck_product_access_policy_lifecycle
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_product_access_policy_descriptor CHECK (jsonb_typeof(descriptor) = 'object')
);

CREATE TABLE auth_product_entitlement_expression (
    bundle_id UUID NOT NULL
        REFERENCES auth_product_authorization_bundle(bundle_id),
    expression_key VARCHAR(180) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL,
    descriptor JSONB NOT NULL,
    PRIMARY KEY (bundle_id, expression_key),
    CONSTRAINT ck_product_entitlement_expression_key
        CHECK (expression_key ~ '^[A-Z][A-Z0-9_]{2,179}$'),
    CONSTRAINT ck_product_entitlement_expression_lifecycle
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_product_entitlement_expression_descriptor
        CHECK (jsonb_typeof(descriptor) = 'object')
);

CREATE TABLE auth_product_predicate_policy (
    bundle_id UUID NOT NULL
        REFERENCES auth_product_authorization_bundle(bundle_id),
    predicate_policy_key VARCHAR(200) NOT NULL,
    owner_service_key VARCHAR(40) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL,
    descriptor JSONB NOT NULL,
    PRIMARY KEY (bundle_id, predicate_policy_key),
    CONSTRAINT ck_product_predicate_policy_key
        CHECK (predicate_policy_key ~ '^predicate\.[a-z][a-z0-9.-]{2,189}$'),
    CONSTRAINT ck_product_predicate_owner_service
        CHECK (owner_service_key IN ('auth', 'platform', 'approval', 'people')),
    CONSTRAINT ck_product_predicate_lifecycle
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_product_predicate_descriptor CHECK (jsonb_typeof(descriptor) = 'object')
);

CREATE TABLE auth_governed_route_contract (
    bundle_id UUID NOT NULL
        REFERENCES auth_product_authorization_bundle(bundle_id),
    route_contract_key VARCHAR(240) NOT NULL,
    navigation_context_id VARCHAR(160) NOT NULL,
    subject_type VARCHAR(24) NOT NULL,
    product_key VARCHAR(80),
    surface_key VARCHAR(120),
    route_kind VARCHAR(16) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL,
    descriptor JSONB NOT NULL,
    PRIMARY KEY (bundle_id, route_contract_key),
    CONSTRAINT ck_governed_route_contract_key
        CHECK (route_contract_key ~ '^route\.[a-z][a-z0-9._-]{3,233}$'),
    CONSTRAINT ck_governed_route_navigation_context
        CHECK (navigation_context_id ~ '^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$'
            AND navigation_context_id NOT LIKE '%\_%' ESCAPE '\'),
    CONSTRAINT ck_governed_route_subject_type
        CHECK (subject_type IN ('PRODUCT', 'GOVERNED_CONTEXT')),
    CONSTRAINT ck_governed_route_subject
        CHECK ((subject_type = 'PRODUCT' AND product_key IS NOT NULL AND surface_key IS NOT NULL)
            OR (subject_type = 'GOVERNED_CONTEXT' AND product_key IS NULL AND surface_key IS NULL)),
    CONSTRAINT ck_governed_route_kind CHECK (route_kind IN ('PAGE', 'DATA', 'ACTION')),
    CONSTRAINT ck_governed_route_lifecycle
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_governed_route_descriptor CHECK (jsonb_typeof(descriptor) = 'object')
);

CREATE TABLE auth_product_authorization_active (
    bundle_key VARCHAR(80) PRIMARY KEY,
    bundle_id UUID NOT NULL UNIQUE,
    revision BIGINT NOT NULL DEFAULT 1,
    activated_by VARCHAR(160) NOT NULL,
    activated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_authorization_active_bundle
        FOREIGN KEY (bundle_id, bundle_key)
        REFERENCES auth_product_authorization_bundle(bundle_id, bundle_key),
    CONSTRAINT ck_product_authorization_active_revision CHECK (revision > 0)
);

CREATE TABLE auth_product_authorization_activation_event (
    activation_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bundle_key VARCHAR(80) NOT NULL,
    from_bundle_id UUID,
    to_bundle_id UUID NOT NULL,
    operation VARCHAR(20) NOT NULL,
    expected_revision BIGINT NOT NULL,
    resulting_revision BIGINT NOT NULL,
    actor_ref VARCHAR(160) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_authorization_event_revision
        UNIQUE (bundle_key, resulting_revision),
    CONSTRAINT fk_product_authorization_event_from
        FOREIGN KEY (from_bundle_id, bundle_key)
        REFERENCES auth_product_authorization_bundle(bundle_id, bundle_key),
    CONSTRAINT fk_product_authorization_event_to
        FOREIGN KEY (to_bundle_id, bundle_key)
        REFERENCES auth_product_authorization_bundle(bundle_id, bundle_key),
    CONSTRAINT ck_product_authorization_event_operation
        CHECK (operation IN ('ACTIVATE', 'ROLLBACK')),
    CONSTRAINT ck_product_authorization_event_revisions
        CHECK (expected_revision >= 0 AND resulting_revision = expected_revision + 1)
);

CREATE INDEX ix_product_capability_product_surface
    ON auth_product_capability_contract(bundle_id, product_key, surface_key);
CREATE INDEX ix_product_access_policy_context
    ON auth_product_access_policy(bundle_id, navigation_context_id);
CREATE INDEX ix_product_predicate_owner
    ON auth_product_predicate_policy(bundle_id, owner_service_key);
CREATE INDEX ix_governed_route_subject
    ON auth_governed_route_contract(bundle_id, subject_type, product_key, surface_key);
CREATE INDEX ix_governed_route_navigation_context
    ON auth_governed_route_contract(bundle_id, navigation_context_id);
CREATE INDEX ix_product_authorization_activation_event
    ON auth_product_authorization_activation_event(bundle_key, occurred_at DESC);

CREATE OR REPLACE FUNCTION dwp_reject_authorization_descriptor_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'authorization descriptor rows are immutable; create a new bundle version';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_product_capability_immutable
    BEFORE UPDATE OR DELETE ON auth_product_capability_contract
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_authorization_descriptor_mutation();
CREATE TRIGGER trg_product_access_policy_immutable
    BEFORE UPDATE OR DELETE ON auth_product_access_policy
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_authorization_descriptor_mutation();
CREATE TRIGGER trg_product_entitlement_expression_immutable
    BEFORE UPDATE OR DELETE ON auth_product_entitlement_expression
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_authorization_descriptor_mutation();
CREATE TRIGGER trg_product_predicate_policy_immutable
    BEFORE UPDATE OR DELETE ON auth_product_predicate_policy
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_authorization_descriptor_mutation();
CREATE TRIGGER trg_governed_route_contract_immutable
    BEFORE UPDATE OR DELETE ON auth_governed_route_contract
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_authorization_descriptor_mutation();

CREATE OR REPLACE FUNCTION dwp_guard_authorization_bundle_update()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.bundle_id <> OLD.bundle_id
       OR NEW.bundle_key <> OLD.bundle_key
       OR NEW.version <> OLD.version
       OR NEW.schema_version <> OLD.schema_version
       OR NEW.checksum_algorithm <> OLD.checksum_algorithm
       OR NEW.checksum <> OLD.checksum
       OR NEW.owner <> OLD.owner
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'authorization bundle identity and checksum are immutable';
    END IF;

    IF NEW.bundle_status <> OLD.bundle_status
       AND NOT ((OLD.bundle_status = 'DRAFT' AND NEW.bundle_status IN ('APPROVED', 'RETIRED'))
             OR (OLD.bundle_status = 'APPROVED' AND NEW.bundle_status IN ('ACTIVE', 'RETIRED'))
             OR (OLD.bundle_status = 'ACTIVE' AND NEW.bundle_status IN ('APPROVED', 'RETIRED'))) THEN
        RAISE EXCEPTION 'invalid authorization bundle status transition: % -> %',
            OLD.bundle_status, NEW.bundle_status;
    END IF;

    IF OLD.bundle_status <> 'DRAFT'
       AND (NEW.approved_by IS DISTINCT FROM OLD.approved_by
            OR NEW.approved_at IS DISTINCT FROM OLD.approved_at) THEN
        RAISE EXCEPTION 'authorization bundle approval evidence is immutable';
    END IF;

    IF NOT (OLD.bundle_status = 'APPROVED' AND NEW.bundle_status = 'ACTIVE')
       AND NEW.activated_at IS DISTINCT FROM OLD.activated_at THEN
        RAISE EXCEPTION 'authorization bundle activation time can change only on activation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_product_authorization_bundle_guard
    BEFORE UPDATE ON auth_product_authorization_bundle
    FOR EACH ROW EXECUTE FUNCTION dwp_guard_authorization_bundle_update();

CREATE TRIGGER trg_product_authorization_bundle_no_delete
    BEFORE DELETE ON auth_product_authorization_bundle
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_authorization_descriptor_mutation();

CREATE OR REPLACE FUNCTION dwp_guard_authorization_active_pointer_update()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM auth_product_authorization_bundle bundle
         WHERE bundle.bundle_id = NEW.bundle_id
           AND bundle.bundle_key = NEW.bundle_key
           AND bundle.bundle_status = 'ACTIVE') THEN
        RAISE EXCEPTION 'authorization active pointer target must be an ACTIVE same-key bundle';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.revision <> 1 THEN
            RAISE EXCEPTION 'the initial authorization active pointer revision must be 1';
        END IF;
    ELSIF NEW.bundle_key <> OLD.bundle_key
          OR NEW.revision <> OLD.revision + 1
          OR NEW.activated_at <= OLD.activated_at THEN
        RAISE EXCEPTION 'authorization active pointer requires a monotonic CAS transition';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_product_authorization_active_pointer_guard
    BEFORE INSERT OR UPDATE ON auth_product_authorization_active
    FOR EACH ROW EXECUTE FUNCTION dwp_guard_authorization_active_pointer_update();

CREATE TRIGGER trg_product_authorization_active_pointer_no_delete
    BEFORE DELETE ON auth_product_authorization_active
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_authorization_descriptor_mutation();

CREATE TRIGGER trg_product_authorization_activation_event_immutable
    BEFORE UPDATE OR DELETE ON auth_product_authorization_activation_event
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_authorization_descriptor_mutation();

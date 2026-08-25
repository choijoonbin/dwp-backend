CREATE TABLE auth_product_authority_endpoint (
    bundle_id UUID NOT NULL
        REFERENCES auth_product_authorization_bundle(bundle_id),
    endpoint_key VARCHAR(180) NOT NULL,
    service_key VARCHAR(40) NOT NULL,
    descriptor JSONB NOT NULL,
    PRIMARY KEY (bundle_id, endpoint_key),
    CONSTRAINT ck_product_authority_endpoint_key
        CHECK (endpoint_key ~ '^[a-z][a-z0-9.-]{2,179}$'),
    CONSTRAINT ck_product_authority_endpoint_service
        CHECK (service_key IN ('auth', 'platform', 'approval', 'people', 'provider')),
    CONSTRAINT ck_product_authority_endpoint_descriptor
        CHECK (jsonb_typeof(descriptor) = 'object')
);

CREATE TRIGGER trg_product_authority_endpoint_immutable
    BEFORE UPDATE OR DELETE ON auth_product_authority_endpoint
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_authorization_descriptor_mutation();

COMMENT ON TABLE auth_product_authority_endpoint IS
    'Immutable bundle-owned public-to-service authority endpoint descriptors; empty for v1-v3.';

-- Declare the immutable v32 seed that governs the two communications-management
-- code sets with the existing communications.content.read authority. The seed
-- loader remains the only importer, and approval plus activation stay explicit;
-- Flyway records the artifact but never activates it.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    32,
    'b620ea86a8310cf23796e3e380b74c39764bdca28f41033496d21887a89da9cc',
    'product-surfaces-v1.bundle-v32.generated.json',
    'DRAFT',
    FALSE);

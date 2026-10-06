-- Keep the bundled SKAX demo branding reproducible after a clean database build.
-- Version zero identifies the untouched V12 seed, so administrator uploads,
-- settings changes, and explicit logo resets are never overwritten.
UPDATE adm_tenant_branding
   SET logo_asset_key = 'builtin/branding/skax-tenant-logo.svg',
       logo_original_name = 'skax-tenant-logo.svg',
       logo_content_type = 'image/svg+xml',
       logo_size_bytes = 6104,
       logo_sha256 = 'd95624857451f9c16f5993c21e14048b253cc3c809e4640d891f3dd1077642b0',
       logo_width = 106,
       logo_height = 56,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE tenant_id = 1
   AND organization_name = 'SKAX'
   AND version = 0
   AND logo_asset_key IS NULL;

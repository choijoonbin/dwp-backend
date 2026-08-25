-- Flow Home v2 presents response, request, and role pulse as three bounded
-- purpose widgets. Existing daily-brief preferences remain byte-for-byte
-- valid; this only adds the compact 1/3 footprint used by response-hub.
UPDATE sys_code_values
   SET behavior_metadata = jsonb_set(
           jsonb_set(
               behavior_metadata,
               '{allowedSizes}',
               '["compact","large","full"]'::jsonb,
               true),
           '{flowAlias}',
           '"response-hub"'::jsonb,
           true),
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_WIDGET'
   AND code = 'daily-brief';

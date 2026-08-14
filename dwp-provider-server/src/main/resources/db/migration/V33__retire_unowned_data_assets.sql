DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM prv_service_health_observations LIMIT 1) THEN
        RAISE EXCEPTION 'prv_service_health_observations contains data and requires an explicit migration plan';
    END IF;
END
$$;

DELETE FROM prv_data_lineage_edges
 WHERE source_asset_key = ANY (ARRAY[
           'auth.public.com_role_hierarchy',
           'auth.public.com_separation_of_duty_rules',
           'people.public.ppl_attribute_definitions',
           'people.public.ppl_attribute_values',
           'people.public.ppl_person_private',
           'platform.public.adm_message_overrides',
           'platform.public.sys_admin_command_requests',
           'platform.public.sys_admin_command_approvals',
           'provider.public.prv_service_health_observations'
       ])
    OR target_asset_key = ANY (ARRAY[
           'auth.public.com_role_hierarchy',
           'auth.public.com_separation_of_duty_rules',
           'people.public.ppl_attribute_definitions',
           'people.public.ppl_attribute_values',
           'people.public.ppl_person_private',
           'platform.public.adm_message_overrides',
           'platform.public.sys_admin_command_requests',
           'platform.public.sys_admin_command_approvals',
           'provider.public.prv_service_health_observations'
       ]);

DELETE FROM prv_data_asset_annotations
 WHERE asset_key = ANY (ARRAY[
           'auth.public.com_role_hierarchy',
           'auth.public.com_separation_of_duty_rules',
           'people.public.ppl_attribute_definitions',
           'people.public.ppl_attribute_values',
           'people.public.ppl_person_private',
           'platform.public.adm_message_overrides',
           'platform.public.sys_admin_command_requests',
           'platform.public.sys_admin_command_approvals',
           'provider.public.prv_service_health_observations'
       ]);

UPDATE prv_data_asset_annotations
   SET lifecycle_state = 'ACTIVE',
       review_state = 'VERIFIED',
       description = 'Normalized retry and failure ledger written by HRIS connector execution.',
       review_note = 'HrisIntegrationRepository records connector failures with redacted messages.',
       last_reviewed_at = CURRENT_TIMESTAMP,
       updated_at = CURRENT_TIMESTAMP
 WHERE asset_key = 'people.public.int_sync_errors';

-- Current service posture is sourced from governed SLO snapshots and tenant service instances.
DROP TABLE prv_service_health_observations;

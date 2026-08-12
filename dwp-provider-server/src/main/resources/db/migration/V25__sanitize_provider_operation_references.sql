UPDATE prv_operations
   SET failure_message = 'Provider operation failed. Review the correlated service trace.'
 WHERE failure_message IS NOT NULL
   AND (
       failure_message ILIKE '%insert into %'
       OR failure_message ILIKE '%update % set %'
       OR failure_message ILIKE '%select % from %'
       OR failure_message ILIKE '%org.postgresql.%'
       OR failure_message ILIKE '%java.%exception%'
       OR failure_message LIKE '%/Users/%'
       OR failure_message LIKE '%/home/%'
   );

UPDATE prv_operation_steps step
   SET external_reference = 'asset-storage:tenant:' || operation.provider_tenant_id
  FROM prv_operations operation
 WHERE step.operation_id = operation.operation_id
   AND step.step_key = 'ASSET_STORAGE'
   AND step.external_reference IS NOT NULL
   AND (
       step.external_reference LIKE '/%'
       OR step.external_reference ~ '^[A-Za-z]:[\\/]'
       OR step.external_reference LIKE 'file:%'
   );

UPDATE prv_tenant_service_instances
   SET external_resource_id = 'asset-storage:tenant:' || provider_tenant_id
 WHERE service_key = 'asset-storage'
   AND external_resource_id IS NOT NULL
   AND (
       external_resource_id LIKE '/%'
       OR external_resource_id ~ '^[A-Za-z]:[\\/]'
       OR external_resource_id LIKE 'file:%'
   );

UPDATE prv_tenant_service_instances
   SET endpoint_reference = NULL
 WHERE endpoint_reference IS NOT NULL
   AND (
       endpoint_reference LIKE '/%'
       OR endpoint_reference ~ '^[A-Za-z]:[\\/]'
       OR endpoint_reference LIKE 'file:%'
   );

COMMENT ON COLUMN prv_operation_steps.external_reference IS
    'Logical downstream resource identifier. Filesystem paths and secret-bearing locations are prohibited.';

COMMENT ON COLUMN prv_operations.failure_message IS
    'Redacted operator-facing failure summary; full diagnostics remain in access-controlled service telemetry.';

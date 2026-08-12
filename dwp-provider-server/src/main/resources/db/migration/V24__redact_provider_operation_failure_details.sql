UPDATE prv_operation_step_attempts
   SET error_message = 'Provider state persistence failed. Review the correlated service trace.'
 WHERE error_message IS NOT NULL
   AND (
       error_message ILIKE '%insert into %'
       OR error_message ILIKE '%update % set %'
       OR error_message ILIKE '%select % from %'
       OR error_message ILIKE '%org.postgresql.%'
       OR error_message ILIKE '%java.%exception%'
       OR error_message LIKE '%/Users/%'
       OR error_message LIKE '%/home/%'
   );

UPDATE prv_operation_steps
   SET last_error_message = 'Provider state persistence failed. Review the correlated service trace.'
 WHERE last_error_message IS NOT NULL
   AND (
       last_error_message ILIKE '%insert into %'
       OR last_error_message ILIKE '%update % set %'
       OR last_error_message ILIKE '%select % from %'
       OR last_error_message ILIKE '%org.postgresql.%'
       OR last_error_message ILIKE '%java.%exception%'
       OR last_error_message LIKE '%/Users/%'
       OR last_error_message LIKE '%/home/%'
   );

COMMENT ON COLUMN prv_operation_step_attempts.error_message IS
    'Redacted operator-facing failure summary; full diagnostics remain in access-controlled service telemetry.';

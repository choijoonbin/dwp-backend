UPDATE com_resources
   SET name = 'HRIS',
       updated_at = CURRENT_TIMESTAMP
 WHERE type = 'APP'
   AND key = 'APP.HRIS'
   AND name <> 'HRIS';

-- Idempotency receipts persist the concrete Java DTO class name so an exact
-- replay cannot be decoded as a different response contract. Phase 2 nested
-- DTO names are 76-88 characters, which do not fit the pre-release 48-character
-- column and would roll back an otherwise successful mutation.
ALTER TABLE usr_home_command_receipts
    ALTER COLUMN response_type TYPE VARCHAR(160);

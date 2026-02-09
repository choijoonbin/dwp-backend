-- ======================================================================
-- Fix: item_type enum to varchar for Hibernate/JPA compatibility
-- PostgreSQL enum vs Java String comparison causes: operator does not exist: dwp_aura.open_item_type = character varying
-- ======================================================================

SET search_path TO dwp_aura, public;

-- 1) Drop indexes that reference item_type
DROP INDEX IF EXISTS dwp_aura.ix_fi_open_item_tenant_type_due;
DROP INDEX IF EXISTS dwp_aura.ix_fi_open_item_partner;

-- 2) Alter column from enum to varchar (Hibernate String mapping 호환)
ALTER TABLE dwp_aura.fi_open_item
  ALTER COLUMN item_type TYPE varchar(10) USING item_type::text;

-- 3) Recreate indexes
CREATE INDEX IF NOT EXISTS ix_fi_open_item_tenant_type_due
  ON dwp_aura.fi_open_item(tenant_id, item_type, due_date) WHERE cleared = false;

CREATE INDEX IF NOT EXISTS ix_fi_open_item_partner
  ON dwp_aura.fi_open_item(tenant_id, item_type, lifnr, kunnr);

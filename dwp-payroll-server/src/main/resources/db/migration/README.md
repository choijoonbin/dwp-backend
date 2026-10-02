# Payroll migration stream

Control-owned bootstrap marker for the reserved Payroll Flyway stream. G3 slices add only
approved, immutable versioned migrations in this location.

The legal-entity scope projection is sealed through a single trusted publisher transaction:
create a `BUILDING` parent, insert its complete membership, then transition it to `ACTIVE`.
The application runtime role remains SELECT-only. Provisioning a distinct least-privilege
projection-writer role and its delivery credentials is an activation gate; application-runtime
DML must not be used as a substitute.

`V3` grants only projection build, seal, and terminal transition columns to the separately
provisioned `${payrollProjectionPublisherRole}`. The publisher has no Payroll configuration,
receipt, audit, schema, or migration-history authority.

`V4` rejects any legacy source-scope rows and changes the projection contract to the exact
People-derived `hcm-scope-*` eligibility key returned by Gateway.

`V5` makes each migration-owned trigger function `SECURITY DEFINER`, pins its search path,
and revokes direct execution while preserving trigger-only enforcement for runtime and publisher DML.

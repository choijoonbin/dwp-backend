# Payroll migration stream

Control-owned bootstrap marker for the reserved Payroll Flyway stream. G3 slices add only
approved, immutable versioned migrations in this location.

The legal-entity scope projection is sealed through a single trusted publisher transaction:
create a `BUILDING` parent, insert its complete membership, then transition it to `ACTIVE`.
The application runtime role remains SELECT-only. Provisioning a distinct least-privilege
projection-writer role and its delivery credentials is an activation gate; application-runtime
DML must not be used as a substitute.

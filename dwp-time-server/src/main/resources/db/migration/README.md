# Time migration stream

Control-owned bootstrap marker for the reserved Time Flyway stream. G3 slices add only
approved, immutable versioned migrations in this location.

- `V1` owns the work-regime, assignment, simulation, receipt, audit, and outbox foundation.
- `V2` owns the server-side target-population projection. Gateway scope hashes are actor-bound
  lookup keys only; work plans persist a stable population and immutable People-assignment
  evidence. The Time runtime can read the authoritative projection but cannot widen or revoke it.
- `V3` records immutable per-command proof of the actor entitlement, stable population, and
  concrete People-assignment membership revalidated by the owner mutation transaction.
- `V4` closes same-state SoD evidence rewrites and permits row locking of read-only projection
  rows without granting the Time runtime authority to modify their business data.
- `V5` grants only target-population projection columns to the separately provisioned
  `${timeProjectionPublisherRole}`. The publisher has no work-regime command, receipt,
  immutable evidence, audit, rule-pack, schema, or migration-history authority.
- `V6` rejects any legacy source-scope rows and changes every persisted Gateway-scope field
  to the exact People-derived `hcm-scope-*` eligibility key returned by Gateway.
- `V7` normalizes every post-foundation trigger function to the migration-owner execution
  profile with a fixed search path and no direct service-role execution.

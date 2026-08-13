# Workforce Access and Export Governance

Status: Accepted; artifact execution disabled until external gates are approved

Last verified: 2026-08-13

Scope: tenant workforce read boundaries and governed asynchronous export requests owned
by `dwp-people-server`.

The user-facing product entry is the unified `/hr` shell. Public directory access and
sensitive workforce operations can appear in that one product experience, but they remain
separate authorization and data contracts. `APP.HRIS:VIEW` never implies
`APP.WORKFORCE_MANAGEMENT:VIEW` or `DATA.WORKFORCE:*`.

## Decision

DWP authorizes workforce data twice: the route permission establishes that the caller may
use the capability, and an active workforce access policy determines the permitted target
population, field groups, and action. UI filters are never treated as an authorization
boundary. Directory, organization intelligence, and export services resolve the same
server-side policy before reading or queuing data.

Workforce export is a request lifecycle, not a synchronous file download. A request stores
an immutable policy snapshot and SHA-256 fingerprint, enters a tenant-scoped queue only
when execution is explicitly enabled, records every worker transition in an append-only
timeline, and expires its artifact after the governed retention window.

## Authorization Invariants

- Resolve the tenant and identity from verified gateway headers; never accept them from a
  body or query parameter.
- Require `READ` or `EXPORT` in an active policy after evaluating user-over-role precedence,
  validity windows, target population, and allowed field groups.
- Expand organization trees in the database and fail closed when the resolved population
  is empty.
- Permit policy administration only through `ADMIN.WORKFORCE_ACCESS:MANAGE` or the
  backward-compatible tenant-governor fallback while permission migration is in progress.
- Prevent administrators from granting a user-scoped policy to themselves.
- Restrict export datasets to registered selection keys and the caller's effective field
  groups.
- Scope request listing, lookup, cancellation, and attempt evidence by tenant and owner;
  workforce governors may inspect all requests in their tenant.

## Export Lifecycle

```text
BLOCKED_PENDING_APPROVAL

QUEUED -> RUNNING -> COMPLETED -> EXPIRED
             |          |
             |          +-- retention cleanup discards the artifact
             +-> RETRY_WAIT -> RUNNING
             +-> FAILED -> QUEUED (one governed manual retry by default)
             +-> CANCEL_REQUESTED -> CANCELLED
QUEUED/RETRY_WAIT -> CANCELLED
```

The request is created as `BLOCKED_PENDING_APPROVAL` while either release policy or
artifact infrastructure is unavailable. Enabling execution while blocker IDs remain is a
startup error. Worker claims use database locking, failures expose only redacted messages,
automatic attempts use bounded backoff, manual retry has a policy budget, and artifacts
that exceed the configured TTL are rejected before publication.

## Persistence Contract

| Object | Responsibility |
| --- | --- |
| `ppl_workforce_access_policies` | Effective-dated subject, population, field, and action boundary |
| `ppl_workforce_export_datasets` | Allowlisted dataset and selection contract |
| `ppl_workforce_export_requests` | Tenant request, policy snapshot, lifecycle, idempotency, artifact integrity, and retention state |
| `ppl_workforce_export_attempt_events` | Append-only worker, retry, cancellation, failure, completion, and expiry evidence |

The schema is introduced by
[`V34`](../../dwp-people-server/src/main/resources/db/migration/V34__add_workforce_access_boundaries.sql),
[`V35`](../../dwp-people-server/src/main/resources/db/migration/V35__govern_workforce_export_requests.sql),
and [`V36`](../../dwp-people-server/src/main/resources/db/migration/V36__complete_workforce_export_retention.sql).
Request idempotency is unique per tenant, requester, and idempotency key. Completed rows
cannot exist without an artifact reference, hash, size, expiry, and completion timestamp.

## Activation Boundary

The internal policy, API, queue, retry, cancellation, expiry, audit, and UI contracts are
implemented. Runtime execution defaults to disabled through
`DWP_WORKFORCE_EXPORT_EXECUTION_ENABLED=false`, with blockers `D-09,D-12`.
`DisabledWorkforceExportArtifactWriter` fails closed so a deployment cannot fabricate a
successful download.

Production activation requires both release decisions:

- `D-09`: approved masking, watermark, recipient, and privacy policy.
- `D-12`: KMS-backed object storage writer, signed delivery mechanism, malware/DLP checks,
  deletion verification, and operational ownership.

Removing blocker strings without supplying the approved writer and evidence is not a valid
activation procedure.

## Verification

Executable coverage includes
[`WorkforceAccessPolicyServiceTest`](../../dwp-people-server/src/test/java/com/dwp/services/people/workforce/WorkforceAccessPolicyServiceTest.java),
[`WorkforceExportPolicyTest`](../../dwp-people-server/src/test/java/com/dwp/services/people/workforce/WorkforceExportPolicyTest.java),
[`WorkforceExportLifecycleTest`](../../dwp-people-server/src/test/java/com/dwp/services/people/workforce/WorkforceExportLifecycleTest.java),
[`WorkforceExportServiceTest`](../../dwp-people-server/src/test/java/com/dwp/services/people/workforce/WorkforceExportServiceTest.java),
[`WorkforceExportWorkerTest`](../../dwp-people-server/src/test/java/com/dwp/services/people/workforce/WorkforceExportWorkerTest.java),
and [`WorkforceExportMaintenanceTest`](../../dwp-people-server/src/test/java/com/dwp/services/people/workforce/WorkforceExportMaintenanceTest.java).

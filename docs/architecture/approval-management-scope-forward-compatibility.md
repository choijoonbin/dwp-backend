# Approval management-scope forward-compatibility runbook

Status: DRAFT implementation contract for CORE-006 G3. This document does not
approve production activation and does not change any external approval,
rollout, or feature flag.

## Fixed boundary

- The browser may send exactly one opaque `contextScopeKey` query parameter.
  The Gateway consumes it, re-evaluates live Auth authority, removes the public
  parameter, and forwards only canonical trusted scope evidence.
- Approval maps that opaque scope to the exact Auth-approved resource-set key.
  Admin collections, objects, actions, policies, signatures, recovery evidence,
  and high-risk commands use that same key. A missing exact context fails closed.
- Work catalogs expose published immutable UUID objects tenant-wide. They do not
  reuse admin-scope queries and never expose draft, future, or expired routes.
- `RS_APPROVALS` is the legacy root boundary. Schema defaults remain temporarily
  during this DRAFT so a pre-activation root-only rollout can coexist; defaults
  are not a cross-scope security mechanism.

## Activation sequence

1. Keep `DWP_APPROVAL_MANAGEMENT_SCOPE_WRITES_ENABLED=false`. Apply V14 and
   deploy scope-aware readers/writers to every Approval pod.
2. Verify externally that no scope-unaware/old pod remains. The database cannot
   identify an old binary, so this all-pod assertion is an orchestration duty,
   not a database-enforced fence.
3. Verify every pod advertises reader capability
   `approval-management-scope-v1`, then set both governed authorization v2 and
   `DWP_APPROVAL_MANAGEMENT_SCOPE_CLUSTER_FENCE_CONFIRMED=true`.
4. Enable `DWP_APPROVAL_MANAGEMENT_SCOPE_WRITES_ENABLED=true` only after steps
   1–3. The first non-root request atomically seeds the active tenant root,
   clones and verifies the four baseline policy versions and signature
   providers, and finally stamps `non_root_writes_activated_at`.
5. Verify A/B negative probes: scope A cannot list, read, mutate, publish,
   replay, delegate, or satisfy a high-risk challenge for scope B.

Provisioning failure rolls back the root seed, scoped clones, and fence stamp as
one transaction. Existing non-ACTIVE tenants are rejected without lifecycle or
timestamp mutation.

## Rollback and recovery

- Before the activation timestamp and before any non-root object exists, rollout
  `110 -> 100` is root-safe; the write gate remains off.
- Once the activation timestamp is set, new binaries reject root-only startup
  even if scoped rows are later removed. This marker is monotonic.
- After activation, rollback to a scope-unaware binary is prohibited. Recovery
  is forward-fix-only: stop new traffic, retain the schema/data, deploy a fixed
  scope-aware build, and re-run A/B probes.
- Never claim the V14 trigger set or readiness runner can stop an already running
  old binary from issuing unscoped SQL. The external all-pod assertion is the
  required safety boundary.

## Operational evidence

Retain the deployment manifest, pod capability inventory, external all-pod
assertion, flag values, fence row, live Auth decision revisions, provisioning
counts, and A/B negative probe results with the release record. No production
flag may be enabled while the authorization bundle remains DRAFT or without the
separate external approval recorded by release governance.

# Verified workspace activity history

Implementation contract, 2026-09-04. This is a read-only execution/change-history surface,
not a second task, calendar, notification, or audit administration application.

## Supported source ownership

- `WORK_ITEM`: only native `TASK` rows owned by `WORKSPACE` or `DWP_WORKSPACE` qualify.
  The event's historical audience must match the viewer, the referenced workspace row
  must still exist and currently be assigned to the viewer, and the viewer must
  currently have `APP.WORK:VIEW`. A foreign-source task, approval, service obligation,
  HR record, or other projection is not authorized merely because it appears in Work.
  Its original domain requires a separate verified source adapter and is excluded here.
- `WORKSPACE_APP` from the native `DWP Apps` writer: historical audience, active catalog row, `APP.APPS:VIEW`, and the
  catalog row's current resource `:VIEW` permission are all required.
- `APP.ACTIVITY:VIEW` grants access to the common history surface, not its source objects.
  Unsupported object types, lost permissions, deleted objects, and another user's or
  tenant's objects are absent from lists/counts and produce indistinguishable 404 details.
  `coverage.supportedObjectTypes` is derived from the viewer's current source permissions;
  a viewer with no readable Platform source receives an empty coverage list, never a claim
  that zero is a complete count for `WORK_ITEM` or `WORKSPACE_APP`.
- Routes are rebuilt from the verified current source key. Stored historical routes
  are never accepted as navigation authority. Audit evidence access remains restricted;
  linking an audit ID does not grant access to the audit administrator API.
- Agent execution snapshots remain in their owner service and are accessed through the
  existing authenticated gateway. This change adds no cross-service trusted ingestion.

## Event facts versus execution state

`CHANGE` and `USAGE` describe completed facts. A work item becoming `WAITING` produces
`state=COMPLETED`, `workStatus=WAITING`, and no execution ID. Historical `RUNNING` change
records retained as `LEGACY` never contribute to current execution counts.

`EXECUTION` requires a stable source-qualified `executionId`, nonnegative monotonic
`executionVersion`, and positive `attempt`. Attempts are sequential retries that
supersede earlier attempts of one logical execution. Concurrent executions must use
different IDs. Latest state is ordered by attempt then version, not arrival time.
The current-state view selects latest first, then applies current source ACL. This
avoids falling back to an older visible state after an access change. No platform
producer emits execution events in this release; workspace execution counts can
legitimately be zero. Agent counts must not be synthesized from work change events.

Runtime workspace commands create the actual audit record and their activity fact
in the same transaction. `auditRecordId` has a tenant-qualified deferred foreign key.
Work source event identity includes stable work UUID and resulting optimistic version;
duplicate writes fail. Verified live rows are append-only.

## HTTP contracts

- `GET /v1/workspace/activity`: compatible `events` and `generatedAt`, with additive
  `nextCursor`, `hasMore`, `snapshotAt`, `startCursor`, and explicit source coverage.
- `GET /v1/workspace/activity/events/{UUID}`: independent authorized detail, including
  events older than any loaded list page.
- `GET /v1/workspace/activity/executions/summary`: full accessible current execution
  counts; not a count of page events. Workspace coverage excludes legacy and samples.

Filters: `actor`, `state`, `query`, `source`, `objectType`, `objectId`, `executionId`,
`from` (inclusive), `to` (exclusive), `cursor`, `limit` (1–100, default 50), and
`includeUsage` (default false). Actor and state values are uppercase. Dates are instants.
Text search treats `%` and `_` literally. Filters and ACL apply in SQL before limit.
Every query is tenant/user scoped; no total can reveal hidden rows.

Pagination orders `occurredAt DESC, id DESC`, retaining a created-at watermark across
pages. Each row's `resumeCursor` can resume after exactly that row. `startCursor`
retains the source watermark when a federated page has not consumed a source row.
Cursors are opaque positions bound to tenant, user, current permission set, language,
and filter values. They are not authorization tokens; ACL is re-evaluated on every
request. Changing bound query context requires restarting pagination. `generatedAt`
is the query response time, not a claim that every upstream source has synchronized.

## Legacy migration and release checks

V222 retains all stored rows. Known seeded IDs/references are explicitly `SAMPLE`.
Actual legacy app records are mapped only by an exact object-addressed catalog route.
Actual legacy native task records are mapped using both original generator summary
prefixes, tenant, native source ownership, and UUID-shaped runtime audit reference. Those reads carry
`dataProvenance=LEGACY`, `auditStatus=LEGACY_UNLINKED`, and no counterfeit audit ID.
Foreign-source legacy projections and unprovable bindings remain `QUARANTINED`, available for controlled reconciliation,
not visible to ordinary users. Migration is separate from menu removal or deletion.

Apply V222 before starting this backend build. Deploying code without its schema is
unsupported. Testcontainers verify the V221→V222 transition, legacy preservation,
live audit/event atomicity, immutable/idempotent writes, source revocation, independent
detail, pagination past 200 records, date/search filters, and latest-execution summaries.
Production rollout still requires the normal release process, authorization review,
and validation of source coverage with the tenant's actual configured integrations.

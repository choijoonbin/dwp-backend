# Verified workspace activity history

Implementation contract, updated 2026-09-08. This is a read-only execution/change-history surface,
not a second task, calendar, notification, or audit administration application.

## Supported source ownership

- `PERSONAL_TASK`: committed Personal Work task commands are projected through V229.
  The event's historical audience and the current task owner must match the viewer,
  with `APP.WORK:VIEW` required on every list/detail/evidence request. A soft-deleted
  personal task retains authorized history with `sourceAccess=DELETED` and
  `sourceRoute=null`; its source cannot be opened. Available tasks use only the
  rebuilt `/work/queue?work=PERSONAL_TASK%3A<taskId>%3A` route.
- `WORK_ITEM`: only native `TASK` rows owned by `WORKSPACE` or `DWP_WORKSPACE` qualify.
  The event's historical audience must match the viewer, the referenced workspace row
  must still exist and currently be assigned to the viewer, and the viewer must
  currently have `APP.WORK:VIEW`. A foreign-source task, approval, service obligation,
  HR record, or other projection is not authorized merely because it appears in Work.
  Its original domain requires a separate verified source adapter and is excluded here.
- `WORKSPACE_APP` from the native `DWP Apps` writer: historical audience, active catalog row, `APP.APPS:VIEW`, and the
  catalog row's current resource `:VIEW` permission are all required.
- `APP.ACTIVITY:VIEW` grants access to the common history surface, not its source objects.
  Unsupported object types, lost permissions, unavailable native Workspace objects, and another user's or
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

## Personal Work command projection (V229)

Successful `CREATE`, `UPDATE`, `STATUS`, and `DELETE` commands bind their existing
receipt, timeline version, audit row, Activity event, and binding row in one database
transaction using deferred constraint triggers. `DAY_PLAN` is excluded. Per-item
batch commands use the same path; failed commands create no Activity fact. Source
event identity is `personal-work-command:<ownerUserId>:<commandId>` and Activity UUIDs
are deterministic. Tenant/owner-qualified command and resource-version uniqueness
prevent duplicate replay, including concurrent attempts. Existing V228 receipts are
backfilled through the same projector.

The additive public fields are `sourceReference`, `resourceVersion`, `idempotencyKey`,
and `resultState`. `state=COMPLETED` means the command completed; `resultState` records
the task outcome (`OPEN`, `IN_PROGRESS`, `WAITING`, `COMPLETED`, `ARCHIVED`, `DELETED`).
No execution identity or running-task count is synthesized. The actor-qualified source
event, current owner, source reference, resource version, command key, result, audit
identity and audit correlation remain attached to the historical fact.

Bound receipt semantic fields, audit rows, timeline rows, events and binding rows are
protected against later mutation. `request_fingerprint` is intentionally excluded from
the receipt guard for V228 compatibility canonicalization. Functions use
`SET search_path FROM CURRENT` for Flyway custom-schema installations. V229 is already
applied locally with checksum `1899611165`; do not edit an applied migration.

The Activity detail shows command evidence and opens the verified Personal Work route.
Browser back restores the source-button focus once only when the same event, location,
available access and route still match. Revoked access, a changed route or another
event cannot restore that focus. The Work owner subsequently added a Personal Work
origin button with exact `source=PERSONAL_TASK` filtering and return-focus handling.
That implementation and its additional 1280/390/320 browser cases belong to the Work
follow-up task; they are not edits or additional E2E passes claimed by this Activity task.
The Work owner's final v5 report confirms all six Personal Work origin/return cases
(1280/390/320 across Chromium and mobile) passed at source snapshot
`4cdc185b3d2e0040720a45a21691878a2c48ef6a6f5fa9e1bf1f359fea8f68f2`.
The Activity frontend closeout links that raw report separately from its own 14 E2E passes.

### Local demo replay

From `dwp-backend`, start Platform with the standard local service/runtime tokens and
`DWP_ACTIVITY_LOCAL_FIXTURES_ENABLED=true`, `DWP_OPENAPI_ENABLED=true`, then run:

```sh
DWP_ACTIVITY_SEED_PROFILE=local-joonbin \
DWP_PLATFORM_SERVICE_TOKEN=dwp-local-platform-service-token \
bash dwp-platform-server/scripts/seed-local-personal-work-activity-demo.sh
```

This loopback-only script uses real Work APIs for tenant `1`, user `900018`
(`joonbin@sk.com`). Fixed command UUIDs produce four tasks in OPEN, IN_PROGRESS,
WAITING and COMPLETED states without duplicating history or overwriting subsequent
tester edits. The four creates plus three status commands account for seven seed
events; with the pre-existing verification history the local account has 20 personal
task receipts, 20 bindings and 20 events. Two further replays on 2026-09-08 preserved
those counts. These Personal Work facts have `dataProvenance=LIVE`; the separate
opt-in `SAMPLE` fixture rule below still applies only to sample data.

Local recovery and final verification evidence is recorded in
`output/activity-closeout-2026-09-08/` under the parent DWP workspace. Production
validation and deployment were explicitly excluded from this closeout.

## HTTP contracts

- `GET /v1/workspace/activity`: compatible `events` and `generatedAt`, with additive
  `nextCursor`, `hasMore`, `snapshotAt`, `startCursor`, and explicit source coverage.
- `GET /v1/workspace/activity/events/{UUID}`: independent authorized detail, including
  events older than any loaded list page.
- `GET /v1/workspace/activity/executions/summary`: full accessible current execution
  counts; not a count of page events. Workspace coverage excludes legacy and samples.

`SAMPLE` is not part of the normal contract. The explicit local fixture is visible in
list/detail/evidence only when the server starts with
`DWP_ACTIVITY_LOCAL_FIXTURES_ENABLED=true` and the exact user plus reserved
correlation/source markers match. Cursor scope includes that flag. Execution summary
always evaluates with fixture visibility disabled, so a local sample never changes a KPI.

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

## Additive evidence and source observations (2026-09-07)

The following are read-only Platform observations, not new execution owners or command APIs:

| GET route (service path)                                | Required scope                                                                                                                     | Result                                                        |
| ------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| `/v1/workspace/activity/events/{id}/evidence`           | `APP.ACTIVITY:VIEW` and the same current native source ACL as event detail                                                         | Native event audit linkage and authorized audit receipt       |
| `/v1/workspace/activity/audit/evidence/{auditRecordId}` | `APP.ACTIVITY:VIEW`, `APP.ASK:VIEW`, exact tenant and current actor, `dwp-agent-runtime` source and UUID-shaped `AGENT_RUN` target | Central audit receipt for the viewer's own Agent run          |
| `/v1/workspace/activity/sources/status`                 | `APP.ACTIVITY:VIEW` plus `APP.MAIL:VIEW` / `APP.CALENDAR:VIEW` for each emitted resource                                           | Only the current user's connector/subject/stream observations |

The gateway adds `/api/platform` to these service paths. Gateway service identity and
positive tenant/user identity are required; Activity rejects provider identity planes,
provider roles and support sessions. These paths extend the existing native Activity
permission boundary. They do not promote or rewrite a product-authorization bundle.
The Platform security filter applies `Cache-Control: private, no-store, max-age=0` to
every `/v1/workspace/activity/**` response, including authentication, authorization and
application errors.

Evidence separates `linkStatus=LINKED|NOT_LINKED` from
`integrityStatus=VERIFIED|FAILED|PENDING|UNAVAILABLE`. The original tenant-qualified FK
proves linkage only. `ADMIN.AUDIT_VIEW:VIEW` is additionally required for `recordHash`,
`hashAlgorithm` and `verifiedAt`; without it, the receipt consistently uses
`auditAccess=RESTRICTED`, null audit details and `integrityStatus=UNAVAILABLE`.
Unavailable, removed, cross-tenant or unreadable native sources are indistinguishable
404 responses. Agent central records not yet ingested also return 404; clients may
refresh, but must not invent a verified record.

`integrityScope=DAILY_CHECKPOINT_REPORTED` means the audit owner's stored daily
checkpoint status, not an independent re-verification or a legal compliance claim.
A central event must have been ingested before checkpoint creation and fall inside its
recorded event-time range. A link, a hash alone, a response time, or an absent checkpoint
cannot be promoted to `VERIFIED`; a linked receipt without a checkpoint is `PENDING`.
No new signing key or competing audit ledger is introduced.

The legacy-compatible `auditStatus` on an activity event is a linkage presentation
field, not the checkpoint integrity verdict. Clients must use the evidence receipt's
`integrityStatus` for that verdict.

Source status exposes only a connector label/key, permitted resource kind, effective
status, and last-attempt/last-success timestamps. Effective status uses lifecycle,
policy, subject consent, connector health and stream state in that order, so
`BLOCKED`/`REVIEW_REQUIRED` policy cannot be hidden by a healthy or ready stream.
Credentials, token data, provider identifiers and raw errors are not selected.
`observedAt` is the time this source ledger was read, not an external synchronization
timestamp. An empty list is unconnected or unreadable coverage, never a 100% health
claim. Agent stage history, progress, attempt, lease and execution titles remain owned
by `dwp_agent` and are not copied into Platform.

### Explicit local fixture

`dwp-platform-server/scripts/seed-local-activity-demo.sql` is opt-in and intentionally
not an automatically deployed Flyway migration. The operator must first confirm the
local IAM identity `joonbin@sk.com` is user `900018` in the default tenant. The script
also checks its existing Calendar public-person binding. Run the file in one transaction
after `SET LOCAL dwp.activity.seed_profile = 'local-joonbin'`.

It creates three independent `[개발 검증]` native work items, their creation audit
records and completed `SAMPLE` change facts, plus two clearly labelled personal
connector fixtures and three streams. Their public effective states are primary Mail
`READY`, primary Calendar `STALE`, and attention Mail `BLOCKED`. Stable IDs, null-safe
identity collision checks and insert-only conflict handling preserve subsequent user
edits, timestamps and existing records. Any work, audit, event, connector, subject or
stream identity collision fails the transaction. A reserved connector, subject or
stream carrying an existing credential, provider binding or sync cursor also fails the
whole transaction instead of reusing it as a fixture.
No passwords, identity grants, refresh tokens, credential references or client IDs are
installed. Connector observations with the reserved `activity-local-joonbin-` key use
`semantics=LOCAL_FIXTURE`; these seeded timestamps are not evidence that Microsoft Graph
or any external system synchronized.

## Verification evidence and operating boundary

The latest targeted regression on 2026-09-07 passed **72/72** tests with no failure,
error or skip: the independent QA suite of 71 tests plus one PostgreSQL regression for
a credential-bearing reserved connector collision. The isolated PostgreSQL coverage
includes default-OFF fixture visibility, list/detail/evidence authorization, LIVE-only
summary, cursor flag binding, policy precedence, identity/credential collision rollback,
nullable marker regression and no-store error responses. The evidence and JUnit/log
artifacts are stored in
`output/activity-design-2026-09-07/independent-evidence-review/` at the workspace root.

This verification does not claim production rollout or operational validation. At the
user's request, production tenant sampling, external provider synchronization,
deployment, permanent monitoring and operating recovery exercises are deferred to one
later coordinated release.
Real connector observations remain `PERSONAL_SYNC_LEDGER`.

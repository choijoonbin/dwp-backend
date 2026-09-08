# Meeting record disposition: design and verification

## Scope and operational prohibition

The missing `meetingRecords` worker is implemented in the Meeting owner service. This is not
an external infrastructure placeholder. The worker is **disabled by default**; V38 authorizes
no existing records. No actual customer record, production database, environment setting,
provider object, or other product was modified/deleted during this work. PostgreSQL testing
uses newly provisioned Testcontainers databases only.

Operational activation and individual approvals are separate decisions, not part of this
implementation task. Existing tenant `retention_days` is used unchanged; no legal period is
invented or shortened. Cancelled records use `updated_at`, ended records use `ended_at`, plus
the current tenant retention days. Every approval snapshots the meeting version, policy
version and calculated deadline. A later change invalidates that approval. Cancellation's
updated-at anchor is conservative: later edits extend the period and require reapproval.

## API for the administration UI

- `GET /v1/admin/record-retention/meetings/{meetingId}` requires `ADMIN.MEETINGS:VIEW`.
- `PUT` at the same path requires `ADMIN.MEETINGS:MANAGE` and `Idempotency-Key`.
- PUT has exactly five fields: `expectedMeetingVersion`, `expectedPolicyVersion`,
  `expectedControlVersion`, `hold`, `purgeAuthorized`. Booleans must be JSON booleans,
  versions non-negative JSON integers. Unknown fields/coercion are rejected.
- `hold=true` and `purgeAuthorized=true` together are invalid. Setting a hold revokes
  approval. Removing the record hold does **not** remove a report's independent legal hold.
  This is a **record-parent deletion pause**, not a cross-artifact legal hold: existing
  chat/report/recording/transcript retention workers continue their own policies. UI must
  disclose this limit and must not label this switch as comprehensive legal preservation.
  The separate full legal-hold workflow readiness remains NOT_VERIFIED.
- Response: `meetingId`, `meetingVersion`, `policyVersion`, `controlVersion`,
  `retentionUntil`, `hold`, `purgeAuthorized`, `state`, `reasons`,
  `authorizationAuditPublished`, `workerEnabled`, `purgedAt`.
- States: `UNCONFIGURED`, `HELD`, `AWAITING_AUTHORIZATION`, `BLOCKED`, `ELIGIBLE`, `PURGED`.
  `UNCONFIGURED` must not hide `reasons`. Approval does not immediately delete anything.
- Initial `controlVersion=0`; accepted commands increment it. GET of a purged ID and
  replay of the exact original command return the independently retained current tombstone.
  New commands against a purged record are rejected. Current authorization is checked again
  even for replay. A replay never restores an older approval over a newer hold.
- Provider support identities, support mode/session and actor-tenant header paths are
  denied by the real Meeting SecurityFilter; ordinary application VIEW is not admin VIEW.
- No user title, participant, transcript, locator, or content is returned by these APIs.

The public OpenAPI/Gateway snapshots were intentionally not generated here. Central sync
must add these two operations after this owner reaches its safe point.

## Tables and transaction boundary

V38 adds three independent tables with no FK back to `vm_meetings`:

1. `vm_meeting_record_dispositions`: record-scoped approval/hold, CAS control version,
   meeting/policy/deadline snapshot, authorization audit ID, purge tombstone. It survives
   parent deletion. Last-evaluated ordering prevents one blocked record from starving others.
2. `vm_meeting_record_deletion_evidence`: one immutable receipt per tenant/meeting,
   approval/purge audit IDs, snapshot versions, deadline, worker fence and metadata manifest.
3. `vm_meeting_record_retention_health`: durable last success/failure, active worker lease,
   fence, and unresolved authorized backlog signal.

The existing `vm_meeting_workspace_commands` keeps actor/tenant/type/idempotency key,
request digest and result ID/version, not command source content. Control change + audit
outbox insert + command receipt commit atomically. Record-scoped advisory locking and CAS
serialize concurrent controls, including the initially absent row.

Worker claim commits first. Purge transaction then locks the active health lease, the
record scope, control, meeting/policy and report/artifact rows. It revalidates preconditions
after locks and checks the fence against **database `clock_timestamp()`**, including after
waits and at terminal completion. Old/stale workers cannot purge or mark success/failure
after another worker reclaims. There is no external HTTP while these locks are held.

Metadata manifest + canonical purge audit + exact tenant/meeting child removal + parent
DELETE + tombstone + successful worker heartbeat commit together. Any audit, SQL/FK or
lease error rolls back all of them. Failure recording is a separate fenced transaction.

## Fail-closed preconditions

| Reason | Required condition / verification |
| --- | --- |
| `RECORD_NOT_TERMINAL` | Only ENDED/CANCELLED; authorization itself rejects future/live records. |
| `MEDIA_NOT_CLOSED` | Media access is INACTIVE or ENDED. |
| `PROVIDER_ROOM_DELETION_UNPROVEN` | If a provider room existed, provider-authoritative closed timestamp exists. |
| `RECORD_RETENTION_NOT_EXPIRED` | Current DB time is at/after the unchanged tenant-derived deadline. |
| `RECORD_LEGAL_HOLD` | No record hold; holds never implicitly expire. |
| `AUTHORIZATION_SNAPSHOT_CHANGED` | Meeting/policy versions and deadline still equal approval snapshot. |
| `REPORT_LEGAL_HOLD` | No independent report legal hold. This API never releases that hold. |
| `REPORT_DELETION_UNPROVEN` | Every report has expired, is DELETED, has null ciphertext/hash and matching existing deletion evidence. |
| `EXTERNAL_ARTIFACT_DELETION_UNPROVEN` | Materialized artifacts need governed SUCCEEDED recording/transcript deletion command with bound artifact ID/provider/receipt, expired retention and removed locator. Unknown/failed/unavailable objects are not assumed absent. A NONE placeholder with no bytes/hash/locator is never-materialized only. |
| `CHAT_DELETION_UNPROVEN` | All chat deadlines expired, plaintext removed, and message-scoped retention evidence exists. |
| `CHILD_RETENTION_NOT_EXPIRED` | Preparation material and facilitation state/question/poll deadlines all expired. |
| `CONTENT_PROCESSING_ACTIVE_OR_UNRESOLVED` | No unfinished/failed media provisioning/cleanup, recording/provider/deletion commands, active provider connection, running AI request/run, or pending/failed invitation. Failed external lifecycle commands need their existing recovery path; this worker never guesses success. |
| `AUTHORIZATION_AUDIT_NOT_PUBLISHED` | Exact bound authorization audit still exists and is PUBLISHED with timestamp. Missing/pruned outbox evidence requires fresh approval, not an inferred publication. |
| `RELATED_AUDIT_NOT_PUBLISHED` | Relevant existing canonical audit outbox events must be published, not pending/failed/dead. |

`MEETING_RECORD_RETENTION_WORKER_NOT_READY` replaces the unconditional missing-worker
readiness reason. Readiness is false before a successful poll, on stale (>3 poll delays)
heartbeat, failed poll, expired active lease, or unresolved approved backlog. A healthy
unexpired active lease does not flap readiness. Worker health is not an assertion that
external provider/KMS/AI operations or legal policy approval have been certified.

## Retained evidence and exact deletion ownership

The manifest is an explicit SQL metadata whitelist, never `row_to_json`: recording and
transcript artifact/command IDs, opaque provider deletion receipt/code/time; report
deletion IDs/times; meeting event ID/type/time. It copies no title, name, email, raw chat,
transcript, encrypted report, source-content hash, storage locator, token or request body.

Existing report-deletion rows have a non-cascade FK and are archived before exact removal.
Recording/transcript commands have circular artifact references; both disappear in the
same valid owner cascade only after their receipts are preserved. Personal-room session
mapping and facilitation poll votes need explicit scoped removal before parent deletion.
The already-expired facilitation aggregate is then removed before participant cascade:
its question/poll author FKs are deeper NO ACTION references and cannot rely on root
cascade ordering. This ordering is covered with populated polls/options/votes.
The personal room itself and other sessions remain. Owner-only provider event locators and
transcript access windows (without parent FK) are removed by exact tenant/meeting match.

Independent chat/facilitation deletion evidence, workspace command receipts, workload
replay security receipts, all canonical audit outbox records, tenant policy, personal room,
template, people directory and series objects remain. Other products' Work/Messaging/Drive
records are never deleted; their Meeting link becomes inaccessible through normal owner
lookup. Preparation deletion removes only the Meeting-owned reference, never its original
document. V38 does not change/drop any existing FK or enable a broad purge primitive.

## Verification commands

From `dwp-backend`:

```sh
./gradlew :dwp-meeting-server:test --tests '*MeetingRecordRetention*Test' --tests '*VideoMeetingAdminIntelligenceReadinessServiceTest' --console=plain
./gradlew :dwp-meeting-server:check --console=plain
python3 scripts/check-source-size.py
python3 scripts/check-test-source-size.py
git diff --check
```

Final full-module check: **95 suites, 576 tests, 0 failures, 0 errors, 1 skip**, BUILD
SUCCESSFUL in 4m24s. The skip is the existing opt-in external LiveKit operational smoke,
not a retention regression. New retention tests: **26 PostgreSQL + 5 real
SecurityFilter/controller PostgreSQL + 2 default-worker wiring = 33 PASS, 0 skip**.
The existing readiness unit (4) and production-constructor wiring (1) also pass.
Source-size 1453, test-size 696, dependency graph 10 exact legacy SCCs / 0 constructor
cycles, service boundaries and diff check pass without baseline increases. Test fixture
seed/reset SQL is limited to each disposable Testcontainers PostgreSQL instance.

Activation requires an operations-approved tenant retention/hold interpretation, enabled
worker deployment, healthy audit delivery, and an explicitly approved disposable canary
record whose external media/report/chat deletion receipts already exist. This task does
not authorize production activation or customer record purging.

### Interrupted-work resumption audit

On 2026-09-07 the resumed independent review found the V38 implementation and its 33
retention tests already present; no production rewrite was needed. An initial cached
Gradle check was not counted as fresh evidence. The forced rerun exposed a stopped local
Docker daemon (one context initialization failure and PostgreSQL skips). The official
`docker desktop start` command restored Docker Desktop 29.7.2; no containers or volumes
were manually deleted and no retention deployment setting was enabled.

The subsequent fresh command
`./gradlew :dwp-meeting-server:check --rerun-tasks --console=plain` completed successfully
in **13m29s: 95 suites, 576 tests, 0 failures, 0 errors, 1 existing external LiveKit skip**.
The retention XML reports contain **26 real PostgreSQL, 5 SecurityFilter/controller
PostgreSQL and 2 wiring tests, all passing with zero skips**. Production/test source-size,
service-boundary, dependency-cycle and scoped diff checks also passed. The independently
reviewed boundaries include the current lease after waits, atomic deletion evidence and
audit, explicit record-scoped authorization, current tenant/permission checks on replay,
and the limited record-parent meaning of the hold control.

This is internal implementation evidence, not an operational go-live approval. Docker
Desktop is left running for the remaining local verification work; the destructive
Meeting record-retention worker remains disabled by default.

## Exact changed-file inventory (this retention task only)

Paths relative to `/Users/a10697/Work/DWP/dwp-backend`:

```text
NEW dwp-meeting-server/src/main/resources/db/migration/V38__govern_expired_meeting_record_disposition.sql
NEW dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/api/MeetingRecordRetentionController.java
NEW dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/api/MeetingRecordRetentionDtos.java
NEW dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/audit/MeetingRecordRetentionAuditRecorder.java
NEW dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/MeetingRecordDispositionRepository.java
NEW dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/MeetingRecordPurgeRepository.java
NEW dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/MeetingRecordRetentionControlService.java
NEW dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/MeetingRecordRetentionGuard.java
NEW dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/MeetingRecordRetentionHealthRepository.java
NEW dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/MeetingRecordRetentionProperties.java
NEW dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/MeetingRecordRetentionService.java
NEW dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/MeetingRecordRetentionTransactions.java
NEW dwp-meeting-server/src/test/java/com/dwp/services/meeting/videomeeting/domain/MeetingRecordRetentionPostgresTest.java
NEW dwp-meeting-server/src/test/java/com/dwp/services/meeting/videomeeting/domain/MeetingRecordRetentionHttpPostgresTest.java
NEW dwp-meeting-server/src/test/java/com/dwp/services/meeting/videomeeting/domain/MeetingRecordRetentionWiringTest.java
NEW docs/workspace/meeting-record-retention-2026-09-07.md
MOD dwp-meeting-server/src/main/java/com/dwp/services/meeting/security/MeetingSecurityFilter.java
MOD dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingAdminIntelligenceReadinessService.java
MOD dwp-meeting-server/src/test/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingAdminIntelligenceReadinessServiceTest.java
MOD dwp-meeting-server/src/test/java/com/dwp/services/meeting/videomeeting/domain/MeetingRuntimeWiringTest.java
```

No generated OpenAPI, common Gateway, other product, configuration or production data
edits; no commit. The prior V37 bookmark work remains separate and unchanged by V38.

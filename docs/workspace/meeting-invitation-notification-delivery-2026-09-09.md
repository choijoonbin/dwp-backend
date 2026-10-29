# Meeting invitation in-app notification delivery

Status: repository fail-closed safeguards complete; production activation blocked pending external
evidence and final-attempt reconciliation tooling

Last reviewed: 2026-10-29

## Boundary

Meetings owns the business event and the current recipient decision. Notification owns the
registered type, template, entitlement and policy admission, inbox materialization, user
preferences, and delivery UI. The integration uses Notification's authenticated internal direct
intent endpoint because the existing Meeting invitation outbox predates the shared domain-event
envelope. It remains a durable handoff: Meeting commits a payload-free event with the business
change, then a leased worker sends one deterministic intent per current recipient.

This implementation proves **durable in-app notification materialization only**. It does not send
email, create or update an external calendar event, call a mobile push provider, prove that a user
read the notification, or provide an external provider delivery/read receipt.

## Decision table

| Meeting fact | Notification source event | Type key | Current recipient rule |
| --- | --- | --- | --- |
| Scheduled | `meetings.meeting.scheduled.v1` | `MEETINGS.INVITATION_CREATED` | Active internal participants |
| Rescheduled | `meetings.meeting.rescheduled.v1` | `MEETINGS.INVITATION_RESCHEDULED` | Active internal participants |
| Cancelled | `meetings.meeting.cancelled.v1` | `MEETINGS.INVITATION_CANCELLED` | Active internal participants |
| Preparation material added | `meetings.meeting.preparation-material-added.v1` | `MEETINGS.PREPARATION_MATERIAL_ADDED` | Active internal participants |
| Preparation material removed | `meetings.meeting.preparation-material-removed.v1` | `MEETINGS.PREPARATION_MATERIAL_REMOVED` | Active internal participants |

The roster is resolved again inside every claim and completion transaction. A recipient must have
a non-null user ID, an `ACTIVE` People snapshot, a non-guest participant role, and an attendance
state other than `DENIED`. Email-only participants, guests, inactive users, and denied users are
excluded. Meeting content, email addresses, join codes, provider tokens, and rendered text never
enter the delivery ledger or the direct request. The only template variable is the meeting UUID.

Every recipient uses a stable UUID derived from delivery contract version, tenant, Meeting outbox
event, and recipient user ID. A response loss therefore replays the exact same request. The thread
key is also recipient-bound. Notification authenticates the call with the exact source
`dwp-meeting-server`, a dedicated 32..512-character token, tenant header, and the owner binding
`dwp-meeting-server=meetings`.

## State and retry contract

The existing public states remain `PENDING`, `DELIVERED`, `FAILED`, and `CANCELLED`.

- A worker claims an event and one recipient with independent random fences and bounded leases.
- Events for the same meeting are serialized by invitation revision. A later event cannot pass a
  `PENDING` predecessor, including while that predecessor waits for a retry or lease recovery.
- Network and Notification 5xx failures retry the same identity after a bounded delay, up to the
  configured maximum attempt count. The deadline covers headers and the complete bounded response
  body and remains shorter than the delivery lease. HTTP 4xx, redirects, invalid media types,
  oversized bodies, schema drift, and contract mismatches fail closed without repeated traffic.
- A 200/201 response is accepted only when the strict success envelope contains one materialized
  recipient and a non-null Notification ID. Policy suppression, entitlement denial, admission
  suppression, and stale/no-projection responses with recipient count zero are not delivery
  success.
- The worker continues the remaining current-recipient fanout after one recipient reaches a
  terminal failure. The parent event becomes `DELIVERED` only after every currently eligible
  recipient row has a persisted Notification intent ID and visible Notification ID receipt. Once
  no current recipient remains pending, any terminal recipient failure makes the parent `FAILED`.
- If a claimed recipient loses eligibility while the HTTP request is in flight, Meeting records
  the owner's already-created receipt as `SUPERSEDED`. That row is excluded from current delivery
  success and is rearmed with the same deterministic identity if the user becomes eligible again.
- If Notification accepted an intent but Meeting failed before storing the receipt, the lease
  expires and the next worker replays the stable source event ID while an ordinary attempt remains.
  Notification returns the same materialization, after which Meeting can store the receipt without
  duplicating the inbox item. If the unknown outcome occurred on the final configured attempt, the
  expired row becomes `RETRY_EXHAUSTED` instead of exceeding the bound. No supported Notification
  receipt lookup or Meeting receipt-import/re-arm command currently exists for that terminal state;
  the row remains `FAILED` and production activation stays blocked rather than inviting a manual
  ledger rewrite.

## Runtime configuration

`dwp.meeting.invitation-delivery.enabled` defaults to `false`. Enabling it requires an HTTPS origin,
or explicitly approved loopback HTTP for local development, a dedicated trimmed token of at least
32 and at most 512 visible non-comma ASCII characters, bounded connect/request timeouts, a bounded
response size, valid batch/attempt limits, health staleness longer than both the poll delay and one
bounded request, and a lease longer than the request timeout. Redirect following is disabled.

The Meeting module rejects `enabled=true` when `dwp.environment` or `DWP_ENVIRONMENT` is
`prod`/`production`, even if the transport settings are syntactically valid. There is intentionally
no boolean or receipt-string bypass. Removing this code gate requires a reviewed change that cites
real external evidence; setting environment variables alone cannot claim production readiness.

`scripts/devctl.py up meeting` resolves the supported local profile to Auth, People, Provider,
Notification, and Meeting. It waits for those four dependencies before starting Meeting. Auth,
People, and Provider supply the gateway's fail-closed product-surface authority path used by a
fresh Meeting browser session; Notification owns invitation delivery. The launcher points the
delivery client to the local Notification service and derives the Notification producer-token
binding from the same dedicated Meeting client token. This prevents the supported Meeting profile
from depending on stale processes from another profile or running an enabled dispatcher against an
absent Notification process. Notification also receives the exact producer allowlist and
producer-to-app binding. Production remains disabled until deployment secrets, service routing,
capacity, alerting and recovery exercises are proven externally and the cross-service terminal
reconciliation tooling is implemented.

## Health and readiness scope

`meetingInvitationDeliveryReadiness` is included in the Actuator readiness group. It never emits
the endpoint or token and it does not probe the Notification mutation route.

| State | Meaning |
| --- | --- |
| `DISABLED_EXTERNAL_GATES` | Production remains safely disabled because external evidence is incomplete. |
| `DISABLED` | The capability is disabled outside production. Meeting itself may remain ready. |
| `PRODUCTION_ACTIVATION_BLOCKED` | Defensive state for an enabled production configuration; normal startup rejects it first. |
| `STARTING` | A non-production enabled worker has local progress but has not completed its first poll. |
| `READY_NON_PRODUCTION` | Configuration, gateway construction, a completed poll, and recent local scheduler/DB progress exist. This is not production approval. |
| `LOCAL_FAILURE` / `POLL_STALE` | Claim/receipt persistence failed locally or local poll progress stopped. Readiness fails closed. |
| `INVALID_CONFIGURATION` | Dispatch bounds are invalid or the strict gateway is unavailable. |

Every state reports `remoteRouteVerification=NOT_CLAIMED`. An `UP` component therefore means only
the scope named `CONFIGURATION_AND_LOCAL_SCHEDULER_ONLY`; it never proves production DNS, TLS,
Notification owner binding, capacity, alerting, or recovery exercises.

## Production activation runbook

The following is the current source of truth. Empty or verbal assurances do not satisfy a gate.

| Gate | Current state | Required evidence before a future activation change |
| --- | --- | --- |
| Default-off and production code guard | Complete | Targeted configuration tests in this repository |
| Fail-closed URL, token, timeout, response, redirect and schema validation | Complete | Gateway contract tests in this repository |
| Durable recipient fence, deterministic identity, bounded attempts and ordinary-attempt replay | Complete | Worker and PostgreSQL recovery tests in this repository |
| Dedicated production secret and rotation | **Blocked externally** | Secret-manager reference; 32..512-character, non-placeholder secret with at least eight distinct characters; proof it differs from peer and all other producer secrets; owner, rotation procedure and completed rotation evidence |
| Notification owner binding and HTTPS routing | **Blocked externally** | Deployed producer allowlist/app binding plus DNS, TLS and network-policy verification |
| Capacity | **Blocked externally** | Approved load result covering peak recipient fanout and Notification admission limits |
| Alerting and on-call | **Blocked externally** | Dashboard and alert rules for stale polls, backlog age, exhausted attempts and Notification 5xx, with routed owner |
| Recovery exercise | **Blocked externally** | Dated exercise covering Notification outage, lost response and Meeting receipt-persistence failure |
| Final-attempt unknown-outcome reconciliation | **Blocked cross-service** | Notification owner lookup by `sourceEventId` plus an audited, fence-preserving Meeting receipt-import or re-arm command and end-to-end tests |

Until every external and cross-service row has reviewable evidence, keep
`DWP_MEETING_INVITATION_DELIVERY_ENABLED=false`; the production guard must remain in place. A future
activation change must first attach those evidence references, remove the hard guard in a separate
review, deploy with the flag still false, validate the health component, and only then run a bounded
canary approved by both Meeting and Notification owners.

Rollback begins by restoring the flag to `false` and restarting Meeting. Do not delete or rewrite
the outbox or recipient ledger: pending leases and deterministic source event IDs are the recovery
record. There is currently no supported re-drive for an event whose final attempt expired with an
unknown owner outcome. Keep that row `FAILED`, open a cross-service incident, and do not import a
receipt or re-arm it by direct SQL. Recovery can become executable only after the owner lookup and
audited Meeting command in the gate table are implemented and exercised with the same source event
ID.

## Evidence

- `GovernedHttpMeetingInvitationNotificationGatewayTest` fixes the request schema, five exact
  event/type mappings, headers, HTTPS/loopback policy, whole-response deadline and size bounds,
  visible-materialization requirement, and 4xx/5xx retry classification.
- `MeetingInvitationDeliveryPostgresTest` applies all Meeting migrations to PostgreSQL and covers
  current-recipient filtering, all-recipient completion, lost-response identity reuse, lease
  fencing, bounded retry, continued fanout after terminal recipient failure, roster change, and
  payload-free storage.
- `MeetingInvitationDeliveryWorkerTest` covers receipt persistence, retry classification, batch
  bounds, local-health reporting, and the ordinary-attempt crash-after-acceptance behavior.
- Notification migration and contract tests cover the five active type versions, ten published
  Korean/English in-app templates, canonical preparation action, entitlement, security allowlist,
  producer ownership binding, normalized global policy uniqueness, reuse of a pre-provisioned
  canonical policy, and transactional rejection of conflicting policy/channel definitions.
- `scripts.tests.test_devctl` verifies the Meeting profile dependencies and start phase, exact local
  token equality, and service-scoped environment exposure.
- `MeetingInvitationDeliveryConfigurationTest` fixes the default-off YAML contract, production
  activation block, strict setting bounds, and readiness membership without exposing secrets.
- `MeetingInvitationDeliveryReadinessTest` fixes disabled, starting, stale, local-failure and
  recovered non-production health states while keeping remote verification explicitly unclaimed.

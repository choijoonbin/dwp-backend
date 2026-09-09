# Meeting invitation in-app notification delivery

Status: implemented behind a disabled-by-default production flag

Last reviewed: 2026-09-09

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
`dwp-meeting-server`, a dedicated 24-plus-character token, tenant header, and the owner binding
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
  expires and the next worker replays the stable source event ID. Notification returns the same
  materialization, after which Meeting can store the receipt without duplicating the inbox item.

## Runtime configuration

`dwp.meeting.invitation-delivery.enabled` defaults to `false`. Enabling it requires an HTTPS origin,
or explicitly approved loopback HTTP for local development, a dedicated trimmed token of at least
24 characters, bounded connect/request timeouts, a bounded response size, valid batch/attempt
limits, and a lease longer than the request timeout. Redirect following is disabled.

`scripts/devctl.py up meeting` resolves the supported local profile to Auth, Notification, and
Meeting. It waits for Auth and Notification readiness before starting Meeting, points the delivery
client to the local Notification service, and derives the Notification producer-token binding from
the same dedicated Meeting client token. This prevents the supported Meeting profile from running
an enabled dispatcher against an absent Notification process. Notification also receives the exact
producer allowlist and producer-to-app binding. Production remains disabled until deployment
secrets, service routing, capacity, alerting, and recovery exercises are configured outside this
repository.

## Evidence

- `GovernedHttpMeetingInvitationNotificationGatewayTest` fixes the request schema, five exact
  event/type mappings, headers, HTTPS/loopback policy, whole-response deadline and size bounds,
  visible-materialization requirement, and 4xx/5xx retry classification.
- `MeetingInvitationDeliveryPostgresTest` applies all Meeting migrations to PostgreSQL and covers
  current-recipient filtering, all-recipient completion, lost-response identity reuse, lease
  fencing, bounded retry, continued fanout after terminal recipient failure, roster change, and
  payload-free storage.
- `MeetingInvitationDeliveryWorkerTest` covers receipt persistence, retry classification, batch
  bounds, and the crash-after-acceptance recovery behavior.
- Notification migration and contract tests cover the five active type versions, ten published
  Korean/English in-app templates, canonical preparation action, entitlement, security allowlist,
  producer ownership binding, normalized global policy uniqueness, reuse of a pre-provisioned
  canonical policy, and transactional rejection of conflicting policy/channel definitions.
- `scripts.tests.test_devctl` verifies the Meeting profile dependencies and start phase, exact local
  token equality, and service-scoped environment exposure.

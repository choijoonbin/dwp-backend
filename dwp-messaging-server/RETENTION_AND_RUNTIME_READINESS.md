# Messaging retention and runtime readiness

This document defines the production contract implemented by migrations `V18` through `V24` and the Messaging
runtime. It deliberately separates “configured” from “verified effective” so an environment cannot
claim production readiness merely because a provider name or credential was supplied.

## Retention semantics

- A user delete is a **soft delete**. `deleted_at` hides the body through the existing product
  contract, while the governed message row remains.
- A retention expiry is a **content purge**. The worker sets `retention_purged_at`, empties the body,
  removes sender snapshot data, reactions, mentions, saved-item links, read observations, realtime
  replay payloads, idempotency results, attachment metadata, and short-lived download grants.
- The message identifier, conversation identifier, sequence, sender user identifier, timestamps,
  and version remain. This is intentional structural evidence needed for sequence, reply, and audit
  integrity; it is not a retained copy of message content.
- Search uses the canonical message body, so a purged empty body is no longer searchable.
- An expired server draft is deleted only when neither a tenant-wide nor conversation Legal Hold
  applies. A terminal (`SENT`, `FAILED`, or `CANCELLED`) scheduled command keeps its identifiers,
  state, timestamps, idempotency keys, and delivery evidence, but `V22` empties the body and
  mentions, removes failure detail, replaces the content-derived request hash with a deterministic
  scheduled-message tombstone digest, and records `payload_purged_at`. Reusing that historical
  idempotency key with a new request therefore remains a conflict and cannot recreate purged content.

## Fail-closed execution states

| State | Mutation allowed | Meaning |
|---|---:|---|
| `DISABLED` | No | Global kill switch is off (`enabled=false`, the default). |
| `DRY_RUN` | No | Candidate and hold counts are recorded, but no message or object is changed. |
| `PAUSED` | No | An operator pause is active. |
| `BLOCKED` | No | Enforcement was requested without all safety authorities. |
| `ENFORCING` | Yes | Legal Hold authority and downstream lifecycle readiness are both explicit. |

`ENFORCING` requires both `hold-authority-ready=true` and
`downstream-lifecycle-ready=true`. A missing Legal Hold authority therefore cannot silently turn a
tenant policy's `retentionDays` value into destructive execution. The downstream gate covers
notification/search/export consumers that can retain derived message content outside this database.

The worker uses a database lease per tenant, renews it while running, processes bounded batches, and
rechecks Legal Hold under a table-share lock in the same transaction that purges each message. A
tenant-wide active hold (`conversation_id IS NULL`) and a conversation hold are both supported.
Messages and continuity payloads have independent bounded batch budgets, and drafts and terminal
scheduled payloads each receive a fair per-batch budget so one backlog cannot starve another.

## Attachment deletion protocol

Provider object deletion is not transactionally atomic with PostgreSQL. The worker therefore uses a
retryable deletion journal:

1. In the message purge transaction, copy opaque attachment object keys to
   `msg_retention_attachment_deletions`.
2. Delete grants and attachment metadata, then redact the message content.
3. After commit, delete provider objects and mark each journal entry complete.
4. On provider failure, retain a payload-free error code and retry on a later enforcing run.

The original provider key exists only while deletion is pending; completion replaces it with the
journal identifier so finished evidence does not retain a usable object locator.

An unresolved object-deletion backlog blocks new content purges for that tenant and makes the run
`FAILED`. This prevents a storage outage from creating an unbounded hidden-object backlog.

## Limits, evidence, and telemetry

The defaults cap one run at 100 rows per message/continuity batch, 10 batches per workload, and 1,000 provider-object deletion
attempts. Every run stores candidate, purged, held, and pending-object counts in
`msg_retention_runs` and adds a payload-free `msg_audit_events` record. No message body, filename, or
object key is emitted as metric or audit evidence.

Metrics:

- `dwp.messaging.retention.messages.purged`
- `dwp.messaging.retention.continuity.payloads.purged`
- `dwp.messaging.retention.runs.blocked`
- `dwp.messaging.retention.runs.failed`
- `dwp.messaging.retention.attachments.deletion.failed`
- `dwp.messaging.retention.run.duration`
- `dwp.messaging.scheduled.delivered`
- `dwp.messaging.scheduled.retried`
- `dwp.messaging.scheduled.failed`
- `dwp.messaging.scheduled.queue.pending`
- `dwp.messaging.scheduled.queue.overdue`
- `dwp.messaging.scheduled.failed.recent`

Health groups:

- `/actuator/health/retention`
- `/actuator/health/presence`
- `/actuator/health/messaging-operations`
- `/actuator/health/messaging-providers`

Retention health is not promoted to `UP` from configuration alone. In `ENFORCING` mode it also
requires every tenant to have a current successful enforcing run, no latest failed/blocked run, and
no pending attachment-deletion backlog. Missing, stale, or unreadable evidence fails closed.

Scheduled delivery writes a payload-free heartbeat for every worker cycle, including an empty
queue. Its health becomes `OUT_OF_SERVICE` for a missing/stale heartbeat, a failed latest cycle, or
an overdue queue beyond the configured threshold. Admin queue/failure counts are tenant-scoped;
actuator health and gauges intentionally use the global shared-worker aggregate.

`GET /v1/scheduled-messages` uses a stable `(created_at, scheduled_message_id)` keyset and returns
`hasMore` plus an opaque `nextCursor`. A cursor is bound to the requesting tenant, user, and status
filter and is rejected if replayed in a different scope. Each item includes `conversationName` only
while the requesting user still has active access to that conversation; otherwise it is `null`
rather than exposing a stale conversation snapshot.

## Admin runtime-readiness contract

`GET /v1/admin/overview` retains its existing fields and adds:

```json
{
  "runtimeReadiness": {
    "evaluatedAt": "2026-10-28T10:00:00Z",
    "overallState": "BLOCKED",
    "capabilities": [
      {
        "capability": "RETENTION_ENFORCEMENT",
        "provider": "database-worker",
        "required": true,
        "configured": true,
        "effective": false,
        "state": "BLOCKED",
        "reasonCode": "LEGAL_HOLD_AUTHORITY_UNAVAILABLE",
        "lastVerifiedAt": "2026-10-28T09:00:00Z",
        "freshness": "CURRENT",
        "pendingWorkCount": 0
      }
    ]
  }
}
```

The capability list covers attachment storage, attachment scanner, meeting, search, presence,
realtime fan-out, durable commit, SSE resume, notification outbox, retention enforcement,
scheduled delivery, and observability. `freshness` is `CURRENT`, `STALE`, or `NEVER`.
Required capabilities in `BLOCKED`, `UNAVAILABLE`, or `DISABLED` make the overall state `BLOCKED`;
a required development/fallback provider makes it `DEGRADED`.

Provider verification is evidence-based:

- S3 uses `HeadBucket`; local filesystem storage can be effective but is always `DEGRADED` and
  development-only.
- ClamAV uses `PING`; the local signature scanner is effective but development-only.
- Redis presence uses the existing authoritative `PING` health check and never falls back to seeded
  directory availability.
- Durable commit and SSE resume use a tenant-scoped PostgreSQL `msg_realtime_events` probe. A failed
  probe blocks both capabilities; configuration alone cannot make the replay path ready.
- Realtime fan-out is an optional latency accelerator because SSE heartbeat catch-up uses the
  durable event log as the authority. It becomes `READY` only after a nonce published on a dedicated
  readiness channel is received by the configured Redis subscriber. Redis `PING` or publish success
  without an acknowledgement is insufficient evidence.
- Notification outbox readiness is tenant-scoped to `event_source = 'urn:dwp:messaging'`. It checks
  the actual domain-event relay lifecycle, DEAD rows, retry state, oldest active work, and backlog
  threshold. `PUBLISHED` proves broker acknowledgement only; it does not prove final notification
  materialization or end-user delivery.
- Meeting provider configuration is reported separately from connectivity; without a provider
  connectivity probe it remains `DEGRADED`, not falsely `READY`.
- PostgreSQL search fallback is effective but `DEGRADED` until a production search provider exists.
- OpenTelemetry configuration is not treated as exporter delivery evidence.

Provider probes are cached and become stale after three minutes by default, avoiding external calls
inside the admin request transaction.

The notification outbox maximum active age defaults to five minutes and the warning threshold to
1,000 rows. They are configurable through
`DWP_MESSAGING_NOTIFICATION_OUTBOX_MAXIMUM_AGE` and
`DWP_MESSAGING_NOTIFICATION_OUTBOX_WARNING_THRESHOLD`. The dedicated Redis round-trip timeout is
bounded to 100 ms–5 s and defaults to one second through
`DWP_MESSAGING_REDIS_READINESS_PROBE_TIMEOUT`.

## Safe rollout

1. Deploy migration and code with retention disabled.
2. Verify S3, ClamAV, Redis, observability, and downstream notification/search lifecycle handling.
3. Establish the compliance-owned Legal Hold creation/release authority and its operational audit.
4. Enable `DRY_RUN`, review candidate/hold counts for at least one full retention interval, and
   confirm `/v1/admin/overview` freshness.
5. Set both safety readiness flags only after evidence review, then change the mode to `ENFORCING`.
6. Use `DWP_MESSAGING_RETENTION_PAUSED=true` for a reversible operator pause or
   `DWP_MESSAGING_RETENTION_ENABLED=false` as the global kill switch.

The backend contract is additive. The frontend admin surface should render `runtimeReadiness` and
must not infer `READY` from provider configuration alone. Legal Hold administration is intentionally
not exposed by this change; until that authority exists and is explicitly certified, enforcement
remains blocked.

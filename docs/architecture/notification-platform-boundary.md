# Notification Platform Backend Boundary

Status: `in-app-pilot-and-tenant-governance-implemented`; production release gates remain open

Last reviewed: 2026-08-27

Canonical product and solution decision:
[`R1 DWP Notification Platform 및 Omnichannel Delivery ADR`](../../../dwp-frontend/docs/03-architecture/R1%20DWP%20Notification%20Platform%20및%20Omnichannel%20Delivery%20ADR.md)

Feature acceptance package:
[`DWP-R1-CORE-005-notification-platform`](../../../dwp-frontend/docs/05-features/DWP-R1-CORE-005-notification-platform/README.md)

## Current Baseline

The repository now has an independent `dwp-notification-server`, a dedicated
`dwp_notification` database, direct-recipient inbox and preference APIs, tenant administration
queries, Redis live hints and a durable SSE catch-up endpoint. The frontend header, notification
center and canonical `/notifications/settings` surface use these APIs rather than static rows.
Messaging emits approved facts through its transactional outbox. The notification service
materializes only registered contracts, enforces effective policy, time-bounded suppressions and
per-user/type admission limits, then records durable audit and delivery-outbox evidence.

Tenant policy and template changes use immutable drafts with separate author and approver duties.
Notification operators can preview, activate and revoke bounded tenant/app/type/channel
suppressions without receiving policy or template publication rights. Audit evidence is projected
to the central audit control plane rather than a private notification audit menu.

This is an implemented **in-app foundation pilot**, not a claim that external omnichannel delivery
is production-ready. Email, Web/Mobile Push, Teams, Slack, large audience fan-out, provider
callbacks and production HA/load/DR evidence remain disabled release-gate work.

The 2026-08-21 runtime audit also verified a real two-account messaging path from the source
transaction through the service outbox, Kafka and the notification inbox. It corrected two
production-path defects: Spring selecting the disabled notification-event constructor and the
outbox lease recovery query binding `Instant` without a PostgreSQL timestamp type. Regression
tests now cover both boundaries. The browser query path also reports the messaging source as
`Messenger`, and coalesced thread updates are deduplicated by change version rather than being
silenced forever by notification ID.

The same audit closed the remaining in-app data and delivery races. Recipient-visible actor,
body, action, target and occurrence data are now immutable user-projection snapshots rather than
reads from a shared logical thread. Authenticated producers are additionally bound to their owned
application contracts, and a producer-supplied reason code can no longer impersonate a published
mandatory policy. Each materialization intent owns a distinct delivery-outbox identity. The
shared domain-event relay now fences completion and failure with both worker identity and a unique
lease token, so an expired worker cannot overwrite a reclaimed event.

Migration V18 also redacts content copied into legacy recipient projections before per-recipient
snapshot isolation existed. It preserves notification identity and triage state while removing
shared actor, body, target and action values that cannot be attributed safely to one recipient.
The recipient table remains protected by enabled and forced RLS.

Migration V19 persists the pre-mutation state required for exact bulk undo. Partial bulk failures
retry only failed recipients, while undo restores only successful mutations without discarding
independent read, saved, completed or snoozed state. Migration V20 records notification target
lifecycle and exposes a source-owned target resolver. The browser revalidates a target immediately
before navigation and renders a bounded 410 result for deleted or revoked resources.

Migration V21 closes a runtime least-privilege gap found through the actual preference UI. The API
role could insert central audit outbox rows but PostgreSQL also required conflict-key visibility for
`ON CONFLICT (event_id)`. The role now receives column-level `SELECT(event_id)` only, without audit
payload visibility. Browser autosave and published audit evidence were reverified after restart.

SSE clients are registered before durable catch-up begins. Live hints arriving during catch-up are
buffered, catch-up advances in bounded pages, and reconnects resume from the greatest persisted
decimal change cursor without JavaScript precision loss. Realtime envelopes distinguish newly
materialized arrivals from user triage and system reconciliation, so read, save, snooze, complete
and retention changes refresh views without producing false new-arrival banners. If a reconnect
cursor is older than the retained watermark, the API returns 409 and the browser clears the stale
cursor across tabs before reconnecting from the current summary and first page.

The browser coordination protocol is versioned independently from the server stream. Version 2
separates its Web Lock and BroadcastChannel namespace from older tabs, reports a connection as live
when the authenticated HTTP stream is accepted, and relays explicit live/polling state to follower
tabs. This prevents a stale leader from an earlier deployed protocol from suppressing the current
runtime. The notification service also disables JPA Open EntityManager in View. Long-lived SSE
responses therefore do not retain JDBC connections after their bounded catch-up query completes.
The local regression held live browser streams while ordinary inbox queries continued and verified
that notification runtime database sessions remained idle rather than exhausting the pool.

The reusable implementation baseline is:

- the CloudEvents-aligned envelope and service-local delivery ledger in
  [`domain-event-delivery-ledger.md`](domain-event-delivery-ledger.md);
- transactional outbox, idempotent inbox, aggregate ordering, dead-letter and replay support in
  `dwp-core`;
- the contract-gated `dwp.domain-events.v1` Kafka topic;
- existing gateway session, CSRF, tenant context, audit and observability policies;
- PostgreSQL and Redis development infrastructure.

Producer onboarding is still contract-gated. A service does not become a notification producer
merely because it writes an audit record or has a local application event.
The required decision table, ownership split, Messaging reference implementation and release gate
are defined in [`notification-producer-onboarding.md`](notification-producer-onboarding.md).

The 2026-08-27 runtime follow-up found that browser reconnects could leave stale SSE emitters until
timeout and exhaust the per-user connection quota. Streams now carry a stable browser client ID;
a reconnect from the same client atomically supersedes its previous emitter before capacity is
evaluated. A genuine capacity rejection returns a content-free `429` with `Retry-After`, so an SSE
`Accept` header cannot turn it into a media-type serialization failure.

## Implemented Module

```text
dwp-notification-server
  api
    inbox
    preference
    admin
    stream
  application
    contract
    materialization
    policy
    template
    delivery
    reconciliation
  domain
    notification-type
    notification
    user-inbox
    delivery-profile
    delivery-job
  infrastructure
    kafka
    persistence
    qos-dispatch
    provider-callback
    redis-hint
    sse
    channel-adapters
```

The service owns the `dwp_notification` database and exposes public endpoints only through the
gateway. It must not be embedded into `dwp-platform-server`, because cross-domain fan-out,
realtime connections, retention and delivery workers have a distinct scale and failure boundary.

The initial implementation keeps the repository's Spring MVC and JDBC/JPA conventions. SSE uses
asynchronous `SseEmitter` connections and Java 21 runtime capabilities; request threads must not
block for the lifetime of a connection. A separate reactive realtime deployment is introduced
only when the approved concurrent-connection load test shows that the shared service cannot keep
the required headroom. This avoids adopting a second persistence stack before there is evidence.

The codebase is one bounded-context module but production processes are independently scalable:
API/SSE, materializer/fan-out, due-job scheduler, and channel workers for critical, interactive,
and bulk lanes. Bulk traffic cannot consume the reserved critical worker capacity.

## Runtime Boundaries

| Boundary               | Contract                                                                                     |
| ---------------------- | -------------------------------------------------------------------------------------------- |
| Producer               | Emits an approved business fact through `DomainEventRecorder`; no direct provider call       |
| Domain event transport | Existing Kafka topic and `dwp-core` outbox/inbox semantics                                   |
| Materializer           | Validates type/schema and atomically creates intent, inbox projection and delivery outbox    |
| User query             | PostgreSQL keyset query scoped by session tenant and user                                    |
| Durable live sync      | Transactional per-user `change_version`; Redis and SSE are hints, not cursor authorities     |
| Live update            | Redis carries a content-free hint; SSE client catches up from a durable cursor               |
| Delivery scheduling    | PostgreSQL Job is authoritative; due scheduler applies tenant fairness and rate limits       |
| Channel delivery       | QoS Kafka trigger, Job-version CAS, channel dedupe, durable attempts and `UNKNOWN` outcome   |
| Provider feedback      | Signed callback ingress, idempotent receipt, `UNKNOWN` reconciliation and suppression        |
| Audience resolution    | Direct IDs first; organization and role require a People snapshot target-population contract |
| Audit                  | Existing audit delivery contract; no private duplicate audit store                           |
| Reference codes        | Existing governed code catalog plus startup drift validation                                 |

## Build Sequence Status

1. [x] Add `dwp-notification-server`, dedicated database, separated DB roles, RLS and health checks.
2. [x] Add a gateway route and explicit service-interface policy entry.
3. [x] Create foundation catalog, intent, direct-recipient inbox and preference migrations.
4. [x] Reuse `dwp-core` event semantics and register the approval pilot consumer contract.
5. [x] Implement keyset inbox, counter·change version, REST mutation and SSE catch-up.
6. [x] Implement tenant policy and user preference composition for the enabled in-app capability.
7. [x] Implement the in-app delivery path before external channels.
8. [ ] The tenant-keyset outbox relay, leases, bounded retries, dead state and tenant-scoped
       maintenance workers are implemented. A narrow cross-tenant due-job scheduler, fair scheduling
       and independently scalable critical·interactive·bulk dispatch workers remain production work.
9. [ ] Add the People internal target-population snapshot contract, then enable resumable
       organization·role fan-out behind a feature flag.
10. [ ] Tenant operational suppression and in-app admission receipts are implemented. Email and
        push adapters, verified-contact resolution, signed callback receipts, destination
        bounce/complaint suppression and `UNKNOWN` reconciliation remain disabled-by-default work.
11. [x] Onboard the approval pilot producer types through schema review.
12. [ ] Run production contract, tenant isolation, QoS, load, retry, callback, DLQ, replay and
        disaster-recovery gates.

## Startup and Configuration Invariants

- Enabling event consumption without Kafka and a registered consumer contract fails startup.
- Enabling Redis realtime without durable PostgreSQL catch-up support fails startup.
- Starting with a runtime DB role that owns tables, is superuser, or has `BYPASSRLS` fails the
  production security self-check.
- Scheduler credentials can read and lease scheduling metadata only. Provider credentials can use
  redacted views and audited commands only; either role reaching user content fails readiness.
- Enabling an external channel without provider credentials, sender policy, timeout, rate limit,
  idempotency and redaction configuration fails startup.
- Enabling email without a verified contact resolver, SPF·DKIM·DMARC evidence, signed feedback
  callback and bounce·complaint suppression fails readiness.
- Enabling organization·role fan-out against the public People directory instead of the approved
  snapshot target-population contract fails startup.
- Production refuses a development Kafka profile with one broker and replication factor one.
- Production requires separate critical, interactive, and bulk worker capacity; a single unlimited
  delivery consumer profile fails readiness.
- Topic auto-creation remains disabled.
- A missing future partition or retention job raises health degradation before inserts fail.
- Service readiness is false while contract/code catalog drift or migration validation fails.
- SSE routes require buffering disabled, heartbeat/read-timeout compatibility, connection limits,
  and drain verification in the deployed ingress profile.
- JPA Open EntityManager in View remains disabled; an SSE connection retaining a JDBC transaction
  or pool lease after catch-up fails readiness.
- Tenant region and cell placement are mandatory; a worker cannot process a payload outside its
  assigned regional cell.

## Observability

Required metrics include event materialization latency, consumer lag, quarantine count, recipient
fan-out rate, inbox query latency, counter drift, SSE active connections and reconnects, queued job
age, QoS lane headroom, delivery result by channel/provider, retry amplification, DLQ count, digest
delay, unknown outcome age, callback signature failures, bounce·complaint suppression, endpoint
staleness, tenant fairness and region/cell placement violations.

Traces preserve `traceparent`, source event ID, correlation ID, intent ID, notification ID and
delivery job ID. User-visible title, body, template variables, endpoint tokens and provider secrets
must not appear in logs, metrics or trace attributes.

## Role and Setting Boundaries

| Persona               | Implemented authority                                                                              | Explicitly excluded                      |
| --------------------- | -------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| User                  | Own inbox state, presentation privacy, channel profile, quiet hours, digest and app/type overrides | Other users, managed mandatory policy    |
| Policy author         | Effective policy read, impact preview and immutable draft                                          | Self-approval, delivery commands         |
| Policy approver       | Independent policy publish                                                                         | Authoring the same revision              |
| Template editor       | Locale preview and immutable template draft                                                        | Publishing own revision                  |
| Template approver     | Independent template publish                                                                       | Editing the same revision                |
| Notification operator | Tenant operations, suppression preview/create/revoke                                               | Policy/template publish, user content    |
| Central auditor       | Correlated `notification.*` evidence through the central audit API                                 | Delivery mutation                        |
| Provider operator     | Target boundary is redacted fleet health, global capability and quota safety                       | Tenant header switching and user content |

Provider fleet APIs, external adapter configuration and production kill-switch commands remain
release-gated. They must be implemented in the Provider Control Plane, not added to the tenant
notification routes or granted implicitly to `TENANT_ADMIN`.

## Release Gate

Foundation implementation is covered by migration, repository, security, cursor, realtime,
translation, API and browser evidence. Decisions `D-NTF-01` through `D-NTF-09` remain explicit
external production gates. External adapters and large-audience processing cannot be reported as
connected before those gates are resolved and evidenced; capability discovery must continue to
return them as unavailable.

The 2026-08-24 local authorization regression additionally verifies that a `WORKSPACE_MEMBER` can
read only their own notification inbox and receives 403 from tenant notification administration.

The complete backend `check`, source-size gate, frontend 86-file/375-test suite and production build
now pass. Actual browser verification covered every user and tenant-operator notification route,
reversible read/save mutations and preference autosave. Notification-center Playwright also passes
in Chromium and iPhone 13 with no serious or critical Axe finding in `main`. Internal deterministic
work for triage combinations, bulk retry/undo, target 410, counter reconciliation, Redis/SSE
catch-up, preference conflict recovery, policy-runtime parity and tenant isolation is closed.

Remaining release work depends on explicit authority or production evidence: the Auth entitlement
contract for permission revocation, the tenant holiday-calendar source, governed replay approval
semantics, Provider support-session content visibility, People target-population snapshots,
external provider credentials and callbacks, and the full browser, mobile, accessibility, load,
security and disaster-recovery matrix. These items remain tracked in the feature acceptance package
and must not be represented as implemented before their owners and environments are approved.

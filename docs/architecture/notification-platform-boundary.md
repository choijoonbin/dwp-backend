# Notification Platform Backend Boundary

Status: `build-ready`; final candidate review complete, implementation not started

Last reviewed: 2026-08-19

Canonical product and solution decision:
[`R1 DWP Notification Platform 및 Omnichannel Delivery ADR`](../../../dwp-frontend/docs/03-architecture/R1%20DWP%20Notification%20Platform%20및%20Omnichannel%20Delivery%20ADR.md)

Feature acceptance package:
[`DWP-R1-CORE-005-notification-platform`](../../../dwp-frontend/docs/05-features/DWP-R1-CORE-005-notification-platform/README.md)

## Current Baseline

The backend does not currently have a notification service, notification database, user inbox,
preference policy engine, delivery worker, or realtime endpoint. The frontend header notification
menu is a static prototype and must not be represented as an operational capability.

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

## Planned Module

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

## Required Build Sequence

1. Add `dwp-notification-server` Gradle module, dedicated database, separated DB roles, RLS and
   health checks.
2. Add a gateway route and explicit service-interface policy entry.
3. Create catalog, policy, template, intent, direct-recipient inbox, preference and delivery
   migrations in the new service only.
4. Reuse `dwp-core` event ledger and register notification consumer contracts.
5. Implement keyset inbox, transactional counter·change version, REST sync/mutation and SSE
   catch-up.
6. Implement tenant policy and user preference composition.
7. Implement the in-app delivery adapter before external channels.
8. Add a narrow cross-tenant scheduler DB role, fair due scheduler, critical·interactive·bulk
   dispatch lanes and tenant-scoped workers.
9. Add the People internal target-population snapshot contract, then enable resumable
   organization·role fan-out behind a feature flag.
10. Add email and push adapters, verified-contact resolution, callback receipts, suppression and
    `UNKNOWN` reconciliation behind disabled-by-default configuration.
11. Onboard no more than three pilot producer event types through schema review.
12. Run contract, RLS, tenant isolation, QoS, load, retry, callback, DLQ, replay and
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

## Implementation Gate

The source module, migrations, API contract and infrastructure changes must not be started as an
unreviewed bulk feature. The product ADR and feature package require architecture, privacy,
security, SRE and design approval. Decisions `D-NTF-01` through `D-NTF-09` remain explicit external
release gates; adapters can be scaffolded but cannot be reported as connected before those gates
are resolved and evidenced.

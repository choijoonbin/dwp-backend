# Domain Event Delivery Ledger

Status: Accepted; Kafka transport implemented, producer onboarding remains contract-gated

Last verified: 2026-08-13

Scope: shared domain-event envelope, contract validation, transactional outbox, idempotent
inbox, aggregate ordering, retry, dead-letter, and replay evidence in `dwp-core`.

## Decision

DWP services use a CloudEvents-aligned envelope and a service-local delivery ledger. A
producer appends an event in the same database transaction as its aggregate mutation. A
transport adapter publishes leased outbox rows. A consumer validates the registered schema
range and atomically combines handler work with inbox completion.

The shared runtime keeps a transport-neutral publisher boundary. Kafka is the approved first
adapter and uses the versioned `dwp.domain-events.v1` topic with tenant, source, aggregate type,
and aggregate ID as the partition key. Setting `dwp.events.transport-enabled=true` without
`dwp.events.transport=kafka` and a configured broker fails startup instead of reporting a false
integration success.

## Envelope And Contract

Every event requires:

- CloudEvents spec version `1.0`, UUID, source, namespaced type, schema version, and time.
- Tenant when applicable, aggregate type, aggregate ID, and strictly positive aggregate
  sequence.
- Correlation ID and optional causation ID and valid W3C `traceparent`.
- JSON data and a SHA-256 payload hash persisted in the delivery ledger.

`DomainEventContractRegistry` rejects unknown event types and unsupported schema versions.
Typed delivery states are also registered in the platform code-contract catalog by
[`V56`](../../dwp-platform-server/src/main/resources/db/migration/V56__register_domain_event_typed_contracts.sql).

## Delivery Invariants

- A reused event ID with a different payload hash is quarantined.
- Producer event ID and source/aggregate/sequence are unique.
- Outbox rows are leased in bounded batches; batch failure is isolated per event.
- Consumer identity plus event ID forms the idempotency key.
- Per-consumer aggregate offsets accept only the next sequence, identify duplicates, and
  defer out-of-order events.
- Handler work and inbox completion share a transaction; retry and dead-letter transitions
  are recorded in independent durable transactions.
- Retry attempts are bounded and errors stored in the ledger are truncated/redacted by the
  calling adapter contract.
- Replay requests have an append-only operator evidence table rather than silently changing
  delivery state.

## Shared Persistence

The repeatable migration
[`R__create_domain_event_delivery_ledger.sql`](../../dwp-core/src/main/resources/db/migration/R__create_domain_event_delivery_ledger.sql)
is included in each database-backed service through core auto-configuration and creates:

| Object | Responsibility |
| --- | --- |
| `sys_domain_event_outbox` | Transactional producer ledger, leases, retry, and dead-letter state |
| `sys_domain_event_inbox` | Consumer dedupe, leases, retry, quarantine, and payload evidence |
| `sys_domain_event_offsets` | Last applied sequence per consumer and aggregate |
| `sys_domain_event_replay_audit` | Controlled replay request evidence |
| `sys_domain_event_dead_letters` | Unified read model for outbox and inbox dead letters |

These service-local tables are infrastructure objects. They are not a cross-database foreign
key model and do not weaken tenant isolation in domain tables.

## Activation Boundary

The internal ledger, envelope, strict registry, relay, consumer factory, dedupe, ordering,
retry, dead-letter, and replay persistence contracts are implemented. Product services do
not yet emit synthetic events merely to claim integration coverage.

Decision `D-07` is resolved for the transport foundation: Kafka, aggregate-stable partitioning,
AsyncAPI schema ownership, idempotent producer settings, and outbox acknowledgement semantics.
Each real producer and consumer still requires a versioned schema registration, retention and
data-classification review, replay/DLQ owner, and failure drill. Until a service completes that
onboarding, `dwp.events.transport-enabled` remains `false` for that service.

The local broker and versioned topic can be provisioned with
`docker compose --profile events up -d kafka kafka-init`. Topic auto-creation stays disabled so
an incorrect topic name cannot silently become a new integration contract.

## Verification

Executable coverage includes
[`DomainEventContractRegistryTest`](../../dwp-core/src/test/java/com/dwp/core/event/DomainEventContractRegistryTest.java),
[`DomainEventOrderingPolicyTest`](../../dwp-core/src/test/java/com/dwp/core/event/DomainEventOrderingPolicyTest.java),
[`IdempotentDomainEventConsumerTest`](../../dwp-core/src/test/java/com/dwp/core/event/IdempotentDomainEventConsumerTest.java),
and [`DomainEventOutboxRelayTest`](../../dwp-core/src/test/java/com/dwp/core/event/DomainEventOutboxRelayTest.java).

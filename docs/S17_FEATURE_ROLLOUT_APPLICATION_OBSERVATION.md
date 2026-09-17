# S17 feature rollout application observation

The Provider settings read model reports application state from explicit runtime receipts. It
does not treat rollout evaluation audit rows, outbox publication, or a successful Provider read as
proof that a consumer applied a decision.

The initial receipt producer is the Gateway product-surface authority path. After an authoritative
decision is accepted into the replica-local `FeatureRolloutDecisionCache`, the Gateway sends an
`APPLIED` receipt through the service-authenticated internal endpoint. Receipts are best-effort:
an unavailable receipt endpoint never changes an access decision. A cache hit sends no duplicate
receipt; the next cache miss refreshes the observation.

The logical target ID is `gateway.sampled-authority-request-path`. `CONVERGED` for this target means
that at least one request path accepted the currently published revision within the freshness
window. It does not mean that every Gateway replica has applied the revision. Fleet-wide
convergence requires a deployment inventory with stable instance identities and expected-replica
discovery; those sources do not exist in the current runtime, so the read model does not claim that
coverage.

Provider persists every accepted receipt in an immutable ledger and updates a tenant-, flag-, and
target-scoped latest projection. Future revisions, unsupported flags, cross-evidence receipt ID
reuse, malformed failure evidence, and untrusted callers are rejected. The S17 evaluator derives
`PUBLISHED_UNOBSERVED`, `CONVERGED`, `DRIFTED`, `FAILED`, and `OBSERVATION_STALE` only from this
projection.

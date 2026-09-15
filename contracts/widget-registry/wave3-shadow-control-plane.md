# Wave 3 Widget Registry shadow control plane

Wave 3 installs the authoritative Widget Registry **control plane** while leaving Home rendering on the existing static registry. `plt_widget_registry_state` is seeded as `migrationMode=SHADOW` and `runtimeActivationReady=false`; the database constraint rejects `AUTHORITATIVE` or runtime activation in this release. Wave 4 owns brokered provider reads and actions.

## Authority and storage

Flyway V256 adds Definition, immutable Definition Version, native Renderer Binding, Release Channel, Evidence, tenant Policy revision/head, instance reference, runtime kill control and approval, append-only registry event, command receipt, and readiness state tables. Published manifest content, evidence, events, and receipts are protected by database immutability constraints. All mutable heads use optimistic versions. Every command also requires an idempotency UUID and is serialized by a transaction-scoped PostgreSQL advisory lock before receipt lookup.

Only `kind=NATIVE` bindings can be stored. Manifest validation binds definition key, owning product, source app resource, renderer key, host API range, and privacy classification to an active allowlisted binding. Renderer URLs, remote JavaScript, and provider data access are absent from the contract.

Provider control-plane traffic enters through these browser-facing gateway namespaces and is proxied to Platform `/v1/admin/...` endpoints:

- `/api/provider/v1/admin/widget-definitions/**`
- `/api/provider/v1/admin/widget-definition-versions/**`
- `/api/provider/v1/admin/widget-runtime-controls/**`
- `/api/provider/v1/admin/widget-registry/**`

The gateway removes a caller-supplied `X-DWP-Control-Plane` header and applies its trusted provider-route marker after identity verification. Platform accepts the provider endpoints only when that marker, the verified `PROVIDER` identity plane, a provider role, and the exact operation permission all agree. Tenant catalog and policy operations remain under `/api/platform/v1/admin/widget-catalog/**` and `/api/platform/v1/admin/widget-policies/**`. Member evaluation is `/api/platform/v1/widget-catalog/effective`.

## Lifecycle rules

A new Version follows `DRAFT → VALIDATED → SUBMITTED → APPROVED`. Rejection follows `SUBMITTED → REJECTED → DRAFT`. Approval binds the current validation run and all six current evidence types: Manifest, Security, Privacy, Accessibility, Performance, and Localization. Author and reviewer differ. A high-risk publish also requires a publisher distinct from both author and approver.

Publish requires `APPROVED`, `PASS`, `CLEAR`, an active native binding, current unexpired evidence, exact manifest and validation IDs, a fresh impact revision, and optimistic version match. Published manifest content becomes immutable. Release safety transitions are fail closed:

- `PUBLISHED/CLEAR → BLOCKED/CLEAR`
- `PUBLISHED|DEPRECATED|BLOCKED + CLEAR → BLOCKED/QUARANTINED`
- any published, deprecated, blocked, or quarantined version → `BLOCKED/REVOKED`
- revoke is irreversible
- deprecation requires another eligible published, certified, clear replacement and a bounded future end date
- channel promotion and rollback require another eligible published, certified, clear immutable version plus exact channel head and impact revision

Tenant policies are immutable revisions behind an optimistic head. A draft validates its release selector, canonical `AudienceSelectorV1`, supported surfaces, and closed configuration object. Publish, revoke, and rollback create or select new history without deleting configuration or instances. Missing policy denies. Runtime controls can disable catalog mutation, discovery, rendering, or actions at global, provider, tenant, definition, or version scope. Re-enable requires a separate, expiring, single-use approval tied to the current control revision.

## Effective catalog boundary

Effective responses expose only these states: `AVAILABLE`, `ALREADY_ADDED`, `DEPRECATED`, and `DENY`. Public reasons are limited by enum to `NOT_AVAILABLE`, `DISABLED_BY_ORGANIZATION`, `APP_ACCESS_REQUIRED`, `INCOMPATIBLE`, `TEMPORARILY_UNAVAILABLE`, `DEPRECATED`, `AVAILABLE`, and `ALREADY_ADDED`. Internal policy, binding, certification, and incident causes never serialize to the member response.

The evaluator uses gateway-verified role, group, and permission headers. It checks the canonical audience selector and every manifest-required application authority per item. A missing/retired policy, unresolved version, inactive binding, unsupported surface/host context, active kill control, block, quarantine, or revocation yields a public deny. An uncertified version also denies, except for the seven explicitly attested `LEGACY_UNVERIFIED` baseline versions while the registry is locked in SHADOW mode. That narrow exception keeps migrated and newly provisioned tenants comparable to the static catalog without claiming certification. Every SHADOW item has `canAdd=false`, and the decision is never injected into existing Home execution.

## Seven native seeds

[`native-widget-manifests.v1.json`](native-widget-manifests.v1.json) is the shared backend/frontend machine-readable contract. Its canonical semantic SHA-256 is `1b71e410d39359573e692d2cd07cb5101c4a07663dd52ce4217e3e2f5d0f4c2b` (`jq -S -c`). Five manifests are exact ADM-009 golden fixtures: command rail, daily brief, focus, schedule, and activity. Focus balance and meeting load are explicit first-party extensions from the Wave 1 catalog. V257 and post-migration tenant provisioning seed all seven idempotently.

The seven legacy rows are `APPROVED/PUBLISHED` only to compare static and shadow catalogs. They carry `attestation.source=LEGACY_UNVERIFIED`, `certificationStatus=NOT_RUN`, and Manifest evidence only. The SHADOW effective catalog may describe these rows as discoverable `AVAILABLE`, but always with placement writes disabled. They are never described as certified or executable. Each must complete the standard six-evidence lifecycle before a later authoritative rollout.

## Deployment and rollback

V256 and V257 are additive and safe while old binaries run because no existing Home table or runtime path is replaced. Deploy the Wave 3 binary, verify readiness reports SHADOW and runtime false, then compare static and shadow decisions. Rolling back the binary leaves inert additive rows; roll forward to modify their schema. Do not set runtime activation through SQL: this release deliberately has no legal activation state.

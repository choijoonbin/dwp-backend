# Wave 3 Widget Registry shadow control plane

Wave 3 installs the authoritative Widget Registry **control plane** while leaving Home rendering on the existing static registry. `plt_widget_registry_state` is seeded as `migrationMode=SHADOW` and `runtimeActivationReady=false`; the database constraint rejects `AUTHORITATIVE` or runtime activation in this release. Wave 4 owns brokered provider reads and actions.

## Authority and storage

Flyway V256 adds Definition, immutable Definition Version, native Renderer Binding, Release Channel, Evidence, tenant Policy revision/head, instance reference, runtime kill control and approval, append-only registry event, command receipt, and readiness state tables. Published manifest content, evidence, events, and receipts are protected by database immutability constraints. All mutable heads use optimistic versions. Every command also requires an idempotency UUID and is serialized by a transaction-scoped PostgreSQL advisory lock before receipt lookup.

Only `kind=NATIVE` bindings can be stored. Manifest validation binds definition key, owning product, source app resource, renderer key, host API range, and privacy classification to an active allowlisted binding. Renderer URLs, remote JavaScript, and provider data access are absent from the contract.

Provider control-plane traffic enters through these browser-facing gateway namespaces and is proxied over the purpose-bound `X-DWP-Widget-Registry-Token` to the internal Platform `/internal/provider-bff/v1/widget-registry/...` namespace. Platform rewrites that namespace only after validating the dedicated secret and strips it before the existing operator, permission, and owner-scope guards run:

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
- deprecation requires another eligible published, certified, clear replacement and a bounded future end date; V259 retains the approved deadline immutably in the version, response, receipt and event, and expired or legacy missing deadlines deny discovery
- channel promotion and rollback require another eligible published, certified, clear immutable version plus exact channel head and impact revision

Tenant policies are immutable revisions behind an optimistic head. A draft validates its release selector, canonical `AudienceSelectorV1`, supported surfaces, and closed configuration object. Publish, revoke, and rollback create or select new history without deleting configuration or instances. Missing policy denies. Runtime controls can disable catalog mutation, discovery, rendering, or actions at global, provider, tenant, definition, or version scope. Re-enable requires a separate, expiring, single-use approval tied to the current control revision.

## Effective catalog boundary

Effective responses expose only these states: `AVAILABLE`, `ALREADY_ADDED`, `DEPRECATED`, and `DENY`. Public reasons are limited by enum to `NOT_AVAILABLE`, `DISABLED_BY_ORGANIZATION`, `APP_ACCESS_REQUIRED`, `INCOMPATIBLE`, `TEMPORARILY_UNAVAILABLE`, `DEPRECATED`, `AVAILABLE`, and `ALREADY_ADDED`. Internal policy, binding, certification, and incident causes never serialize to the member response.

The evaluator uses gateway-verified role, group, and permission headers. It checks the canonical audience selector and every manifest-required application authority per item. A missing/retired policy, unresolved version, inactive binding, unsupported surface/host context, active kill control, block, quarantine, or revocation yields a public deny. An uncertified version also denies, except for the seven explicitly attested `LEGACY_UNVERIFIED` baseline versions while the registry is locked in SHADOW mode. The exception requires the exact seeded definition/version IDs, definition key, owning product, semantic version, manifest hash, native renderer, binding owner/source, binding revision, and `certificationStatus=NOT_RUN`; failed or expired certification always denies. That narrow exception keeps migrated and newly provisioned tenants comparable to the static catalog without claiming certification. Every SHADOW item has `canAdd=false`, and the decision is never injected into existing Home execution.

## Seven native seeds

[`native-widget-manifests.v1.json`](native-widget-manifests.v1.json) is the shared backend/frontend machine-readable contract. Its canonical semantic SHA-256 is `3838b553235923be694a1a334bd6652abe9cb380603293dd4c6bd15337f5732e` (`jq -S -c`), its file SHA-256 is `54514ac24326ebf32d2694cc678501ad7a16887933609fe37fcb70941687ba08`, and the expected active-binding catalog revision is `656986e3056f42073ff5af2b6501d798d33ee2fabc615c8d602bd7f0edc20939`. Five manifests are exact resealed ADM-009 golden fixtures: command rail, daily brief, focus, schedule, and activity. Focus balance and meeting load are explicit first-party extensions from the Wave 1 catalog.

V257 retains the original seven immutable `1.0.0` snapshots. V260 appends corrected `1.0.1` snapshots for command rail, focus balance, and meeting load, advances their STABLE channel heads, and marks their superseded `1.0.0` snapshots `BLOCKED` with replacement links. It accepts only the exact V257 pre-state or the exact completed V260 state; collisions, partial application, and changed binding/channel ownership abort the migration. Post-migration tenant provisioning seeds the same seven channel policies idempotently. The final control plane has seven definitions, ten immutable versions, ten Manifest evidence rows, and seven STABLE channel heads.

The seven effective baseline rows are `APPROVED/PUBLISHED` only to compare static and shadow catalogs. They carry `attestation.source=LEGACY_UNVERIFIED`, `certificationStatus=NOT_RUN`, and Manifest evidence only. The SHADOW effective catalog may describe these rows as discoverable `AVAILABLE`, but always with placement writes disabled. They are never described as certified or executable. Each must complete the standard six-evidence lifecycle before a later authoritative rollout.

## Deployment and rollback

V256 through V260 preserve the existing Home rendering path. V260 changes only shadow control-plane history, binding metadata, and release heads; it does not activate registry execution. Provision the same independently generated `DWP_WIDGET_REGISTRY_PROVIDER_TOKEN` only to Provider and Platform, deploy both Wave 3 binaries, verify readiness reports SHADOW and runtime false, then compare static and shadow decisions. A missing token fails every Provider registry call closed. Rolling back the binary leaves inert additive rows; roll forward to modify their schema. Do not set runtime activation through SQL: this release deliberately has no legal activation state.

V259 adds nullable deprecation deadlines without inventing dates for existing deprecated rows. Those legacy rows remain available for review but deny discovery. New deprecation writes require a future deadline within 365 days, which cannot be removed or extended even after block, revocation, or channel rollback. Rolling back to an older binary leaves this database guard installed; its date-unaware deprecation writes fail closed. Keep registry operations disabled when rolling back to a binary without deadline-aware catalog evaluation.

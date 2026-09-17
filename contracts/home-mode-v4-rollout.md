# Home Composition v4 fleet activation contract

Home Composition v4 and mode-scoped Home Views are an expand-and-activate change. Migrations
V292 and V293 are safe to install while the legacy application is serving traffic. V293 retains
one active `CLASSIC` row and one default per legacy user/surface and marks rows written by a
mode-unaware binary. It does not create a second mode row.

## Capability signal

The member and administrator Home Experience responses may contain
`homeContractCapabilities`. While `DWP_HOME_MODE_V4_ACTIVATION_ENABLED=false` (the default), or
while the continuity transaction has not committed, the array is empty, the selected preference
store remains `LEGACY`, the composer remains disabled, the personalization API rejects requests,
Home Experience returns and creates a schema-v3 policy projection, and composition policy updates
cannot persist a v4 document.

Setting the flag requests activation. The capability array remains empty and every v4 write stays
closed until the startup coordinator commits. A failed transaction prevents application readiness
and leaves the runtime gate closed.

After a successful activation, the response advertises these exact capabilities:

```json
[
  "HOME_COMPOSITION_V4",
  "MODE_SCOPED_HOME_VIEWS",
  "THREE_INDEPENDENT_HOME_MODES",
  "FOUR_DEVICE_LAYOUTS"
]
```

Clients must treat an absent or empty capability array as the legacy contract. They may send a
`modeKey`, write a v4 composition policy, or persist four device overlays only after the matching
capability is present.

## Selection and catalog APIs

The organization policy owns two distinct axes: `allowedModes` is the set a member may select and
`defaultMode` is the initial fallback. A member's independent `currentMode` is returned by
`GET /v1/home-preferences`. The same response also returns `enabledModes`, which applies rollout and
kill-switch availability to the organization allowlist, plus `disabledModeReasons` for modes that
remain allowed by policy but cannot currently be selected.

Use `PUT /v1/home-preferences/current-mode` (or the surface-scoped equivalent) with
`{"currentMode":"MZ_V1","version":<current preference version>}` to change only this selection.
This operation neither replaces the layout nor marks it customized, and it remains available when
layout personalization is disabled. `PUT /v1/home-preferences` remains the layout mutation API.

The effective widget catalog accepts an optional mode on
`GET /v1/widget-catalog/effective?surfaceKey=workspace-home&mode=MZ_V1`. The server validates the
requested mode against both the tenant allowlist and runtime availability before resolving the
corresponding Classic, Flow, or MZ host contexts. Omitting the parameter preserves the tenant
default behavior for older clients. Existing published Flow widget manifests remain canonical;
the catalog maps their personal and governed placement contexts into the MZ host at evaluation
time, so this compatibility extension does not invent new manifest hashes.

Administrators write the complete v4 policy through
`PUT /v1/admin/home-experience/composition`. They can validate it without persistence through
`POST /v1/admin/home-experience/composition/preview`; the preview returns the canonical policy,
`enabledModes`, `effectiveDefaultMode`, and rollout or kill-switch `warnings`.

Pre-Wave1 Home View clients did not send `modeKey`. After activation, the server resolves an omitted
mode from the tenant's effective experience: Flow tenants continue in `FLOW_V1`, MZ / AI Stage
tenants continue in `MZ_V1`, and Classic tenants continue in `CLASSIC` for `workspace-home`.
Other product surfaces retain `CLASSIC`. An explicitly supplied mode is always authoritative.
The three keys own independent saved-view defaults, responsive overlays, cache and ETag identities,
rollout controls, and telemetry dimensions. `MZ_V1` is never represented as a Flow preset or theme.

## Deployment sequence

1. Keep `DWP_HOME_MODE_V4_ACTIVATION_ENABLED=false` and the existing Home View read/write rollout
   flags disabled on every Wave1 pod.
2. Deploy V292 and V293 and verify the canonical launchpad and migration checks. Legacy unscoped
   queries must still return exactly their prior row and default counts. Existing `DESKTOP` and
   `MOBILE` overlays remain unchanged and writable during this pre-activation window; the expanded
   constraint also permits canonical device classes, so mixed-fleet rows may temporarily contain
   both an alias and its canonical counterpart.
3. Deploy the Wave1 binary everywhere. Drain and terminate every binary that does not return the
   capability field. Do not set the activation flag while an old pod can still write a row using
   the database's legacy marker default.
4. Set `DWP_HOME_MODE_V4_ACTIVATION_ENABLED=true` on the fully compatible fleet and restart it. A
   transaction-scoped PostgreSQL advisory lock serializes concurrent pods. The coordinator copies
   active legacy `workspace-home` views and their revision, device, and widget state into the
   selected advanced namespace (`FLOW_V1` or `MZ_V1`) only for tenants whose persisted policy
   selects that mode. Source rows remain the independent Classic rollback projection. In the same
   transaction it first resolves device aliases: an existing
   canonical overlay wins over the matching alias, and an alias without a canonical peer is renamed
   to `DESKTOP_STANDARD` or `MOBILE_STANDARD`. It then fails actionable source proposals so cached
   commands cannot mutate Classic, clears every legacy marker, commits, and only then opens the
   runtime gate. Any failure rolls back the alias resolution as well as every partial copy. Other
   product surfaces are never cloned.
5. Enable the existing dual-write, shadow-compare and read flags, then independently enable
   `DWP_HOME_FLOW_ENABLED` and/or `DWP_HOME_MZ_ENABLED`, followed by the composer flag.
6. Confirm clients observed `MODE_SCOPED_HOME_VIEWS` before allowing mode-aware mutations.

Each compatible pod reruns the coordinator during an enabled startup. Lineage uniqueness and
mode-scoped uniqueness make it idempotent; after the first transaction clears the legacy markers,
subsequent pods acquire the advisory lock, observe no work, commit, and become ready.

## Rollback

Disable the existing Home View/Flow rollout flags first, then disable
`DWP_HOME_MODE_V4_ACTIVATION_ENABLED`. This removes the capabilities, returns reads to the preserved
legacy preference path, and blocks further v4/mode-aware writes. The additive Classic, Flow, and
MZ rows, revisions, and device overlays remain available for a compatible roll-forward.

Once activation has created multiple mode rows, a pre-Wave1 binary is not a supported rollback
target: its unscoped queries cannot distinguish Classic, Flow, and MZ. Keep the Wave1 binary with the
interlock closed, or perform an explicitly reviewed data-convergence operation before rolling
back to a pre-Wave1 binary. Never start a pre-Wave1 binary against an activated database without
that convergence.

The PostgreSQL integration test proves that V293 preserves one legacy row/default and legacy
device aliases, legacy writers remain accepted before activation, canonical rows win deterministic
alias collisions, a failed activation rolls back both alias conversion and every partial copy,
successful activation leaves only canonical device classes, repeated activation is idempotent, and
subsequent advanced-mode changes leave the other namespaces unchanged. The dedicated MZ migration,
experience, view, runtime, cache, ETag, telemetry and OpenAPI tests prove the third namespace.
`HomeExperienceServiceTest` and
`HomePersonalizationAccessTest` prove that capabilities and mode-scoped APIs stay fail-closed until
both the operator request and continuity transaction are complete.

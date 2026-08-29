# Product Authorization Contract

`product-surfaces-v1.yaml` is the only machine-authoritative source for the
CORE-006 authorization registry. The file uses the JSON-compatible profile of
YAML 1.2 so generation does not depend on a workstation-specific YAML parser.

Ownership is split deliberately:

- Identity Architecture and Security approve the bundle schema, capabilities,
  access policies, route bindings, version lineage and activation pointer.
- Each routed owner service and Auth own evidence evaluation for predicate
  descriptors whose `ownerServiceKey` names that service.
- Frontend manifests and test fixtures may reference stable contract keys, but
  must not redefine capability codes, profiles, predicates or API bindings.

Generate both checked-in artifacts:

```sh
./scripts/generate-product-authorization-contracts.py
```

CI must use:

```sh
./scripts/generate-product-authorization-contracts.py --check
```

The generator applies each descriptor enrichment in the first gate snapshot
where that descriptor exists, then preserves it in every later monotonic
superset. It expands projection bindings and descriptor-to-route reverse
indexes, validates same-bundle references and computes SHA-256 over canonical
JSON with the mutable `checksum` and `bundleStatus` members omitted. The
canonical source contains a version 1 base plus append-only version 2, 3 and 4
waves. It emits complete snapshots rather than deltas:

- `product-surfaces-v1.bundle-v1.json` — W0/Canary, checksum `bc34f47b…`
- `product-surfaces-v1.bundle-v2.json` — W1a Approvals, checksum `5b634a35…`
- `product-surfaces-v1.bundle-v3.json` — W1b HCM candidate, checksum `f90c4e3a…`
- `product-surfaces-v1.bundle-v4.json` — twelve-product exact closure, checksum `a9cd0826…`
- `product-surfaces-v1.json` — byte-identical latest/final alias of bundle v4
- `product-surfaces-v1.index.json` — checksummed version/artifact index

Auth classpath resources use the same names with `.generated.json` before the
extension. Every contract snapshot is byte-identical to its Auth seed peer;
the latest Auth alias is byte-identical to bundle v4. The generator verifies
all files, checksums, aliases, descriptor preservation and monotonic reverse
references in both generate and `--check` modes.

Bundle version 1 contains only the W0 registry, Named Reviewer and the
Communications/Services technical canary, including its exact responsibility,
wire-equivalence and query constraints. Version 2 adds the complete Approvals
descriptor closure, seven closed projection schema hashes, all four HIGH
command bindings and the Auth step-up authority endpoint. It contains zero HCM
routes, capabilities or product policies. The Approval and Platform runtime PEP
resources project version 2 only. Version 3 is the exact monotonic superset that
adds the HCM descriptors and their enrichments; no HCM runtime PEP or activation
is wired by this contract generation step. Version 4 is the exact monotonic
superset that adds the remaining product descriptors and closes at least one
`PAGE`, `DATA` and `ACTION` route for every rollout product. Generating or
loading version 4 does not approve or activate it.

The signed pilot fixture has no authoritative top-level registry reference.
Its `registryLineage` is informational only; every test case and step-up
challenge carries an exact checksummed `requiredRegistryRef` for the earliest
snapshot that contains its descriptors (Canary v1, Approvals v2, HCM v3, and a
composition-derived maximum for guard cases). All six backend fixture adapters
validate and return those case/challenge references and reject cross-gate refs.
The fixture generator cross-checks the index, every contract/Auth-seed byte pair,
canonical bundle checksums and descriptor counts before signing challenges. Each
challenge derives its owner, audience, route, paired service binding, target source
and expected-version source from exactly one registry `stepUpCommandBinding`.
Owner audiences are closed by `audienceByOwnerService`. Path targets use the exact
named template parameter; body targets concatenate the ordered UTF-8 string field
values with a single reserved `:` delimiter (currently `dataset:population`);
field values containing the delimiter or line breaks are rejected. A
`COMMAND_BODY` version must be an integral payload member equal to `targetVersion`;
a `COMMAND_HEADER` version is forbidden from the signed payload.

Platform telemetry dimensions are generated as
`platform-telemetry-dimensions-v3.generated.json`. The projection is anchored
to the W1b registry v3 and covers all 12 governed product manifests, including
the eight compatibility products whose authorization contracts are not yet
active. That closed, checksummed projection binds each allowed product to its
surfaces, each surface to exact UI route IDs, and every surface to canonical
task and scope-kind allowlists. Telemetry compatibility does not activate
authorization enforcement, and arbitrary product, surface, route, task or scope
dimensions remain fail-closed.

Product Surface activation controls are Provider-owned runtime state and are not
members of the immutable authorization bundle. For product `p`, Gateway composes
`S/E_p/U_p` from the tenant-global
`access.product-surfaces.context-shadow.v1`, the product-scoped
`access.product-surfaces.capability-enforcement.<p>.v1`, and
`ux.product-surfaces.<p>.v1`. Only `000`, `100`, `110`, and `111` are valid;
`E_p` implies `S` and `U_p` implies `E_p`. The legacy tenant-global
`access.product-surfaces.capability-enforcement.v1` remains only as immutable
rollout/audit compatibility evidence. New Gateway binaries do not read it or use
it as a master switch, and operators must not create new rollouts for it.

The v4 candidate bundle and checksummed rollout inventory cover twelve products.
X-03 treats a product contract as `EXACT` only when the bundle has at least one
`PAGE`, `DATA`, and `ACTION` route for that product. All twelve products now meet
that contract and may use the explicit pilot ceiling `111`; the missing-contract
guard still caps any future `INCOMPLETE_KINDS` or `MISSING` product at `100` with
authority not evaluated. Product-scoped activation does not weaken that fence,
alter any v1-v4 bundle byte/checksum, or turn the DRAFT v4 artifact into release
approval. `MISSING` describes an authorization contract, not a product's UI
inventory or telemetry.

The 46-case canonical negative fixture binding proves only catalog integrity:
the checksum, record count, unique fixture IDs and non-empty signed input and
expected-outcome fields. Its referenced adapter test projects fixture records;
it does not call a Gateway or owner-service PEP. X-03 release completion still
requires separately recorded automated execution evidence and owner approval.

X-03 `ownerService` identifies the service that receives the product's public
Gateway route and owns its independent policy-enforcement filter; it is not a
generic Platform fallback. The matrix checker accepts evidence only from the
routed owner's executable test boundary. DWAI.ON additionally pins an immutable
Agent revision and executable pytest attestation rather than accepting a textual
cross-repository reference. The current calculation-derived matrix has all
twelve products `EXACT`, all 60 owner-service attack-vector cells evidenced, no
product blocker, and `completionState=COMPLETE`. That local technical closure
does not replace provider approval, staging evidence, manual acceptance,
penetration testing, release approval or any other external production evidence.

Gateway persists the last approved `S/E_p` pair at
`dwp:gateway:product-surface:se-latch:v2:<tenant>:<product>`. Provider failure
restores that pair and forces `U_p=0`, so an established `111` can become `110`
but never implicitly `100`. A missing v2 latch with a legacy v1 tenant latch is
`MIGRATION_REQUIRED` and returns 503 rather than inferring per-product state;
corrupt or unavailable durable state also fails closed. A higher approved
revision with `E_p=false` is the only rollout path from `110` to `100`.

Runtime loaders reject `test.*` keys and never read test registry overrides.
The checksummed seed index imports versions 1 through 4 in order, all as `DRAFT`.
It contains no active version field or pointer. Activation is an explicit CAS
pointer transition after independent approval; loading a seed does not approve
or activate it.

## Production/shared approval, activation and rollback runbook

Feature rollout deployment is separately fenced from bundle activation. Deploy
V37 and the Provider evaluator first with every product `E_p` default-off, then
replace every old Gateway pod before enabling any `E_p`. Enable only products
whose exact bundle, owner PEP, readiness evidence, and durable v2 latch are
verified. Roll back in `U_p -> E_p -> S` order; an old Gateway binary is not a
safe rollback target until all product `E_p` values and the legacy global E are
confirmed off. None of the checked-in local rollout values is production
approval or activation evidence.

The local pilot runner is not an operations interface. It remains opt-in, checks
the exact `DWP_ENVIRONMENT=local` marker, and must stay disabled in every shared
or production environment. Production/shared changes use only the dedicated Auth
internal operations API under:

```text
/internal/auth/v1/product-authorization/operations/bundles
```

There are two independently authenticated lanes. Blank secrets fail closed with
`401`, the two configured secrets must differ, and a generic service token or a
token from the other lane is rejected.

| Lane | Exact service identity | Secret header | Server configuration |
| --- | --- | --- | --- |
| approval | `dwp-provider-server` | `X-DWP-Product-Authorization-Approval-Token` | `DWP_PRODUCT_AUTHORIZATION_PROVIDER_APPROVAL_TOKEN` |
| release preflight, activation, rollback | `dwp-platform-server` | `X-DWP-Product-Authorization-Activation-Token` | `DWP_PRODUCT_AUTHORIZATION_PLATFORM_ACTIVATION_TOKEN` |

Every call also carries the single exact
`X-DWP-Service-Identity` header. Provision the secrets from separate secret
manager entries, grant each only to its named workload, exclude both headers
from request logging, rotate them independently, and restart Auth after a
rotation. Never copy either secret into a change ticket, command history, or
application configuration committed to source control.

### 1. Preflight the immutable artifact

Run the generator check in the reviewed source revision and take `version` and
`checksum` from the checked-in bundle index. Importing through the seed loader is
permitted, but the imported row must still be `DRAFT`; the seed loader contains
no approval or active-pointer configuration.

```sh
./scripts/generate-product-authorization-contracts.py --check
```

Record the source revision, bundle key, version, checksum and change reference.
The requester and security approver must be different actor references.

### 2. Provider approval lane (no activation)

Send `POST` to
`/{bundleKey}/versions/{version}/approval` with the provider lane headers and:

```json
{
  "checksum": "<exact 64-character lowercase SHA-256>",
  "requestedBy": "<maker actor reference>",
  "approvedBy": "<independent checker actor reference>",
  "changeRef": "<approved change reference>"
}
```

Success returns the exact immutable bundle with `bundleStatus=APPROVED` and does
not create or change the active pointer. A retry is accepted only when the same
actor and change evidence already exists in the governed audit ledger; an older
local/manual approval cannot be silently adopted as production evidence.

### 3. Platform release preflight and CAS activation

Using only the platform lane, read the exact target with
`GET /{bundleKey}/versions/{version}` and the pointer with
`GET /{bundleKey}/active`. The active endpoint is pointer/CAS inspection only;
it does not prove that any target is release-eligible. Confirm target status,
checksum and approval evidence only from the exact version preflight.
The version preflight returns the immutable bundle plus its provider-governed
`approvalEvidence` (`requestedBy`, `approvedBy`, `changeRef`, `approvedAt`). A
`DRAFT`, legacy/local approval, mismatched stored approver, duplicate evidence or
bundle without exact id/key/version/checksum evidence fails closed instead of
being presented as release-ready.
If the active read returns `404`, the initial expected revision is `0`; otherwise
use the returned `activeRevision` without modification.

Send `POST` to `/{bundleKey}/versions/{version}/activation`:

```json
{
  "checksum": "<the exact approved checksum>",
  "expectedRevision": 0,
  "activatedBy": "<platform release actor, different from requestedBy and approvedBy>",
  "changeRef": "<the same approved change reference>"
}
```

Activation accepts only an `APPROVED` target with the exact single provider
governance event. `activatedBy` must differ from both `requestedBy` and
`approvedBy`, and the activation `changeRef` must equal the approval evidence.
It requires a newer version than the current active bundle and changes the
pointer with compare-and-swap. A `409`
means status, checksum, version lineage, approval evidence or pointer revision
changed: stop, repeat both reads, and reconcile the change; never increment or
guess a revision. After success, repeat both reads and verify the returned
version, checksum and resulting revision.

### 4. Atomic rollback to approved evidence

Rollback never deletes a bundle or event. Select only the immediately previous
`APPROVED` version, read and verify its exact checksum, and take the current
revision from the active preflight. Send `POST` to
`/{bundleKey}/versions/{targetVersion}/rollback`:

```json
{
  "checksum": "<exact previous approved checksum>",
  "expectedRevision": 8,
  "rolledBackBy": "<platform incident actor, different from target requestedBy and approvedBy>",
  "changeRef": "<incident or emergency change reference>",
  "reason": "<specific rollback reason of at least ten characters>"
}
```

Auth locks the bundle lineage, changes the former active bundle back to
`APPROVED`, changes the exact target to `ACTIVE`, advances the CAS pointer by one
and writes both pointer lineage and governance evidence in the same transaction.
The rollback target must also carry its exact single provider-governed approval,
and the rollback actor must differ from both its original requester and approver.
Skipping versions, rolling forward through this endpoint, stale revisions,
checksum mismatches and unapproved targets fail closed.
If the incident is resolved, the rolled-back newer bundle can be reactivated
only through the normal activation endpoint with its original governed approval
reference and the then-current CAS revision. That creates a new release-ledger
revision; it does not replace or duplicate the approval.

### 5. Audit and incident evidence

`auth_product_authorization_activation_event` is the immutable from/to pointer
lineage. `auth_product_authorization_governance_event` is the immutable
maker-checker ledger containing exact bundle identity/checksum, operation,
expected/resulting revision, requester, decision actor, change reference, reason
and caller service identity. Both ledgers prohibit update/delete. Retain the API
result and the two post-change preflight responses with the change record. Treat
any mutation without both matching ledger entries as incomplete and block the
next rollout until reconciled.

The operations security filter also publishes only a successfully verified lane
identity to API history as `actorType=SERVICE`, `actorId=<exact workload>` and
`authType=SERVICE`; rejected credentials remain unauthenticated and neither
purpose token is copied into audit attributes or responses.

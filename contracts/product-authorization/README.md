# Product Authorization Contract

`product-surfaces-v1.yaml` is the only machine-authoritative source for the
CORE-006 authorization registry. The file uses the JSON-compatible profile of
YAML 1.2 so generation does not depend on a workstation-specific YAML parser.

Ownership is split deliberately:

- Identity Architecture and Security approve the bundle schema, capabilities,
  access policies, route bindings, version lineage and activation pointer.
- Platform, Approval, People and Auth own evidence evaluation for predicate
  descriptors whose `ownerServiceKey` names their service.
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
canonical source contains a version 1 base plus append-only version 2 and
version 3 waves. It emits complete snapshots rather than deltas:

- `product-surfaces-v1.bundle-v1.json` — W0/Canary, checksum `bc34f47b…`
- `product-surfaces-v1.bundle-v2.json` — W1a Approvals, checksum `5b634a35…`
- `product-surfaces-v1.bundle-v3.json` — W1b HCM candidate, checksum `f90c4e3a…`
- `product-surfaces-v1.json` — byte-identical latest/final alias of bundle v3
- `product-surfaces-v1.index.json` — checksummed version/artifact index

Auth classpath resources use the same names with `.generated.json` before the
extension. Every contract snapshot is byte-identical to its Auth seed peer;
the latest Auth alias is byte-identical to bundle v3. The generator verifies
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
is wired by this contract generation step.

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

Platform telemetry dimensions are generated from the W0.5 + W1a v2 snapshot as
`platform-telemetry-dimensions-v2.generated.json`. That closed projection binds
each allowed product to its surfaces and each surface to exact UI route IDs;
HCM remains absent until W1b runtime enablement.

Runtime loaders reject `test.*` keys and never read test registry overrides.
The checksummed seed index imports only versions 1, 2 and 3 in order, all as
`DRAFT`. It contains no active version field or pointer. Activation is an
explicit CAS pointer transition after independent approval; loading a seed does
not approve or activate it.

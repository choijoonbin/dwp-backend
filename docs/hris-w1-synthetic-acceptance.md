# HRIS W1 synthetic backend acceptance

`scripts/hris_w1_synthetic_acceptance.py` is a disposable, synthetic-only
backend gate for the HRIS Wave 1 release path. It creates resources owned by one
run, records evidence, exercises the governed Auth lifecycle, and always tries
to roll back and remove its runtime resources.

The runner does not produce an overall W1 release decision. Its successful
terminal statuses have deliberately narrower meanings:

- `AUTH_SUBGATE_PASS` proves the isolated Auth migration and governed HTTP
  approve/activate/rollback path only.
- `BACKEND_RUNTIME_SUBGATE_PASS` proves that all required backend services
  became healthy, the caller-supplied live checkpoint passed while v34 was
  active, and Auth rolled back to v33. Browser, tenant, and owner acceptance
  remain the caller's responsibility.

Any other terminal result is `HOLD`. A `HOLD` must not be represented as a W1
pass.

This successor is pinned to the G3 `KEEP_V34` decision: canonical v33 is the
proven baseline, canonical v34 is the selected version, and Auth V242 is the
current migration head. Before creating any runtime resource, the runner
checks the generated authorization index and v34 bundle, all current
v32/v33/v34 checksums, the V242 migration path, and the absence of V243. A
published v35, an Auth V243 lease, or any changed latest-version binding stops
the run. In that case G3 must review and rebind the lifecycle to
v34→v35→v34; operators must not weaken the guard.

The Auth-only and full commands below are G6 operations. Do not run either on
an integration candidate before deterministic G5 gates pass and the frontend
and backend heads are frozen.

## Prerequisites

- Docker with permission to create and remove containers and networks.
- Java suitable for the repository build and runtime.
- The repository Gradle wrapper, or an explicit compatible Gradle executable.
- A committed, tracked-clean worktree. The runner binds every result to Git
  `HEAD` and its own SHA-256 before building, then verifies both again after the
  build.
- No shared database, Redis instance, customer data, or production credential
  is required or allowed.

The runner starts uniquely named Postgres and Redis containers on random
loopback ports. Postgres uses tmpfs and Redis persistence is disabled. It
generates 19 synthetic credentials and identities for every run. Service
processes receive an allowlisted host environment plus explicit synthetic
configuration, so ambient `DWP_*`, `SPRING_*`, database, and cloud credentials
cannot silently redirect them to another environment.

## Run the Auth lifecycle subgate

From the backend repository root:

```sh
python3 scripts/hris_w1_synthetic_acceptance.py --auth-only
```

To diagnose with already-built executable jars:

```sh
python3 scripts/hris_w1_synthetic_acceptance.py --auth-only --skip-build
```

`--skip-build` is diagnostic-only and always ends in `HOLD`, even when the
lifecycle succeeds. A PASS-eligible run always invokes every selected
`bootJar` task with Gradle `--rerun-tasks`, records each resulting JAR hash, and
starts only JARs whose content still matches that hash.

If the wrapper cannot be used, select a compatible Gradle executable:

```sh
python3 scripts/hris_w1_synthetic_acceptance.py \
  --auth-only \
  --gradle-executable /absolute/path/to/gradle
```

This mode performs two independent database installs across four exact
checkpoints:

1. A new `dwp_auth_latest_clean` database is installed to the repository's
   current Flyway head V242. The exact V237–V242 tail and V242 head marker must
   be present. Current v32, v33, and v34 release declarations must have their
   canonical checksums while the bundle table remains empty and no active
   pointer exists.
2. A different new `dwp_auth` database is installed to Flyway V240, verified
   with only canonical v33 imported as `DRAFT`; v34 must be absent. Auth
   approves and activates v33 as revision 1 while the database is still at
   V240.
3. The process is stopped and the same database is upgraded to the exact V241
   boundary. Only canonical v34 is imported, and both the database and HTTP API
   must show the preserved active v33 revision 1 pointer with v34 still
   `DRAFT`.
4. Auth is stopped again and the same database is upgraded seedlessly to V242.
   Both bundle rows and the active v33 revision 1 pointer must remain exact.
   The gate then proves the four exact `SIMULATE`, `PUBLISH`, `REVERSE`, and
   `RECONCILE` permissions, both HR_ADMIN and PAYROLL_ADMIN active templates,
   and `ALLOW` materialization for every eligible existing role before v34 can
   be approved or activated.

The V240 snapshot requires exactly one bundle row in the entire table:
`product-surfaces` v33. The V241 and V242 snapshots require exactly two total
rows: `product-surfaces` v33 and v34. Any additional bundle key or version
fails the gate. The V242 post-boundary snapshot independently proves the
current Flyway head and exact payroll authorization effect.

Against the running Auth service it verifies unauthenticated denial, separated
maker/checker approval, CAS activation of v33 and v34, stale-revision denial,
the active v34 pointer, rollback to v33, and immutable governance and activation
events. Governance assertions bind the exact checksums, maker/checker/release
actors, change references, rollback reason, revision transitions, and caller
service identities. A passing run ends at active v33 revision 3.

## Run the full backend subgate

Full mode requires an actual live checkpoint command. The option consumes the
rest of the command line, so it must be last:

```sh
python3 scripts/hris_w1_synthetic_acceptance.py \
  --checkpoint-executable-sha256 <lowercase-sha256> \
  --checkpoint-command /absolute/path/to/approved-w1-live-checkpoint --its-argument
```

The executable path must be absolute and identify a non-symlink, executable
regular file. Its actual content must match
`--checkpoint-executable-sha256` both before the build and immediately before
execution. The hash binds the result to bytes; because the same caller supplies
the executable and hash, it is provenance and drift protection, not an
independent signature or authority attestation.

The checkpoint starts only after Auth, Platform, People, Provider, Payroll,
Time, and Gateway are healthy with v34 active. It receives these non-secret
environment variables:

- `DWP_W1_RUN_ID`
- `DWP_W1_EVIDENCE_DIR`
- `DWP_W1_CHECKPOINT_MANIFEST`
- `DWP_W1_AUTH_URL`
- `DWP_W1_PLATFORM_URL`
- `DWP_W1_PEOPLE_URL`
- `DWP_W1_PROVIDER_URL`
- `DWP_W1_PAYROLL_URL`
- `DWP_W1_TIME_URL`
- `DWP_W1_GATEWAY_URL`
- `DWP_W1_ACTIVE_BUNDLE_VERSION`
- `DWP_W1_ACTIVE_BUNDLE_REVISION`
- `DWP_W1_ACTIVE_BUNDLE_CHECKSUM`

Before the external checkpoint can start, the runner also queries the isolated
`dwp_platform.adm_workspace_apps` table for the two run-owned synthetic tenant
IDs. Each tenant must have exactly one `ref-app-people` row, and its `name_ko`,
`name_en`, and `owner_name` values must all be exactly `HRIS`. Missing,
duplicate, malformed, or differently labeled database evidence fails the gate
closed. A successful observation is digest-bound into the
`global-home-hris-display-identity-database` PASS phase.

The command does not inherit arbitrary host variables. It receives only a
small process-runtime allowlist (`PATH`, Java/locale/temp/time-zone settings)
and the synthetic values above.

Exit code zero is necessary but not sufficient. The command must write the JSON
file named by `DWP_W1_CHECKPOINT_MANIFEST`. The runner verifies this contract:

```json
{
  "schemaVersion": 1,
  "runId": "<DWP_W1_RUN_ID>",
  "syntheticOnly": true,
  "status": "PASS",
  "activeBundle": {
    "version": 34,
    "revision": 2,
    "checksum": "852d20e1e639e1a7170f02b5714d21d8c51a9eb8ff5ac32d8b7940b82d6be83b"
  },
  "endpoints": {
    "auth": "<DWP_W1_AUTH_URL>",
    "platform": "<DWP_W1_PLATFORM_URL>",
    "people": "<DWP_W1_PEOPLE_URL>",
    "provider": "<DWP_W1_PROVIDER_URL>",
    "payroll": "<DWP_W1_PAYROLL_URL>",
    "time": "<DWP_W1_TIME_URL>",
    "gateway": "<DWP_W1_GATEWAY_URL>"
  },
  "assertions": [
    {
      "name": "tenant-a.general-owner-api",
      "status": "PASS",
      "evidencePath": "checkpoint/tenant-a-general-owner-api.json",
      "evidenceSha256": "<lowercase SHA-256 of that non-empty evidence file>"
    }
  ]
}
```

The manifest must include every required, uniquely named passing assertion:

- `tenant-a.general-owner-api`
- `tenant-a.high-assurance-step-up`
- `tenant-a.receipt-lineage`
- `tenant-a.idempotency-replay`
- `tenant-a.separation-of-duties`
- `tenant-b.feature-off`
- `tenant-b.denied`
- `isolation.cross-tenant-denied`
- `isolation.population-boundary`
- `negative.stale-evidence-denied`
- `negative.expired-evidence-denied`
- `negative.revoked-evidence-denied`
- `negative.unmapped-route-denied`
- `path.browser-gateway-owner-db`
- `rollout.flag-off`

Each evidence path must be a non-symlink regular file inside the run directory,
separate from the manifest and every other assertion, and its digest must
match. Each evidence file must itself be a JSON object containing
`schemaVersion: 1`, the exact run id, `syntheticOnly: true`, its assertion name,
`status: PASS`, v34 revision 2 with its exact canonical checksum, all seven
exact endpoints, and a non-empty list of non-empty observation objects. The
runner parses all 15 evidence files; it does not accept opaque files based on
hash alone. The manifest SHA-256 and the complete assertion-to-path-and-digest
map are preserved in both the checkpoint phase and result provenance.

`path.browser-gateway-owner-db` has one stricter, ordered contract: it contains
exactly two observations. Index 0 is the exact `BROWSER_GATEWAY_OWNER_DB` PAY
lineage record, and index 1 is the exact `BROWSER_GLOBAL_HOME_IDENTITY` record
for synthetic tenant A. The latter must bind `/` to `ref-app-people`, with its
visible, short, and full labels all exactly `HRIS`, and carry only a canonical
browser-artifact-root-relative screenshot path plus a lowercase SHA-256. The
runner reconstructs
`checkpoint/browser/hris-w1-live-browser-<run-id>/<screenshot path>`, reads the
non-symlink regular file with a bounded attested read, compares its SHA-256,
and recomputes the observation digest. Missing, extra, reordered, malformed,
path-traversing, label-drifted, or digest-drifted observations fail closed. The
validated Global Home observation is preserved in final checkpoint provenance
alongside the manifest-bound assertion evidence.

The checkpoint runs in a new process group. Success, non-zero exit, and timeout
paths all inspect the group, terminate descendants, and verify that no member
remains. A command that leaves a child behind results in `HOLD` even if cleanup
succeeds; an unverified cleanup also makes teardown fail. Consequently no
checkpoint process can remain able to modify evidence after the final scan.

The manifest is also bound to all seven loopback endpoints and the exact v34
revision 2 state and checksum. An arbitrary single assertion cannot promote
the runtime to `BACKEND_RUNTIME_SUBGATE_PASS`.

Omitting either checkpoint option is an argument error. A no-op such as
`/usr/bin/true` cannot pass because it produces no evidence manifest. If the
checkpoint fails, the runner still attempts the governed v34-to-v33 rollback
before teardown.

## External migration-control evidence

People currently fails closed before health when no externally issued Migration
Control run receipt and canonical Control reference are available. Payroll and
Time have the same class of startup guard and have not been claimed healthy past
that boundary in the current full run. Relevant application inputs include:

- People: `DWP_PEOPLE_MIGRATION_CONTROL_RUN_RECEIPT_JSON`,
  `DWP_PEOPLE_MIGRATION_CONTROL_RUN_RECEIPT_SHA256`, and
  `DWP_PEOPLE_MIGRATION_CONTROL_REFERENCE`.
- Payroll: `DWP_PAYROLL_MIGRATION_CONTROL_RUN_RECEIPT_JSON`,
  `DWP_PAYROLL_MIGRATION_CONTROL_RUN_RECEIPT_SHA256`, and
  `DWP_PAYROLL_MIGRATION_CONTROL_REFERENCE`.
- Time: `DWP_TIME_MIGRATION_CONTROL_RUN_RECEIPT_JSON`,
  `DWP_TIME_MIGRATION_CONTROL_RUN_RECEIPT_SHA256`, and
  `DWP_TIME_MIGRATION_CONTROL_REFERENCE`.

This runner intentionally does not mint, fixture, or inject those trusted
artifacts. Ambient copies of the variables are not inherited by service
processes. Reaching the next full-runtime boundary requires integration with
the approved Migration Control issuer and verification path.

Do not replace that integration with a self-issued receipt, a fixture claiming
to be a trusted feed, direct administrator SQL that marks adoption complete, or
weakened startup validation. Normal creation of the disposable database,
schemas, and separated migration/runtime roles is permitted; synthesizing
deployment authority is not. In the absence of an approved issuer, `HOLD` is
the correct result.

## Evidence and teardown

By default each run writes an ignored, run-scoped directory under
`output/hris-w1-synthetic-<timestamp>-<suffix>/`. A custom `--output-dir` must be
new, absolute, and have the exact basename bound to the run id. Important files
include:

- `result.json`: scoped status, phases, rollback state, HTTP evidence digests,
  teardown verification, Git/runner/JAR/build provenance, checkpoint executable
  provenance, and any validated manifest/evidence digest bindings.
- `db/`: current latest-clean, exact V240/V241 boundaries, V242 post-boundary
  upgrade and payroll-authorization effect, governance-event, activation-event, and
  `global-home-hris-display-identity.json` Platform catalog evidence.
- `health/`: successful service health responses.
- `http/`: lifecycle responses. Bodies over 256 KiB are replaced by a minimal
  summary with their original byte count and SHA-256 digest.
- `logs/`: build, service, and external checkpoint logs.
- `checkpoint/`: the full-mode checkpoint manifest and its digest-bound
  assertion evidence.
- `runtime.json`: loopback endpoints for a full runtime while it is active.

The runner scans its evidence for every generated secret. A finding changes the
result to `HOLD` and the containing evidence file is deleted rather than left
behind. It writes `result.json`, scans again with the final result included,
records that outcome, rewrites the result with generated values redacted, and
validates the exact final files once more. In a `finally`-equivalent cleanup
path it stops owned service processes, force-removes only its uniquely named
containers, removes its network, and inspects for residual processes,
containers, and networks. Teardown errors or residual resources also force
`HOLD`; inspect the `teardown` object in `result.json` before treating any
subgate as usable evidence.

# Immutable local Java runtime images

The local development launcher runs every Spring Java service from an immutable,
content-addressed Spring Boot image. This prevents a Gradle build in one process
from replacing a shared JAR that an already-running JVM may still read lazily.
`build/libs` is a build workspace; it is never a live Java runtime after a
service has been adopted by the image launcher.

This contract applies to Auth, Platform, People, Provider, Approval, Space,
Messaging, Notification, Meeting, Time, Payroll, and Gateway. Agent and
Frontend retain their native launch commands.

## Safety and failure model

The launcher assumes that source files and build outputs can change at any
time, a Gradle build can fail or be interrupted, process IDs can be reused,
ports can be taken by unrelated processes, and persisted state can be stale or
malformed. Local files are user-controlled, but a malformed state file or a
symlink must never expand the launcher's signalling or execution authority.

The following properties are mandatory:

- A selected set of images is completely built and validated before any
  existing process is stopped.
- A running process reads only its content-addressed image. Later changes to
  `build/libs`, including `clean`, cannot alter that process's class path.
- Build, staging, or validation failure leaves the current runtime untouched.
- Only a process whose recorded identity still matches can be signalled.
  External IDE processes and unrecorded port owners are never adopted or
  stopped.
- No authenticated functional probe is sent until managed process identity is
  established and remains stable across the probe.
- A generation is restarted at most once during one reconciliation. Repeated
  failure is latched instead of causing a restart loop.

## Image lifecycle

For `./dev up <profiles>`, the launcher resolves the selected Java services and
invokes all corresponding `bootJar` tasks in one Gradle invocation. It then
validates each completed JAR as an owner-controlled, non-symlink regular file,
checks that the file is stable while hashing it, verifies the ZIP and Spring
Boot launcher structure, and computes SHA-256 and migration digests.

An image is published below:

```text
.dev-runtime/images/sha256/<sha256>/<service>.jar
```

Publication uses an owner-only temporary file, file `fsync`, a no-clobber hard
link into the digest path, and directory `fsync`. An existing digest path is
reused only after its content and metadata are revalidated; it is never
overwritten. Published images are read-only. A partial or changed artifact is
never promoted.

After every selected image is available, the launcher reloads process state,
preflights all candidate identities, stops only verified stale candidates in
reverse dependency order, waits for each port to be released, and starts them
in the normal dependency phases with `java -jar <image>`. Health and service-
specific readiness, including the authenticated Meeting Home probe, must pass
before the generation is recorded as ready.

Legacy managed `bootRun` processes are treated as a launcher-generation drift.
They are transitioned once only after their existing recorded identity is
verified. External processes remain outside launcher authority.

## State and locking

`.dev-runtime/processes.json` remains owner-only and atomically replaced. A
Java process record includes its normal PID, process-group identity, launch
command, port and log, plus an immutable image envelope equivalent to:

```json
{
  "version": 1,
  "service": "meeting",
  "module": "dwp-meeting-server",
  "path": ".dev-runtime/images/sha256/<sha256>/meeting.jar",
  "sha256": "<64 lowercase hexadecimal characters>",
  "size": 123456,
  "migrationDigest": "<64 lowercase hexadecimal characters>"
}
```

The state loader validates the complete envelope, canonical path containment,
service-to-module binding, digest syntax, and command-to-image binding. State
cannot authorize an arbitrary JAR or shell command. Unknown, duplicate,
symlinked, overly permissive, or path-escaping state fails closed.

The ordinary state-change lock protects short state mutations and process
transitions. Long Gradle builds do not hold that lock. A watcher snapshots the
target managed identities while holding the lock, builds and stages outside
the lock, then reacquires it and proceeds only for services that are still
managed by the same identities. A concurrent `stop` therefore cannot be
blocked for the duration of a build, and a service stopped during a build is
never resurrected.

## Migration and rollback policy

The migration digest is derived from the exact migration entries embedded in
the candidate image, ordered by path and content. A failed generation may be
automatically restored to its previous image only when its migration digest is
unchanged. If database migrations differ, the launcher cannot prove that the
old binary remains compatible with the database and must not start it
automatically.

For a multi-service transition, automatic rollback is allowed only when every
affected service is migration-equivalent to its previous image. Otherwise the
failed transition is stopped, recorded truthfully, and reported for explicit
operator resolution. The launcher never attempts a destructive database
rollback.

## Image retention

Published generations are intentionally retained. Automatic garbage collection
is not part of the launcher because a filesystem-only age policy cannot prove
that an older generation is no longer useful for recovery. Content-addressing
deduplicates identical builds, and normal `up`, `watch`, `stop`, and `down`
commands never remove an image.

`./dev reset --yes` may remove the image store only after all managed process
groups have been verified stopped and their ports released. Until then, image
files are runtime state rather than disposable build output.

## Foreground watcher

The optional watcher is started explicitly:

```sh
./dev watch meeting
./dev watch core
```

It is a foreground, single-instance process. It polls portable source
fingerprints, waits for a quiet debounce window, and builds a new immutable
generation outside the state lock. Only services that are both selected and
currently managed are eligible for replacement. Compile failure, unstable
input, identity drift, an external owner, or port takeover leaves existing
services untouched.

One source generation can cause at most one replacement attempt. A failed
attempt is latched until a different stable source generation is observed.
`SIGINT` or `SIGTERM` stops only the watcher; it does not stop healthy services.
The watcher does not apply database rollback and does not turn a stopped
service back on.

## Commands and observability

- `./dev up <profiles>` builds desired images, reconciles verified managed
  services, and waits for readiness.
- `./dev watch <profiles>` continuously performs the same safe transition for
  stable source changes.
- `./dev status` reports ownership, readiness, and the active image digest
  without mutating state. Watcher build or generation failures are latched for
  that foreground watcher process and written to its standard error stream.
- `./dev logs [service]` shows the existing service logs. Image build and
  watcher diagnostics must not print tokens, secret environment values, or
  authenticated response bodies.
- `./dev stop` and `./dev down` keep their existing verified process-group and
  port-release semantics.

## Verification and rollout

Unit and POSIX integration tests cover stable hashing, no-clobber publication,
state validation, path and symlink attacks, PID reuse, external owners, port
races, build failure, cleanup, migration-aware rollback, generation restart
limits, watcher debounce, stop-during-build, circuit breaking, and retained
generation safety.

Meeting is the first live canary. Auth, Notification, and Meeting must start
from immutable image paths; the authenticated Meeting Home contract and the
browser home screen must both succeed. While Meeting serves repeated Home
requests, rebuilding, replacing, and cleaning `build/libs` must produce no
5xx response or ZIP/class-loading error and must not change the running PID or
image digest. A second `./dev up meeting` must be idempotent.

After the canary passes, the same launcher is enabled for every Java service.
The foreground watcher is enabled only after manual `up` remains stable. A
legacy bootRun escape hatch must not silently bypass image validation; any
temporary diagnostic bypass is explicit, unadopted, and outside managed
runtime guarantees.

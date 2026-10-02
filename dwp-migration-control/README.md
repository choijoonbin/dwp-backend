# DWP Migration Control boundary

`dwp-migration-control` is the offline, Flyway-native database bootstrap and
cutover runner for isolated development and verification environments. It is
not authorization to use the development bootstrap principal in production.
Production activation remains closed until the G6 deployment control supplies
service-offline fencing, independently anchored receipts, secret delivery, and
removes migration-owner credentials from long-running application processes.

The runner authenticates its Flyway connections directly as the dedicated
migration principal. It never uses `SET ROLE`; consequently `RESET ROLE` cannot
recover bootstrap authority because `session_user` and `current_user` are the
same migration login. Before any mutation, the bootstrap connection acquires
an instance/service advisory lock, requires the database to have no other
session at all, and read-only verifies the external predecessor receipt against
the complete live Flyway history and protected-object inventory. A mismatch
closes the connection and leaves passwords, roles, ownership and every ACL
untouched. Only after that preflight succeeds does Control require an exact
database ACL: the owner keeps its implicit owner privileges, and only the
service runtime, migration, the service's pinned Provider metadata reader, and
an explicitly plan-declared projection publisher may have `CONNECT`. A
publisher must be able to connect to exactly its service database across the
whole cluster. An unknown/PUBLIC grant is rejected rather than normalized.
Control then revokes database `CONNECT` from every service principal, rotates
the runtime, migration, and publisher passwords, terminates the use of the old
credentials, and rechecks that no service session is connected. Only the
rotated migration credential can connect during the Control window. A failed
run leaves every service principal with `CONNECT` denied and every rotating
service credential replaced by an unknown value.

The development orchestrator applies the same ordering one level earlier. It
classifies every controlled database before provisioning any of them. Only a
catalog with no Flyway history, no adoption metadata and no non-extension
object in a protected schema may receive initial extension/role/schema grants.
An existing catalog requires its private external Control receipt first; the
ordinary startup path never repairs or normalizes such a catalog before the
Control preflight. A partial migration without its receipt is an explicit
recovery case and fails closed. Provider's four metadata databases must also be
all fresh or all existing, preventing a cross-database role update from
partially changing a sealed instance.

Each service launcher stages only that service's `db/migration` resources (and
People's separate `db/performance-migration` stream) into a private Control
classpath root. The service's compiled classes, application runtime classpath,
and `META-INF/services` are never attached to the privileged process. A staging
gate rejects symlinks, duplicate paths, closure drift, and any per-file SHA-256
mismatch before Java starts. The Control reference covers the orchestrating
`devctl.py`, Gradle launcher/wrapper, Control sources, the complete executable
`dwp-core` runtime source/resources, service startup guards/configuration, and
every staged migration source.

Before that window opens, migration, Provider metadata, and projection
publisher principals must have zero inbound and outgoing role memberships.
Application runtime memberships must also be empty, except that an established
Notification database has the complete, option- and grantor-pinned three-role
set described below. The same membership boundary is checked again before
successful `CONNECT` restoration. At the post-migration boundary, runtime and
publisher logins must own no database object or schema and must have `CREATE` on
no schema, including system schemas. The migration login may own and create
only in the plan's exact protected schemas; no service login may own an
extension. Control removes stale direct schema grants across the entire catalog
and then proves this exact shape before sealing the run receipt.

Trigger enforcement cannot be disabled by a service identity. After the
predecessor receipt has been verified, Control removes direct and PUBLIC
`SET` authority for `session_replication_role`, clears database-, role-, and
role/database-scoped overrides, and requires the effective value to remain
`origin` before migration, at postflight, and after connection restoration.
Notification's three SET-able managed roles and Provider's four metadata roles
are covered by the same rule. Existing receipt/history/inventory drift is
always checked before this normalization, so the repair step cannot erase a
failed evidence comparison.

System-catalog access is separately bounded to PostgreSQL's initialized ACL.
At read-only preflight and again before a receipt is sealed, Control compares
each runtime, migration, metadata, projection publisher, and Notification
managed role against
`pg_init_privs` (with PostgreSQL's object-type default ACL as the fallback).
Any direct service-role grant, any PUBLIC widening, or any executable
user-created routine in `pg_catalog` fails closed. Control does not silently
revoke an unexpected catalog grant on an existing database because doing so
would erase the evidence of an out-of-band privilege change.

Extensions required by an unprivileged Flyway stream are installed by the
bootstrap owner before Control starts; migration roles never receive database
`CREATE` for this purpose. People requires `btree_gist` 1.7 and `pgcrypto` 1.3;
Payroll and Time require `btree_gist` 1.7. Preflight compares the complete
non-`plpgsql` extension inventory, including schema, pinned version, and owner,
so a platform default-version upgrade or an unexpected extension fails closed.

Temporary database privileges are denied at entry and exit. They are granted
only around the exact immutable versions listed in `ControlPlan` and are
revoked in `finally`. Notification V2 and V22 additionally need PostgreSQL role
DDL. Those immutable files contain PostgreSQL's superuser-reserved
`ALTER ROLE ... NOSUPERUSER` clause, so merely granting `CREATEROLE` cannot
replay them. Each therefore receives a temporary `SUPERUSER` capability for
exactly one migration only after the complete source bytes, Flyway pending
version/script, exact role targets, and forbidden-authority statement scan
match the pinned contract. Flyway still logs in directly as the migration
principal (`session_user == current_user`), and the capability is removed in
`finally` before any subsequent migration or receipt can be emitted. This
development replay exception is not a reusable production administrator
credential or a general migration mode.
The two privileged Flyway calls suppress every default or programmatic callback;
only the one attested versioned migration may be pending. At steady state the
notification login has exactly three `SET TRUE`/`INHERIT FALSE` memberships,
granted by the bootstrap owner without admin option, while the migration login
has none. The API and worker roles can execute only the five RLS scope helpers;
the audit-relay role and direct runtime login can execute no protected routine.
Legacy Notification adoption is deliberately supported only after the immutable
role-authority floor V2, V5, and V22 is already successful. An older database is
rejected before ownership transfer; those privileged migrations must be handled
by a separately reviewed recovery operation rather than widening the generic
adoption runner.

Approval retention uses a different, non-superuser capability plan. Control
pre-provisions the exact NOLOGIN/NOSUPERUSER owner and executor roles; the
Flyway login remains NOCREATEROLE. PostgreSQL 16+ gives a non-superuser
CREATEROLE creator an unavoidable ADMIN-only membership on each role, so the
plan pins that membership to `ADMIN TRUE, INHERIT FALSE, SET FALSE` and pins its
grantor. V24, V28-V33, and V38 run one at a time with only the retention-owner
membership and owner `CREATE` on `public`; V24 alone additionally gets
current-database `CREATE` to introduce `apr_retention_internal`. Every
window-scoped grant is removed independently in `finally`, and the database ACL, effective database
privileges, memberships, schema CREATE authority and schema inventory are
proven closed before another version runs. After all migration windows close,
Control installs exactly `USAGE ON SCHEMA public` for the owner and executor;
this is the read-only namespace reachability required by the reviewed
SECURITY DEFINER/table grants and never includes CREATE or role membership. An
already-applied version is provenance-checked without reopening any capability.
The private schema and its actual auxiliary owner are part of the receipt
inventory. Runtime startup also rejects any database authority on either
auxiliary role, any executor write/DDL or grant option, and every auxiliary ACL
outside the role/class/schema plan; it also requires those two steady public
USAGE rows. Exact ACL bytes remain sealed in the protected definition fingerprint.
Because PostgreSQL has no per-role deny ACL, provisioning must remove PUBLIC
`CONNECT`, `CREATE`, and `TEMPORARY` from every database (including template
catalogs) before these roles are created. Control verifies that precondition; it
does not erase an existing cluster-wide ACL drift during receipt preflight.

After migration, the shared domain-event ledger is normalized as an independent
capability: outbox, inbox, and offsets allow runtime `SELECT/INSERT/UPDATE`;
replay audit allows `INSERT` only; and the dead-letter view allows no direct
runtime operation. Column/default/grant-option privileges remain zero.
Notification transport is disabled, so its direct login and all three managed
roles receive zero privileges on all five ledger relations.

Every successful run emits a content-addressed receipt chaining its predecessor
and sealing the complete Flyway history and protected-object definition/ACL
inventory. Applications must compare that externally deployed receipt,
receipt digest, and current Control reference on every strict startup. A source,
migration, guard, configuration, or dependency-boundary change therefore needs
a new Control run and receipt; it must never be silently resealed. The reference
also includes the shared `dwp-core` repeatable Flyway resources because they are
loaded from every controlled service's runtime classpath.

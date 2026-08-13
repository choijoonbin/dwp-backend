# Role Delegation and Privilege Boundary

Status: Accepted

Last verified: 2026-08-13

Scope: tenant role delegation and separation-of-duties enforcement owned by
`dwp-auth-server`. Provider operator authorization remains owned by
`dwp-provider-server` and is outside this decision.

## Decision

DWP uses deny-by-default role delegation. Authentication proves identity; it does not grant authority to delegate every role visible in the tenant. Every role assignment mutation must resolve the actor's current effective roles from the database and match an active policy in `sys_role_assignment_policies`.

The authorization model separates four concerns:

1. Role definition: `sys_builtin_role_catalog` owns stable built-in role identity and governance metadata.
2. Delegation authority: `sys_role_assignment_policies` defines which grantor role can assign which target role and by which workflow.
3. Separation of duties: `sys_role_conflict_policies` rejects conflicting direct and inherited effective role combinations.
4. Assignment evidence: successful and denied mutations are recorded in `sys_identity_audit_events` and forwarded through the audit outbox.

## Assignment Classes

| Class | Roles | Direct tenant-admin assignment |
| --- | --- | --- |
| `BASELINE` | `WORKSPACE_MEMBER` | Required for every managed workforce identity |
| `DELEGATED` | `HR_ADMIN`, `PEOPLE_ADMIN`, `AUDITOR` | Allowed when an active direct policy exists |
| `GOVERNED` | `TENANT_ADMIN`, `AUDIT_ADMIN` | Approval workflow only; never exposed by the direct API |
| `CONTROL_PLANE` | `ADMIN`, `PLATFORM_ADMIN`, `PROVIDER_*` | Provisioning/control-plane workflow only |

`TENANT_ADMIN` can directly assign only `WORKSPACE_MEMBER`, `HR_ADMIN`, `PEOPLE_ADMIN`, and `AUDITOR`. The API returns only those options. Hiding options in the UI is not an authorization control; the service rejects any submitted role outside that set.

## Enforcement Invariants

- Re-resolve the actor's active effective roles from persistent data for every mutation.
- Reject self-management and targets that hold any peer, higher, custom, governed, or control-plane role.
- Permit assignment only to `ACTIVE` or onboarding `INVITED` identities; lifecycle workflows own suspended and inactive identities.
- Require optimistic access revision and entity version checks.
- Require a human justification of 10 to 500 characters.
- Retain effective `WORKSPACE_MEMBER` baseline access.
- Evaluate role conflicts against the prospective direct roles plus inherited group roles.
- Reject `AUDITOR` combined with `HR_ADMIN` or `PEOPLE_ADMIN` to preserve audit independence.
- Keep audit view, investigation, export, and configuration permissions out of `TENANT_ADMIN`; use `AUDITOR` or `AUDIT_ADMIN` through governed workflows.
- Revoke all active target sessions after a successful role change.
- Treat built-in role definitions and their permission sets as immutable in tenant governance APIs.
- Apply the same delegation boundary to group role assignment and revocation.
- Audit denied attempts in a separate transaction with a stable reason code.

## Workflow Boundary

Governed tenant roles use the purpose-built eligibility, request, approval, activation,
expiry, revocation, and emergency-access lifecycle. They must not be added to the direct
assignment policy as a shortcut. Control-plane role issuance and an external enterprise
IdP remain separate provisioning responsibilities. SCIM and HRIS provisioning must keep
the same baseline, conflict, audit, row-locking, access-revision, and session-invalidation
invariants; an IdP connector cannot bypass separation-of-duties checks.

## Implementation Conformance

| Area | Status | Implementation evidence |
| --- | --- | --- |
| Built-in role classification, direct delegation policy, and conflict policy | Implemented | [`V30__enforce_role_delegation_boundaries.sql`](../../dwp-auth-server/src/main/resources/db/migration/V30__enforce_role_delegation_boundaries.sql) |
| Per-request actor role and active policy resolution | Implemented | [`RoleDelegationPolicyService`](../../dwp-auth-server/src/main/java/com/dwp/services/auth/service/RoleDelegationPolicyService.java) |
| Direct user role boundary, baseline, conflict, version, audit, and session invalidation | Implemented | [`IdentityAdminService`](../../dwp-auth-server/src/main/java/com/dwp/services/auth/service/IdentityAdminService.java) |
| Group role grant/revoke delegation boundary and member session invalidation | Implemented | [`AccessGovernanceService`](../../dwp-auth-server/src/main/java/com/dwp/services/auth/service/AccessGovernanceService.java) |
| Denied mutation audit in an independent transaction | Implemented | [`IdentityAuditService`](../../dwp-auth-server/src/main/java/com/dwp/services/auth/service/IdentityAuditService.java) |
| Group assignment conflict evaluation for every current group member | Implemented | [`GroupRoleConflictGuard`](../../dwp-auth-server/src/main/java/com/dwp/services/auth/service/GroupRoleConflictGuard.java) locks affected identities and evaluates prospective effective roles before local-admin or SCIM membership writes. |
| Uniform direct/group assignment justification policy | Implemented | Direct and group assignment DTOs require 10-500 characters; privileged workflows use the separately governed 10-1000 evidence limit. |
| Governed tenant-role eligibility, approval, JIT, emergency, expiry, and revocation | Implemented | [`V36__add_privileged_access_lifecycle.sql`](../../dwp-auth-server/src/main/resources/db/migration/V36__add_privileged_access_lifecycle.sql), [`PrivilegedAccessService`](../../dwp-auth-server/src/main/java/com/dwp/services/auth/service/PrivilegedAccessService.java) |
| SCIM group SoD, atomic rejection, access revision, session invalidation, and denied audit | Implemented | [`ScimGroupService`](../../dwp-auth-server/src/main/java/com/dwp/services/auth/scim/ScimGroupService.java), [`ScimProvisioningAuditService`](../../dwp-auth-server/src/main/java/com/dwp/services/auth/scim/ScimProvisioningAuditService.java) |
| External IdP and control-plane role issuance | External Gate | Provider selection, assurance policy, credential, and sandbox evidence are tracked as frontend release decision `D-01`; no synthetic success path is enabled. |

The executable policy tests are
[`RoleDelegationPolicyServiceTest`](../../dwp-auth-server/src/test/java/com/dwp/services/auth/service/RoleDelegationPolicyServiceTest.java),
[`IdentityAdminServiceTest`](../../dwp-auth-server/src/test/java/com/dwp/services/auth/service/IdentityAdminServiceTest.java),
[`AccessGovernanceServiceTest`](../../dwp-auth-server/src/test/java/com/dwp/services/auth/service/AccessGovernanceServiceTest.java),
[`GroupRoleConflictGuardTest`](../../dwp-auth-server/src/test/java/com/dwp/services/auth/service/GroupRoleConflictGuardTest.java),
and [`ScimGroupServiceTest`](../../dwp-auth-server/src/test/java/com/dwp/services/auth/scim/ScimGroupServiceTest.java).

## Security Basis

This decision follows least privilege, separation of duties, deny-by-default authorization, per-request permission validation, and just-in-time or approval-based privileged role assignment practices.

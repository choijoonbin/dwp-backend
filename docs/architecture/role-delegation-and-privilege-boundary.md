# Role Delegation and Privilege Boundary

Status: Accepted

Last verified: 2026-08-20

Scope: tenant role delegation, application-scoped administrative responsibility,
runtime entitlement, and separation-of-duties enforcement owned by
`dwp-auth-server`. Provider operator authorization remains owned by
`dwp-provider-server` and is a separate control plane.

## Decision

DWP uses deny-by-default role delegation. Authentication proves identity; it does not grant authority to delegate every role visible in the tenant. Every role assignment mutation must resolve the actor's current effective roles from the database and match an active policy in `sys_role_assignment_policies`.

The authorization model separates six concerns:

1. Role definition: `sys_builtin_role_catalog` owns stable built-in role identity and governance metadata.
2. Delegation authority: `sys_role_assignment_policies` defines which grantor role can assign which target role and by which workflow.
3. Separation of duties: `sys_role_conflict_policies` rejects conflicting direct and inherited effective role combinations.
4. Assignment evidence: successful and denied mutations are recorded in `sys_identity_audit_events` and forwarded through the audit outbox.
5. Resource responsibility: application duties are assigned to explicit resource sets rather than granting tenant-wide application authority.
6. Runtime entitlement: approved application access is effective only through an active Auth-owned `com_principal_resource_grants` record.

## Assignment Classes

| Class | Roles | Direct tenant-admin assignment |
| --- | --- | --- |
| `BASELINE` | `WORKSPACE_MEMBER` | Required for every managed workforce identity |
| `DELEGATED` | `IDENTITY_ADMIN`, `APP_CATALOG_ADMIN`, `HR_ADMIN`, `PEOPLE_ADMIN`, `AUDITOR`, `COMMUNICATIONS_*`, `SERVICE_*`, `DWAION_ADMIN`, `DWAION_AGENT_*`, `DWAION_GOVERNANCE_MANAGER`, `DWAION_EVALUATOR`, `DWAION_AUDITOR` | Allowed only through the active assignment mode declared for that target role |
| `GOVERNED` | `TENANT_ADMIN`, `AUDIT_ADMIN` | Approval workflow only; never exposed by the direct API |
| `CONTROL_PLANE` | `ADMIN`, `PLATFORM_ADMIN`, `PROVIDER_*` | Provisioning/control-plane workflow only |

`TENANT_ADMIN` can directly assign only roles allowed by an active `DIRECT`
policy in `sys_role_assignment_policies`: `WORKSPACE_MEMBER`, `IDENTITY_ADMIN`,
`APP_CATALOG_ADMIN`, `HR_ADMIN`, `PEOPLE_ADMIN`, `AUDITOR`,
`COMMUNICATIONS_EDITOR`, `COMMUNICATIONS_PUBLISHER`,
`SERVICE_CATALOG_MANAGER`, and `SERVICE_AGENT`. The API returns only those
options. Hiding options in the UI is not an authorization control; the service
rejects any submitted role outside that set.

DWAI·ON administration is deliberately separate from tenant administration.
`TENANT_ADMIN`, `ADMIN`, and `PLATFORM_ADMIN` may sponsor the privileged roles
only through an active `APPROVAL` policy; none of those grantor roles inherits AI
operations access. The legacy composite `DWAION_ADMIN` role remains available for
approved full-product operators, while routine work is split into the following
least-privilege roles:

- `DWAION_AGENT_EDITOR` authors draft agent revisions but cannot publish them.
- `DWAION_AGENT_PUBLISHER` reviews and publishes revisions but cannot author them.
- `DWAION_GOVERNANCE_MANAGER` operates source, action, safety, and retention policy
  without conversation-content or audit-export access.
- `DWAION_EVALUATOR` owns encrypted evaluation sets, quality runs, run-to-run regression
  review, and metrics-only evidence export. Export remains a separate permission from view.
- `DWAION_AUDITOR` reads retention and append-only governance evidence without
  mutation authority.

Maker-checker and audit independence are enforced by role conflicts:
`DWAION_AGENT_EDITOR` conflicts with `DWAION_AGENT_PUBLISHER`,
`DWAION_GOVERNANCE_MANAGER` conflicts with `DWAION_AUDITOR`, and
`DWAION_EVALUATOR` conflicts with `DWAION_AUDITOR`. Product authority is divided
across `ADMIN.DWAION_OPERATIONS`, `ADMIN.DWAION_AGENTS`,
`ADMIN.DWAION_SOURCES`, `ADMIN.DWAION_ACTIONS`, `ADMIN.DWAION_SAFETY`,
`ADMIN.DWAION_EVALUATION`, `ADMIN.DWAION_RETENTION`, and
`ADMIN.DWAION_AUDIT`. Environment-specific customer delivery decisions use
`ADMIN.DWAION_GATES`; governance managers configure and validate while auditors
approve. A gate owner, configurator, or validator cannot approve the same gate.
The retired aggregate `ADMIN.DWAION` resource is disabled
and no longer authorizes a route by itself.

## Application Responsibility Boundary

Application administration is not one tenant-wide role. The catalog governor
assigns `APP_OWNER` to an application resource set. An owner can delegate
`APP_CONFIG_ADMIN`, `APP_ACCESS_MANAGER`, `APP_ACCESS_APPROVER`, and
`APP_ACCESS_REVIEWER` only inside that owned set, but cannot create another owner.

- `APP_CATALOG_ADMIN` governs ownership and can read the tenant request queue;
  it cannot approve, fulfil, or revoke requests.
- A scope with an effective `APP_OWNER` but no effective `APP_ACCESS_APPROVER`
  may bootstrap exactly its first manual user approver through an independent
  active `APP_CATALOG_ADMIN`. The owner must be the requester. Once any effective
  approver exists, every later control-plane decision requires an exact-scope
  `APP_ACCESS_APPROVER`; the catalog exception is closed.
- `TENANT_ADMIN` does not inherit request queue, approval, fulfilment, or
  revocation authority. Application duties require an explicit scoped
  responsibility even for the tenant accountable administrator.
- `APP_ACCESS_APPROVER` can decide requests only for its resource set.
- `APP_ACCESS_MANAGER` can fulfil, retry, or revoke only for its resource set.
- A requester cannot approve or fulfil their own request.
- The approver of a request cannot fulfil the same request.
- Access managers conflict with approver and reviewer responsibilities on an
  overlapping resource set.
- Approval changes workflow state only. Platform must independently call the
  Auth entitlement adapter before runtime access becomes effective.

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
- Keep identity administration, catalog governance, application approval,
  entitlement fulfilment, and periodic review as distinct responsibilities.
- Revoke pending and active application responsibilities when the owning
  application entitlement is retired; a re-enabled application receives a new
  active owner assignment.
- Keep optional application access out of `WORKSPACE_MEMBER`; distribute it
  through group or user runtime entitlement records with source evidence.
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
| Application resource sets, scoped responsibilities, conflict checks, delegation, and expiry | Implemented | [`V40__add_scoped_application_administration.sql`](../../dwp-auth-server/src/main/resources/db/migration/V40__add_scoped_application_administration.sql), [`AppGovernanceService`](../../dwp-auth-server/src/main/java/com/dwp/services/auth/service/AppGovernanceService.java) |
| Auth-owned principal resource entitlement, idempotent grant/revoke, expiry, and audit | Implemented | [`V44__add_principal_resource_entitlement_lifecycle.sql`](../../dwp-auth-server/src/main/resources/db/migration/V44__add_principal_resource_entitlement_lifecycle.sql), [`AppEntitlementService`](../../dwp-auth-server/src/main/java/com/dwp/services/auth/service/AppEntitlementService.java) |
| Independent Platform request decision, fulfilment, retry, revocation, and requester/approver/fulfiller separation | Implemented | [`V59__complete_app_access_fulfilment_lifecycle.sql`](../../dwp-platform-server/src/main/resources/db/migration/V59__complete_app_access_fulfilment_lifecycle.sql), [`WorkspaceService`](../../dwp-platform-server/src/main/java/com/dwp/services/platform/workspace/WorkspaceService.java) |
| Product-aware tenant resource and built-in permission templates | Implemented | [`V49__harden_tenant_authorization_and_seed_skax_groups.sql`](../../dwp-auth-server/src/main/resources/db/migration/V49__harden_tenant_authorization_and_seed_skax_groups.sql), [`AuthTenantProvisioningService`](../../dwp-auth-server/src/main/java/com/dwp/services/auth/provisioning/AuthTenantProvisioningService.java) |
| SKAX functional groups, access packages, app responsibilities, and drift diagnostics | Implemented | Auth V49 and [`audit-authorization-model.sh`](../../scripts/audit-authorization-model.sh) |
| Independent DWAI·ON operations delegation, SoD, granular resources, and SKAX verification group | Implemented | [`V74__authorize_dwaion_operations.sql`](../../dwp-auth-server/src/main/resources/db/migration/V74__authorize_dwaion_operations.sql), [`V75__harden_dwaion_privileged_assignment.sql`](../../dwp-auth-server/src/main/resources/db/migration/V75__harden_dwaion_privileged_assignment.sql), [`V76__separate_dwaion_governance_permissions.sql`](../../dwp-auth-server/src/main/resources/db/migration/V76__separate_dwaion_governance_permissions.sql), and [`V77__authorize_dwaion_evaluation_evidence_export.sql`](../../dwp-auth-server/src/main/resources/db/migration/V77__authorize_dwaion_evaluation_evidence_export.sql); Gateway resolves the operation-specific `ADMIN.DWAION_*` resource and does not infer it from tenant roles. |
| Environment-specific DWAI·ON delivery gates, independent approval, and evidence permissions | Implemented | [`V78__authorize_dwaion_operational_delivery_gates.sql`](../../dwp-auth-server/src/main/resources/db/migration/V78__authorize_dwaion_operational_delivery_gates.sql) and the [Customer Policy and Release Gate Register](../delivery/customer-policy-and-release-gate-register.md). |
| External IdP and control-plane role issuance | External Gate | Provider selection, assurance policy, credential, and sandbox evidence are tracked as [`D-01`](../delivery/customer-policy-and-release-gate-register.md); no synthetic success path is enabled. |
| External Entra/Okta entitlement mapping and drift reconciliation | External Gate | DWP Auth runtime entitlement is implemented; external IAM credentials, mapping, sandbox evidence, and reconciliation SLA remain [`D-16`](../delivery/customer-policy-and-release-gate-register.md). |

The executable policy tests are
[`RoleDelegationPolicyServiceTest`](../../dwp-auth-server/src/test/java/com/dwp/services/auth/service/RoleDelegationPolicyServiceTest.java),
[`IdentityAdminServiceTest`](../../dwp-auth-server/src/test/java/com/dwp/services/auth/service/IdentityAdminServiceTest.java),
[`AccessGovernanceServiceTest`](../../dwp-auth-server/src/test/java/com/dwp/services/auth/service/AccessGovernanceServiceTest.java),
[`GroupRoleConflictGuardTest`](../../dwp-auth-server/src/test/java/com/dwp/services/auth/service/GroupRoleConflictGuardTest.java),
and [`ScimGroupServiceTest`](../../dwp-auth-server/src/test/java/com/dwp/services/auth/scim/ScimGroupServiceTest.java).

## Security Basis

This decision follows least privilege, separation of duties, deny-by-default authorization, per-request permission validation, and just-in-time or approval-based privileged role assignment practices.

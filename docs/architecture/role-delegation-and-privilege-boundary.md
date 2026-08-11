# Role Delegation and Privilege Boundary

Status: Accepted

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

## Future Workflows

Governed and control-plane roles require purpose-built request, approval, expiry, and activation workflows. They must not be added to the direct assignment policy as a shortcut. SCIM and HRIS provisioning must use the `PROVISIONING` mode and preserve the same baseline, conflict, audit, and session-invalidation invariants.

## Security Basis

This decision follows least privilege, separation of duties, deny-by-default authorization, per-request permission validation, and just-in-time or approval-based privileged role assignment practices.

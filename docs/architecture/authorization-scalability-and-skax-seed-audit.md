# Authorization Scalability and SKAX Seed Audit

Status: Implemented and verified

Last verified: 2026-08-20

## Verdict

The DWP authorization model is suitable for multi-tenant growth because tenant
roles, group inheritance, application responsibilities, runtime entitlements,
provider duties, and audit evidence are separate concerns. The implementation
is intentionally hybrid RBAC plus scoped resource relationships rather than a
single administrator hierarchy.

The pre-audit implementation was not release-ready: tenant provisioning granted
every common action on every enabled resource to `TENANT_ADMIN`, application
request APIs accepted broad tenant roles, and SKAX had no effective group-based
access packages. Auth V49 and the related Platform and Frontend changes close
those gaps.

The current conservative assessment is **B**. The internal model and local
SKAX reference tenant are complete enough for product development and customer
demonstration. An **A** requires customer-selected IdP assurance plus real
Entra/Okta assignment mapping, sandbox evidence, drift reconciliation, and
production operation evidence for time-bound privileged activation.

## Authorization Planes

| Plane | Primary model | Boundary |
| --- | --- | --- |
| Workforce baseline | `WORKSPACE_MEMBER` role permissions | First-party common applications only |
| Functional operations | Assignable non-privileged roles through groups | Communications and service operations |
| Tenant governance | Privileged direct or approved role assignments | One customer tenant |
| Application administration | `responsibility + resource set + validity` | Explicit application set |
| Runtime application access | Principal resource grants | User or group, source, validity, lifecycle |
| Provider operations | Provider-owned operator roles and workflows | Separate control plane |

All authorization remains deny-by-default. Product entitlements decide which
resources exist; roles decide allowed actions; groups distribute reusable
access; scoped responsibilities decide who owns, approves, fulfils, and reviews
an application.

## Provisioning Contract

Auth V49 adds two system catalogs:

- `sys_tenant_resource_templates`: product-entitlement-aware resource materialization.
- `sys_tenant_role_permission_templates`: explicit built-in role and permission matrix.

Platform V68 registers the template `resource_type` and shared
`lifecycle_state` checks in the central code-contract registry. This keeps the
Auth database constraints, administrator metadata, and deployment audit on one
versioned contract as new tenant resource classes are introduced.

`AuthTenantProvisioningService` now creates every tenant-facing built-in role,
assigns the initial administrator both `TENANT_ADMIN` and
`WORKSPACE_MEMBER`, materializes only entitled resources, retires disabled app
resource sets and their live responsibilities, and ensures every active app has
an accountable bootstrap owner. Tenant-defined custom resources and custom
roles are not deleted by template synchronization.

## SKAX Reference Tenant

| Group | Purpose | Seed result |
| --- | --- | --- |
| `SKAX_ALL_EMPLOYEES` | Common optional apps | 177 active or invited `@sk.com` identities; mail, collaboration, knowledge |
| `SKAX_ERP_USERS` | ERP access package | 3 members; business ERP |
| `SKAX_LEGACY_OPERATIONS_USERS` | Legacy operations package | 5 members; legacy operations |
| `SKAX_COMMUNICATIONS_EDITORS` | Functional role | `COMMUNICATIONS_EDITOR` |
| `SKAX_SERVICE_CATALOG_MANAGERS` | Functional role | `SERVICE_CATALOG_MANAGER` |
| `SKAX_SERVICE_AGENTS` | Functional role | `SERVICE_AGENT` |
| `SKAX_APP_OWNERS` | App accountability | `APP_OWNER` on 14 Auth app resource sets |
| `SKAX_APP_CONFIGURATION_ADMINS` | App configuration | `APP_CONFIG_ADMIN` on 14 sets |
| `SKAX_APP_ACCESS_MANAGERS` | Access fulfilment and revocation | 5 external or optional apps |
| `SKAX_APP_ACCESS_APPROVERS` | Independent decisions | 5 external or optional apps |
| `SKAX_APP_ACCESS_REVIEWERS` | Independent certification | 5 external or optional apps |

Privileged tenant roles remain direct or approval-based and cannot be assigned
through groups. Existing SKAX workforce identities retain the
`WORKSPACE_MEMBER` baseline even when they also hold a specialist role.

## Enforced Invariants

- `TENANT_ADMIN` has no implicit app request decision, fulfilment, or revocation authority.
- `WORKSPACE_MEMBER` has no direct optional external-app permission.
- Every active or invited workforce identity has the baseline role.
- Privileged or non-group-assignable roles cannot be active group assignments.
- Every active application resource set has an owner and configuration administrator.
- Optional apps have independent manager, approver, and reviewer responsibilities.
- Disabled resources have no active principal grant.
- Retired app resource sets have no pending or active responsibility.
- Overlapping manager and approver or reviewer duties are reported as a SoD conflict.
- Platform active app keys must resolve to enabled Auth resources in the same tenant.

The database view `sys_authorization_integrity_findings` exposes runtime drift.
`scripts/audit-authorization-model.sh` additionally validates the SKAX package
matrix and cross-service application contract and fails deployment checks on a
violation.

## Runtime Evidence

The following checks passed on 2026-08-14:

- Auth V49 migration through Flyway on a clone of the V48 database.
- New tenant provisioning, repeat provisioning, entitlement removal, and re-enable.
- New tenant: 12 tenant-facing roles, two administrator foundation roles, 14
  enabled app resource sets, and 14 owners.
- Entitlement removal: four sets retired and zero live responsibilities on retired sets.
- Entitlement re-enable: all 14 sets active and owned again.
- SKAX authorization audit: all checks passed with zero integrity findings, zero
  standing privileged group grants, and seven active time-bound eligibility records.
- Central code-contract audit: 588 contracts, 2,261 active values, and 690
  bindings passed.
- SKAX authorization groups: 17 active groups, 178 all-employees, 3 ERP users,
  and independent members in functional or application-duty groups.
- Gateway login and `/api/auth/me` for member, tenant admin, access manager, and approver personas.
- Tenant admin app request API and direct UI route: HTTP 403 and `/403`.
- Scoped manager and approver request queue: HTTP 200 with five exact app scopes each.
- Frontend architecture, authorization, route, i18n, lint, type, production build,
  bundle-budget, desktop, and mobile verification passed with no release blocker.

## External Gates

The remaining customer decisions are tracked only as
[`D-01`](../delivery/customer-policy-and-release-gate-register.md) and
[`D-16`](../delivery/customer-policy-and-release-gate-register.md) in the
[Customer Policy and Release Gate Register](../delivery/customer-policy-and-release-gate-register.md).
This document does not maintain a second status or closure checklist for them.

Until those gates are supplied, DWP reports connector configuration as required
and does not claim that an external directory assignment succeeded.

# Provider and Tenant Support Access Boundary

Status: Accepted

Last verified: 2026-08-26

## Decision

A provider operator is a control-plane identity. It is not a tenant member or a
tenant administrator, even when the Auth schema stores the operator in the
bootstrap tenant. Provider access to customer resources is always explicit,
time-bound, purpose-bound, and audited through a support session.

The following invariants are mandatory:

- An auth identity has exactly one immutable `identity_plane`: `PROVIDER` or
  `TENANT`. Its roles must match that plane, including direct memberships and
  open privileged grants. The built-in role catalog reserves `PROVIDER_*` for
  the `PROVIDER` family so database, JWT, Gateway, and service classification
  cannot drift. Removing the last role does not convert the credential to the
  other plane.
- Auth login and token rotation/elevation fail closed for a mixed effective role
  set. JWT validation also rejects both a mixed current set and a mixed claim.
- Direct, group, SCIM, and privileged activation workflows evaluate the same
  prospective role-plane rule before mutation. Database triggers remain the
  final boundary for out-of-band SQL writes.
- Provider roles cannot be assigned to tenant groups. Scheduled group
  assignments participate in preflight before `valid_from`; time alone must not
  create a latent mixed identity.
- A provider JWT has no ambient access to `/api/platform/**`, `/api/people/**`,
  Auth work/admin governance APIs, or any other tenant data-plane API. Gateway
  permits `/api/provider/**` and an exact allowlist of self-service Auth account
  endpoints; `/api/auth/**` is never a blanket control-plane exception. Tenant
  product-surface context, authority evaluation, governed-route evaluation, and
  step-up endpoints are deny-all for provider support. Preview calls the one
  redacted Platform projection directly.
- The provider's normal frontend surface is `/provider/**` plus the limited
  `/account` profile, security, locale, and non-sensitive display self-service.
  `/admin/**`, personal home, and tenant work routes remain forbidden even
  during active support. While support is active, every `/account` child route
  redirects to `/provider/support`; existing browser-local provider display
  preferences are preserved but cannot be opened or edited. A provider opening
  a tenant-only `home`, `notifications`, or `managed` settings URL outside
  support is safely redirected to `/account/settings/appearance` without a
  tenant preference API call.
- One provider operator may have only one `ACTIVE` support session at a time,
  regardless of target tenant. Service preflight produces a clear conflict and
  the provider database partial unique index closes concurrent races.
- A support session has a server-authoritative 15-minute idle lease. Each
  authorized data-plane request atomically verifies and touches only that
  session and its target tenant; effective expiry is always
  `min(absolute expiry, last used + 15 minutes)`. Browser polling does not renew
  the lease.
- Standard JIT activation requires both a deployment switch and the durable
  `STANDARD_JIT` database control. Both default disabled outside the explicit
  local fixture. Disabling remains available while activation is disabled,
  atomically revokes active sessions, completes their requests, and records
  extended-retention audit evidence. There is no HTTP re-enable command.
- Support cookies are resolved server-side. Client-supplied support headers are
  removed, and downstream services accept only the verified target tenant,
  operator tenant, session revision, and scope headers emitted by Gateway.
  The browser support-session context omits `authTenantId`; the internal
  `/internal/provider/v1/support-access/resolve` response alone carries the
  verified Auth routing tenant required by Gateway.
  The actual browser cookie uses the coarse service paths
  `/api/provider/v1/admin/`, `/api/auth/`, and `/api/platform/v1/admin/`.
  Those `Path` attributes are not authorization: Gateway projects support
  authority only onto the exact preview GET and denies the other paths.
- Every support session is bound to the Auth JWT `sid` session family that
  activated it. Rotation of a still-valid `DWP_SESSION` preserves this binding,
  while logout and re-login produce a different family and cannot reuse a
  surviving support cookie. There is no separate refresh-token cookie and an
  expired JWT is not refreshable. Provider V41 revokes unbound historical active
  rows instead of guessing a binding.
- Auth V99 through V104 serialize role/authority mutations on the canonical user
  row under the service `READ COMMITTED` transaction contract. This closes
  concurrent opposite-plane write skew as well as future-dated tenant grants.
- Explicit `identityPlane` is the only authority source. The Gateway check that
  also recognizes a `PROVIDER_*` role exists solely as a deny-side defense so a
  suspicious provider claim cannot escape the provider boundary; it never
  infers an allow-side plane or grants provider authority.
- Break-glass is not a retired scope and is not controlled by a runtime
  kill-switch. It is unconditionally disabled in service code and returns
  `INVALID_STATE` until incident binding, fresh MFA, alerting, customer notice,
  and their release evidence are delivered as an explicit future change.

## Tenant Experience Preview

Provider diagnostics do not impersonate a user and do not expose the regular
home response. `GET /api/platform/v1/admin/tenant-experience-preview` is the only
safe experience preview contract and requires the dedicated
`TENANT_EXPERIENCE_PREVIEW` support scope.

The `tenant-experience-preview.v1` response contains published tenant branding
and home configuration only. It intentionally excludes:

- user personalization and user-generated content;
- workforce data and live announcements;
- asset URLs, object keys, original filenames, and media metadata;
- revision history, drafts, audit actor identifiers, and update timestamps.

The preview is read-only. A write method is denied by provider scope resolution
and by the Platform enforcement point.

`TENANT_CONFIGURATION_READ`, `TENANT_CONFIGURATION_WRITE`, and `WORKFORCE_READ`
are retired and deny-all. They do not provide alternate configuration,
announcement, or workforce paths. A scope may return only with an exact
projection or command contract and separate release evidence.

Scope authorization is a dual gate. The requested and session-bound scope must
be `ACTIVE` in `prv_support_scope_catalog`, and Provider policy code, Gateway,
and the target-service PEP must independently allow the exact HTTP method and
canonical path. A catalog-only or code-only addition remains denied.

Customer-approved support activation is fail-closed outside local development
until an authoritative approval verifier is integrated. The local
`LOCAL_REFERENCE_ONLY` path requires both the canonical `local` environment and
an explicit fixture opt-in; it is E2E evidence, not customer approval.

## Bootstrap and Local Review Accounts

Migration Auth V99 converts the historical `admin@dwp.local` row from
`ADMIN + PROVIDER_ADMIN` to provider-only and revokes its existing sessions.
The separately seeded `provider.admin@dwp.local` remains the normal local
provider review account. `hyunwoo.park@sk.com` is the usable SKAX tenant
administrator and has no provider role. These local accounts are verification
fixtures, not a production identity-source contract.

Auth V105 revokes all existing active activation tokens and installs a trigger
that rejects every insert into `sys_account_activation_tokens`. Introducing a
verified customer-owned out-of-band delivery channel must not edit V105; it
requires a new forward migration that opens only the newly governed issuance
path. Until then, provider-origin tenant-administrator invitation issuance is
runtime fail-closed with `409 RESOURCE_CONFLICT`. Generated OpenAPI removes the
success `200` and declares only `409 AdministratorInvitationConflictError`
(`E1009`); its contract test also rejects activation token/path fields.

## Enforcement Evidence

| Boundary | Implementation |
| --- | --- |
| Shared role-plane policy | `RolePlaneBoundary`, `RoleDelegationPolicyService` |
| Login, issuance, rotation, elevation, JWT fail-closed | `AuthService`, `AuthSessionService`, `AuthSessionJwtValidator` |
| Provisioned tenant administrator postcondition | `AuthTenantProvisioningService` |
| Durable identity plane, cleanup, session revoke, policies, review cleanup, serialized triggers | Auth V99 through V104 |
| Explicit Auth/Gateway identity-plane contract; no role inference | `AuthService`, `AuthSessionVerifier`, `VerifiedIdentity` |
| No ambient provider data-plane access | `ProviderDataPlaneBoundaryFilter`, `ProviderAuthPlaneBoundaryFilter`, `DurableIdentityPlaneGuard` |
| No direct Gateway-owned handler or internal product-authority bypass | `ProductSurfaceForwardingGuardFilter`, `ProductAuthorizationOperationsSecurityConfig` |
| No provider-issued tenant-admin activation capability | `AuthTenantProvisioningService`, Auth V105 |
| Server-side JIT token, target, absolute/idle expiry, and exact scope validation | `ProviderSupportAccessService`, `ProviderSupportAccessPolicy`, `ProviderSupportSessionRepository` |
| One active target and login-session binding per operator | `ProviderControlPlaneService`, `ProviderSupportSessionRepository`, Provider V38/V41/V46 |
| Dual activation kill switch and revocation audit | `ProviderSupportActivationGate`, `ProviderSupportActivationService`, Provider V46 |
| Browser/internal support DTO separation | `ProviderSupportDtos`, `ProviderSupportAccessController`, `ProviderSupportSessionVerifier` |
| Redacted preview contract | `TenantExperiencePreviewController`, `TenantExperiencePreviewService` |

Provider migration traceability is explicit:

| Migration | Boundary evidence |
| --- | --- |
| Provider V39 | Retires `WORKFORCE_READ`, cancels open requests, revokes active sessions, and audits the policy action |
| Provider V40 | Preserves privileged-support audit evidence with extended-retention outbox records and automatic lifecycle audit |
| Provider V41 | Binds support sessions to the Auth `sid` family and revokes unbound historical active rows |
| Provider V42 | Enforces the `PROVIDER_*` operator-role namespace in the provider database |
| Provider V43 | Revokes requests and sessions immediately when any support scope retires |
| Provider V44 | Retires broad `TENANT_CONFIGURATION_READ/WRITE` support scopes |
| Provider V46 | Enforces exact Preview/ACTIVE/L1/customer-approved sessions, 15-minute idle expiry, tenant readiness, and the durable activation kill switch |

The PostgreSQL integration tests exercise migration from Auth V98, bootstrap
session revocation, direct/group/PIM/tenant-authority trigger rejection, preview
scope creation and retirement revocation, provider reviewer cleanup, provider
activation-capability revocation, auth-session binding, denial evidence
surviving business rollback, automatic lifecycle audit evidence, stale-session
expiry, atomic idle touch, kill-switch and tenant-state revocation, exact-scope
commit rejection, and the concurrent active-session unique invariant.

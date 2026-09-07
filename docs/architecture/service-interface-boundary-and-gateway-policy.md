# Service Interface Boundary and Gateway Policy

Status: Accepted
Last reviewed: 2026-08-20
Signed-workload contract addendum: 2026-09-04 (client registration, not source authorization activation)

## Decision

DWP uses an edge Gateway boundary plus explicit internal service contracts.
The policy is not "every backend-to-backend call must pass through the Gateway".
The policy is:

1. Browser, frontend application, and external client traffic enters through the Gateway.
2. Frontend runtime calls use shared API clients and `/api/**` Gateway routes only.
3. Gateway routes public API prefixes to owning services and injects trusted service identity.
4. Backend service-to-service HTTP is allowed only for documented `/internal/**` control-plane or source-owner verification contracts, or named gateway verification contracts.
5. Internal contracts require purpose-specific credentials such as `X-DWP-Provisioning-Token`, `X-DWP-Identity-Sync-Token`, support validation plus service identity, or an explicitly registered signed-workload profile. These alternatives are not interchangeable.
6. Internal contracts propagate `X-Correlation-ID`, W3C `traceparent`, and `tracestate` when a request context exists.
7. Backend services may depend on shared libraries and contract modules only. They must not import or build-time depend on sibling service implementation modules.
8. Cross-service database access is prohibited except the provider data-governance metadata scanner. That exception is read-only, uses metadata credentials, and scans schema/catalog metadata rather than business records.
9. External SaaS connectors are separate adapter contracts and must validate host allowlists or trusted provider hosts.

## Current Exceptions

| Exception | Status | Reason | Guard |
| --- | --- | --- | --- |
| Gateway `AuthSessionVerifier` -> Auth `/auth/me` | Allowed by policy | Edge session verification before Gateway forwards the request | Session cookie/context forwarding, timeout, trace context propagation, no `X-DWP-Service-Token` |
| Gateway `ProviderSupportSessionVerifier` -> Provider `/internal/provider/v1/support-access/resolve` | Allowed by policy | Support-session scope resolution for delegated support access | `X-DWP-Service-Token` plus `X-DWP-Support-Validation-Token`, trace context propagation |
| Provider provisioning -> Auth/Platform/People `/internal/provider/v1/**` | Allowed by policy | Tenant lifecycle orchestration across service-owned stores | `X-DWP-Provisioning-Token`, `OutboundHttpHeaders.propagateObservability` |
| Provider code catalog -> Platform `/internal/provider/v1/code-catalog/**` | Allowed by policy | Product code-contract catalog read model owned by Platform | `X-DWP-Provisioning-Token`, `OutboundHttpHeaders.propagateObservability` |
| Platform -> Auth `/internal/identity/v1/**` | Allowed by policy | Runtime app entitlement and saved-view subject validation | `X-DWP-Identity-Sync-Token`, `OutboundHttpHeaders.propagateObservability` |
| Approval -> Auth `/internal/identity/v1/tenants/{tenantId}/users**` | Allowed by policy | Active delegate validation and bounded directory search before approval delegation changes | `X-DWP-Identity-Sync-Token`, read-only GET, Resilience4j bulkhead/circuit breaker/idempotent retry, `OutboundHttpHeaders.propagateObservability` |
| People -> Auth `/internal/identity/v1/workforce-events` | Allowed by policy | Workforce identity projection into central identity | `X-DWP-Identity-Sync-Token`, `OutboundHttpHeaders.propagateObservability` |
| Platform Work -> Meeting `POST /internal/v1/meeting-followups/resolve` | Client registered; source promotion NO-GO | Read only owner-confirmed follow-up task terms | Dedicated `dwp1` assertion, exact body/action/subject/source binding, no retries or redirects, trace propagation; current Meeting authority and People eligibility remain separate release gates |
| Provider data-governance JDBC metadata scan | Allowed by policy | Provider control plane metadata inventory and lineage view | `connection.setReadOnly(true)`, metadata DB credentials, catalog SQL only |
| Microsoft Graph and Workday adapters | Not a DWP app-to-app exception | External enterprise connectors | Trusted host or tenant-configured host allowlist |

## Gap Assessment

The direct internal calls above were not accidental public API bypasses. They are implemented as internal control-plane or projection contracts with dedicated credentials.

The gap was governance, not the runtime call intent:

- the rules were spread across implementation and feature documents;
- direct HTTP client exceptions were not centrally allowlisted;
- frontend Gateway-only runtime calls were not enforced by an API boundary check;
- the provider metadata scan exception was not documented as the only accepted cross-service database access case.

This ADR closes that gap by defining the policy and tying it to automated checks.

## Service Interface Contract Manifest

`docs/architecture/service-interface-contracts.json` is the single source of truth for approved backend HTTP clients and cross-database metadata exceptions.

The manifest records each approved interface with an ID, classification, source service, target service, owning file, purpose, auth model, required implementation markers, and forbidden markers. The checker validates both directions:

- code cannot introduce a new `RestClient`, `WebClient`, or `java.net.http.HttpClient` without a manifest entry;
- manifest entries cannot point to missing files, unknown services, duplicate IDs, duplicate paths, or incomplete contract metadata;
- allowlisted clients must keep their required path, credential, and observability markers;
- `internal-http` contracts must use `/internal/**`, propagate observability headers, require a purpose-specific service token or the separately typed signed-workload profile below, and forbid Gateway `/api/**` calls;
- `gateway-verifier` contracts must originate from the Gateway and preserve W3C trace context;
- external connectors must keep their host validation markers and must not reuse DWP service-to-service credentials;
- cross-database exceptions must stay explicitly registered as metadata-only access and keep read-only scanner safeguards.

### Signed-workload source verification

The optional, closed `signedWorkload` object currently supports only `dwp1-hmac-sha256` on
`internal-http`. It records the existing same-service Java protocol and signer sources, exact
POST path, dedicated assertion header, issuer, audience, and a lifetime of at most 30 seconds.
The client, protocol, and signer must be distinct files within the source service's Java tree;
path traversal and symlink escapes are rejected. Missing metadata does not relax the existing
purpose-specific-token branch.

The checker verifies the protocol literals at actual Java declaration positions and executable
client/signer wiring: endpoint, posted and signed bytes, subject/source/action claims, exact expiry
argument, nonce, body digest, HMAC, dedicated key, no-redirect policy, and observability propagation.
Comments and string/text-block decoys cannot satisfy these executable checks. Registered signed
clients remain checked when their HTTP import is deleted. Gateway identities, legacy credentials,
and automatic retry annotations are forbidden in the declared sources.

This is a static architecture gate, not Java dataflow analysis or proof of cryptographic execution.
Owner HTTP/signature tests remain mandatory. Receiver replay rejection, current product entitlement,
identity plane, exact follow-up capability, report/candidate ACL, retention, source version and target
eligibility are separate runtime obligations. Registering the Work client neither creates an Auth or
People endpoint nor approves a role grant, candidate bundle, rollout flag, or source promotion.
Until those boundaries are approved and implemented, Meeting-to-Work source conversion stays NO-GO.

## Standards Alignment

| Reference architecture | DWP position | Current implementation |
| --- | --- | --- |
| NIST SP 800-207 Zero Trust | Do not trust a request because it is internal; verify each resource access. | Gateway verifies session identity, service routes inject service identity, internal contracts use purpose-specific tokens. |
| OWASP API Security Top 10 2023 | Prevent broken object/function/property authorization and unrestricted internal exposure. | Browser traffic is forced through `/api/**`; `/internal/**` is not exposed to frontend clients; sensitive backend HTTP clients are allowlisted. |
| OpenTelemetry / W3C Trace Context | Preserve causal trace context across service hops. | Gateway creates `traceparent`; allowlisted service clients propagate `X-Correlation-ID`, `traceparent`, and `tracestate`. |
| Spring Cloud Gateway | Centralize edge routing, identity enrichment, CSRF, service token injection, and future distributed rate limiting. | Gateway owns `/api/**` routing and service identity filters. Redis-backed request rate limiting is a production deployment decision, not enabled implicitly. |
| Service mesh / Istio | Use workload identity and mTLS for east-west transport encryption when deployed on Kubernetes or mesh-capable infrastructure. | Current local architecture uses application-level tokens; production mTLS/workload identity remains a deployment gate. |

## Enforcement

Backend:

- `scripts/check-service-boundaries.py` blocks sibling service imports and sibling service Gradle dependencies.
- The same script loads `docs/architecture/service-interface-contracts.json` and blocks new backend `RestClient`, `WebClient`, or `java.net.http.HttpClient` usage unless the source file is registered there.
- The manifest itself is validated for duplicate IDs, duplicate paths, unknown services, missing files, missing required fields, invalid interface types, and interface-type-specific security rules.
- Allowlisted internal clients must keep their required path and credential markers.
- Allowlisted internal clients must propagate request-scoped correlation and W3C trace context. Servlet services use `OutboundHttpHeaders.propagateObservability`; Gateway WebClient verifiers copy `traceparent` and `tracestate`.
- New cross-service database references fail unless explicitly registered as an accepted metadata exception in the manifest.

Frontend:

- `scripts/check-api-boundaries.mjs` blocks direct `fetch`, local Axios instances, service-port URLs, `/internal/**` calls, and literal `axiosInstance` calls that do not start with `/api/`.
- `architecture:check`, `lint`, and `build` run the API boundary check.

## Change Rule

Any new DWP service-to-service interface must be added through one of these paths:

1. Gateway public API route for browser or external client access.
2. Internal HTTP contract with an owning service, `/internal/**` path, purpose-specific credential or explicit signed-workload profile, timeout, trace propagation, and contract entry.
3. Domain event/outbox contract for asynchronous projection.
4. External connector adapter with trusted host or tenant allowlist validation.

A backend interface change is not complete until the manifest entry, owning implementation, and automated boundary check are updated together.

Direct use of another service's public `/v1/**` API, Gateway `/api/**` API from a backend service, or another service's database is rejected by policy.

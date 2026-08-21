# Cryptographic Key and Secret Management Policy

- Status: Accepted policy, implementation planned
- Decision date: 2026-08-20
- Delivery item: `R3-02-KMS`
- Scope: `dwp-backend`, `dwp_agent`, object storage, connectors and deployment configuration

## 1. Decision

DWP uses one provider-independent key-management contract and environment-specific adapters.
Configuration files may contain provider type, endpoint, key identifier, alias, version and mounted
file path. They must never contain plaintext production key material, passwords, client secrets or
private keys.

The environment contract is:

| Environment | Allowed provider | Key material source | Required behavior |
| --- | --- | --- | --- |
| `local` | `local-inline` or `local-file` | Git에서 제외된 `application-local.yml`, `.env.local` 또는 runtime directory | 개발용 평문 키·토큰을 허용하되 Local profile 밖에서는 거부한다. |
| `dev` | Managed KMS and Secret Manager sandbox | Workload identity or secret volume | `local-file` and plaintext environment key material are rejected. |
| `qa` | Managed KMS and Secret Manager non-production account | Workload identity or secret volume | Production-equivalent rotation, denial and recovery tests are mandatory. |
| `prod` | Approved KMS/HSM and Secret Manager | Workload identity only | No local fallback; unavailable or unauthorized key operations fail closed. |

Local development is an explicit exception. Backend services may keep all development-only keys,
tokens and passwords as plaintext in an ignored `application-local.yml`; Agent may use an ignored
`.env.local`. Binary keys or certificates can instead use `.dev-runtime/keys/` inside each runtime
repository. A service owns only its own subtree:

```text
.dev-runtime/keys/
  <service>/
    <purpose>/
      <version>.key
```

These files are developer-machine artifacts, use permission `0600` where supported and are excluded
from Git. Stable local test values are permitted so developers can reproduce encrypted flows without
external infrastructure. They must not protect shared `dev`, `qa`, `prod` or real customer data and
are not copied into images, test reports, logs or backups.

An ignored local file may provide a plaintext value directly:

```yaml
dwp:
  security:
    key-management:
      provider: local-inline
      active-version: local-v1
      inline-key: "<local-development-key>"
```

Tracked `application-dev.yml`, `application-qa.yml` and `application-prod.yml` files may define only
non-secret routing metadata. Their illustrative future contract is:

```yaml
dwp:
  security:
    key-management:
      provider: ${DWP_KEY_PROVIDER}
      key-reference: ${DWP_KEY_REFERENCE}
      active-version: ${DWP_KEY_ACTIVE_VERSION}
      local-root: ${DWP_LOCAL_KEY_ROOT:.dev-runtime/keys}
      fail-closed: true
```

`key-reference` is a KMS key ID, alias, URI or mounted secret name, not key material. Deployment
manifests provide the actual reference and workload identity. This restriction applies to shared
`dev`, `qa` and `prod`; the ignored `local` profile keeps the plaintext exception above.

## 2. Key hierarchy and isolation

Keys are separated by environment, service and purpose. Encryption, signing, HMAC, object-storage
SSE and connector credentials must not reuse the same key material.

The default hierarchy is:

1. An environment-specific KMS/HSM root of trust.
2. A service and purpose scoped key-encryption key or alias.
3. Short-lived data-encryption keys for envelope encryption.
4. A versioned ciphertext record containing algorithm, key reference, key version, wrapped data key,
   nonce and schema version.

Authenticated encryption uses AES-256-GCM with a unique 96-bit nonce. AAD binds ciphertext to
non-secret context such as environment, service, purpose, tenant ID, resource type, resource ID and
schema version. Context values can appear in provider audit logs, so they must not contain names,
email addresses, document titles, prompts or other personal data.

Shared platform keys are permitted only for data with the same classification and access boundary.
Restricted tenants or regulated workloads can receive dedicated tenant keys without changing the
application contract.

## 3. Keys and secrets are different controls

KMS manages cryptographic keys and envelope encryption. Secret Manager manages database passwords,
OAuth client secrets, API tokens, certificates and connector credentials. DWP stores only secret
references in relational tables and configuration.

Cloud-provider credentials must use workload identity, instance identity or an equivalent short-lived
mechanism. Static cloud access keys in YML, environment files or CI variables are prohibited.

## 4. Lifecycle policy

Every managed key has an owner, purpose, classification, environment, creation time, active version,
rotation policy, recovery owner and destruction approval record.

- New writes use only the active version. Reads may use explicitly registered previous versions.
- The initial data-key rotation target is 90 days. The Security owner may shorten it by classification.
- KMS root or key-encryption-key rotation follows provider capability and an approved annual maximum.
- Suspected compromise triggers immediate disablement, replacement, impact analysis and controlled
  rewrap or re-encryption. Scheduled rotation is not used as an incident response substitute.
- Old versions remain decrypt-only until all referenced data is rewrapped, expired or legally disposed.
- Destruction requires dual approval, dependency inventory, recovery proof and a provider waiting
  period. A key with live ciphertext references cannot be destroyed.
- Legal hold prevents ciphertext and required decryption keys from expiring independently.

Rotation jobs are idempotent and resumable. They record counts, failures and checkpoints without
logging plaintext or ciphertext bodies.

## 5. Access and separation of duties

Runtime identities receive only the cryptographic operations and key resources required by their
service and purpose. They cannot create, change policy, disable or delete keys.

Key administrators manage lifecycle but cannot read application data. Security auditors can inspect
policy and usage events but cannot decrypt. Break-glass access is time-bound, approved, alerted and
reviewed after use. Provider administrators, tenant administrators and application administrators do
not receive direct KMS access through the DWP UI.

## 6. Availability, recovery and failure behavior

- `local` may use plaintext inline values or local files without KMS so all product paths remain
  developable offline.
- `dev`, `qa` and `prod` never fall back to a plaintext inline value, local file or default key.
- Startup validates environment/profile agreement, provider type, key reference, identity and a
  non-destructive cryptographic probe. Unsafe configuration blocks readiness.
- Runtime KMS calls use bounded timeout, circuit breaking and retry only for idempotent operations.
- Plaintext data keys may be cached in process memory for a short bounded period; they are never
  persisted or logged and are cleared on shutdown where the runtime permits.
- A KMS outage blocks new protected writes. Reads fail with a controlled unavailable response rather
  than returning unprotected or partially decrypted data.
- Recovery design covers regional replication or provider-supported multi-region keys, key metadata
  backup, restore tests and documented RTO/RPO. Exporting raw root keys is prohibited unless an
  approved HSM escrow model explicitly requires it.

## 7. Audit and monitoring

Audit events record service identity, operation, key reference or alias, key version, purpose,
non-sensitive context, result, latency, correlation ID and environment. Plaintext keys, data keys,
secrets and sensitive encryption context are never recorded.

Alerts cover authorization denial spikes, disabled or pending-deletion keys, unexpected principals,
cross-environment access, rotation backlog, decrypt failures, KMS latency/error budgets and attempts
to use retired versions. Provider audit logs are exported to the approved SIEM and protected by the
same immutable-retention policy as other security evidence.

## 8. Current implementation assessment

| Area | Current state | Gap |
| --- | --- | --- |
| Local supervisor | `scripts/devctl.py` injects plaintext local DB passwords, service tokens, encryption and audit keys | Keep this explicit local exception; add typed provider metadata and leakage tests. |
| Agent payload | AES-256-GCM, versioned active and previous keys | Key material is environment-injected; common provider adapter is absent. |
| Productivity connector | AES-256-GCM with a configured data key | Direct key configuration must move behind `KeyProvider`. |
| Platform and Messaging S3 | Optional SSE-KMS key ID support | Environment enforcement, identity and recovery evidence remain external. |
| Production readiness | Production secret and unsafe-default checks exist | `dev` and `qa` provider/profile policy and common key probes are absent. |
| Restricted fields and exports | Fail-closed or disabled behind delivery gates | Envelope encryption, object storage and migration workers remain planned. |

## 9. Implementation tracking

`R3-02-KMS`의 미종결 작업, 고객별 선택안과 종료 증거는
[Customer Policy and Release Gate Register](../delivery/customer-policy-and-release-gate-register.md)의
`G-01`, `G-11`, `G-12`, `G-13`에서만 관리한다. 이 ADR은 Key Provider 경계와 금지 규칙을
정의하며 별도 TODO 상태를 유지하지 않는다.

## 10. References

- [NIST SP 800-57 Part 1 Rev. 5](https://csrc.nist.gov/pubs/sp/800/57/pt1/r5/final)
- [Spring Boot externalized configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- [AWS KMS IAM policy best practices](https://docs.aws.amazon.com/kms/latest/developerguide/iam-policies-best-practices.html)
- [AWS KMS encryption context](https://docs.aws.amazon.com/kms/latest/developerguide/encrypt_context.html)
- [OWASP Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html)

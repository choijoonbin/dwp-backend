# Work Hub source actions and personal work extensions

The Work UI invokes each source's own commands. Personal task completion does not approve,
resolve, or revoke a linked source. A service requester response does not resolve its ticket.

## Requester information response

`POST /api/platform/v1/services/requests/{requestId}/information-response`

```json
{
  "values": { "softwareName": "Design tools", "businessReason": "Review work" },
  "message": "Requested information is supplied for review.",
  "version": 3,
  "idempotencyKey": "a4c13eac-5df9-49ec-84c3-6918e72d025b"
}
```

The request must belong to the authenticated tenant and requester, still be
`AWAITING_REQUESTER`, and match its optimistic version. The entire submitted values object
is validated against the request's snapshotted schema, including required fields and unknown
field rejection. The trimmed response message has 10–2,000 characters. Acceptance changes the
source to `IN_PROGRESS`, increments its version, and persists a `REQUESTER_RESPONDED` timeline
entry, receipt and audit in the same transaction. Same-key identical retries return current
source evidence without repeating the transition or audit; different content conflicts.

The canonical permission is `APP.EMPLOYEE_SERVICES:VIEW` plus `UPDATE`; `APP.SERVICES`
is not an alias. Source PEP applies even when the older Services v4 flag is off. Enforced
rollouts also require the exact source SELF context/scope, current non-expired decision and
matching expected decision revision. Provider/support identities cannot submit this command.
Its private in-process authority marker is set only after that verification; it preserves the
immutable Platform v1 PEP registry for all other requests.

The additive authorization registration is bundle v6 action
`route.services.work.request-information-response.action`, capability
`services.request.respond`. Immutable v1–v5 checksums are unchanged.

## Personal tasks

Create/update input now accepts ordered `checklist` (maximum 100 entries, each with UUID
`itemId`, nonblank `title` of at most 500 characters and boolean `completed`) and
`sourceReferences` (maximum 10 unique source identities). A parent task version owns both
arrays atomically. Checking every item does not implicitly complete the parent task.

On update, omitted/null arrays retain their stored value; an empty array clears it. Do not
combine `sourceReferences` with the earlier singular `sourceReference` or explicit
`clearSourceReference` mode. The first source is mirrored in the singular response `source`
for old clients; `sources` contains the ordered list. Old input constructors, status endpoints
and pre-extension command fingerprints remain compatible.

Each new source reference uses the existing source resolver. Read responses resolve current
source permission again. Remote bookmarks remain `REFERENCE_ONLY`; they do not assert access
to source metadata. Revoked or deleted sources expose no cached title, route or identity. An
omitted list preserves a private reference even after access changes; removing it is explicit.

`POST /api/platform/v1/workspace/work-hub/personal-tasks/{taskId}/delete` takes `{ "version": n }`
and the existing UUID `Idempotency-Key` header. It requires owned Work VIEW/UPDATE authority,
soft-deletes the task, increments its version and removes its selections from existing personal
day plans, rebuilding contiguous positions and incrementing each affected plan's version. Audit,
append-only task timeline and Calendar records are retained. Task lookup/listing and native-source
resolution exclude deleted tasks; replaying an older task mutation cannot resurrect them. Delete
itself is idempotent. Audit failure rolls back the task and plan changes together.

## Deployment and verification

Apply Flyway V227 and V228 through Platform startup. Gateway copies the generated latest bundle
at build time; Auth imports the v6 artifact as DRAFT. Restart those development services after
building. A new DRAFT does not replace the active authorization bundle.

Local verification on 2026-09-07 applied V227/V228 successfully and restarted Platform, Auth and
Gateway. Actual personal list returned 200, missing-task delete returned 404, and read-only delete
returned 403. Real PostgreSQL tests cover concurrent service command replay, schema/ownership
rejection, transactional audit rollback, checklist/source persistence, deleted-task replay, plan
version changes and old command fingerprint compatibility.

The local tenant 1 Provider evaluation is Services rollout **111**, while the active Auth bundle
remains **v3** and v6 is DRAFT. Current Auth environment has neither
`DWP_PRODUCT_AUTHORIZATION_PROVIDER_APPROVAL_TOKEN` nor
`DWP_PRODUCT_AUTHORIZATION_PLATFORM_ACTIVATION_TOKEN` configured. Enabling the new action therefore
requires the existing governed release process: an exact v6 checksum/change reference, distinct
requester/provider approver, then a distinct Platform release actor using current active revision
CAS. The local bootstrap runner intentionally targets v3 only. No release credentials, approval
records, rollout settings or active pointer were fabricated or rewritten for this feature.

v6 checksum: `e966b7340da431481bb9f577724224645be45ac50e169c75da1a521f2cde925a`.

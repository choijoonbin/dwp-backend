# HRIS G7 Control backend authority

Date: 2026-10-12

This package is the Control-owned bootstrap required before PER, TIM, or HRM
module implementation is allowed to depend on shared authorization and
cross-service contracts. It does not implement module business tables,
migrations, or employee-change workflows.

## Pinned decisions

- Product Authorization v35 is one append-only wave. Its generated checksum is
  `38ef33070a33b5da9a7825c764bd24daacd24c8ca4008f3a926727321d3d098d`.
- The five G7 workbench reads are governed `PAGE` routes, not duplicate
  `PAGE`/`DATA` contracts. Their UI routes are
  `/hr/operations/performance/review-operations`,
  `/hr/operations/time/clock-ingestion`,
  `/hr/operations/time/exceptions`,
  `/hr/operations/people/employee-change-requests`, and
  `/hr/settings/reference-data`.
- `route.hcm.operations.time-clock-event-void.action` is a step-up command
  bound to `eventId`, target type `CLOCK_EVENT`, audience `dwp-time-server`,
  and command header `X-DWP-Expected-Object-Version`.
- The next unused cross-module identifier after the sealed XCON-001 through
  XCON-021 sequence is XCON-022. It is assigned to
  `EmployeeChangeApplied.v1`; its canonical schema and generated Java record
  carry the standard event/tenant/correlation envelope and the six G2 payload
  fields.
- G2 `SVC-PEP-HRM-001` remains the exact Approval-to-People callback at
  `POST /internal/hris-people/v1/employee-change-requests/{requestPublicId}/approval-decisions`.
- PDX-010 `ApprovalRequestV1` has no `DRAFT` state. Therefore the successor
  People-to-Approval binding is the atomic `SVC-PEP-APR-001` create-and-submit
  call at `POST /internal/hris-approval/v1/approval-requests`; a separate
  submit endpoint is forbidden because it would introduce orphanable remote
  drafts.

The machine-readable Approval bridge decision is
`contracts/in-process/hris/approval-employee-change-bridge.v1.json`.

## Security boundary

Both Approval directions terminate at an isolated dispatcher before ordinary
browser/Gateway authorization. The bootstrap admits only typed evidence already
authenticated by a future transport adapter. It requires the exact binding,
method and path; the exact caller workload and audience; mTLS/SPIFFE or an
audience-pinned client credential JWT; signed tenant, current owner grant and
target-tenant match; exact purpose and body digest; TLS 1.3; a proof lifetime of
at most 30 seconds; and single-use proof/nonce reservation. Authorization,
Cookie, Origin, Referer, browser client-hint, end-user identity, and forwarded
user headers fail closed.

The in-memory replay and idempotency stores are explicitly bootstrap/test
adapters. Durable owner-side ledgers and concrete token parsing remain module
token work; their absence cannot silently enable a route.

## Deliberately deferred

- HRM employee-change business package and database migrations.
- Durable Approval and People inbox/outbox adapters.
- Concrete mTLS/JWT token wire format and key rotation.
- The HRM-owned `CommandReceiptV1` response implementation for the decision
  callback.

These deferrals preserve the module boundary while leaving exact provider and
consumer contracts available for the subsequent HRM implementation packet.

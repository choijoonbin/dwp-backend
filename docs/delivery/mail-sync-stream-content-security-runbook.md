# Mail sync, event stream, and attachment security runbook

## Runtime gates

- Apply `V370__operate_mail_sync_stream_and_content_security.sql` before enabling the sync,
  subscription, webhook, or event-stream workers. Apply
  `V371__reconcile_mail_provider_delivery_and_purge.sql` before enabling external delivery or
  provider purge, and `V372__govern_mail_proposal_provenance_and_bulk_intents.sql` before enabling
  Agent-produced proposals or browser bulk mutations.
- External provider callbacks remain fail-closed until a provider-owned
  `MailWebhookVerificationPort` is deployed. Callback tokens and client-state values must never
  be written to logs or database columns; only SHA-256 receipts are accepted by the ingress.
- In production, setting `DWP_MAIL_ATTACHMENTS_ENABLED=true` requires the trusted scanner owner.
  Set `DWP_MAIL_TRUSTED_SCANNER_ENABLED=true`, an HTTPS origin in
  `DWP_MAIL_TRUSTED_SCANNER_BASE_URL`, and a secret-injected
  `DWP_MAIL_TRUSTED_SCANNER_SERVICE_TOKEN`.
- Treat the scanner hostname as an operator-managed allowlist entry: pin it in egress policy and
  private DNS, prohibit redirects, and do not point the setting at user-controlled or general
  proxy hosts. The application rejects non-HTTPS origins, URL userinfo, queries, and fragments.
- The scanner must expose `POST /v1/scan` and `GET /health/ready`. Scan responses are bounded JSON
  objects with `verdict` (`CLEAN` or `REJECTED`) and a non-sensitive evidence code.
- Microsoft Graph and Gmail adapters are disabled by default. Enabling one also requires an exact
  HTTPS allowlist, the remote token-broker owner, provider consent, and a fresh readiness receipt.
  The application never receives or persists refresh tokens; it leases a short-lived access token
  using the opaque approved Secret Store reference and clears the leased token after use.
- Keep SSE disabled at the Gateway until the central product-authorization bundle publishes the
  exact owner routes `route.mail.work.events.data` and
  `route.mail.work.events-catch-up.data`. Platform owns both bindings already, but a missing or
  stale central decision remains fail-closed; do not add a route bypass for rollout convenience.

## Durable sync operations

`POST /v1/admin/mail/connections/{connectionId}/sync` returns HTTP 202 and an `ACCEPTED`
operation. Provider I/O starts only after `MailSyncJobWorker` acquires a lease. Monitor:

- `dwp.mail.sync.jobs{outcome=...}` and `dwp.mail.sync.jobs.duration`
- `mail_sync_jobs` rows in `RETRY_WAIT` or `DEAD_LETTER`
- `cursor_reset_receipt` for `SYNC_CURSOR_RESET_REQUIRED` (it never contains the provider cursor)

An expired lease is recoverable until `maximum_attempts` is reached. A provider-classified cursor
reset locks the account, advances its cursor generation, installs the connector's opaque full-sync
seed, records hash-only prior/reset evidence, and enqueues exactly one `RECOVERY` job. No messages
are deleted. A repeated reset from that recovery job is dead-lettered for operator review; never
paste provider cursors into SQL.

Each lease processes a bounded number of provider pages. If that budget is reached while the
provider reports more data, the worker records `CONTINUATION_SCHEDULED`, returns the same durable
job to `RETRY_WAIT` without consuming a failure attempt, and resumes from the cursor generation
committed by the last successful page.

## Provider subscriptions and callbacks

`mail_provider_subscriptions` is the renewal/expiry ledger. Store only hashes for provider
subscription references and client state. Connector implementations own subscription renewal and
webhook signature/token validation. Verified callback receipts enqueue sync work only; they never
materialize messages directly.

Set `DWP_MAIL_SUBSCRIPTIONS_ENABLED=true` only with an HTTPS public origin in
`DWP_MAIL_SUBSCRIPTION_CALLBACK_BASE_URL` and a configured provider/token-broker owner. The worker
creates one account-bound PENDING row per active Graph/Gmail account, claims it with a lease, calls
the provider outside the database transaction, and persists only SHA-256 subscription/client-state
receipts. Monitor `dwp.mail.subscriptions{outcome=...}` and terminal `FAILED` rows whose
`next_renewal_attempt_at` is null. The provider account reference is used only in the connector
request and is never copied into the subscription ledger. Graph subscriptions use a random client
state whose SHA-256 receipt is stored; Gmail watch renewal stores only the provider subscription
and push-binding hashes.

The only callback route admitted without the DWP service token is an exact `POST` to
`/internal/v1/mail/webhooks/{provider}` after the dedicated Mail filter succeeds. Notifications
must have a non-empty, size-capped body and a provider verifier receipt. Microsoft Graph ownership
validation is the sole empty-body exception: `provider=microsoft_graph`, exactly one bounded
`validationToken` query value, no control characters or additional query fields, and no request
body. It returns the decoded token verbatim as `text/plain`; never log or persist it.

## Delivery and reconciliation

`mail_delivery_outbox.delivery_status` deliberately separates three truths:

- `DELIVERED`: deterministic recipient delivery; currently emitted only by DWP Sandbox.
- `PROVIDER_ACCEPTED`: Graph/Gmail accepted the submission, but recipient delivery is unconfirmed.
- `RESULT_UNKNOWN`: submission may have reached the provider. Automatic retry is prohibited to
  avoid duplicate delivery; an operator must reconcile provider evidence.

Graph HTTP 202 and a successful Gmail send response are provider acceptance, not recipient
delivery. Rate limits retain the provider retry instant. A connection/readiness failure before a
request can be submitted is retryable; a timeout or transport loss after submission becomes
`RESULT_UNKNOWN`. Monitor queue age and `dwp.mail.delivery{outcome=...}` without adding tenant,
user, address, subject, provider body, token, or opaque reference metric tags.

## Legal hold and provider purge

The purge worker revalidates execution authority and legal hold, stages provider references and a
hash-only hold receipt in the same transaction, and then records local deletion and provider
deletion independently. Only `PURGED` is complete. `NOT_SUPPORTED`,
`RECONCILIATION_REQUIRED`, and `FAILED` remain non-complete evidence and must not publish a purge
completion result. Successful provider receipts erase staged raw references; ambiguous receipts
retain them for restricted operator reconciliation. Never manually change a provider command to
`PURGED` without provider evidence.

## Event-stream recovery

Clients connect to `GET /v1/mail/events?after=<sequence>` and persist each SSE `id`. Reconnect with
either `after` or `Last-Event-ID`. The stream emits only sequence, event type, thread ID, and event
time after applying the same personal/shared-mailbox read boundary as thread queries. If SSE is
unavailable, poll `GET /v1/mail/events/catch-up?after=<sequence>` until `hasMore=false`.

Monitor `dwp.mail.events.stream` and `dwp.mail.events.catchup.size`. A 429 indicates the configured
client cap; use polling fallback or increase the cap only after checking database query load.

## Attachment scanner incidents

The readiness probe is exposed under the application health endpoint as
`mailAttachmentScanner`. Scanner timeout, malformed responses, redirects, non-HTTPS setup, and
oversized bodies all fail closed. Monitor `dwp.mail.attachment.scan` and
`dwp.mail.attachment.scan.duration`. Never include the service token, callback token, file bytes,
file name, subject, recipient list, or message body in incident evidence.

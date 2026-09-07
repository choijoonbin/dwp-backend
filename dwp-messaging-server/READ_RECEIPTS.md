# Messaging Read Receipts

All routes use `/api/messaging/v1` publicly and `/v1` at the owner service.
Responses use the existing `ApiResponse` envelope. New responses are `Cache-Control: no-store`.
Identity is exclusively the gateway-verified tenant and user, never request-supplied identity.

## Personal Privacy

`GET /privacy-preferences` returns `data: {readReceiptsEnabled: boolean, version: number}`.
The default is `{readReceiptsEnabled: true, version: 0}` without creating a row.

`PUT /privacy-preferences` accepts those same two required fields. Version zero creates the
preference; subsequent saves compare the supplied version and increment it. Concurrent or stale
saves return `409`. Preferences belong to one tenant/user; there is no administrator override.
Sharing is unilateral: disabling your sharing does not prevent you viewing others' shared receipts.

## Observations

`POST /conversations/{conversationId}/read-receipts`

- Body: `{messageIds: UUID[]}`, 1-50 unique IDs.
- Response: `data: {observedMessageIds: UUID[]}` in request order, including already observed IDs.
- Every ID must be an undeleted `USER` message visible to the active caller under the current
  conversation membership/history policy. Any inaccessible ID returns generic `404` with no writes.
- Observations are idempotent on `(tenant_id, user_id, message_id)`. They do not advance the unread
  cursor and produce no public event. No client timestamp or asserted recipient identity is accepted.
- The frontend reports actual focused, sufficiently visible message rows, including opened replies.
  The server records that report; it cannot prove human attention. Old cursors are never backfilled.
- Observations persist while sharing is disabled. Re-enabling reveals past observations.

## Sender Queries

`GET /conversations/{conversationId}/read-receipts?messageIds=uuid,uuid`
returns `data: ReceiptSummary[]` in request order. Supply 1-50 unique IDs.

`GET /conversations/{conversationId}/messages/{messageId}/receipts`
returns `data: ReceiptSummary` using the same query and rules.

```typescript
type ReceiptSummary = {
  messageId: string;
  recipients: {
    userId: number;
    personPublicId: string | null;
    displayName: string;
    status: 'READ' | 'UNREAD' | 'UNAVAILABLE';
  }[];
  readCount: number;
  unreadCount: number;
  unavailableCount: number;
};
```

Only the author, with current active membership and history access, may query their undeleted
`USER` messages. Moderation/ownership is not an author override. Any inaccessible batch ID causes
a generic `404`, never a partial response or an indication of which ID exists.

Recipients exclude the sender and must have active membership and an active tenant people snapshot,
history access to the message, and a current membership term starting no later than message creation.
Later full-history joiners and revoked/rejoined members are excluded from old-message counts.
An empty eligible audience returns an empty list and zero counts, not a synthetic unread state.

- `READ`: an explicit message observation exists and that recipient currently shares receipts.
- `UNREAD`: no observation exists; display "Not confirmed", not an assertion of unread content.
- `UNAVAILABLE`: that recipient does not share, regardless of stored observations.

There is no `readAt`. Conversation cursor timestamps are not per-message read times.
The batch executes one SQL statement for a consistent authorization/eligibility/consent snapshot.

## Existing Members and Events

`MemberSummary` adds `readReceiptVisibility: 'SHARED' | 'PRIVATE'`. Private non-self members return
`lastReadMessageId: null`, `lastReadSequence: 0`, `lastReadAt: null`. Self remains `SHARED` with the real
cursor. Treat missing visibility as private. Never derive per-message receipt states from these
global cursors: advancing past a thread reply does not establish that the reply was opened.

Keep the 15-second batch polling interval and refresh after relevant existing conversation/message/
membership events. No new public receipt or privacy event is emitted: the current SSE envelope
identifies its actor, and receipt observation/privacy mutation must not disclose private activity.
Opt-out is reflected by subsequent reads; a server cannot retract data already delivered to clients.

`messaging.read-cursor.updated` remains self-only. `messaging.privacy-preferences.updated` is also
self-only and carries only `{version}` as its payload. Recorder, durable replay/live eligibility,
and the V16 database constraint enforce the actor-only audience. Redis carries only wake-up hints;
gateway SSE forwards the owner-filtered stream, not database rows or private actor payloads directly.

Messaging's owner filter permits `APP.MESSAGING:VIEW` users to save their own preference and report
observations; message-write permission is not required. Other command permissions are unchanged.
Gateway source review found no shared change necessary: `GeneratedProductRouteCatalog` treats
Messaging as incremental and unmatched siblings as `UNGOVERNED`; `ProductSurfaceDecisionContextFilter`
passes those routes through. `AuthSessionVerifier` fetches the `APP.MESSAGING` permission prefix and
`VerifiedIdentityFilter` forwards permissions unchanged. Normal session and CSRF checks still apply.

## Verification

Apply the new Messaging-owned V16 migration. No previous migration or other service is changed.
Run `:dwp-messaging-server:test` with `DWP_MESSAGING_INTEGRATION_DB_URL`,
`DWP_MESSAGING_INTEGRATION_DB_USERNAME`, and `DWP_MESSAGING_INTEGRATION_DB_PASSWORD` pointing to a fresh
PostgreSQL test database. Tests cover observations versus cursors/replies, privacy flips, self cursor
preservation, tenant/author/history/membership boundaries, idempotent all-or-nothing writes,
concurrent optimistic versions, HTTP contracts, and private event boundaries.

# Messenger Home Shared Assets

## Public Contract

`GET /api/messaging/v1/home/shared-assets?limit=6` forwards to the owner service
`GET /v1/home/shared-assets`. Requires the existing trusted Gateway identity and
`APP.MESSAGING:VIEW`; callers cannot select another tenant or user.

The ordinary `ApiResponse` envelope contains:

```typescript
{
  generatedAt: string;
  items: Array<{
    id: string;
    kind: 'FILE' | 'LINK';
    conversationId: string;
    conversationName: string;
    messageId: string;
    sharedAt: string;
    senderName: string;
    title: string;
    attachmentId: string | null;
    url: string | null;
    contentType: string | null;
    sizeBytes: number | null;
  }>;
}
```

`limit` must be 1 through 20, default 6. Results are ordered by actual message
creation time descending with a deterministic ID tie-breaker. Duplicate URLs
within a message are folded. Link candidates are bounded to the latest 100
visible link-bearing messages, at most 20 URLs per message, each at most 2048
characters. This is a recent Home preview, not exhaustive document search.
The existing lower-body trigram search index supports the link predicate.

`generatedAt` is query-generation time, not a claim about provider sync health.
Likewise Home `people.presenceState` is the existing directory presence snapshot,
not evidence of an active Messenger socket or a measured connection heartbeat.

## Visibility And Navigation

- Only active conversations, current active memberships and active directory
  viewers are considered. Message sequence must satisfy the viewer's history
  start. Deleted and system messages never contribute shared assets.
- Files must be attached to that visible message and have `CLEAN` status.
  Quarantined, rejected, scanning, expired and unattached uploads are excluded.
- FILE entries expose metadata and an opaque attachment ID, never storage keys,
  presigned URLs, content bytes, scan errors, upload tokens or download grants.
- LINK entries are parsed from actual message text without network fetches.
  Only HTTP(S) URIs with a valid host and without embedded credentials are
  accepted. No external preview scraping or AI-generated titles are performed.
- Opening the source conversation is the default Home action. Direct file
  downloads must still use the existing one-use attachment grant endpoint.
  The attachment repository now rechecks active conversation/person, current
  membership, message deletion and history boundaries both before granting and
  atomically when consuming the grant. Revocation cannot be bypassed by a grant
  issued earlier.
- Responses are `Cache-Control: no-store`. Frontend query state must also be
  scoped to the authenticated tenant/user and invalidated on session changes.

## Home Metric Corrections

Home unread conversation counts now ignore deleted messages, matching Inbox.
Archived conversations no longer contribute unread mentions or saved-item
counts. Saved-list pagination uses the same active-conversation constraint.
Saved tombstones remain available for the user's existing unsave workflow.

No tables or migrations were added. Applied Messaging V16 is unchanged.
Shared Gateway/OpenAPI artifacts are deliberately not regenerated in this owner
change; the integration owner should include this endpoint in the ordinary
contract snapshot regeneration before a full repository build.

## Verification

`MessagingHomeAssetPostgresIntegrationTest` covers actual PostgreSQL visibility,
sorting, safe file states and download-grant revocation. Home metrics have a
separate PostgreSQL regression. Service, URI parser and HTTP tests exercise
bounded input, deterministic IDs, identity/permission denial and no-store output.
All run through the existing `DWP_MESSAGING_INTEGRATION_DB_URL` test convention.

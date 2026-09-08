# U07 personal record bookmarks — implementation evidence

Scope: the record-library favorite control and favorite-only history tab. This is
personal metadata, not a new sharing permission, review workflow, retention
policy, recording capability or AI readiness claim.

## Public service contract

All paths below are Meeting owner-service paths; the existing public Gateway
Meeting prefix remains unchanged. The Gateway and generated OpenAPI files were
not edited in this change.

- `GET /v1/history/bookmarks?meetingIds=<uuid>&meetingIds=<uuid>` accepts 1–100
  distinct IDs, in current-page display order. The standard `ApiResponse.data`
  contains `{items:[{meetingId,favorite,version,updatedAt}]}` in the same order.
  Every ID must currently be accessible to the verified actor and have lifecycle
  `ENDED` or `CANCELLED`. One inaccessible ID rejects the whole batch with 404;
  no partial response is returned. Unsaved state is `false / 0 / null`, with no
  database write on GET. Invalid, duplicate or oversized batches return 400.
- `PUT /v1/meetings/{meetingId}/bookmark` requires `Idempotency-Key` and exactly
  `{favorite:boolean,expectedVersion:nonnegative-integer}`. It returns one
  bookmark state. Actor or tenant fields are not accepted in this command.
  Every newly accepted command, including a no-op boolean, increments version
  and sets `updatedAt`. A stale version or changed payload/target under the same
  key returns 409.
- Existing `GET /v1/history?page=0&pageSize=30` is unchanged by default. Optional
  `favoriteOnly=true` adds an actor/tenant-bound favorite predicate to both the
  paged query and total count. The public service transaction uses repeatable
  read so page and count share one database snapshot. No history DTO fields were
  removed or renamed by this work.

All favorite operations require `APP.MEETINGS:VIEW`. The Meeting SecurityFilter
has a narrowly matched personal PUT exception, not a broad VIEW mutation rule.
Provider-support role, SUPPORT mode, support-session and actor-tenant headers
are rejected for bookmark endpoints and favorite-only history. Personal
responses are `private, no-store`, `no-cache`, `no-referrer`.

## Storage and transaction design

V37 creates `vm_meeting_record_bookmarks` with primary key
`(tenant_id,user_id,meeting_id)`, `favorite`, `version`, `updated_at`, an exact
tenant/meeting foreign key with delete cascade, and a partial favorite index.
It contains no title, participant name, transcript, media URL or access ticket.
False rows are retained for correct versioning; deleting the parent meeting
removes its bookmark rows.

The service checks current record access before consulting a receipt. It then
uses `MeetingWorkspaceCommands` with command type `RECORD_BOOKMARK_UPDATE` and
hashes the meeting ID, boolean and expected version. The command receipt key is
already tenant/actor/type/key scoped. After any same-key lock wait, the service
locks the Meeting owner row and performs a fresh access statement before
locking/updating personal state. This rejects access revoked while a command
waited. The authorization predicate is the existing organizer/non-DENIED
participant predicate: INTERNAL scope alone never grants membership.

State CAS, metadata-only canonical audit and command receipt all commit in the
same transaction. A replay rechecks current access and returns the current
personal row without reapplying an old boolean; its version may be newer than
the original receipt. Audit failure rolls back both an initial insert and an
update of an existing row, including the receipt. There is no external I/O.

Malformed command/UUID errors use a local controller handler which neither logs
nor echoes caller content. Boolean and integer JSON types are strict: string or
numeric boolean coercion, fractional versions and extra actor fields fail 400.

## Regression coverage

`dwp-meeting-server/src/test/java/com/dwp/services/meeting/videomeeting/domain/MeetingRecordBookmarkPostgresTest.java`

- `currentPageReadIsOrderedUserScopedAndDoesNotWriteDefaults`
- `viewOnlyActorPersistsBooleanVersionAndOtherActorNeverSeesIt`
- `readAndWriteDenyCrossTenantUninvitedInternalAndMixedPageWithoutLeakingPartialState`
- `activeMeetingsAndInvalidPageCommandsAreRejectedWithoutState`
- `idempotencyBindsTargetPayloadAndActorAndReplayReturnsCurrentStateWithoutReapplying`
- `deniedParticipantCannotReadWriteOrReplayPreviouslySuccessfulReceipt`
- `favoriteOnlyAppliesBeforePaginationAndCountAndKeepsUnfilteredHistoryCompatible`
- `firstWriteAuditFailureRollsBackStateAndReceiptAtomically`
- `existingStateAuditFailureRollsBackCasAndDoesNotAdvanceReceipt`
- `concurrentCasAllowsExactlyOneNewCommandAndSameKeyRaceCommitsOnlyOnce`
- `commandWaitingOnOwnerLockRechecksAccessAfterRevocationCommits`
- `endedRecordSupportsBookmarkAndParentDeletionCascadesOnlyItsPersonalState`
- `successfulNoOpStillAdvancesVersionAndMetadataNeverStoresRecordContentOrTickets`
- `providerSupportAndMissingAppViewCannotUsePersonalBookmarksOrFavoriteHistory`

`dwp-meeting-server/src/test/java/com/dwp/services/meeting/videomeeting/domain/MeetingRecordBookmarkHttpPostgresTest.java`

- `actualFilterAndControllerAllowViewOnlyPersonalStateAndReturnPrivateCacheHeaders`
- `actualOwnerPepHidesCrossTenantAndUninvitedUsersForReadWriteAndReplay`
- `supportRoleModeSessionAndActorHeadersCannotReachPersonalBookmarkEndpoints`
- `forgedServiceIdentityAndNonExactViewExceptionNeverMutate`
- `booleanAndVersionAreRequiredAndMalformedOrMixedBatchNeverReturnsPartialState`
- `malformedCallerContentIsNeitherLoggedNorEchoed`

The HTTP tests execute the actual MeetingSecurityFilter, controller, Spring
transaction proxy, services, repositories and migrated PostgreSQL. They do not
substitute a mocked authorization result or in-memory persistence. PostgreSQL
concurrency tests exercise separate connections and real transaction locks.

## Integration handoff

Frontend ownership remains with the root agent: fetch current-page states,
toggle with the current version and a new command key, reconcile the returned
canonical state, and use the server favorite-only filter when that tab is
selected. Do not infer availability before the new backend deployment and V37
migration are active. Central owner must refresh/check generated OpenAPI after
the runtime implementation freezes. Existing LiveKit/KMS/STT/LLM/recording and
retention operational NO-GO gates remain unchanged by this metadata feature.

## Final verification

- `./gradlew :dwp-meeting-server:check --console=plain`: latest-snapshot PASS,
  92 suites / 543 tests / 0 failures / 0 errors / 1 skipped. The sole skip is the
  existing opt-in `LiveKitLocalOperationalSmokeTest`; it is not a bookmark test
  and does not close the credentialed LiveKit operating gate.
- The new bookmark PostgreSQL suite has 14/14 passing tests, and the actual
  SecurityFilter + controller + transaction-proxy HTTP/PostgreSQL suite has
  6/6 passing tests, with zero skips. HTTP history tests assert PostgreSQL's
  actual `SHOW transaction_isolation` is `repeatable read` before querying.
- `python3 scripts/check-source-size.py`: PASS (1442 production files).
- `python3 scripts/check-test-source-size.py`: PASS (693 Java test files).
- `python3 scripts/check-java-dependency-cycles.py`: PASS (zero Spring
  constructor cycles; existing exact legacy SCC baseline unchanged).
- `python3 scripts/check-service-boundaries.py`: PASS.
- `git diff --check`: PASS.

No partial commit, generated contract overwrite, infrastructure readiness
override or operational database seed/change was performed. Migration behavior
was exercised only in isolated PostgreSQL test containers. The root integration
owner retains responsibility for deploying V37, restarting the live service,
checking generated contracts and performing the final frontend/runtime review.

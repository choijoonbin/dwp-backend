package com.dwp.services.platform.calendar;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.*;

/** Stable row-model owner inherited by the scheduling-poll repository facade. */
abstract class CalendarSchedulingPollRows {
    record IdentityRow(long userId, UUID personPublicId) { }

    record IdentityProfile(
            long userId,
            UUID personPublicId,
            String email,
            String displayName) { }

    record PollRow(
            long tenantId,
            UUID pollId,
            long organizerUserId,
            UUID organizerPersonPublicId,
            PollState state,
            String title,
            String description,
            String timeZone,
            OffsetDateTime expiresAt,
            UUID confirmedCandidateId,
            UUID confirmedEventId,
            UUID createIdempotencyKey,
            String createFingerprint,
            OffsetDateTime purgeAfter,
            boolean legalHold,
            long version,
            OffsetDateTime updatedAt) { }

    record CandidateRow(
            UUID candidateId,
            int ordinal,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            UUID resourceId,
            String availabilityState,
            String availabilityReason,
            long version,
            OffsetDateTime updatedAt) { }

    record ParticipantRow(
            UUID participantId,
            Long userId,
            UUID personPublicId,
            String externalEmail,
            String displayName,
            boolean required,
            boolean externalVotingEnabled) { }

    record VoteRow(
            UUID participantId,
            UUID candidateId,
            VoteChoice choice,
            String comment,
            long version) { }

    record CommandReceiptRow(
            UUID pollId,
            String commandType,
            String fingerprint,
            PollState resultingState,
            long resultingVersion,
            UUID resultingEventId) { }

    record HoldRow(
            UUID holdId,
            UUID candidateId,
            UUID participantId,
            PollHoldKind kind,
            PollHoldState state,
            UUID personPublicId,
            UUID resourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime expiresAt,
            long fencingToken,
            long version) { }
}

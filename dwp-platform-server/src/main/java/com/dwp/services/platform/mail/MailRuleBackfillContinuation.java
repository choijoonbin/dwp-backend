package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

final class MailRuleBackfillContinuation {

    record Cursor(
            UUID accountId,
            OffsetDateTime snapshotAt,
            OffsetDateTime afterCreatedAt,
            UUID afterThreadId) {

        Cursor advance(MailOrganizationQueryRepository.RuleCandidate candidate) {
            return new Cursor(accountId, snapshotAt, candidate.createdAt(), candidate.threadId());
        }
    }

    private MailRuleBackfillContinuation() {
    }

    static Cursor resolve(UUID accountId, String token) {
        if (token == null || token.isBlank()) {
            return new Cursor(accountId, OffsetDateTime.now(ZoneOffset.UTC), null, null);
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] values = decoded.split("\\|", -1);
            if (values.length != 5 || !"v1".equals(values[0])) throw invalid();
            UUID tokenAccountId = UUID.fromString(values[1]);
            OffsetDateTime snapshotAt = OffsetDateTime.parse(values[2]);
            OffsetDateTime afterCreatedAt = values[3].isBlank()
                    ? null : OffsetDateTime.parse(values[3]);
            UUID afterThreadId = values[4].isBlank() ? null : UUID.fromString(values[4]);
            if (!accountId.equals(tokenAccountId)
                    || (afterCreatedAt == null) != (afterThreadId == null)
                    || snapshotAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5))) {
                throw invalid();
            }
            return new Cursor(accountId, snapshotAt, afterCreatedAt, afterThreadId);
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    static String encode(Cursor cursor) {
        String value = String.join("|",
                "v1",
                cursor.accountId().toString(),
                cursor.snapshotAt().toString(),
                cursor.afterCreatedAt() == null ? "" : cursor.afterCreatedAt().toString(),
                cursor.afterThreadId() == null ? "" : cursor.afterThreadId().toString());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static BaseException invalid() {
        return new BaseException(
                ErrorCode.INVALID_INPUT_VALUE,
                "The mail rule backfill continuation token is invalid.");
    }
}

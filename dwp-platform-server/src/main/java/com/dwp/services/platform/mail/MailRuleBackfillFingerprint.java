package com.dwp.services.platform.mail;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
class MailRuleBackfillFingerprint {

    String preview(
            UUID accountId,
            List<MailOrganizationDtos.RuleSummary> rules,
            List<MailOrganizationQueryRepository.RuleCandidate> candidates) {
        MessageDigest digest = digest();
        append(digest, "mail-rule-backfill-preview.v1");
        append(digest, accountId);
        for (MailOrganizationDtos.RuleSummary rule : rules) {
            append(digest, rule.ruleId());
            append(digest, rule.version());
            append(digest, rule.priority());
            append(digest, rule.stopProcessing());
            append(digest, rule.matchMode());
            append(digest, rule.conditions());
            append(digest, rule.actions());
        }
        for (MailOrganizationQueryRepository.RuleCandidate candidate : candidates) {
            append(digest, candidate.threadId());
            append(digest, candidate.version());
            append(digest, candidate.sender());
            append(digest, candidate.recipient());
            append(digest, candidate.subject());
            append(digest, candidate.body());
            append(digest, candidate.attachments());
            append(digest, candidate.importance());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    String request(UUID accountId, String previewFingerprint) {
        MessageDigest digest = digest();
        append(digest, "mail-rule-backfill-request.v1");
        append(digest, accountId);
        append(digest, previewFingerprint);
        return HexFormat.of().formatHex(digest.digest());
    }

    boolean matches(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void append(MessageDigest digest, Object value) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '\n');
    }
}

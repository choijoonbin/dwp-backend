package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

record NotificationQualityFactContext(
        boolean actionRequired,
        boolean collapsed,
        String threadIdentityHash,
        String sourceIdentityHash) {

    static NotificationQualityFactContext from(
            long tenantId,
            DirectMaterializationRequest request,
            TemplateContract contract,
            boolean collapsed) {
        String threadIdentity = request.threadKey() == null || request.threadKey().isBlank()
                ? request.sourceEventId().toString()
                : request.threadKey().trim();
        return new NotificationQualityFactContext(
                request.actionRequired(),
                collapsed,
                identity("thread", tenantId, contract.typeVersionId(), threadIdentity),
                identity("source", tenantId, contract.typeVersionId(), request.sourceEventId().toString()));
    }

    static String notificationIdentity(long tenantId, UUID notificationId) {
        return identity("notification", tenantId, null, notificationId.toString());
    }

    static String threadIdentity(long tenantId, UUID typeVersionId, String threadKey) {
        return identity("thread", tenantId, typeVersionId, threadKey);
    }

    private static String identity(
            String namespace,
            long tenantId,
            UUID typeVersionId,
            String value) {
        try {
            String canonical = namespace + '\u0000' + tenantId + '\u0000'
                    + (typeVersionId == null ? "" : typeVersionId) + '\u0000' + value;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}

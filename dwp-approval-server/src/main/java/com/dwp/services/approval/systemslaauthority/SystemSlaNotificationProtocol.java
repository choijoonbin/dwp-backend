package com.dwp.services.approval.systemslaauthority;

import java.util.Set;

public final class SystemSlaNotificationProtocol {
    private SystemSlaNotificationProtocol() { }
    public static final String PATH = "/internal/approval/v1/quorum-sla/recipient-authority/evaluate";
    public static final String HEADER = "X-DWP-Notification-Approval-System-Sla-Token";
    public static final String TRANSPORT_ISSUER = "dwp-notification-system-sla-transport";
    public static final String TRANSPORT_AUDIENCE = "dwp-approval-system-sla-notification-transport";
    public static final String TRANSPORT_PURPOSE = "NOTIFICATION_APPROVAL_SYSTEM_SLA_TRANSPORT_V1";
    public static final String ATTESTATION_ISSUER = "dwp-approval-system-sla-notification";
    public static final String ATTESTATION_AUDIENCE = "dwp-notification-system-sla-source";
    public static final String ATTESTATION_PURPOSE = "APPROVAL_SYSTEM_SLA_NOTIFICATION_ATTESTATION_V1";
    public static final Set<String> REQUEST_FIELDS = Set.of("eventId", "eventType", "tenantId", "requestId", "originalEnvelopeSha256",
            "canonicalEnvelopeSha256", "recipientSnapshotSha256", "sourcePinsSha256", "requestedRecipientUserIds");
    public static final Set<String> TRANSPORT_FIELDS = Set.of("iss", "aud", "sub", "iat", "nbf", "exp", "jti", "purpose", "method", "path", "requestNonce", "requestBodySha256", "sourcePinsSha256");
}

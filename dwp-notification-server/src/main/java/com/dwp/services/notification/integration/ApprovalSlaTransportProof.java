package com.dwp.services.notification.integration;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Transport authentication cannot substitute for the separately verified Approval current-source response. */
public final class ApprovalSlaTransportProof {
    static final String PATH = "/internal/approval/v1/quorum-sla/recipient-authority/evaluate";
    static final String HEADER = "X-DWP-Notification-Approval-System-Sla-Token";
    private final RSAKey key;
    private final Clock clock;

    public ApprovalSlaTransportProof(RSAKey key, List<RSAKey> prohibited, Clock clock) {
        if (key == null || !key.isPrivate() || key.size() < 2048 || clock == null
                || !JWSAlgorithm.RS256.equals(key.getAlgorithm()) || !KeyUse.SIGNATURE.equals(key.getKeyUse())
                || key.getKeyID() == null
                || !key.getKeyID().matches("notification-sla-recipient-transport:[A-Za-z0-9._-]{1,48}")
                || prohibited == null || prohibited.isEmpty()) throw invalid();
        try {
            for (RSAKey other : prohibited) {
                if (other == null || key.getKeyID().equals(other.getKeyID())
                        || key.computeThumbprint().equals(other.computeThumbprint())) throw invalid();
            }
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw invalid(); }
        this.key = key; this.clock = clock;
    }

    Exchange issue(ApprovalSlaNotificationPlan plan) {
        if (plan == null || plan.recipients().isEmpty() || plan.recipients().size() > 1000) throw invalid();
        var input = Map.of("eventId", plan.eventId().toString(), "eventType", plan.eventType(),
                "tenantId", plan.actor().tenantId(), "requestId", plan.requestId().toString(),
                "originalEnvelopeSha256", plan.originalEnvelopeSha256(),
                "canonicalEnvelopeSha256", plan.envelopeSha256(),
                "recipientSnapshotSha256", plan.recipientSnapshotSha256(),
                "sourcePinsSha256", plan.sourcePinsSha256(),
                "requestedRecipientUserIds", plan.recipients().stream().map(ApprovalSlaNotificationPlan.Recipient::userId).toList());
        byte[] body = ApprovalSlaNotificationContract.canonical(input).getBytes(StandardCharsets.UTF_8);
        String hash = ApprovalSlaNotificationContract.sha256(new String(body, StandardCharsets.UTF_8));
        UUID nonce = UUID.randomUUID(), jti = UUID.randomUUID();
        Instant issued = Instant.ofEpochSecond(clock.instant().getEpochSecond()), expires = issued.plusSeconds(30);
        try {
            SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), new JWTClaimsSet.Builder()
                    .issuer("dwp-notification-system-sla-transport")
                    .claim("aud", "dwp-approval-system-sla-notification-transport")
                    .subject("dwp-notification-server").issueTime(Date.from(issued))
                    .notBeforeTime(Date.from(issued)).expirationTime(Date.from(expires)).jwtID(jti.toString())
                    .claim("purpose", "NOTIFICATION_APPROVAL_SYSTEM_SLA_TRANSPORT_V1")
                    .claim("method", "POST").claim("path", PATH).claim("requestNonce", nonce.toString())
                    .claim("requestBodySha256", hash).claim("sourcePinsSha256", plan.sourcePinsSha256()).build());
            token.sign(new RSASSASigner(key));
            return new Exchange(body, token.serialize(), nonce, hash, expires);
        } catch (Exception error) { throw invalid(); }
    }

    record Exchange(byte[] body, String token, UUID nonce, String bodySha256, Instant expiresAt) {
        Exchange { body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Dedicated Approval SLA transport proof is invalid.");
    }
}

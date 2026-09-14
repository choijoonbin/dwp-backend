package com.dwp.services.approval.systemslaauthority;

import static com.dwp.services.approval.systemslaauthority.SystemSlaJson.*;
import static com.dwp.services.approval.systemslaauthority.SystemSlaNotificationProtocol.*;
import java.time.*;
import java.util.*;

public final class SystemSlaNotificationAttestationIssuer {
    private final SystemSlaJson json;
    private final SystemSlaNotificationKeys keys;
    private final Clock clock;
    public SystemSlaNotificationAttestationIssuer(SystemSlaJson json, SystemSlaNotificationKeys keys, Clock clock) { this.json = json; this.keys = keys; this.clock = clock; }
    public Map<String, String> issue(SystemSlaNotificationVerifier.Verified notification, SystemSlaSourceAttestationVerifier.Verified authority) {
        if (notification == null || authority == null || !authority.exchange().seal().delivery()) throw denied();
        var seal = authority.exchange().seal(); var input = notification.request(); var bindings = seal.bindings();
        if (!json.digest(bindings.at("/source/event")).equals(json.digest(Map.of("eventId", uuid(input, "eventId"), "eventType", text(input, "eventType", 40),
                "originalEnvelopeSha256", hash(input, "originalEnvelopeSha256"), "canonicalEnvelopeSha256", hash(input, "canonicalEnvelopeSha256"))))
                || integer(bindings, "tenantId", true) != integer(input, "tenantId", true)
                || !uuid(bindings.at("/source/request"), "requestId").equals(uuid(input, "requestId"))) throw denied();
        Instant until = authority.expiresAt();
        for (Instant bound : List.of(seal.validUntil(), notification.expiresAt(), clock.instant().plusSeconds(30))) if (bound.isBefore(until)) until = bound;
        long now = clock.instant().getEpochSecond(), exp = until.getEpochSecond(); if (exp <= now || exp > now + 30) throw denied();
        var seats = new ArrayList<Map<String, Object>>(); var users = new ArrayList<Long>();
        for (var seat : authority.recipients()) {
            long user = integer(seat, "userId", true); UUID task = uuid(seat, "taskId");
            boolean eligible = seat.get("eligible").booleanValue() && seal.taskEligible(task);
            String reason = seat.get("eligible").booleanValue() && !seal.taskEligible(task) ? "TASK_STATE_NOT_ELIGIBLE" : text(seat, "reason", 40);
            users.add(user); seats.add(Map.of("userId", user, "personPublicId", uuid(seat, "personPublicId"), "taskId", task,
                    "taskVersion", integer(seat, "taskVersion", false), "eligible", eligible, "reason", reason));
        }
        if (!json.digest(users).equals(json.digest(input.get("requestedRecipientUserIds")))) throw denied();
        var profile = new LinkedHashMap<String, Object>();
        for (String key : List.of("eventId", "eventType", "tenantId", "requestId", "originalEnvelopeSha256", "canonicalEnvelopeSha256", "recipientSnapshotSha256", "sourcePinsSha256")) profile.put(key, input.get(key));
        profile.put("generation", integer(bindings.at("/source/stage"), "generation", true));
        profile.put("leaseEpoch", integer(bindings.at("/source/timer"), "leaseEpoch", true));
        profile.put("authorityRevision", "asla-" + sha((authority.authorityRevision() + '\n' + seal.nativeVectorSha256()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        profile.put("validUntil", exp); profile.put("recipients", seats);
        var claims = new LinkedHashMap<String, Object>(); claims.put("iss", ATTESTATION_ISSUER); claims.put("aud", ATTESTATION_AUDIENCE); claims.put("purpose", ATTESTATION_PURPOSE);
        claims.put("iat", now); claims.put("nbf", now); claims.put("exp", exp); claims.put("jti", UUID.randomUUID().toString());
        claims.put("requestNonce", notification.nonce()); claims.put("requestBodySha256", notification.bodySha256()); claims.put("profileSha256", json.digest(profile)); claims.put("profile", profile);
        String token = SystemSlaSourceProofIssuer.sign(claims, keys.signer(), json);
        var response = Map.of("attestation", token); if (json.bytes(response).length > 524288) throw unavailable(); return response;
    }
}

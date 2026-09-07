package com.dwp.services.platform.workhub.assignment;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import static com.dwp.services.platform.workhub.assignment.MeetingFollowupProtocol.*;

/** Audience-isolated, short-lived assertion. The Meeting recipient owns single-use JTI validation. */
final class MeetingFollowupWorkloadSigner {
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private final String keyId;
    private final byte[] secret;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Supplier<UUID> nonce;

    MeetingFollowupWorkloadSigner(String keyId, String secretBase64, ObjectMapper mapper) {
        this(keyId, secretBase64, mapper, Clock.systemUTC(), UUID::randomUUID);
    }

    MeetingFollowupWorkloadSigner(String keyId, String secretBase64, ObjectMapper mapper,
                                 Clock clock, Supplier<UUID> nonce) {
        if (keyId == null || !keyId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
            throw new IllegalArgumentException("A dedicated Work/Meeting assertion key ID is required.");
        this.keyId = keyId;
        try { this.secret = Base64.getDecoder().decode(secretBase64); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("Invalid assertion key material."); }
        if (secret.length < 32) throw new IllegalArgumentException("Assertion key material is too short.");
        this.mapper = mapper;
        this.clock = clock;
        this.nonce = nonce;
    }

    String sign(Request request, byte[] exactBody) {
        if (request == null || request.tenantId() <= 0 || request.actorUserId() <= 0
                || request.source() == null || request.source().meetingId() == null
                || request.source().reportId() == null || request.source().candidateId() == null
                || request.action() == null || exactBody == null) {
            throw new IllegalArgumentException("A complete Work/Meeting request binding is required.");
        }
        try {
            long issuedAt = clock.instant().getEpochSecond();
            Claims claims = new Claims(1, keyId, ISSUER, AUDIENCE, "POST", PATH,
                    request.tenantId(), request.actorUserId(), request.source().meetingId(),
                    request.source().reportId(), request.source().candidateId(), request.action(),
                    issuedAt, issuedAt + 30, nonce.get(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(exactBody)));
            String input = "dwp1." + BASE64.encodeToString(mapper.writeValueAsBytes(claims));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return input + "." + BASE64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign the Work/Meeting request.", exception);
        }
    }

    private record Claims(int v, String kid, String iss, String aud, String method, String path,
                          long tenantId, long actorUserId, UUID meetingId, UUID reportId,
                          UUID candidateId, Operation action, long iat, long exp, UUID jti,
                          String bodySha256) { }
}

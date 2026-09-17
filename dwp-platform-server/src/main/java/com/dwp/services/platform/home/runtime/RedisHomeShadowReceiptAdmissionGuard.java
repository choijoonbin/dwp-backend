package com.dwp.services.platform.home.runtime;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisScriptingCommands;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class RedisHomeShadowReceiptAdmissionGuard
        implements HomeShadowReceiptAdmissionGuard {

    static final int DEFAULT_MAX_RECEIPTS = 4_096;
    static final int DEFAULT_MAX_RECIPIENTS = 2_048;
    static final int DEFAULT_MAX_RECEIPTS_PER_WINDOW = 60;
    static final Duration DEFAULT_DEDUPE_TTL = Duration.ofMinutes(10);
    static final Duration DEFAULT_RATE_WINDOW = Duration.ofMinutes(1);

    private static final String RECEIPTS_KEY =
            "dwp:platform:home-shadow-admission:v1:{home-shadow}:receipts";
    private static final String RECIPIENTS_KEY =
            "dwp:platform:home-shadow-admission:v1:{home-shadow}:recipients";
    private static final String RATE_KEY_PREFIX =
            "dwp:platform:home-shadow-admission:v1:{home-shadow}:rate:";

    private static final String ADMIT_SCRIPT = """
            local redis_time = redis.call('TIME')
            local now_ms = (tonumber(redis_time[1]) * 1000)
              + math.floor(tonumber(redis_time[2]) / 1000)
            local receipt_expiry = now_ms + tonumber(ARGV[3])
            local recipient_expiry = now_ms + tonumber(ARGV[4])

            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now_ms)
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now_ms)

            if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
              return 'DUPLICATE'
            end

            local rate = tonumber(redis.call('GET', KEYS[3]) or '0')
            if rate >= tonumber(ARGV[7]) then
              return 'RATE_LIMITED'
            end

            if not redis.call('ZSCORE', KEYS[2], ARGV[2])
              and redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[6]) then
              return 'CAPACITY_REJECTED'
            end
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[5]) then
              return 'CAPACITY_REJECTED'
            end

            redis.call('ZADD', KEYS[1], receipt_expiry, ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            redis.call('ZADD', KEYS[2], recipient_expiry, ARGV[2])
            redis.call('PEXPIRE', KEYS[2], ARGV[4])
            local updated_rate = redis.call('INCR', KEYS[3])
            if updated_rate == 1 then
              redis.call('PEXPIRE', KEYS[3], ARGV[4])
            end
            return 'ADMITTED'
            """;

    private final RedisScriptingCommands<String, String> redis;
    private final byte[] privacySecret;
    private final int maxReceipts;
    private final int maxRecipients;
    private final int maxReceiptsPerWindow;
    private final Duration dedupeTtl;
    private final Duration rateWindow;

    RedisHomeShadowReceiptAdmissionGuard(
            RedisScriptingCommands<String, String> redis,
            String privacySecret) {
        this(redis, privacySecret, DEFAULT_MAX_RECEIPTS, DEFAULT_MAX_RECIPIENTS,
                DEFAULT_MAX_RECEIPTS_PER_WINDOW, DEFAULT_DEDUPE_TTL,
                DEFAULT_RATE_WINDOW);
    }

    RedisHomeShadowReceiptAdmissionGuard(
            RedisScriptingCommands<String, String> redis,
            String privacySecret,
            int maxReceipts,
            int maxRecipients,
            int maxReceiptsPerWindow,
            Duration dedupeTtl,
            Duration rateWindow) {
        if (redis == null) throw new IllegalArgumentException("Redis is required");
        if (privacySecret == null
                || privacySecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "Home shadow admission privacy secret must contain at least 32 bytes");
        }
        if (maxReceipts < 1 || maxRecipients < 1 || maxReceiptsPerWindow < 1
                || invalidDuration(dedupeTtl) || invalidDuration(rateWindow)) {
            throw new IllegalArgumentException("Home shadow admission bounds are invalid");
        }
        this.redis = redis;
        this.privacySecret = privacySecret.getBytes(StandardCharsets.UTF_8).clone();
        this.maxReceipts = maxReceipts;
        this.maxRecipients = maxRecipients;
        this.maxReceiptsPerWindow = maxReceiptsPerWindow;
        this.dedupeTtl = dedupeTtl;
        this.rateWindow = rateWindow;
    }

    @Override
    public Admission admit(
            long tenantId,
            long userId,
            String decisionRevision,
            HomeShadowReceiptController.ShadowReceiptRequest request) {
        String recipient = digest(tenantId + "|" + userId);
        String semanticPayload = request.schemaVersion()
                + "|" + request.outcome().name()
                + "|" + request.reasons().stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.joining(","))
                + "|" + request.mismatchCount()
                + "|" + request.homeMode()
                + "|" + request.deviceClass()
                + "|" + request.runtimeState()
                + "|" + request.rolloutRing();
        String receipt = digest(recipient + "|" + decisionRevision + "|" + semanticPayload);
        try {
            String result = redis.eval(
                    ADMIT_SCRIPT,
                    ScriptOutputType.VALUE,
                    new String[]{RECEIPTS_KEY, RECIPIENTS_KEY, RATE_KEY_PREFIX + recipient},
                    receipt,
                    recipient,
                    Long.toString(dedupeTtl.toMillis()),
                    Long.toString(rateWindow.toMillis()),
                    Integer.toString(maxReceipts),
                    Integer.toString(maxRecipients),
                    Integer.toString(maxReceiptsPerWindow));
            return result == null ? Admission.UNAVAILABLE : decode(result);
        } catch (RuntimeException exception) {
            return Admission.UNAVAILABLE;
        }
    }

    private Admission decode(String value) {
        try {
            return Admission.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return Admission.UNAVAILABLE;
        }
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(privacySecret, "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private static boolean invalidDuration(Duration duration) {
        return duration == null || duration.isZero() || duration.isNegative()
                || duration.compareTo(Duration.ofHours(1)) > 0;
    }
}

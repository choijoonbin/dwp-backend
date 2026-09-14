package com.dwp.services.auth.informationreplay;

import static com.dwp.services.auth.informationreplay.InformationReplayJson.*;
import static com.dwp.services.auth.informationreplay.InformationReplayProofVerifier.earlier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Both purpose-specific JTIs are consumed atomically across replicas, at the current source deadline. */
public final class InformationReplayReplayStore {
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            if redis.call('EXISTS',KEYS[1]) == 1 or redis.call('EXISTS',KEYS[2]) == 1 then return 0 end
            redis.call('PSETEX',KEYS[1],ARGV[1],ARGV[2])
            redis.call('PSETEX',KEYS[2],ARGV[1],ARGV[3])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    public InformationReplayReplayStore(StringRedisTemplate redis) { this.redis = redis; }
    public void requireReady() {
        if (redis == null) throw unavailable();
        try {
            if (!Boolean.TRUE.equals(redis.execute((RedisCallback<Boolean>) connection -> "PONG".equals(connection.ping())))) throw unavailable();
        } catch (com.dwp.core.exception.BaseException error) { throw error; }
        catch (RuntimeException error) { throw unavailable(); }
    }
    public void consume(InformationReplayProofVerifier.Verified proof, Instant sourceDeadline) {
        if (redis == null) throw unavailable();
        long ttl = Duration.between(Instant.now(), earlier(proof.expiresAt(), sourceDeadline)).toMillis();
        if (ttl <= 0 || ttl > 30000) throw denied();
        String prefix = "dwp:auth:information-replay:v1:{" + proof.caller().tenantId() + ':' + InformationReplayProtocol.OWNER_PURPOSE + "}:";
        try {
            Long result = redis.execute(CONSUME, List.of(prefix + "owner:" + proof.sourceJti(), prefix + "transport:" + proof.transportJti()),
                    Long.toString(ttl), proof.bindingsSha256(), proof.bodySha256());
            if (result == null) throw unavailable();
            if (result != 1) throw denied();
        } catch (com.dwp.core.exception.BaseException error) { throw error; }
        catch (RuntimeException error) { throw unavailable(); }
    }
}

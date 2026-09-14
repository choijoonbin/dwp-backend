package com.dwp.services.auth.workflowplanning;

import static com.dwp.services.auth.workflowplanning.PlanningProtocol.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class PlanningReplayStore {
    private static final DefaultRedisScript<Long> CONSUME=new DefaultRedisScript<>("""
            if redis.call('EXISTS',KEYS[1]) == 1 or redis.call('EXISTS',KEYS[2]) == 1 then return 0 end
            redis.call('SET',KEYS[1],ARGV[2],'EX',ARGV[1])
            redis.call('SET',KEYS[2],ARGV[2],'EX',ARGV[1])
            return 1
            """,Long.class);
    private final StringRedisTemplate redis; private final Clock clock;
    public PlanningReplayStore(StringRedisTemplate redis,Clock clock) {this.redis=redis;this.clock=clock;}
    public void requireReady() {
        if(redis==null) throw unavailable();
        try { if(!"PONG".equals(redis.execute((org.springframework.data.redis.core.RedisCallback<String>)connection->connection.ping()))) throw unavailable(); }
        catch(RuntimeException failed) {throw unavailable();}
    }
    public void consume(PlanningProofVerifier.Verified proof,Instant authorityDeadline) {
        if(redis==null) throw unavailable(); if(authorityDeadline==null || authorityDeadline.isAfter(proof.expiresAt())) throw denied();
        if(!authorityDeadline.isAfter(clock.instant())) throw denied();
        long remaining=Duration.between(clock.instant(),proof.replayUntil()).toNanos();
        if(remaining<=0 || remaining>Duration.ofSeconds(30).toNanos()) throw denied();
        long ttl=(remaining+999999999L)/1000000000L;
        String prefix="dwp:auth:workflow-planning:{"+proof.bindings().tenantId()+':'+OWNER_PURPOSE+"}:";
        final Long result;
        try { result=redis.execute(CONSUME,List.of(prefix+"owner:"+proof.sourceJti(),prefix+"transport:"+proof.transportJti()),Long.toString(ttl),proof.bodySha256()); }
        catch(RuntimeException failed) {throw unavailable();}
        if(result==null) throw unavailable(); if(result!=1) throw denied();
    }
}

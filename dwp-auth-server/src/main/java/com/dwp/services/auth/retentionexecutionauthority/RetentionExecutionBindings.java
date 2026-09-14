package com.dwp.services.auth.retentionexecutionauthority;

import static com.dwp.services.auth.retentionexecutionauthority.RetentionExecutionJson.*;
import static com.dwp.services.auth.retentionexecutionauthority.RetentionExecutionProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record RetentionExecutionBindings(Target target,Context context,NativeSource nativeSource) {
    public record Target(UUID intentId,long tenantId,long actorId,UUID requestId,String resourceSetKey,long requestVersion,
            UUID policyId,long policyVersion,long holdVersion,String inventorySha256,String commandFingerprint,long intentVersion) { }
    public record Context(String accessMode,String source) { }
    public record NativeSource(String intentState,String authoritySha256,Instant createdAt) { }
    public static RetentionExecutionBindings parse(JsonNode bindings,Instant now) {
        exact(bindings,Set.of("target","context","nativeSource"));var target=bindings.get("target");
        exact(target,Set.of("intentId","tenantId","actorId","requestId","resourceSetKey","requestVersion","policyId","policyVersion",
                "holdVersion","inventorySha256","commandFingerprint","intentVersion"));
        String resource=text(target,"resourceSetKey",80);if(!resource.matches("[A-Z][A-Z0-9_]{2,79}")) throw denied();
        var typed=new Target(uuid(target,"intentId"),integer(target,"tenantId",1),integer(target,"actorId",1),uuid(target,"requestId"),resource,
                integer(target,"requestVersion",0),uuid(target,"policyId"),integer(target,"policyVersion",0),integer(target,"holdVersion",0),
                hash(target,"inventorySha256"),hash(target,"commandFingerprint"),integer(target,"intentVersion",0));
        var context=bindings.get("context");exact(context,Set.of("accessMode","source"));
        String mode=text(context,"accessMode",30),source=text(context,"source",40);
        if(!"NORMAL".equals(mode) || !"CURRENT_AUTH_MANAGEMENT".equals(source)) throw denied();
        var nativeSource=bindings.get("nativeSource");exact(nativeSource,Set.of("intentState","authoritySha256","createdAt"));
        if(!"QUEUED".equals(text(nativeSource,"intentState",32))) throw denied();final Instant created;
        try {created=Instant.parse(text(nativeSource,"createdAt",40));} catch(RuntimeException invalid) {throw denied();}
        if(created.isAfter(now)) throw denied();
        return new RetentionExecutionBindings(typed,new Context(mode,source),
                new NativeSource("QUEUED",hash(nativeSource,"authoritySha256"),created));
    }
}

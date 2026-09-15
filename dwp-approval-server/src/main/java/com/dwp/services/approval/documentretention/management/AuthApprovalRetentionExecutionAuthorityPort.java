package com.dwp.services.approval.documentretention.management;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Purpose-local, single-attempt Auth transport for a freshly claimed retention intent. */
public final class AuthApprovalRetentionExecutionAuthorityPort implements ApprovalRetentionExecutionAuthorityPort {
    public static final String PATH="/internal/auth/v1/approval-retention-execution-authority/evaluate";
    public static final String HEADER="X-DWP-Approval-Retention-Execution-Token";
    private static final String OWNER_ISSUER="dwp-approval-server:retention-execution-source:v1";
    private static final String OWNER_AUDIENCE="dwp-auth-server:retention-execution-source:v1";
    private static final String OWNER_PURPOSE="APPROVAL_RETENTION_EXECUTION_SOURCE_V1";
    private static final String TRANSPORT_ISSUER="dwp-approval-server:retention-execution-transport:v1";
    private static final String TRANSPORT_AUDIENCE="dwp-auth-server:retention-execution-transport:v1";
    private static final String TRANSPORT_PURPOSE="APPROVAL_RETENTION_EXECUTION_TRANSPORT_V1";
    private static final int OWNER_LIMIT=16384,TRANSPORT_LIMIT=2048,BODY_LIMIT=24576;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final URI endpoint;
    private final RSAKey owner;
    private final RSAKey transport;
    private final HttpClient http;

    public AuthApprovalRetentionExecutionAuthorityPort(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper,
            Clock clock,URI endpoint,RSAKey owner,RSAKey transport) {
        this.jdbc=java.util.Objects.requireNonNull(jdbc);
        this.mapper=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                .maxNumberLength(20).maxStringLength(65536).build());
        this.clock=java.util.Objects.requireNonNull(clock);
        this.endpoint=endpoint(endpoint);
        this.owner=privateKey(owner);this.transport=privateKey(transport);
        try {
            if(this.owner.getKeyID().equals(this.transport.getKeyID())
                    || MessageDigest.isEqual(this.owner.toRSAPublicKey().getEncoded(),this.transport.toRSAPublicKey().getEncoded()))
                throw ApprovalRetentionErrors.unavailable();
        } catch(com.dwp.core.exception.BaseException invalid) {throw invalid;}
        catch(Exception invalid) {throw ApprovalRetentionErrors.unavailable();}
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public SignedAuthorization current(Target target) {
        if(target==null) throw ApprovalRetentionErrors.forbidden();
        var source=source(target);var exchange=exchange(target,source);
        var builder=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                .header("Content-Type",MediaType.APPLICATION_JSON_VALUE)
                .header("X-DWP-Service-Identity","dwp-approval-server")
                .header(HEADER,exchange.transport());
        var trace=new org.springframework.http.HttpHeaders();
        com.dwp.core.http.OutboundHttpHeaders.propagateObservability(trace);
        trace.forEach((name,values)->{
            if(Set.of("traceparent","tracestate","x-correlation-id").contains(name.toLowerCase(java.util.Locale.ROOT)))
                values.forEach(value->builder.header(name,value));
        });
        var body=new BoundedBody(BODY_LIMIT);var pending=http.sendAsync(builder
                .POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body())).build(),ignored->body);
        try {
            var response=pending.get(5,TimeUnit.SECONDS);
            if(response.statusCode()==401 || response.statusCode()==403) throw ApprovalRetentionErrors.forbidden();
            if(response.statusCode()==409) throw ApprovalRetentionErrors.conflict();
            if(response.statusCode()!=200) throw ApprovalRetentionErrors.unavailable();
            var content=response.headers().allValues("Content-Type");
            if(content.size()!=1 || !MediaType.APPLICATION_JSON_VALUE.equals(content.getFirst().split(";",2)[0].strip()))
                throw ApprovalRetentionErrors.forbidden();
            return authorization(response.body());
        } catch(com.dwp.core.exception.BaseException failure) {throw failure;}
        catch(InterruptedException failure) {Thread.currentThread().interrupt();throw ApprovalRetentionErrors.unavailable();}
        catch(java.util.concurrent.ExecutionException failure) {
            if(failure.getCause() instanceof com.dwp.core.exception.BaseException expected) throw expected;
            throw ApprovalRetentionErrors.unavailable();
        } catch(java.util.concurrent.TimeoutException failure) {throw ApprovalRetentionErrors.unavailable();}
        finally {pending.cancel(true);body.cancel();}
    }

    private Source source(Target expected) {
        var rows=jdbc.query("""
            SELECT intent_id,tenant_id,actor_user_id,request_id,resource_set_key,request_version,
                   policy_id,policy_version,hold_version,inventory_sha256,command_fingerprint,
                   version,state,authority_sha256,created_at
              FROM apr_retention_dispatch_intents WHERE intent_id=:intent
            """,Map.of("intent",expected.intentId()),(result,row)->new Source(new Target(
                result.getObject("intent_id",UUID.class),result.getLong("tenant_id"),result.getLong("actor_user_id"),
                result.getObject("request_id",UUID.class),result.getString("resource_set_key"),
                result.getLong("request_version"),result.getObject("policy_id",UUID.class),
                result.getLong("policy_version"),result.getLong("hold_version"),
                result.getString("inventory_sha256"),result.getString("command_fingerprint"),result.getLong("version")),
                result.getString("state"),result.getString("authority_sha256"),
                result.getTimestamp("created_at").toInstant()));
        if(rows.size()!=1) throw ApprovalRetentionErrors.hidden();
        var source=rows.getFirst();
        if(!expected.equals(source.target()) || !"QUEUED".equals(source.state())
                || source.authoritySha256()==null || !source.authoritySha256().matches("[a-f0-9]{64}")
                || source.createdAt()==null || source.createdAt().isAfter(clock.instant()))
            throw ApprovalRetentionErrors.conflict();
        return source;
    }

    private Exchange exchange(Target target,Source source) {
        Instant now=Instant.ofEpochSecond(clock.instant().getEpochSecond());
        Instant expires=now.plusSeconds(20);UUID sourceJti=UUID.randomUUID(),transportJti=UUID.randomUUID();
        var bindings=new LinkedHashMap<String,Object>();
        bindings.put("target",target);bindings.put("context",Map.of("accessMode","NORMAL","source","CURRENT_AUTH_MANAGEMENT"));
        bindings.put("nativeSource",Map.of("intentState",source.state(),"authoritySha256",source.authoritySha256(),
                "createdAt",source.createdAt().toString()));
        JsonNode frozen=sorted(mapper.valueToTree(bindings));String bindingsSha=sha(bytes(frozen));
        var ownerClaims=claims(OWNER_ISSUER,OWNER_AUDIENCE,OWNER_PURPOSE,target.actorId(),sourceJti,now,expires);
        ownerClaims.put("bindings",frozen);ownerClaims.put("bindingsSha256",bindingsSha);
        String ownerProof=sign(ownerClaims,owner);if(ownerProof.length()>OWNER_LIMIT) throw ApprovalRetentionErrors.forbidden();
        byte[] body=bytes(Map.of("sourceProof",ownerProof,"bindings",frozen));
        if(body.length>BODY_LIMIT) throw ApprovalRetentionErrors.forbidden();
        var transportClaims=claims(TRANSPORT_ISSUER,TRANSPORT_AUDIENCE,TRANSPORT_PURPOSE,target.actorId(),transportJti,now,expires);
        transportClaims.put("method","POST");transportClaims.put("path",PATH);transportClaims.put("sourceJti",sourceJti.toString());
        transportClaims.put("bodySha256",sha(body));transportClaims.put("bindingsSha256",bindingsSha);
        String token=sign(transportClaims,transport);if(token.length()>TRANSPORT_LIMIT) throw ApprovalRetentionErrors.forbidden();
        return new Exchange(body,token);
    }

    private SignedAuthorization authorization(byte[] body) {
        try {
            JsonNode value=mapper.readTree(body);exact(value,Set.of("payloadBase64Url","signatureBase64Url"));
            String payload=text(value,"payloadBase64Url",12000),signature=text(value,"signatureBase64Url",100);
            if(!payload.matches("[A-Za-z0-9_-]{1,12000}") || !signature.matches("[A-Za-z0-9_-]{86}"))
                throw ApprovalRetentionErrors.forbidden();
            byte[] decoded=Base64.getUrlDecoder().decode(payload);
            if(!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(payload))
                throw ApprovalRetentionErrors.forbidden();
            return new SignedAuthorization(payload,signature);
        } catch(com.dwp.core.exception.BaseException failure) {throw failure;}
        catch(Exception invalid) {throw ApprovalRetentionErrors.forbidden();}
    }

    private LinkedHashMap<String,Object> claims(String issuer,String audience,String purpose,long actor,UUID id,
            Instant issued,Instant expires) {
        var result=new LinkedHashMap<String,Object>();result.put("iss",issuer);result.put("aud",audience);
        result.put("sub",Long.toString(actor));result.put("iat",issued.getEpochSecond());result.put("nbf",issued.getEpochSecond());
        result.put("exp",expires.getEpochSecond());result.put("jti",id.toString());result.put("purpose",purpose);return result;
    }

    private String sign(Map<String,Object> claims,RSAKey key) {
        try {
            var signed=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                    .keyID(key.getKeyID()).build(),new Payload(bytes(claims)));
            signed.sign(new RSASSASigner(key));return signed.serialize();
        } catch(Exception failure) {throw ApprovalRetentionErrors.unavailable();}
    }

    private byte[] bytes(Object value) {
        try {return mapper.writeValueAsBytes(sorted(mapper.valueToTree(value)));}
        catch(Exception invalid) {throw ApprovalRetentionErrors.unavailable();}
    }

    private JsonNode sorted(JsonNode value) {
        if(value.isObject()) {var result=mapper.createObjectNode();var names=new TreeSet<String>();value.fieldNames().forEachRemaining(names::add);
            for(String name:names) result.set(name,sorted(value.get(name)));return result;}
        if(value.isArray()) {var result=mapper.createArrayNode();value.forEach(child->result.add(sorted(child)));return result;}
        if(value.isFloatingPointNumber()) throw ApprovalRetentionErrors.forbidden();return value.deepCopy();
    }

    private static RSAKey privateKey(RSAKey value) {
        if(value==null || !value.isPrivate() || value.size()<2048 || value.getKeyID()==null
                || !value.getKeyID().matches("[A-Za-z0-9._:-]{1,80}")
                || value.getAlgorithm()!=null && !"RS256".equals(value.getAlgorithm().getName())
                || value.getKeyUse()!=null && !KeyUse.SIGNATURE.equals(value.getKeyUse()))
            throw ApprovalRetentionErrors.unavailable();
        return value;
    }

    private static URI endpoint(URI value) {
        if(value==null || value.getHost()==null || value.getUserInfo()!=null || value.getQuery()!=null || value.getFragment()!=null
                || !PATH.equals(value.getRawPath()) || !("https".equals(value.getScheme()) || "http".equals(value.getScheme())
                && Set.of("localhost","127.0.0.1","::1","[::1]").contains(value.getHost())))
            throw ApprovalRetentionErrors.unavailable();
        return value;
    }

    private static void exact(JsonNode value,Set<String> fields) {
        if(value==null || !value.isObject()) throw ApprovalRetentionErrors.forbidden();
        var actual=new HashSet<String>();value.fieldNames().forEachRemaining(actual::add);
        if(!fields.equals(actual)) throw ApprovalRetentionErrors.forbidden();
    }

    private static String text(JsonNode value,String name,int limit) {
        JsonNode field=value.get(name);
        if(field==null || !field.isTextual() || field.textValue().isBlank() || field.textValue().length()>limit
                || field.textValue().codePoints().anyMatch(Character::isISOControl)) throw ApprovalRetentionErrors.forbidden();
        return field.textValue();
    }

    private static String sha(byte[] value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
        catch(Exception impossible) {throw new IllegalStateException(impossible);}
    }

    private record Source(Target target,String state,String authoritySha256,Instant createdAt) {}
    private record Exchange(byte[] body,String transport) {
        private Exchange {body=body.clone();}
        @Override public byte[] body() {return body.clone();}
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private Flow.Subscription subscription;
        private BoundedBody(int limit) {this.limit=limit;}
        @Override public CompletionStage<byte[]> getBody() {return result;}
        @Override public synchronized void onSubscribe(Flow.Subscription value) {
            if(subscription!=null || result.isDone()) {value.cancel();return;}subscription=value;value.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if(result.isDone()) return;
            for(var buffer:buffers) {
                if(buffer.remaining()>limit-bytes.size()) {result.completeExceptionally(ApprovalRetentionErrors.forbidden());cancel();return;}
                var copy=new byte[buffer.remaining()];buffer.get(copy);bytes.writeBytes(copy);
            }
            subscription.request(1);
        }
        @Override public synchronized void onError(Throwable failure) {result.completeExceptionally(failure);}
        @Override public synchronized void onComplete() {result.complete(bytes.toByteArray());}
        private synchronized void cancel() {
            result.completeExceptionally(new java.util.concurrent.CancellationException());if(subscription!=null) subscription.cancel();
        }
    }
}

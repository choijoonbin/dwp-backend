package com.dwp.services.approval.systemslaauthority;

import static com.dwp.services.approval.systemslaauthority.SystemSlaJson.*;
import static com.dwp.services.approval.systemslaauthority.SystemSlaSourceProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.*;

/** Capture-time signature validation only. This never creates a current grant or repairs a missing witness. */
public final class SystemSlaSourceWitnessVerifier {
    private static final Set<String> STANDARD=Set.of("iss","aud","sub","iat","nbf","exp","jti","purpose");
    private final SystemSlaJson json;
    private final SystemSlaSourceKeys keys;
    public SystemSlaSourceWitnessVerifier(SystemSlaJson json,SystemSlaSourceKeys keys) { this.json=json; this.keys=keys; }
    public void verify(JsonNode row,JsonNode current) {
        try {
            if (row==null || !row.isObject()) throw denied();
            String encoded=text(row,"source_body",BODY_LIMIT*2); byte[] raw=Base64.getDecoder().decode(encoded);
            if (!Base64.getEncoder().encodeToString(raw).equals(encoded)) throw denied();
            var body=json.parse(raw); SystemSlaJson.keys(body,Set.of("sourceProof","bindings")); var binding=body.get("bindings");
            SystemSlaJson.keys(binding,Set.of("tenantId","operation","source","audience","sourceDigest","authorityValidUntil"));
            var source=binding.get("source"); SystemSlaJson.keys(source,Set.of("request","workflow","form","payload","policy","stage","timer","event"));
            if (!"PRODUCE".equals(text(binding,"operation",7)) || !source.get("event").isNull()
                    || integer(binding,"tenantId",true)!=integer(current,"tenantId",true) || integer(row,"tenant_id",true)!=integer(current,"tenantId",true)
                    || !hash(row,"bindings_sha256").equals(json.digest(binding)) || !hash(row,"original_source_digest").equals(hash(binding,"sourceDigest"))
                    || !hash(binding,"sourceDigest").equals(json.digest(Map.of("tenantId",binding.get("tenantId"),"operation","PRODUCE","source",source,"audience",binding.get("audience"))))) throw denied();
            String transport=text(row,"transport_proof",2048), auth=text(row,"auth_attestation",BODY_LIMIT);
            if (!hash(row,"proof_sha256").equals(json.digest(Map.of("sourceBodySha256",sha(raw),"transportProof",transport,"authAttestation",auth)))) throw denied();
            if (!text(row,"event_authority_revision",69).equals("asla-"+sha((text(row,"authority_revision",69)+'\n'+hash(row,"native_vector_sha256")).getBytes(java.nio.charset.StandardCharsets.UTF_8)))) throw denied();
            var owner=jwt(text(body,"sourceProof",16384),union("bindingsSha256","sourceDigest"),keys.owner().toPublicJWK());
            var transit=jwt(transport,union("method","path","sourceProofJti","bodySha256","bindingsSha256"),keys.transport().toPublicJWK());
            var attestation=jwt(auth,ATTESTATION_FIELDS,null);
            Instant captured=Instant.parse(text(row,"created_at",40)), rowExpires=Instant.parse(text(row,"expires_at",40));
            long ownerIssued=standard(owner,OWNER_ISSUER,OWNER_AUDIENCE,OWNER_PURPOSE,captured);
            standard(transit,TRANSPORT_ISSUER,TRANSPORT_AUDIENCE,TRANSPORT_PURPOSE,captured);
            standard(attestation,ATTESTATION_ISSUER,ATTESTATION_AUDIENCE,ATTESTATION_PURPOSE,captured);
            UUID ownerId=uuid(owner,"jti"), transportId=uuid(transit,"jti"), authId=uuid(attestation,"jti");
            if (ownerId.equals(transportId) || ownerId.equals(authId) || transportId.equals(authId)
                    || !ownerId.equals(uuid(row,"owner_jti")) || !transportId.equals(uuid(row,"transport_jti")) || !authId.equals(uuid(row,"auth_jti"))
                    || !ownerId.equals(uuid(transit,"sourceProofJti")) || !ownerId.equals(uuid(attestation,"sourceProofJti"))
                    || !transportId.equals(uuid(attestation,"transportProofJti")) || !"POST".equals(text(transit,"method",4)) || !PATH.equals(text(transit,"path",100))
                    || !hash(owner,"sourceDigest").equals(hash(binding,"sourceDigest")) || !hash(attestation,"sourceDigest").equals(hash(binding,"sourceDigest"))) throw denied();
            for (var claim:List.of(owner,transit,attestation)) if (!hash(claim,"bindingsSha256").equals(json.digest(binding))) throw denied();
            if (!hash(transit,"bodySha256").equals(sha(raw)) || !hash(attestation,"bodySha256").equals(sha(raw))) throw denied();
            var authority=attestation.get("authority"); SystemSlaJson.keys(authority,Set.of("authorityRevision","sourceVectorSha256","evaluatedAt","expiresAt"));
            Instant evaluated=Instant.parse(text(authority,"evaluatedAt",40)), expires=Instant.parse(text(authority,"expiresAt",40));
            if (!expires.equals(rowExpires) || !expires.equals(Instant.ofEpochSecond(integer(attestation,"exp",true)))
                    || expires.isAfter(Instant.ofEpochSecond(integer(owner,"exp",true))) || expires.isAfter(Instant.ofEpochSecond(integer(transit,"exp",true)))
                    || expires.isAfter(Instant.parse(text(binding,"authorityValidUntil",40))) || !captured.isBefore(expires)
                    || evaluated.isBefore(Instant.ofEpochSecond(ownerIssued)) || evaluated.isAfter(captured)
                    || !text(authority,"authorityRevision",69).equals("asla-"+hash(authority,"sourceVectorSha256"))
                    || !text(row,"authority_revision",69).equals(text(authority,"authorityRevision",69))) throw denied();
            immutable(source,current.get("source"),row);
            var event=current.at("/source/event");
            if (!uuid(row,"event_id").equals(uuid(event,"eventId")) || !hash(row,"raw_envelope_sha256").equals(hash(event,"originalEnvelopeSha256"))
                    || !hash(row,"canonical_envelope_sha256").equals(hash(event,"canonicalEnvelopeSha256"))) throw denied();
            seats(binding,attestation,current);
        } catch (Exception invalid) { throw denied(); }
    }
    private void immutable(JsonNode original,JsonNode current,JsonNode row) {
        SystemSlaJson.keys(original.get("request"),Set.of("requestId","requestVersion","requesterUserId","requesterPersonPublicId","dataClassification","resourceSetKey"));
        for (String key:List.of("requestId","requesterUserId","requesterPersonPublicId","dataClassification","resourceSetKey")) equal(original.at("/request/"+key),current.at("/request/"+key));
        if (!text(row,"original_classification",20).equals(text(original.get("request"),"dataClassification",20))
                || !uuid(row,"request_id").equals(uuid(original.get("request"),"requestId"))
                || integer(row,"original_request_version",false)!=integer(original.get("request"),"requestVersion",false)
                || integer(original.get("request"),"requestVersion",false)>integer(current.get("request"),"requestVersion",false)) throw denied();
        for (String key:List.of("workflow","form","payload","policy")) equal(original.get(key),current.get(key));
        SystemSlaJson.keys(original.get("stage"),Set.of("stepId","generation","version","stageKey","candidateRole","frozenPoolSha256"));
        for (String key:List.of("stepId","generation","stageKey","candidateRole","frozenPoolSha256")) equal(original.at("/stage/"+key),current.at("/stage/"+key));
        if (!uuid(row,"step_id").equals(uuid(original.get("stage"),"stepId")) || integer(row,"generation",true)!=integer(original.get("stage"),"generation",true)
                || integer(row,"original_stage_version",false)!=integer(original.get("stage"),"version",false)
                || integer(original.get("stage"),"version",false)>integer(current.get("stage"),"version",false)) throw denied();
        SystemSlaJson.keys(original.get("timer"),Set.of("timerId","version","kind","dueAt","leaseEpoch","leaseOwner","leaseUntil","policyVersion"));
        for (String key:List.of("timerId","kind","dueAt","leaseEpoch","policyVersion")) equal(original.at("/timer/"+key),current.at("/timer/"+key));
        if (!uuid(row,"timer_id").equals(uuid(original.get("timer"),"timerId"))) throw denied();
    }
    private void seats(JsonNode binding,JsonNode attestation,JsonNode current) {
        var audience=binding.get("audience"); var seats=attestation.get("recipients"); var output=current.get("audience");
        if (!audience.isArray() || audience.isEmpty() || audience.size()>1000 || !seats.isArray() || audience.size()!=seats.size() || !output.isArray() || output.isEmpty()) throw denied();
        long previous=0;
        for (int index=0;index<seats.size();index++) {
            var seat=seats.get(index); var original=audience.get(index); SystemSlaJson.keys(original,Set.of("userId","personPublicId","taskId","taskVersion"));
            SystemSlaJson.keys(seat,Set.of("userId","personPublicId","taskId","taskVersion","eligible","reason","expiresAt"));
            long user=integer(seat,"userId",true); if (user<=previous || !seat.get("eligible").isBoolean()) throw denied(); previous=user;
            for (String field:List.of("userId","personPublicId","taskId","taskVersion")) equal(original.get(field),seat.get(field));
            uuid(seat,"personPublicId"); uuid(seat,"taskId"); integer(seat,"taskVersion",false);
            String reason=text(seat,"reason",40); Instant expiry=Instant.parse(text(seat,"expiresAt",40));
            if (!REASONS.contains(reason) || seat.get("eligible").booleanValue()!=reason.equals("ELIGIBLE")
                    || expiry.isBefore(Instant.ofEpochSecond(integer(attestation,"exp",true))) || expiry.isAfter(Instant.parse(text(binding,"authorityValidUntil",40)))) throw denied();
        }
        for (var seat:output) {
            var matches=java.util.stream.StreamSupport.stream(seats.spliterator(),false).filter(item -> integer(item,"userId",true)==integer(seat,"userId",true)).toList();
            if (matches.size()!=1 || !matches.getFirst().get("eligible").booleanValue()) throw denied();
            for (String field:List.of("userId","personPublicId","taskId","taskVersion")) equal(seat.get(field),matches.getFirst().get(field));
        }
    }
    private JsonNode jwt(String token,Set<String> fields,RSAKey family) throws Exception {
        if (!token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw denied();
        var parts=token.split("\\."); var header=json.parse(part(parts[0])); SystemSlaJson.keys(header,Set.of("alg","typ","kid"));
        String kid=text(header,"kid",80); if (!"RS256".equals(text(header,"alg",5)) || !"JWT".equals(text(header,"typ",3))) throw denied();
        var key=family==null?keys.attestation(kid):family; if (!kid.equals(key.getKeyID())) throw denied();
        part(parts[2]); if (!SignedJWT.parse(token).verify(new RSASSAVerifier(key))) throw denied();
        var claims=json.parse(part(parts[1])); SystemSlaJson.keys(claims,fields); return claims;
    }
    private long standard(JsonNode claims,String issuer,String audience,String purpose,Instant captured) {
        if (!issuer.equals(text(claims,"iss",100)) || !audience.equals(text(claims,"aud",100)) || !purpose.equals(text(claims,"purpose",100))
                || !"dwp-approval-server".equals(text(claims,"sub",50))) throw denied();
        long issued=integer(claims,"iat",true), expiry=integer(claims,"exp",true); uuid(claims,"jti");
        if (issued!=integer(claims,"nbf",true) || issued>captured.getEpochSecond() || expiry<=captured.getEpochSecond() || expiry<=issued || expiry-issued>30) throw denied(); return issued;
    }
    private void equal(JsonNode left,JsonNode right) { if (left==null || right==null || !json.digest(left).equals(json.digest(right))) throw denied(); }
    private static Set<String> union(String... fields) { var result=new HashSet<>(STANDARD); Collections.addAll(result,fields); return Set.copyOf(result); }
    private static byte[] part(String raw) {
        byte[] bytes=Base64.getUrlDecoder().decode(raw); if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw)) throw denied(); return bytes;
    }
}

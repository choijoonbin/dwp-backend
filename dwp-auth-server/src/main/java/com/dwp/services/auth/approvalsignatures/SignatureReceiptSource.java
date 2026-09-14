package com.dwp.services.auth.approvalsignatures;

import static com.dwp.services.auth.approvalsignatures.SignatureAuthorityJson.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Set;

/** Closed metadata source. None of these fields can authorize artifact, terms or payload access. */
final class SignatureReceiptSource {
    static final Set<String> FIELDS=Set.of("requestId","ownerUserId","resourceSetKey","receiptId","originalOperation","targetId","idempotencyKey",
            "bodySha256","eventSequence","resultVersion","resultState","committedAt","signatureRequestId","sourceCurrent","currentMetadataSha256");
    static void validate(JsonNode source,JsonNode body,long actor,String rs,String key,java.util.UUID objectId) {
        keys(source,FIELDS); keys(body,Set.of("originalOperation","targetId","bodySha256"));
        for(String field:Set.of("requestId","receiptId","targetId","signatureRequestId")) uuid(source,field);
        hash(source,"bodySha256");hash(source,"currentMetadataSha256");
        String op=text(source,"originalOperation",7),state=text(source,"resultState",16);
        if(!Set.of("CREATE","CONSENT","SIGN","CANCEL").contains(op) || !Set.of("AWAITING_CONSENT","CONSENTED","ATTESTED","CANCELLED").contains(state)
                || !switch(op){case "CREATE"->"AWAITING_CONSENT".equals(state);case "CONSENT"->"CONSENTED".equals(state);case "SIGN"->"ATTESTED".equals(state);default->"CANCELLED".equals(state);}
                || integer(source,"ownerUserId")!=actor || !rs.equals(text(source,"resourceSetKey",80)) || !key.equals(text(source,"idempotencyKey",120))
                || !objectId.equals(uuid(source,"requestId")) || integer(source,"eventSequence")!=integer(source,"resultVersion")
                || !source.path("sourceCurrent").isBoolean() || !op.equals(text(body,"originalOperation",7))
                || !uuid(source,"targetId").equals(uuid(body,"targetId")) || !hash(source,"bodySha256").equals(hash(body,"bodySha256"))
                || !uuid(source,op.equals("CREATE")?"requestId":"signatureRequestId").equals(uuid(source,"targetId"))) throw denied();
        try { Instant.parse(text(source,"committedAt",40)); }catch(RuntimeException invalid){throw denied();}
    }
}

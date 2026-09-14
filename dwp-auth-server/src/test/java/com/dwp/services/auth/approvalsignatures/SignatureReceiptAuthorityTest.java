package com.dwp.services.auth.approvalsignatures;

import static org.assertj.core.api.Assertions.*;
import com.dwp.core.exception.BaseException;
import com.nimbusds.jwt.SignedJWT;
import java.util.*;
import org.junit.jupiter.api.*;

class SignatureReceiptAuthorityTest {
    static SignatureAuthorityTestFixture f;
    @BeforeAll static void keys(){f=new SignatureAuthorityTestFixture();}
    Map<String,Object> binding(boolean current){
        var b=f.binding(SignatureAuthorityProtocol.Operation.GET);UUID request=UUID.randomUUID(),ceremony=UUID.randomUUID();
        var source=new LinkedHashMap<String,Object>();source.put("requestId",request.toString());source.put("ownerUserId",99L);source.put("resourceSetKey","RS_APPROVALS");
        source.put("receiptId",UUID.randomUUID().toString());source.put("originalOperation","SIGN");source.put("targetId",ceremony.toString());source.put("idempotencyKey","original-sign-key");
        source.put("bodySha256","a".repeat(64));source.put("eventSequence",2L);source.put("resultVersion",2L);source.put("resultState","ATTESTED");
        source.put("committedAt",SignatureAuthorityTestFixture.NOW.minusSeconds(300).toString());source.put("signatureRequestId",ceremony.toString());source.put("sourceCurrent",current);source.put("currentMetadataSha256","b".repeat(64));
        var body=Map.of("originalOperation","SIGN","targetId",ceremony.toString(),"bodySha256","a".repeat(64));
        b.put("operation","COMMAND_RECEIPT");b.put("objectId",request.toString());b.put("idempotencyKey","original-sign-key");b.put("source",source);
        b.put("sourceSha256",f.json.digest(source));b.put("commandBody",body);b.put("bodySha256",f.json.digest(body));return b;
    }
    @Test void matchingReceiptAndVerifiedMismatchingReceiptHaveDistinctMetadataOnlyPrivateProofs() throws Exception {
        for(boolean current:List.of(true,false)){
            var e=f.exchange(binding(current));var proof=f.verifier.verify(e.bytes(),e.token());assertThat(proof.sourceKind()).isEqualTo("COMMAND_RECEIPT");
            assertThat(proof.binding().path()).isEqualTo("/v1/signature-command-receipts/original-sign-key");
            var jwt=SignedJWT.parse(new SignatureAuthorityIssuer(f.keys,SignatureAuthorityTestFixture.CLOCK).issue(proof,
                    new SignatureCurrentAuthority.Observation("auth","policy","b".repeat(64),"c".repeat(64),SignatureAuthorityTestFixture.NOW.plusSeconds(30),false)));
            assertThat(jwt.getJWTClaimsSet().getBooleanClaim("sourceCurrent")).isEqualTo(current);assertThat(jwt.getJWTClaimsSet().getStringClaim("sourceKind")).isEqualTo("COMMAND_RECEIPT");
            assertThat(jwt.getJWTClaimsSet().getStringClaim("contract")).isEqualTo("DWP_APPROVAL_SIGNATURE_COMMAND_RECEIPT_AUTHORITY_V1");
            assertThat(jwt.getJWTClaimsSet().getClaims()).doesNotContainKeys("artifact","terms","payload","objectVersion");
        }
    }
    @Test void callerUnknownBooleanArtifactSourceOrQueryRetargetCannotProduceMetadataAuthority(){
        for(String field:List.of("sourceCurrent","ownerUserId","targetId","bodySha256")){
            var b=binding(true);var source=(Map<?,?>)b.get("source");var changed=new LinkedHashMap<String,Object>();source.forEach((k,v)->changed.put((String)k,v));
            changed.put(field,switch(field){case "sourceCurrent"->"unknown";case "ownerUserId"->100L;case "targetId"->UUID.randomUUID().toString();default->"0".repeat(64);});
            b.put("source",changed);b.put("sourceSha256",f.json.digest(changed));var e=f.exchange(b);
            assertThatThrownBy(()->f.verifier.verify(e.bytes(),e.token())).as(field).isInstanceOf(BaseException.class);
        }
        var b=binding(true);b.put("operation","GET");var e=f.exchange(b);assertThatThrownBy(()->f.verifier.verify(e.bytes(),e.token())).isInstanceOf(BaseException.class);
    }
}

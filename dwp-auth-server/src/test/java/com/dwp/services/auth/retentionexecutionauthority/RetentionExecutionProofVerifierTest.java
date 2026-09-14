package com.dwp.services.auth.retentionexecutionauthority;

import static org.assertj.core.api.Assertions.*;
import static com.dwp.services.auth.retentionexecutionauthority.RetentionExecutionProtocol.*;
import com.dwp.core.exception.BaseException;
import java.lang.reflect.Modifier;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetentionExecutionProofVerifierTest {
    private final RetentionExecutionProofFixture fixture=new RetentionExecutionProofFixture();
    @Test void exactSeparateCryptoProfileAndPrivateFactoryShapes() {
        var source=fixture.issue(fixture.bindings(1,7,"RS_APPROVALS"));var proof=fixture.verifier().verify(source.body(),source.token());
        assertThat(proof.sourceJti()).isEqualTo(source.ownerJti());assertThat(proof.bindings().context().accessMode()).isEqualTo("NORMAL");
        var owner=fixture.json.parse(RetentionExecutionJson.part(source.proof().split("\\.")[1]),OWNER_LIMIT);
        var transport=fixture.json.parse(RetentionExecutionJson.part(source.token().split("\\.")[1]),OWNER_LIMIT);
        assertThat(owner.size()).isEqualTo(10);assertThat(transport.size()).isEqualTo(13);assertThat(source.token().length()).isLessThanOrEqualTo(2048);
        assertThat(RetentionExecutionProofVerifier.Verified.class.getDeclaredConstructors()).allSatisfy(c->assertThat(Modifier.isPrivate(c.getModifiers())).isTrue());
        assertThat(RetentionExecutionAuthorityService.Current.class.getDeclaredConstructors()).allSatisfy(c->assertThat(Modifier.isPrivate(c.getModifiers())).isTrue());
        assertThat(RetentionExecutionAuthorityService.Current.class.getDeclaredMethods()).noneMatch(m->Modifier.isStatic(m.getModifiers()));
        assertThat(RetentionExecutionAuthorityService.class.getDeclaredFields()).noneMatch(f->f.getType().equals(RetentionExecutionAuthorityProducer.class));
    }
    @Test void fullBodyTamperDuplicateTrailingAndModeSubstitutionDenyBeforeAuth() {
        var source=fixture.issue(fixture.bindings(1,7,"RS_APPROVALS"));var reads=new AtomicInteger();
        var service=new RetentionExecutionAuthorityService(fixture::verifier,p->{reads.incrementAndGet();throw new AssertionError();},
                new RetentionExecutionReplayStore(null,Clock.systemUTC()),c->{throw new AssertionError();},Clock.systemUTC(),true);
        var tampered=fixture.json.parse(source.body(),BODY_LIMIT).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)tampered.at("/bindings/target")).put("requestVersion",5);
        for(byte[] bytes:List.of(fixture.json.bytes(tampered),"{} {}".getBytes(),"{\"sourceProof\":\"a\",\"sourceProof\":\"b\",\"bindings\":{}}".getBytes()))
            assertThatThrownBy(()->service.preverify(bytes,source.token())).isInstanceOf(BaseException.class);
        for(String mode:List.of("ELEVATED","PROVIDER_SUPPORT")) {var binding=fixture.bindings(1,7,"RS_APPROVALS");((com.fasterxml.jackson.databind.node.ObjectNode)binding.get("context")).put("accessMode",mode);
            var invalid=fixture.issue(binding);assertThatThrownBy(()->service.preverify(invalid.body(),invalid.token())).isInstanceOf(BaseException.class);}
        assertThat(reads).hasValue(0);
    }
    @Test void wrongPurposePathSubjectAndExpiredOrUnknownKidDeny() {
        var source=fixture.issue(fixture.bindings(1,7,"RS_APPROVALS"));var original=(com.fasterxml.jackson.databind.node.ObjectNode)fixture.json.parse(RetentionExecutionJson.part(source.token().split("\\.")[1]),OWNER_LIMIT);
        for(var entry:Map.of("purpose","APPROVAL_SYSTEM_SLA_TRANSPORT_V1","path","/internal/approval-workflow/admin-planning","sub","8").entrySet()) {
            var changed=original.deepCopy();changed.put(entry.getKey(),entry.getValue());
            assertThatThrownBy(()->fixture.verifier().verify(source.body(),fixture.sign(fixture.transport,changed))).isInstanceOf(BaseException.class);
        }
        var expired=fixture.issue(fixture.bindings(1,7,"RS_APPROVALS"),-1,-1);
        assertThatThrownBy(()->fixture.verifier().verify(expired.body(),expired.token())).isInstanceOf(BaseException.class);
        assertThatThrownBy(()->fixture.keys.owner("unregistered-rotation-key")).isInstanceOf(BaseException.class);
    }
    @Test void keyPurposeMaterialAndIdentifiersCannotBeReused() {
        assertThatThrownBy(()->new RetentionExecutionKeys(fixture.json,fixture.ring(fixture.owner),fixture.ring(fixture.owner),fixture.privateKey(),fixture.publicKey(),
                "isolated-current-retention-auth","isolated-retention-execution",List.of())).isInstanceOf(BaseException.class);
        String pem="-----BEGIN PRIVATE KEY-----\n"+fixture.privateKey()+"\n-----END PRIVATE KEY-----";
        assertThatThrownBy(()->new RetentionExecutionKeys(fixture.json,fixture.ring(fixture.owner),fixture.ring(fixture.transport),fixture.privateKey(),fixture.publicKey(),
                "isolated-current-retention-auth","isolated-retention-execution",List.of(pem))).isInstanceOf(BaseException.class);
        assertThatThrownBy(()->new RetentionExecutionKeys(fixture.json,fixture.ring(fixture.owner),fixture.ring(fixture.transport),fixture.privateKey(),fixture.publicKey(),
                "isolated-current-retention-auth","isolated-retention-owner",List.of())).isInstanceOf(BaseException.class);
    }
}

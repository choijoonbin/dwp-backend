package com.dwp.services.auth.workflowplanning;

import static org.assertj.core.api.Assertions.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanningProofVerifierTest {
    private final PlanningProofTestFixture fixture=new PlanningProofTestFixture();
    private com.fasterxml.jackson.databind.node.ObjectNode bindings(int stages) {
        return fixture.bindings(1,100,UUID.randomUUID(),"context-fixture","scope-fixture",Instant.now().plusSeconds(60).getEpochSecond(),stages);
    }
    @Test void verifiesClosedSigned64StageBodyWithoutHeaderGrowth() {
        var exchange=fixture.issue(bindings(64));assertThat(exchange.transport().length()).isLessThan(2048);
        assertThat(exchange.body().length).isGreaterThan(8192).isLessThan(524288);
        assertThat(fixture.verifier().verify(exchange.body(),exchange.transport()).bindings().source().get("topology")).hasSize(64);
    }
    @Test void bodyTamperAndWrongPurposeAreForbidden() {
        var exchange=fixture.issue(bindings(1));var tampered=(com.fasterxml.jackson.databind.node.ObjectNode)fixture.json.parse(exchange.body(),524288);
        tampered.withObject("/bindings/owner").put("actorId",101);
        denied(()->fixture.verifier().verify(fixture.json.bytes(tampered),exchange.transport()));
        for(String purpose:new String[]{"APPROVAL_FORM_REFERENCE_RESOLVE_V1","APPROVAL_WORKFLOW_RUNTIME_TRANSPORT_V1"}) {
            var wrong=fixture.issue(bindings(1),owner->{},transport->transport.put("purpose",purpose));denied(()->fixture.verifier().verify(wrong.body(),wrong.transport()));
        }
    }
    @Test void rejectsSignedAliasesUnknownKeysAnd65Stages() {
        for(var mutation:java.util.List.<java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode>>of(
                value->value.withObject("/owner").put("routeContractKey","route.approvals.admin.workflow-planning-simulation.read"),
                value->value.withObject("/owner").put("extra",true),
                value->value.withObject("/source").put("extra",true),
                value->value.withObject("/owner").put("method","GET"),
                value->value.withObject("/owner").put("rolloutState","100"))) {
            var binding=bindings(1);mutation.accept(binding);var exchange=fixture.issue(binding);denied(()->fixture.verifier().verify(exchange.body(),exchange.transport()));
        }
        var tooMany=fixture.issue(bindings(65));denied(()->fixture.verifier().verify(tooMany.body(),tooMany.transport()));
    }
    @Test void exactDigestAndJtiAndExpiryRemainMandatory() {
        for(var mutation:java.util.List.<java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode>>of(
                value->value.put("sourceProofSha256","0".repeat(64)),value->value.put("bodySha256","0".repeat(64)),
                value->value.set("jti",value.get("sourceProofJti")),value->value.put("extra",true),
                value->value.remove("contextKey"),value->value.put("exp",Instant.now().getEpochSecond()))) {
            var exchange=fixture.issue(bindings(1),owner->{},mutation);denied(()->fixture.verifier().verify(exchange.body(),exchange.transport()));
        }
    }
    @Test void jsonRejectsDuplicatesTrailingFloatsAndLongMin() {
        for(String input:new String[]{"{\"a\":1,\"a\":2}","{} {}","{\"n\":1.1}","{\"n\":-9223372036854775808}"})
            denied(()->fixture.json.parse(input.getBytes(StandardCharsets.UTF_8),1024));
    }
    private static void denied(Runnable command) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(BaseException.class,error->assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}

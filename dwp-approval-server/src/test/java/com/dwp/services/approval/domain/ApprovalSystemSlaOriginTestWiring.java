package com.dwp.services.approval.domain;

import com.dwp.services.approval.systemslaauthority.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Native PG tests use actual Nimbus validation of fixture signatures, not deployed Auth authority. Auth interop tests use real HTTP. */
final class ApprovalSystemSlaOriginTestWiring {
    private ApprovalSystemSlaOriginTestWiring() { }
    static void bind(ApprovalSystemSlaNativeSourcePostgresTest f) {
        f.f.sla.bindProducer(lease -> {
            try {
                var fixture=new ApprovalSystemSlaSourceCryptoPostgresTest(); fixture.fixture=f;
                fixture.keys=new SystemSlaSourceKeys(f.json,ApprovalSystemSlaSourceCryptoPostgresTest.OWNER.toJSONString(),
                        ApprovalSystemSlaSourceCryptoPostgresTest.TRANSPORT.toJSONString(),ApprovalSystemSlaSourceCryptoPostgresTest.jwks(ApprovalSystemSlaSourceCryptoPostgresTest.AUTH),List.of());
                var seal=f.source.produce(lease);
                fixture.exchange=new SystemSlaSourceProofIssuer(f.json,fixture.keys,Clock.systemUTC()).issue(seal);
                var verified=new SystemSlaSourceAttestationVerifier(f.json,fixture.keys,Clock.systemUTC()).verify(fixture.response(fixture.claims()),fixture.exchange);
                var recipients=java.util.stream.StreamSupport.stream(verified.recipients().spliterator(),false)
                        .filter(seat -> seat.get("eligible").booleanValue() && seal.taskEligible(SystemSlaJson.uuid(seat,"taskId"))).map(seat -> seat.get("userId").longValue()).toList();
                String revision="asla-"+SystemSlaJson.sha((verified.authorityRevision()+'\n'+seal.nativeVectorSha256()).getBytes(StandardCharsets.UTF_8));
                return new ApprovalWorkflowQuorumSlaRuntime.ProducerAuthority(recipients,revision,verified.expiresAt(),() -> f.source.unchanged(seal),
                        event -> new SystemSlaSourceWitnessJournal(new NamedParameterJdbcTemplate(f.f.jdbc),f.json).record(event,verified));
            } catch (RuntimeException failure) { throw failure; } catch (Exception failure) { throw new AssertionError(failure); }
        });
    }
    static ApprovalSystemSlaSourceCryptoPostgresTest original(ApprovalSystemSlaNativeSourcePostgresTest f,ApprovalWorkflowQuorumSlaRuntime.Lease lease) throws Exception {
        var fixture=new ApprovalSystemSlaSourceCryptoPostgresTest(); fixture.fixture=f;
        fixture.keys=new SystemSlaSourceKeys(f.json,ApprovalSystemSlaSourceCryptoPostgresTest.OWNER.toJSONString(),
                ApprovalSystemSlaSourceCryptoPostgresTest.TRANSPORT.toJSONString(),ApprovalSystemSlaSourceCryptoPostgresTest.jwks(ApprovalSystemSlaSourceCryptoPostgresTest.AUTH),List.of());
        fixture.exchange=new SystemSlaSourceProofIssuer(f.json,fixture.keys,Clock.systemUTC()).issue(f.source.produce(lease));
        fixture.verifier=new SystemSlaSourceAttestationVerifier(f.json,fixture.keys,Clock.systemUTC()); return fixture;
    }
}

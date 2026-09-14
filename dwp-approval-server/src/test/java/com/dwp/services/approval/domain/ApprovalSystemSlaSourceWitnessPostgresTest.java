package com.dwp.services.approval.domain;

import static org.assertj.core.api.Assertions.*;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.systemslaauthority.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ApprovalSystemSlaSourceWitnessPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine").withLabel("dwp-owner","cicero-v30-original-source-witness");
    ApprovalSystemSlaNativeSourcePostgresTest f;
    ApprovalWorkflowQuorumSlaRuntime.Lease lease;
    NamedParameterJdbcTemplate jdbc;
    @BeforeEach void setup() {
        f=new ApprovalSystemSlaNativeSourcePostgresTest(); f.f=new ApprovalWorkflowQuorumPostgresFixture(); f.f.initialize(PG);
        jdbc=new NamedParameterJdbcTemplate(f.f.jdbc); f.source=new ApprovalSystemSlaNativeSource(jdbc,f.mapper); lease=f.ready(true);
    }
    SystemSlaSourceKeys keys() {
        return new SystemSlaSourceKeys(f.json,ApprovalSystemSlaSourceCryptoPostgresTest.OWNER.toJSONString(),ApprovalSystemSlaSourceCryptoPostgresTest.TRANSPORT.toJSONString(),
                ApprovalSystemSlaSourceCryptoPostgresTest.jwks(ApprovalSystemSlaSourceCryptoPostgresTest.AUTH),List.of());
    }
    @Test void sameTransactionCaptureBindsOriginalClassFullProofHashesAndHistoricalSignaturesWithoutReadHealing() {
        assertThat(f.f.sla.finish(lease)).isTrue(); var proof=f.notification(f.notificationRequest()); String before=f.database();
        var seal=f.f.tx.execute(status -> f.source.deliver(proof)); var row=seal.originalWitness();
        assertThat(row.get("original_classification").asText()).isEqualTo(seal.bindings().at("/source/request/dataClassification").asText());
        assertThat(row.get("source_body").asText()).isNotBlank(); assertThat(row.get("owner_jti")).isNotEqualTo(row.get("transport_jti"));
        assertThat(row.get("auth_jti")).isNotEqualTo(row.get("owner_jti"));
        assertThatCode(() -> new SystemSlaSourceWitnessVerifier(f.json,keys()).verify(row,seal.bindings())).doesNotThrowAnyException();
        assertThatCode(() -> new SystemSlaSourceProofIssuer(f.json,keys(),Clock.systemUTC()).issue(seal)).doesNotThrowAnyException();
        assertThat(f.database()).isEqualTo(before);
        for (String mutation:List.of("UPDATE apr_system_sla_source_witnesses SET original_classification='RESTRICTED' WHERE event_id=?",
                "DELETE FROM apr_system_sla_source_witnesses WHERE event_id=?")) {
            assertThatThrownBy(() -> f.f.jdbc.update(mutation,UUID.fromString(row.get("event_id").asText()))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThat(f.database()).isEqualTo(before);
        assertThat(f.f.jdbc.queryForObject("SELECT has_table_privilege('dwp_approval_retention_executor','apr_system_sla_source_witnesses','DELETE')",Boolean.class)).isFalse();
    }
    @Test void missingHistoricalWitnessIsExplicitUnknownBeforeFreshAuthAndCannotBeRepairedFromCurrentClassification() {
        var old=new ApprovalWorkflowQuorumSlaRuntime(jdbc,f.mapper,f.f.tx,f.f,new AuditOutboxRecorder(jdbc,f.mapper,"dwp-approval-server","test","test"));
        assertThat(old.finish(lease)).isTrue(); var proof=f.notification(f.notificationRequest()); String before=f.database();
        assertThatThrownBy(() -> f.f.tx.execute(status -> f.source.deliver(proof))).isInstanceOf(BaseException.class).hasMessageContaining("UNKNOWN_ORIGINAL_SYSTEM_SLA_SOURCE");
        assertThat(f.database()).isEqualTo(before);
    }
    @Test void originalClassAndProofJtiHashCaptureTimeOrBodyTamperNeverBecomesFreshAuthority() {
        assertThat(f.f.sla.finish(lease)).isTrue(); var seal=f.f.tx.execute(status -> f.source.deliver(f.notification(f.notificationRequest())));
        for (var mutation:List.<java.util.function.Consumer<ObjectNode>>of(row -> row.put("original_classification","RESTRICTED"),
                row -> row.put("owner_jti",UUID.randomUUID().toString()),row -> row.put("transport_jti",UUID.randomUUID().toString()),
                row -> row.put("auth_jti",UUID.randomUUID().toString()),row -> row.put("native_vector_sha256","0".repeat(64)),
                row -> row.put("proof_sha256","0".repeat(64)),row -> row.put("created_at","2000-01-01T00:00:00Z"),
                row -> row.put("source_body",Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8))))) {
            var row=(ObjectNode) seal.originalWitness(); mutation.accept(row);
            assertThatThrownBy(() -> new SystemSlaSourceWitnessVerifier(f.json,keys()).verify(row,seal.bindings())).isInstanceOf(BaseException.class);
        }
        String before=f.database(); f.f.jdbc.update("UPDATE apr_requests SET data_classification='RESTRICTED',version=version+1 WHERE request_id=?",f.f.request);
        String tampered=f.database(); assertThat(tampered).isNotEqualTo(before);
        assertThatThrownBy(() -> f.f.tx.execute(status -> f.source.deliver(f.notification(f.notificationRequest())))).isInstanceOf(BaseException.class);
        assertThat(f.database()).isEqualTo(tampered);
    }
    @Test void forgedSameRoleSqlRowAndTenantSelectorAreDeniedCryptographicallyBeforeAnyFreshAuthHttp() {
        f.f.sla.bindProducer(value -> {
            try {
                var fixture=ApprovalSystemSlaOriginTestWiring.original(f,value); var original=fixture.verifier.verify(fixture.response(fixture.claims()),fixture.exchange);
                String revision="asla-"+SystemSlaJson.sha((original.authorityRevision()+'\n'+original.exchange().seal().nativeVectorSha256()).getBytes(StandardCharsets.UTF_8));
                return new ApprovalWorkflowQuorumSlaRuntime.ProducerAuthority(List.of(101L,102L,103L),revision,original.expiresAt(),() -> f.source.unchanged(original.exchange().seal()),event -> forgeSqlAfterPrivateCapture(event,original));
            } catch (RuntimeException failure) { throw failure; } catch (Exception failure) { throw new AssertionError(failure); }
        });
        assertThat(f.f.sla.finish(lease)).isTrue(); var proof=f.notification(f.notificationRequest()); var calls=new java.util.concurrent.atomic.AtomicInteger();
        var client=new AuthApprovalSystemSlaAuthorityClient(java.net.URI.create("http://127.0.0.1:1"+SystemSlaSourceProtocol.PATH),new SystemSlaSourceAttestationVerifier(f.json,keys(),Clock.systemUTC())) {
            @Override public SystemSlaSourceAttestationVerifier.Verified evaluate(SystemSlaSourceProofIssuer.Exchange exchange) { calls.incrementAndGet(); throw new AssertionError("Forged historical evidence must not mint fresh Auth HTTP"); }
        };
        var current=new SystemSlaCurrentSource(f.source,() -> new SystemSlaSourceProofIssuer(f.json,keys(),Clock.systemUTC()),() -> client,
                () -> null,() -> null,f.f.tx,Clock.systemUTC(),true);
        String before=f.database(); assertThatThrownBy(() -> current.recipients(proof)).isInstanceOf(BaseException.class);
        assertThat(calls.get()).isZero(); assertThat(f.database()).isEqualTo(before);
    }
    void forgeSqlAfterPrivateCapture(UUID event,SystemSlaSourceAttestationVerifier.Verified original) {
        try {
            f.f.jdbc.execute("SAVEPOINT private_origin_capture"); new SystemSlaSourceWitnessJournal(jdbc,f.json).record(event,original);
            var row=new LinkedHashMap<>(f.f.jdbc.queryForMap("SELECT * FROM apr_system_sla_source_witnesses WHERE event_id=?",event));
            f.f.jdbc.execute("ROLLBACK TO SAVEPOINT private_origin_capture");
            assertThatThrownBy(() -> new SystemSlaSourceWitnessJournal(jdbc,f.json).record(event,original)).isInstanceOf(BaseException.class);
            var real=SignedJWT.parse((String) row.get("auth_attestation"));
            var forged=new JWSObject(real.getHeader(),real.getPayload()); forged.sign(new RSASSASigner(ApprovalSystemSlaNativeSourcePostgresTest.OTHER));
            row.put("auth_attestation",forged.serialize()); row.put("proof_sha256",f.json.digest(Map.of("sourceBodySha256",SystemSlaJson.sha((byte[]) row.get("source_body")),
                    "transportProof",row.get("transport_proof"),"authAttestation",row.get("auth_attestation"))));
            jdbc.queryForObject("SELECT set_config('dwp.approval.system_sla.tenant','42',true)",Map.of(),String.class);
            // Column names come only from this fixed table's DB metadata, never request input.
            if (row.keySet().stream().anyMatch(key -> !key.matches("[a-z][a-z0-9_]*"))) throw new AssertionError("Unexpected fixture column");
            jdbc.update("INSERT INTO apr_system_sla_source_witnesses("+String.join(",",row.keySet())+") VALUES("+row.keySet().stream().map(key -> ":"+key).collect(java.util.stream.Collectors.joining(","))+")",row);
        } catch (RuntimeException failure) { throw failure; } catch (Exception failure) { throw new AssertionError(failure); }
    }
    @Test void concurrentFinishHasExactlyOneEventAndWitnessAndFailedPostCaptureRollsBackBoth() throws Exception {
        var barrier=new CyclicBarrier(2); var pool=Executors.newFixedThreadPool(2);
        try {
            var jobs=List.of(pool.submit(() -> { barrier.await(); return f.f.sla.finish(lease); }),pool.submit(() -> { barrier.await(); return f.f.sla.finish(lease); }));
            assertThat(List.of(jobs.get(0).get(30,TimeUnit.SECONDS),jobs.get(1).get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(f.f.jdbc.queryForObject("SELECT count(*) FROM apr_system_sla_source_witnesses WHERE request_id=?",Long.class,f.f.request)).isEqualTo(1L);
        } finally { pool.shutdownNow(); }
        setup(); String before=f.database();
        f.f.sla.bindProducer(value -> {
            try {
                var fixture=ApprovalSystemSlaOriginTestWiring.original(f,value); var original=fixture.verifier.verify(fixture.response(fixture.claims()),fixture.exchange);
                var calls=new java.util.concurrent.atomic.AtomicInteger(); String revision="asla-"+SystemSlaJson.sha((original.authorityRevision()+'\n'+original.exchange().seal().nativeVectorSha256()).getBytes(StandardCharsets.UTF_8));
                return new ApprovalWorkflowQuorumSlaRuntime.ProducerAuthority(List.of(101L,102L,103L),revision,original.expiresAt(),() -> { if (calls.incrementAndGet()>1) throw SystemSlaJson.changed(); },
                        event -> new SystemSlaSourceWitnessJournal(jdbc,f.json).record(event,original));
            } catch (RuntimeException failure) { throw failure; } catch (Exception failure) { throw new AssertionError(failure); }
        });
        assertThatThrownBy(() -> f.f.sla.finish(lease)).isInstanceOf(BaseException.class); assertThat(f.database()).isEqualTo(before);
    }
    @Test void sqlCanonicalBoundedNumbersAndCrossTransactionInsertAreNotHistoricalRepairPermissions() {
        for (String value:List.of("1.0","9007199254740992","-1")) assertThatThrownBy(() -> f.f.jdbc.queryForObject("SELECT system_sla_witness_canonical_json(?::jsonb)",String.class,value)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(f.f.sla.finish(lease)).isTrue(); String before=f.database();
        assertThatThrownBy(() -> f.f.jdbc.update("INSERT INTO apr_system_sla_source_witnesses SELECT * FROM apr_system_sla_source_witnesses WHERE request_id=?",f.f.request)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(f.database()).isEqualTo(before);
    }
    @Test void privateOriginalFromAnotherTransactionOrDataSourceCannotAuthorizeJournalWrites() throws Exception {
        var captured=f.f.tx.execute(status -> {
            try {
                var fixture=ApprovalSystemSlaOriginTestWiring.original(f,lease); return fixture.verifier.verify(fixture.response(fixture.claims()),fixture.exchange);
            } catch (RuntimeException failure) { throw failure; } catch (Exception failure) { throw new AssertionError(failure); }
        });
        String before=f.database();
        assertThatThrownBy(() -> f.f.tx.executeWithoutResult(status -> new SystemSlaSourceWitnessJournal(jdbc,f.json).record(UUID.randomUUID(),captured))).isInstanceOf(BaseException.class);
        assertThat(captured.exchange().seal().capturedIn(new org.postgresql.ds.PGSimpleDataSource(),1)).isFalse();
        assertThat(f.database()).isEqualTo(before);
    }
}

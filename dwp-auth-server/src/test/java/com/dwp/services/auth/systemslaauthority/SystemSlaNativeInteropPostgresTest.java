package com.dwp.services.auth.systemslaauthority;

import static org.assertj.core.api.Assertions.*;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.*;
import com.dwp.services.approval.integration.ApprovalIntegrationOutboxRepository;
import com.dwp.services.approval.integration.ApprovalIntegrationPublisher;
import com.dwp.services.approval.systemslaauthority.*;
import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;

/** Real Approval workload, real Auth PG/Redis, real signed HTTP. Test start authority is never a production bean. */
@Testcontainers
class SystemSlaNativeInteropPostgresTest {
    @org.testcontainers.junit.jupiter.Container static final PostgreSQLContainer<?> APPROVAL = pg("approval");
    @org.testcontainers.junit.jupiter.Container static final PostgreSQLContainer<?> AUTH = pg("auth");
    @org.testcontainers.junit.jupiter.Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379).withLabel("dwp-owner", "cicero-system-native-interop");
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    static final com.dwp.services.approval.systemslaauthority.SystemSlaJson JSON = new com.dwp.services.approval.systemslaauthority.SystemSlaJson(MAPPER);
    static final RSAKey OWNER = key("approval_sla_owner_interop"), TRANSPORT = key("approval_sla_transport_interop"), ATTEST = key("auth_sla_attestation_interop");
    static final RSAKey NF_TRANSPORT = key("notification-sla-recipient-transport:interop"), NF_ATTEST = key("approval-sla-recipient-authority:interop");
    static JdbcTemplate approval, auth;
    static NamedParameterJdbcTemplate namedApproval, namedAuth;
    static TransactionTemplate tx;
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    ApprovalSystemSlaNativeSource nativeSource;
    ApprovalWorkflowQuorumRuntime runtime;
    ApprovalWorkflowQuorumSlaRuntime sla;
    ApprovalWorkflowQuorumSlaRuntime.Lease lease;
    UUID request, workflow, workflowVersion;
    int count;
    boolean forbidGenericSlaFallback;

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class CapturingRelayConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        CapturingPublisher approvalSystemSlaCapturingPublisher() {
            return new CapturingPublisher();
        }
    }

    static final class CapturingPublisher implements ApprovalIntegrationPublisher {
        final java.util.concurrent.ConcurrentLinkedQueue<ApprovalIntegrationOutboxRepository.PendingEvent> events =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        @Override public void publish(ApprovalIntegrationOutboxRepository.PendingEvent event) { events.add(event); }
    }
    @BeforeAll static void databases() {
        var root = Files.isDirectory(Path.of("dwp-auth-server")) ? Path.of(".") : Path.of("..");
        var approvalDs = ds(APPROVAL); var authDs = ds(AUTH);
        migrate(approvalDs, root, "dwp-approval-server"); migrate(authDs, root, "dwp-auth-server");
        approval = new JdbcTemplate(approvalDs); auth = new JdbcTemplate(authDs);
        namedApproval = new NamedParameterJdbcTemplate(approvalDs); namedAuth = new NamedParameterJdbcTemplate(authDs);
        tx = new TransactionTemplate(new DataSourceTransactionManager(approvalDs)); tx.setIsolationLevel(2);
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379)); connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection); redis.afterPropertiesSet();
        approval.queryForObject("SELECT seed_approval_tenant(42)", Object.class);
        auth.update("INSERT INTO com_tenants(tenant_id,code,name) VALUES(42,'SYSTEM_NATIVE_INTEROP','Disposable native interop') ON CONFLICT(tenant_id) DO NOTHING");
        long role = auth.queryForObject("INSERT INTO com_roles(tenant_id,code,name) VALUES(42,'SLA_REVIEWER','Native SLA test reviewer') RETURNING role_id", Long.class);
        for (String resource : List.of("APP.APPROVALS", "ACTION.APPROVAL_TASK")) auth.update("INSERT INTO com_resources(tenant_id,type,key,name) VALUES(42,?,?,?)", resource.startsWith("APP.") ? "APP" : "ACTION", resource, resource);
        for (var grant : List.of(List.of("APP.APPROVALS", "VIEW"), List.of("ACTION.APPROVAL_TASK", "VIEW"), List.of("ACTION.APPROVAL_TASK", "APPROVE")))
            auth.update("INSERT INTO com_role_permissions(tenant_id,role_id,resource_id,permission_id) SELECT 42,?,resource_id,permission_id FROM com_resources,com_permissions WHERE tenant_id=42 AND key=? AND code=?", role, grant.get(0), grant.get(1));
        UUID rs = UUID.randomUUID();
        auth.update("INSERT INTO com_admin_resource_sets(resource_set_id,tenant_id,resource_set_key,name,resource_type) VALUES(?,42,'RS_APPROVALS','Native SLA','APP')", rs);
        auth.update("INSERT INTO com_admin_resource_set_members(tenant_id,resource_set_id,resource_type,resource_key) VALUES(42,?,'APP','APP.APPROVALS')", rs);
        auth.batchUpdate("INSERT INTO com_users(user_id,tenant_id,display_name,email,identity_plane,person_public_id) VALUES(?,42,'Native SLA subject',?,'TENANT',?)", java.util.stream.LongStream.rangeClosed(901101,902100).mapToObj(user -> new Object[]{user,user+"@native-sla.test",person(user)}).toList());
        auth.update("INSERT INTO com_role_members(tenant_id,role_id,user_id) SELECT 42,?,user_id FROM com_users WHERE tenant_id=42 AND user_id BETWEEN 901101 AND 902100", role);
        approval.update("UPDATE apr_policy_rules SET version=version+1 WHERE tenant_id=42 AND policy_key IN('BLOCK_SELF_APPROVAL','REQUIRE_REJECT_REASON','SLA_ESCALATION')");
        approval.update("INSERT INTO apr_policy_rule_versions(policy_version_id,tenant_id,policy_id,version_number,enforcement_mode,severity,lifecycle_state,rule_payload,change_reason,submitted_by,submitted_at,published_by,published_at,review_comment) SELECT gen_random_uuid(),tenant_id,policy_id,version+1,enforcement_mode,severity,lifecycle_state,rule_payload,'Real disposable independent review fixture',90,clock_timestamp()-interval '2 minutes',91,clock_timestamp()-interval '1 minute','Interop only' FROM apr_policy_rules WHERE tenant_id=42 AND policy_key IN('BLOCK_SELF_APPROVAL','REQUIRE_REJECT_REASON','SLA_ESCALATION')");
    }
    @AfterAll static void close() { if (connection != null) connection.destroy(); }
    void ready(int size) {
        count = size; request = UUID.randomUUID(); workflowVersion = UUID.randomUUID();
        workflow = approval.queryForObject("SELECT workflow_id FROM apr_workflow_definitions WHERE tenant_id=42 AND workflow_key='ACCESS_EXCEPTION'", UUID.class);
        int version = approval.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM apr_workflow_versions WHERE tenant_id=42 AND workflow_id=?", Integer.class, workflow);
        var definition = ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(new ApprovalWorkflowQuorumDefinition.Stage("REVIEW", "Review", "SLA_REVIEWER", new ApprovalWorkflowQuorum.Rule(ApprovalWorkflowQuorum.Mode.ALL, null),15,List.of())));
        approval.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,definition_sha256,lifecycle_state,published_at,published_by) VALUES(?,42,?,?,?::jsonb,?,'PUBLISHED',now(),91)", workflowVersion,workflow,version,definition.canonicalJson(),definition.sha256());
        UUID form = approval.queryForObject("SELECT immutable.form_version_id FROM apr_form_workflow_bindings binding JOIN apr_forms form ON form.tenant_id=binding.tenant_id AND form.form_id=binding.form_id JOIN apr_form_versions immutable ON immutable.tenant_id=form.tenant_id AND immutable.form_id=form.form_id AND immutable.version_number=form.current_version WHERE binding.tenant_id=42 AND binding.workflow_id=? AND binding.binding_type='DEFAULT' AND binding.lifecycle_state='ACTIVE'",UUID.class,workflow);
        approval.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,requester_user_id,requester_person_public_id,status,submitted_at,due_at) VALUES(?,42,?,?,?,'Native SLA',99,?,'IN_REVIEW',now(),now()+interval '60 minutes')",request,"NATIVE-"+request,workflowVersion,form,person(99));
        approval.update("INSERT INTO apr_request_payloads(tenant_id,request_id,payload,payload_sha256,schema_version) VALUES(42,?,'{}'::jsonb,?,1)",request,JSON.digest(Map.of()));
        var startAuthority = new ApprovalWorkflowQuorumAuthority() {
            @Override public ApprovalWorkflowQuorum.CandidatePool candidates(ApprovalWorkflowQuorum.Pins pins,UUID id,ApprovalWorkflowQuorumDefinition.Stage stage,Instant now) {
                if (forbidGenericSlaFallback) throw new AssertionError("SLA must not borrow generic workflow candidate authority");
                return new ApprovalWorkflowQuorum.CandidatePool(42,pins.workflowVersionId(),stage.candidateRole(),java.util.stream.LongStream.range(901101,901101+count).mapToObj(user -> new ApprovalWorkflowQuorum.Subject(42,user,person(user),ApprovalWorkflowQuorum.IdentityPlane.TENANT,true,Set.of("SLA_REVIEWER"),true)).toList(),"test-start-only",true,false,now,now.plusSeconds(60));
            }
            @Override public ApprovalWorkflowQuorum.CurrentAuthority voter(ApprovalWorkflowQuorum.Snapshot snapshot,long actor,long principal,Instant now) { throw new AssertionError("No voter fixture fallback"); }
        };
        var audit = new AuditOutboxRecorder(namedApproval,MAPPER,"dwp-approval-server","interop","interop");
        runtime = new ApprovalWorkflowQuorumRuntime(namedApproval,MAPPER,tx,startAuthority,audit); sla = new ApprovalWorkflowQuorumSlaRuntime(namedApproval,MAPPER,tx,startAuthority,audit);
        var pins = runtime.canonicalPins(42,request,definition); runtime.start(42,request,pins,definition);
        approval.update("UPDATE apr_quorum_sla_timers SET due_at=now()-interval '1 minute' WHERE request_id=?",request);
        lease = sla.claim("native-interop",60,100).stream().filter(value -> value.requestId().equals(request)).findFirst().orElseThrow();
        nativeSource = new ApprovalSystemSlaNativeSource(namedApproval,MAPPER);
    }
    @Test void actualThousandTasksAndCompletedOutboxBindFreshSignedAuthHttpBeforeNotificationSignature() throws Exception {
        ready(1000);
        try (var server = new SystemSlaEmbeddedServer(authService(),true)) {
            var source = source(server); var current = tx.execute(status -> source.produce(lease));
            assertThat(current.recipients()).hasSize(1000); assertThat(current.recipients().findValues("eligible")).allMatch(JsonNode::booleanValue);
            ApprovalSystemSlaProducerConfiguration.bind(sla,producer(server,source,null)); forbidGenericSlaFallback = true;
            assertThat(sla.finish(lease)).isTrue(); var proof = notification(); var response = source.recipients(proof);
            var token = SignedJWT.parse(response.get("attestation")); assertThat(token.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(NF_ATTEST))).isTrue();
            var claims = JSON.parse(token.getPayload().toBytes()); assertThat(claims.size()).isEqualTo(11);
            var profile = claims.get("profile"); assertThat(profile.size()).isEqualTo(13); assertThat(profile.get("recipients")).hasSize(1000);
            assertThat(profile.get("recipients").get(999).size()).isEqualTo(6); assertThat(profile.get("authorityRevision").asText()).matches("asla-[a-f0-9]{64}");
            assertThat(profile.get("originalEnvelopeSha256")).isEqualTo(proof.request().get("originalEnvelopeSha256"));
            assertThat(profile.get("canonicalEnvelopeSha256")).isEqualTo(proof.request().get("canonicalEnvelopeSha256"));
            assertThat(claims.get("profileSha256").asText()).isEqualTo(JSON.digest(profile)); assertThat(claims.get("exp")).isEqualTo(profile.get("validUntil"));
        }
    }
    @Test void actualCurrentAuthRoleRevocationKeepsFrozenSeatsExplicitlyDeniedAndChangesAuthorityRevision() throws Exception {
        ready(3);
        try (var server = new SystemSlaEmbeddedServer(authService(),true)) {
            var source = source(server); var before = tx.execute(status -> source.produce(lease));
            var row = auth.queryForMap("DELETE FROM com_role_members WHERE tenant_id=42 AND user_id=901101 RETURNING role_id,user_id");
            try {
                var after = tx.execute(status -> source.produce(lease)); assertThat(after.authorityRevision()).isNotEqualTo(before.authorityRevision());
                assertThat(after.recipients()).hasSize(3); assertThat(after.recipients().get(0).get("reason").asText()).isEqualTo("ROLE_MISSING");
                assertThat(after.recipients().get(0).get("eligible").booleanValue()).isFalse();
            } finally { auth.update("INSERT INTO com_role_members(tenant_id,role_id,user_id) VALUES(42,?,?)",row.get("role_id"),row.get("user_id")); }
        }
    }
    @Test void genuineNativeVoteAfterOriginalEventKeepsOriginalPinsAndFreshAuthDeniesOnlyItsCompletedSeat() throws Exception {
        ready(3);
        try (var server = new SystemSlaEmbeddedServer(authService(),true)) {
            var source = source(server); ApprovalSystemSlaProducerConfiguration.bind(sla,producer(server,source,null)); forbidGenericSlaFallback=true;
            assertThat(sla.finish(lease)).isTrue(); var original = notification(); var prior = JSON.parse(SignedJWT.parse(source.recipients(original).get("attestation")).getPayload().toBytes()).get("profile");
            // Only the vote command's test authority is a fixture; SYSTEM authorization remains real signed Auth PG/Redis/HTTP.
            var voteAuthority = new ApprovalWorkflowQuorumAuthority() {
                @Override public ApprovalWorkflowQuorum.CandidatePool candidates(ApprovalWorkflowQuorum.Pins pins,UUID id,ApprovalWorkflowQuorumDefinition.Stage stage,Instant now) {
                    return new ApprovalWorkflowQuorum.CandidatePool(42,pins.workflowVersionId(),stage.candidateRole(),java.util.stream.LongStream.range(901101,901104)
                            .mapToObj(user -> new ApprovalWorkflowQuorum.Subject(42,user,person(user),ApprovalWorkflowQuorum.IdentityPlane.TENANT,true,Set.of("SLA_REVIEWER"),true)).toList(),"fixture-vote-only",true,false,now,now.plusSeconds(30));
                }
                @Override public ApprovalWorkflowQuorum.CurrentAuthority voter(ApprovalWorkflowQuorum.Snapshot snapshot,long actor,long principal,Instant now) {
                    assertThat(actor).isEqualTo(901101); assertThat(principal).isEqualTo(actor);
                    var seat = new ApprovalWorkflowQuorum.Subject(42,actor,person(actor),ApprovalWorkflowQuorum.IdentityPlane.TENANT,true,Set.of("SLA_REVIEWER"),true);
                    return new ApprovalWorkflowQuorum.CurrentAuthority(ApprovalWorkflowQuorum.AccessMode.NORMAL,"fixture-vote-only",now,now.plusSeconds(30),seat,seat,null);
                }
            };
            var stage = approval.queryForMap("SELECT step_id,generation,version FROM apr_quorum_stage_runtime WHERE request_id=?",request);
            var task = approval.queryForMap("SELECT task_id,version FROM apr_tasks WHERE request_id=? AND assignee_user_id=901101",request);
            var definition = ApprovalWorkflowQuorumDefinition.compile(approval.queryForObject("SELECT definition::text FROM apr_workflow_versions WHERE workflow_version_id=?",String.class,workflowVersion));
            var pins = runtime.canonicalPins(42,request,definition);
            var voted = new ApprovalWorkflowQuorumRuntime(namedApproval,MAPPER,tx,voteAuthority,new AuditOutboxRecorder(namedApproval,MAPPER,"dwp-approval-server","interop","interop"))
                    .vote(new ApprovalWorkflowQuorumRuntime.VoteCommand(42,request,(UUID) stage.get("step_id"),((Number) stage.get("generation")).longValue(),(UUID) task.get("task_id"),
                            ((Number) task.get("version")).longValue(),((Number) stage.get("version")).longValue(),pins,901101,901101,ApprovalWorkflowQuorum.Decision.APPROVE,""));
            assertThat(voted.requestStatus()).isEqualTo("IN_REVIEW"); assertThat(voted.approved()).isEqualTo(1);
            String before = business(); var response = source.recipients(notification()); var signed = SignedJWT.parse(response.get("attestation"));
            assertThat(signed.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(NF_ATTEST))).isTrue(); var profile = JSON.parse(signed.getPayload().toBytes()).get("profile");
            assertThat(profile.get("recipients")).hasSize(3); assertThat(profile.at("/recipients/0/eligible").booleanValue()).isFalse();
            assertThat(profile.at("/recipients/0/reason").asText()).isEqualTo("TASK_STATE_NOT_ELIGIBLE");
            for (int index=1;index<3;index++) assertThat(profile.at("/recipients/"+index+"/eligible").booleanValue()).isTrue();
            for (int index=0;index<3;index++) assertThat(profile.at("/recipients/"+index+"/taskVersion")).isEqualTo(prior.at("/recipients/"+index+"/taskVersion"));
            for (String key : List.of("originalEnvelopeSha256","canonicalEnvelopeSha256","recipientSnapshotSha256","sourcePinsSha256","generation","leaseEpoch")) assertThat(profile.get(key)).isEqualTo(prior.get(key));
            assertThat(profile.get("authorityRevision")).isNotEqualTo(prior.get("authorityRevision")); assertThat(notification().request()).isEqualTo(original.request()); assertThat(business()).isEqualTo(before);
        }
    }
    @Test void nativeSourceRevokeAfterRealAuthHttpIsZeroWriteAndDefaultDisabledAuthFailsClosed() throws Exception {
        ready(3); long events = approval.queryForObject("SELECT count(*) FROM apr_integration_outbox WHERE request_id=?",Long.class,request);
        var original = authService();
        try (var server = new SystemSlaEmbeddedServer(original,true)) { assertThatThrownBy(() -> tx.execute(status -> source(server,true).produce(lease))).isInstanceOf(BaseException.class); }
        assertThat(approval.queryForObject("SELECT count(*) FROM apr_integration_outbox WHERE request_id=?",Long.class,request)).isEqualTo(events);
        try (var disabled = new SystemSlaEmbeddedServer(original,false)) { assertThatThrownBy(() -> tx.execute(status -> source(disabled).produce(lease))).isInstanceOf(BaseException.class); }
    }
    @Test void realAuthRoleRevokeOnFreshPostWriteRoundTripRollsBackEventAuditAndTimerCompletion() throws Exception {
        ready(3); String before = business(); var row = auth.queryForMap("SELECT role_id,user_id FROM com_role_members WHERE tenant_id=42 AND user_id=901101");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var server = new SystemSlaEmbeddedServer(authService(),true)) {
            ApprovalSystemSlaProducerConfiguration.bind(sla,producer(server,source(server),() -> {
                if (calls.incrementAndGet()==2) auth.update("DELETE FROM com_role_members WHERE tenant_id=42 AND user_id=901101");
            }));
            forbidGenericSlaFallback = true;
            assertThatThrownBy(() -> sla.finish(lease)).isInstanceOf(BaseException.class);
            assertThat(calls.get()).isEqualTo(2); assertThat(business()).isEqualTo(before);
        } finally { auth.update("INSERT INTO com_role_members(tenant_id,role_id,user_id) VALUES(42,?,?) ON CONFLICT DO NOTHING",row.get("role_id"),row.get("user_id")); }
    }
    @Test void nativeTaskAdvanceAfterFreshAuthCannotReleaseNotificationAttestationAndRollsBackItsOwnWrite() {
        ready(3);
        try (var server = new SystemSlaEmbeddedServer(authService(),true)) {
            ApprovalSystemSlaProducerConfiguration.bind(sla,producer(server,source(server),null)); forbidGenericSlaFallback=true;
            assertThat(sla.finish(lease)).isTrue(); var original = notification(); String before = business();
            assertThatThrownBy(() -> source(server,false,true).recipients(original)).isInstanceOf(BaseException.class);
            assertThat(business()).isEqualTo(before); assertThat(notification().request()).isEqualTo(original.request());
        }
    }
    @Test void actualApprovalSchedulerCreatesAndRelaysDueOutboxBeforeNotificationRechecksFreshAuthority() throws Exception {
        ready(3);
        approval.update("UPDATE apr_quorum_sla_timers SET due_at=clock_timestamp()+interval '1 hour' WHERE request_id=? AND timer_id<>?",request,lease.timerId());
        approval.update("UPDATE apr_quorum_sla_timers SET lease_until=clock_timestamp()-interval '1 second' WHERE timer_id=?",lease.timerId());
        var root = Files.isDirectory(Path.of("dwp-auth-server")) ? Path.of(".") : Path.of("..");
        try (var server = new SystemSlaEmbeddedServer(authService(),true)) {
            var properties = new LinkedHashMap<String,Object>(); properties.put("server.port",0); properties.put("otel.sdk.disabled",true);
            properties.put("dwp.observability.api-history.enabled",false); properties.put("spring.datasource.url",APPROVAL.getJdbcUrl()); properties.put("spring.datasource.username",APPROVAL.getUsername()); properties.put("spring.datasource.password",APPROVAL.getPassword());
            properties.put("spring.flyway.locations","filesystem:"+root.resolve("dwp-approval-server/src/main/resources/db/migration")+",filesystem:"+root.resolve("dwp-core/src/main/resources/db/migration"));
            properties.put("dwp.approval.system-sla.source.enabled",true); properties.put("dwp.approval.system-sla.source.owner-private-jwk",OWNER.toJSONString()); properties.put("dwp.approval.system-sla.source.transport-private-jwk",TRANSPORT.toJSONString()); properties.put("dwp.approval.system-sla.source.attestation-public-jwks",jwks(ATTEST));
            properties.put("dwp.approval.system-sla.source.auth-base-url",server.endpoint().toString().replace(SystemSlaProtocol.PATH,""));
            properties.put("dwp.approval.system-sla.notification.transport-public-jwks",jwks(NF_TRANSPORT)); properties.put("dwp.approval.system-sla.notification.attestation-private-jwk",NF_ATTEST.toJSONString()); properties.put("dwp.approval.system-sla.notification.attestation-public-jwks",jwks(NF_ATTEST));
            properties.put("dwp.approval.workflow-quorum.sla.enabled",true); properties.put("dwp.approval.workflow-quorum.sla.batch-size",1);
            properties.put("dwp.approval.workflow-quorum.sla.poll-delay-ms",250); properties.put("dwp.approval.integration-relay.enabled",true);
            properties.put("dwp.approval.integration-relay.poll-delay-ms",250);
            String[] arguments = properties.entrySet().stream().map(value -> "--"+value.getKey()+"="+value.getValue()).toArray(String[]::new);
            try (var boot = new org.springframework.boot.builder.SpringApplicationBuilder(
                    com.dwp.services.approval.ApprovalServerApplication.class,CapturingRelayConfiguration.class).run(arguments)) {
                var database = boot.getBean(JdbcTemplate.class);
                assertThat(database.queryForObject("SELECT current_database()",String.class)).isEqualTo(APPROVAL.getDatabaseName());
                assertThat(boot.getBean(SystemSlaProducerSource.class)).isNotNull();
                assertThat(boot.getBean(ApprovalWorkflowQuorumSlaWorker.class)).isNotNull();
                var captured = boot.getBean(CapturingPublisher.class);
                await(() -> database.queryForObject("SELECT count(*) FROM apr_integration_outbox WHERE request_id=? AND event_type LIKE 'Approval.Quorum.Sla%' AND status='PUBLISHED'",Integer.class,request)==1);
                var ownEvents = captured.events.stream()
                        .filter(event -> event.requestId().equals(request))
                        .filter(event -> event.eventType().startsWith("Approval.Quorum.Sla"))
                        .toList();
                assertThat(ownEvents).hasSize(1);
                var relayed = ownEvents.getFirst(); assertThat(relayed.eventType()).startsWith("Approval.Quorum.Sla");
                assertThat(relayed.requestId()).isEqualTo(request); assertThat(relayed.payloadSha256()).hasSize(64);
                Thread.sleep(600);
                assertThat(database.queryForObject("SELECT count(*) FROM apr_integration_outbox WHERE request_id=? AND event_type LIKE 'Approval.Quorum.Sla%'",Integer.class,request)).isEqualTo(1);
                assertThat(captured.events.stream()
                        .filter(event -> event.requestId().equals(request))
                        .filter(event -> event.eventType().startsWith("Approval.Quorum.Sla")))
                        .hasSize(1);
                var proof = notification(); var token = nfTransport(proof.request()); int port = ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) boot).getWebServer().getPort();
                var call = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+SystemSlaNotificationProtocol.PATH)).header("Content-Type","application/json").header("X-DWP-Service-Identity","dwp-notification-server").header(SystemSlaNotificationProtocol.HEADER,token).POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(JSON.bytes(proof.request()))).build();
                var response = java.net.http.HttpClient.newHttpClient().send(call,java.net.http.HttpResponse.BodyHandlers.ofByteArray()); assertThat(response.statusCode()).isEqualTo(200);
                var signed = SignedJWT.parse(JSON.parse(response.body()).get("attestation").asText()); assertThat(signed.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(NF_ATTEST))).isTrue();
                assertThat(JSON.parse(signed.getPayload().toBytes()).at("/profile/recipients")).hasSize(3);
            }
        }
    }
    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline) {if(condition.getAsBoolean()) return;Thread.sleep(25);}
        throw new AssertionError("Timed out waiting for scheduled SYSTEM_SLA delivery");
    }
    SystemSlaProducerSource producer(SystemSlaEmbeddedServer server,SystemSlaCurrentSource source,Runnable beforeFreshHttp) {
        var keys = new SystemSlaSourceKeys(JSON,OWNER.toJSONString(),TRANSPORT.toJSONString(),jwks(ATTEST),List.of(NF_TRANSPORT.toJSONString(),NF_ATTEST.toJSONString()));
        var client = new AuthApprovalSystemSlaAuthorityClient(server.endpoint(),new SystemSlaSourceAttestationVerifier(JSON,keys,Clock.systemUTC())) {
            @Override public SystemSlaSourceAttestationVerifier.Verified evaluate(SystemSlaSourceProofIssuer.Exchange exchange) {
                if (beforeFreshHttp!=null) beforeFreshHttp.run(); return super.evaluate(exchange);
            }
        };
        return new SystemSlaProducerSource(source,nativeSource,() -> new SystemSlaSourceProofIssuer(JSON,keys,Clock.systemUTC()),() -> client,JSON,Clock.systemUTC(),new SystemSlaSourceWitnessJournal(namedApproval,JSON));
    }
    String business() {
        var values = new LinkedHashMap<String,Object>();
        for (String table : List.of("apr_requests","apr_quorum_stage_runtime","apr_steps","apr_tasks","apr_quorum_sla_timers","apr_request_events","apr_integration_outbox","sys_audit_outbox","apr_system_sla_source_witnesses"))
            values.put(table,approval.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(row) ORDER BY to_jsonb(row)::text),'[]'::jsonb)::text FROM "+table+" row",String.class));
        return JSON.digest(values);
    }
    SystemSlaCurrentSource source(SystemSlaEmbeddedServer server) {
        return source(server,false);
    }
    SystemSlaCurrentSource source(SystemSlaEmbeddedServer server,boolean revokeAfterHttp) {
        return source(server,revokeAfterHttp,false);
    }
    SystemSlaCurrentSource source(SystemSlaEmbeddedServer server,boolean revokeAfterHttp,boolean advanceDeliveryTask) {
        var keys = new SystemSlaSourceKeys(JSON,OWNER.toJSONString(),TRANSPORT.toJSONString(),jwks(ATTEST),List.of(NF_TRANSPORT.toJSONString(),NF_ATTEST.toJSONString()));
        var client = new AuthApprovalSystemSlaAuthorityClient(server.endpoint(),new SystemSlaSourceAttestationVerifier(JSON,keys,Clock.systemUTC())) {
            @Override public SystemSlaSourceAttestationVerifier.Verified evaluate(SystemSlaSourceProofIssuer.Exchange exchange) {
                var verified = super.evaluate(exchange);
                if (revokeAfterHttp) approval.update("UPDATE apr_steps SET status='SKIPPED',version=version+1 WHERE step_id=?",lease.stepId());
                if (advanceDeliveryTask && exchange.seal().delivery()) assertThat(approval.update("UPDATE apr_tasks SET version=version+1 WHERE request_id=? AND assignee_user_id=901102",request)).isEqualTo(1);
                return verified;
            }
        };
        var nf = new SystemSlaNotificationKeys(JSON,jwks(NF_TRANSPORT),NF_ATTEST.toJSONString(),jwks(NF_ATTEST),keys.publicKeys());
        return new SystemSlaCurrentSource(nativeSource,() -> new SystemSlaSourceProofIssuer(JSON,keys,Clock.systemUTC()),() -> client,
                () -> new SystemSlaNotificationVerifier(JSON,nf,Clock.systemUTC()),() -> new SystemSlaNotificationAttestationIssuer(JSON,nf,Clock.systemUTC()),tx,Clock.systemUTC(),true);
    }
    SystemSlaNotificationVerifier.Verified notification() {
        var row = approval.queryForMap("SELECT event_id,event_type,payload::text FROM apr_integration_outbox WHERE request_id=? AND event_type LIKE 'Approval.Quorum.Sla%'",request);
        String raw = (String) row.get("payload"); var envelope = JSON.parse(raw.getBytes(StandardCharsets.UTF_8)); var body = envelope.get("payload");
        var pins = new LinkedHashMap<String,JsonNode>(); for (String key : List.of("requestTitle","managementResourceSetKey","stageKey","authorityRevision","stepId","workflowVersionId","formVersionId","timerId","workflowDefinitionSha256","formSchemaSha256","payloadSha256","policySha256","requestVersion","stageVersion","generation","workflowVersion","payloadRevision","policyVersion","leaseEpoch")) pins.put(key,body.get(key));
        var input = Map.of("eventId",row.get("event_id"),"eventType",row.get("event_type"),"tenantId",42,"requestId",request,"originalEnvelopeSha256",com.dwp.services.approval.systemslaauthority.SystemSlaJson.sha(raw.getBytes(StandardCharsets.UTF_8)),"canonicalEnvelopeSha256",JSON.digest(envelope),"recipientSnapshotSha256",body.get("recipientSnapshotSha256"),"sourcePinsSha256",JSON.digest(pins),"requestedRecipientUserIds",body.get("recipientUserIds"));
        byte[] bytes = JSON.bytes(input);
        return new SystemSlaNotificationVerifier(JSON,new SystemSlaNotificationKeys(JSON,jwks(NF_TRANSPORT),NF_ATTEST.toJSONString(),jwks(NF_ATTEST),List.of(OWNER.toPublicJWK(),TRANSPORT.toPublicJWK(),ATTEST.toPublicJWK())),Clock.systemUTC()).verify(bytes,nfTransport(JSON.tree(input)));
    }
    String nfTransport(JsonNode input) {
        byte[] bytes = JSON.bytes(input); long now = Instant.now().getEpochSecond();
        var claims = new LinkedHashMap<String,Object>(); claims.put("iss",SystemSlaNotificationProtocol.TRANSPORT_ISSUER); claims.put("aud",SystemSlaNotificationProtocol.TRANSPORT_AUDIENCE); claims.put("sub","dwp-notification-server");
        claims.put("iat",now); claims.put("nbf",now); claims.put("exp",now+30); claims.put("jti",UUID.randomUUID()); claims.put("purpose",SystemSlaNotificationProtocol.TRANSPORT_PURPOSE); claims.put("method","POST"); claims.put("path",SystemSlaNotificationProtocol.PATH); claims.put("requestNonce",UUID.randomUUID()); claims.put("requestBodySha256",com.dwp.services.approval.systemslaauthority.SystemSlaJson.sha(bytes)); claims.put("sourcePinsSha256",input.get("sourcePinsSha256"));
        try {
            var token = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(NF_TRANSPORT.getKeyID()).build(),new Payload(JSON.bytes(claims))); token.sign(new RSASSASigner(NF_TRANSPORT));
            return token.serialize();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    static SystemSlaKeys authKeys() { return new SystemSlaKeys(new SystemSlaJson(MAPPER),jwks(OWNER),jwks(TRANSPORT),ATTEST.toJSONString(),jwks(ATTEST),List.of(NF_TRANSPORT.toJSONString(),NF_ATTEST.toJSONString())); }
    static SystemSlaProofVerifier authVerifier() { return new SystemSlaProofVerifier(new SystemSlaJson(MAPPER),authKeys(),Clock.systemUTC()); }
    static SystemSlaAuthorityService authService() { return new SystemSlaAuthorityService(SystemSlaNativeInteropPostgresTest::authVerifier,new SystemSlaCurrentAuthority(new SystemSlaSubjectRepository(namedAuth,new SystemSlaJson(MAPPER)),Clock.systemUTC()),new SystemSlaReplayStore(redis,Clock.systemUTC()),new SystemSlaAttestationIssuer(new SystemSlaJson(MAPPER),SystemSlaNativeInteropPostgresTest::authKeys,Clock.systemUTC()),true); }
    static UUID person(long user) { return UUID.nameUUIDFromBytes(("native-interop:"+user).getBytes(StandardCharsets.UTF_8)); }
    static RSAKey key(String id) { try { return new RSAKeyGenerator(2048).keyID(id).algorithm(JWSAlgorithm.RS256).keyUse(KeyUse.SIGNATURE).generate(); } catch (Exception failure) { throw new AssertionError(failure); } }
    static String jwks(RSAKey key) { return new JWKSet(key.toPublicJWK()).toString(); }
    static PostgreSQLContainer<?> pg(String name) { return new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("system_native_"+name).withLabel("dwp-owner","cicero-system-native-interop"); }
    static DriverManagerDataSource ds(PostgreSQLContainer<?> pg) { return new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()); }
    static void migrate(DriverManagerDataSource source,Path root,String module) { var f = Flyway.configure().dataSource(source).locations("filesystem:"+root.resolve(module+"/src/main/resources/db/migration"),"filesystem:"+root.resolve("dwp-core/src/main/resources/db/migration")).load(); f.migrate(); f.validate(); assertThat(f.info().pending()).isEmpty(); }
}

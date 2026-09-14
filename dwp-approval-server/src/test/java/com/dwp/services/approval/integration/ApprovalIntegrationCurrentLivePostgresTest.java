package com.dwp.services.approval.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.documentretention.ApprovalRetentionProtocol;
import com.dwp.services.approval.documentretention.ApprovalRetentionRecordExecutor;
import com.dwp.services.approval.documentretention.ApprovalRetentionRules;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Actual outbox/native epoch transactions and HTTP callback; not Kafka broker or installed SLA activation evidence. */
@Testcontainers
class ApprovalIntegrationCurrentLivePostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    JdbcTemplate jdbc;TransactionTemplate tx;ApprovalIntegrationOutboxRepository repository;
    UUID request,eventId,outbox;HttpServer server;URI endpoint;
    final AtomicInteger requests=new AtomicInteger();final AtomicReference<String> received=new AtomicReference<>();
    String body="{\"eventType\":\"approval.request.submitted\",\"payload\":{\"summary\":\"Current source only\"},\"tenantId\":42}";

    @BeforeEach void before() throws Exception {
        var source=new PGSimpleDataSource();source.setURL(PG.getJdbcUrl());source.setUser(PG.getUsername());source.setPassword(PG.getPassword());
        jdbc=new JdbcTemplate(source);
        jdbc.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        var flyway=Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(false).load();flyway.clean();flyway.migrate();
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));repository=new ApprovalIntegrationOutboxRepository(jdbc);
        jdbc.queryForObject("SELECT seed_approval_tenant(42)",Object.class);
        request=UUID.randomUUID();eventId=UUID.randomUUID();outbox=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,requester_user_id,status)
                SELECT ?,42,?,workflow_version_id,form_version_id,'Current relay source',99,'IN_REVIEW'
                  FROM apr_workflow_versions CROSS JOIN apr_form_versions
                 WHERE apr_workflow_versions.tenant_id=42 AND apr_form_versions.tenant_id=42 LIMIT 1
                """,request,"OUTBOX-"+request);
        jdbc.update("INSERT INTO apr_integration_outbox(outbox_id,event_id,tenant_id,request_id,event_type,payload,payload_sha256) VALUES(?,?,42,?,'approval.request.submitted',?::jsonb,?)",outbox,eventId,request,body,"a".repeat(64));
        server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/events",exchange->{received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});
        server.start();endpoint=URI.create("http://localhost:"+server.getAddress().getPort()+"/events");
    }
    @AfterEach void after(){if(server!=null)server.stop(0);}

    @Test void actualHttpIsInvokedOnlyForCurrentLeaseAndAcknowledgementPersistsAfterResponse() {
        var event=claim();assertEquals(1,event.attemptCount());assertNotNull(event.leaseUntil());assertEquals("a".repeat(64),event.payloadSha256());
        assertTrue(publish(event));assertEquals(1,requests.get());assertEquals(event.payload(),received.get());
        assertEquals("PUBLISHED",status());assertTrue(tx.execute(status->repository.claim(1,"same-worker")).isEmpty());
    }

    @Test void sameWorkerSameEventReclaimCannotHealOldNativeEpochOrAcknowledgeOrFailLatestLease() {
        var first=claim();expire();var second=claim();assertEquals(first.attemptCount()+1,second.attemptCount());assertEquals(first.eventId(),second.eventId());
        String before=row();assertFalse(publish(first));assertFalse(Boolean.TRUE.equals(tx.execute(status->repository.markFailed(first,"same-worker",3,"stale"))));
        assertEquals(before,row());assertEquals(0,requests.get());assertTrue(publish(second));assertEquals(1,requests.get());
    }

    @ParameterizedTest @ValueSource(strings={"PREPARED","IRREVERSIBLE","IRREVERSIBLE_BLOCKED","OBJECTS_CONFIRMED","LOCAL_DB_PURGED","COMPLETE"})
    void stagedNonLiveClosesClaimAndAlreadyClaimedPublishFailureWithoutAnyDeliveryOrAcknowledgement(String state) {
        var event=claim();jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id,state) VALUES(42,?,?)",request,state);
        String before=row();assertTrue(tx.execute(status->repository.claim(1,"same-worker")).isEmpty());assertFalse(publish(event));
        assertFalse(Boolean.TRUE.equals(tx.execute(status->repository.markFailed(event,"same-worker",3,"must not record"))));
        assertEquals(before,row());assertEquals(0,requests.get());
    }

    @ParameterizedTest @ValueSource(strings={"body","digest","event-id","type","request","epoch","until"})
    void alteredServerPendingEventCannotSubstituteCurrentNativeBodyIdentityOrLease(String change) {
        var actual=claim();
        var forged=new ApprovalIntegrationOutboxRepository.PendingEvent(actual.outboxId(),change.equals("event-id")?UUID.randomUUID():actual.eventId(),42,
                change.equals("request")?UUID.randomUUID():request,change.equals("type")?"approval.request.withdrawn":actual.eventType(),change.equals("body")?"{}":actual.payload(),
                change.equals("epoch")?actual.attemptCount()+1:actual.attemptCount(),change.equals("digest")?"b".repeat(64):actual.payloadSha256(),change.equals("until")?actual.leaseUntil().plusSeconds(1):actual.leaseUntil());
        String before=row();assertFalse(publish(forged));assertEquals(before,row());assertEquals(0,requests.get());
    }

    @Test void expiredLeaseAndUnboundLegacyAcknowledgementsFailClosedBeforeHttpOrWrites() {
        var event=claim();expire();String before=row();assertFalse(publish(event));
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->repository.markPublished(outbox,"same-worker")).getErrorCode());
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->repository.markFailed(outbox,"same-worker",1,3,"unbound")).getErrorCode());
        assertEquals(before,row());assertEquals(0,requests.get());
    }

    @Test void actualHttpPostCheckRejectsLeaseExpiryInsteadOfAcknowledgingAnExpiredSend() {
        var event=claim();assertFalse(Boolean.TRUE.equals(tx.execute(status->repository.publishCurrent(event,"same-worker",current->{send(current);expire();}))));
        assertEquals(1,requests.get());assertEquals("SENDING",status());
    }

    @Test void currentBodyChangeDuringNetworkCallbackCannotBeAcknowledgedByOriginalClaim() {
        var event=claim();assertFalse(Boolean.TRUE.equals(tx.execute(status->repository.publishCurrent(event,"same-worker",current->{send(current);jdbc.update("UPDATE apr_integration_outbox SET payload='{\"changed\":true}'::jsonb WHERE outbox_id=?",outbox);}))));
        assertEquals(1,requests.get());assertEquals("SENDING",status());
    }

    @Test void currentPublishStartedBeforeInitiallyAbsentHeadWaitsAndNeverCallsHttpAfterRetentionCommit() throws Exception {
        var event=claim();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);String before=row();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var writer=pool.submit(()->tx.executeWithoutResult(status->{jdbc.queryForObject("SELECT request_id FROM apr_requests WHERE request_id=? FOR UPDATE",UUID.class,request);jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id,state) VALUES(42,?,'IRREVERSIBLE')",request);locked.countDown();await(release);}));
            assertTrue(locked.await(10,TimeUnit.SECONDS));var sender=pool.submit(()->publish(event));
            try {blocked();assertFalse(sender.isDone());release.countDown();writer.get(10,TimeUnit.SECONDS);assertFalse(sender.get(10,TimeUnit.SECONDS));}
            finally {release.countDown();}
        }
        assertEquals(before,row());assertEquals(0,requests.get());
    }

    @Test void requestWriteLockOutlivesActualHttpAndBlocksLateRetentionUntilPublicationCommit() throws Exception {
        var event=claim();var sending=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var sender=pool.submit(()->tx.execute(status->repository.publishCurrent(event,"same-worker",current->{sending.countDown();await(release);send(current);})));
            assertTrue(sending.await(10,TimeUnit.SECONDS));var writer=pool.submit(()->tx.executeWithoutResult(status->{jdbc.queryForObject("SELECT request_id FROM apr_requests WHERE request_id=? FOR UPDATE",UUID.class,request);jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id,state) VALUES(42,?,'PREPARED')",request);}));
            try {blocked();assertFalse(writer.isDone());assertEquals(0,requests.get());release.countDown();assertTrue(sender.get(10,TimeUnit.SECONDS));writer.get(10,TimeUnit.SECONDS);}
            finally {release.countDown();}
        }
        assertEquals(1,requests.get());assertEquals("PUBLISHED",status());
    }

    @Test void orphanedLegacyOutboxCannotHealOriginalClaimOrCallHttpWithoutItsRequestBinding() {
        var event=claim();jdbc.update("UPDATE apr_integration_outbox SET request_id=NULL WHERE outbox_id=?",outbox);String before=row();
        assertFalse(publish(event));assertEquals(before,row());assertEquals(0,requests.get());
    }

    @Test void actualNativeRetentionCannotStagePendingDeliveryWithoutBrokerAcknowledgement() throws Exception {
        var executor=retained();var protocol=new ApprovalRetentionProtocol(executor);
        String before=row();assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->protocol.prepare(42,request,0));
        assertEquals(before,row());assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM apr_record_retention_heads WHERE request_id=?",Long.class,request));assertEquals(0,requests.get());
    }

    @Test void actualPrivateLocalPurgeClosesHistoricalNativeEventWithoutAnyHttpOrAcknowledgementRepair() throws Exception {
        var executor=retained();var event=claim();assertTrue(publish(event));assertEquals(1,requests.get());
        var protocol=new ApprovalRetentionProtocol(executor);UUID claim=protocol.prepare(42,request,0);protocol.claim(claim,1);
        assertEquals("LOCAL_DB_PURGED",new ApprovalRetentionRecordExecutor(executor).purgeLocal(claim,2));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM apr_requests WHERE request_id=?",Long.class,request));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM apr_integration_outbox WHERE outbox_id=?",Long.class,outbox));
        String before=control();assertFalse(publish(event));assertFalse(Boolean.TRUE.equals(tx.execute(status->repository.markFailed(event,"same-worker",3,"must not resurrect"))));
        assertEquals(before,control());assertEquals(1,requests.get());
    }

    private PGSimpleDataSource retained() throws Exception {
        jdbc.update("UPDATE apr_requests SET status='APPROVED',completed_at=clock_timestamp()-interval '40 days',created_at=clock_timestamp()-interval '40 days' WHERE request_id=?",request);
        jdbc.update("INSERT INTO apr_request_payloads(tenant_id,request_id,payload,payload_sha256,schema_version,created_at) VALUES(42,?,'{}'::jsonb,?,1,clock_timestamp()-interval '40 days')",request,"a".repeat(64));
        jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type,changed_by,created_at) VALUES(?,42,?,1,'{}'::jsonb,?,'DRAFT_CREATED',99,clock_timestamp()-interval '40 days')",UUID.randomUUID(),request,"a".repeat(64));
        jdbc.update("INSERT INTO apr_document_heads(tenant_id,request_id,retain_until) VALUES(42,?,clock_timestamp()-interval '1 day')",request);
        jdbc.update("UPDATE apr_integration_outbox SET created_at=clock_timestamp()-interval '40 days' WHERE outbox_id=?",outbox);
        UUID policy=UUID.randomUUID();
        tx.executeWithoutResult(status->{
            new com.dwp.services.approval.document.ApprovalDocumentRepository(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),new com.dwp.services.approval.document.ApprovalDocumentCanonical(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()))
                .policy(new com.dwp.services.approval.security.ApprovalRequestContext.Actor(99L,42L,null,"Disposable retention source",Set.of(),Set.of()),"RS_APPROVALS",false);
            jdbc.update("INSERT INTO apr_retention_policy_heads(tenant_id,resource_set_key,policy_id,published_revision,version) VALUES(42,'RS_APPROVALS',?,1,2)",policy);
            var rules=new ApprovalRetentionRules(true,List.of("INTERNAL","CONFIDENTIAL","RESTRICTED"),1,30,1,1,1,50000,1000);
            String json;
            try {json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rules);}catch(java.io.IOException failure){throw new IllegalStateException(failure);}
            jdbc.update("INSERT INTO apr_retention_policy_versions SELECT 42,?,1,r,encode(sha256(convert_to(r::text,'UTF8')),'hex'),99,clock_timestamp() FROM (SELECT ?::jsonb r) q",policy,json);
            jdbc.update("INSERT INTO apr_retention_policy_publications VALUES(?,42,?,1,99,100,'Explicit disposable retention review',clock_timestamp())",UUID.randomUUID(),policy);
        });
        jdbc.execute("ALTER ROLE dwp_approval_retention_executor LOGIN PASSWORD 'disposable-only'");
        var source=new PGSimpleDataSource();source.setURL(PG.getJdbcUrl());source.setUser("dwp_approval_retention_executor");source.setPassword("disposable-only");return source;
    }
    private String control(){return jdbc.queryForObject("SELECT jsonb_build_array((SELECT count(*) FROM apr_record_purge_journal),(SELECT count(*) FROM apr_record_tombstones),(SELECT count(*) FROM sys_audit_outbox),(SELECT count(*) FROM apr_integration_outbox))::text",String.class);}

    private ApprovalIntegrationOutboxRepository.PendingEvent claim(){return Objects.requireNonNull(tx.execute(status->repository.claim(1,"same-worker"))).getFirst();}
    private boolean publish(ApprovalIntegrationOutboxRepository.PendingEvent event){return Boolean.TRUE.equals(tx.execute(status->repository.publishCurrent(event,"same-worker",this::send)));}
    private void send(ApprovalIntegrationOutboxRepository.PendingEvent event){try{var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.ofString(event.payload())).build(),HttpResponse.BodyHandlers.discarding());assertEquals(204,response.statusCode());}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}
    private void expire(){jdbc.update("UPDATE apr_integration_outbox SET locked_until=clock_timestamp()-interval '1 second' WHERE outbox_id=?",outbox);}
    private String status(){return jdbc.queryForObject("SELECT status FROM apr_integration_outbox WHERE outbox_id=?",String.class,outbox);}
    private String row(){return jdbc.queryForObject("SELECT to_jsonb(event)::text FROM apr_integration_outbox event WHERE outbox_id=?",String.class,outbox);}
    private static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Concurrent transaction timed out");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}}
    private void blocked() throws Exception{for(int i=0;i<500;i++){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Long.class)>0)return;Thread.sleep(20);}throw new IllegalStateException("Expected PostgreSQL lock wait was not observed");}
}

package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.documentretention.*;
import com.dwp.services.approval.domain.*;
import com.dwp.services.approval.document.ApprovalDocumentDtos;
import com.dwp.services.approval.document.ApprovalDocumentOwnerRepository;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ApprovalRetentionLiveReadPostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    UUID request,task;
    ApprovalRetentionProtocol protocol;
    TransactionTemplate workerTransaction;
    JdbcTemplate worker;
    @BeforeEach void before() throws Exception {
        initializeDocuments(PG);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        jdbc.execute("ALTER ROLE dwp_approval_retention_executor LOGIN PASSWORD 'disposable-only'");var ds=new PGSimpleDataSource();ds.setURL(PG.getJdbcUrl());ds.setUser("dwp_approval_retention_executor");ds.setPassword("disposable-only");
        workerTransaction=new TransactionTemplate(new DataSourceTransactionManager(ds));protocol=new ApprovalRetentionProtocol(ds);worker=new JdbcTemplate(ds);
        UUID template=tx(()->drafts.create(body("Template"),"template",null)).requestId();tx(()->{approvals.submit(template,0,null);return null;});
        request=UUID.randomUUID();task=UUID.randomUUID();UUID step=UUID.randomUUID(),policy=UUID.randomUUID();
        tx(()->{management.policy();
            jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,summary,requester_user_id,status,management_resource_set_key,completed_at,created_at,updated_at) SELECT ?,42,?,workflow_version_id,form_version_id,'Retained private title','Retained private summary',99,'APPROVED','RS_APPROVALS',clock_timestamp()-interval '40 days',clock_timestamp()-interval '40 days',clock_timestamp()-interval '40 days' FROM apr_requests WHERE request_id=?",request,"LIVE-"+request,template);
            jdbc.update("INSERT INTO apr_request_payloads SELECT 42,?,payload,payload_sha256,schema_version,clock_timestamp()-interval '40 days',clock_timestamp()-interval '40 days' FROM apr_request_payloads WHERE request_id=?",request,template);
            jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type,changed_by,created_at) SELECT ?,42,?,1,payload,payload_sha256,'DRAFT_CREATED',99,clock_timestamp()-interval '40 days' FROM apr_request_payloads WHERE request_id=?",UUID.randomUUID(),request,request);
            jdbc.update("INSERT INTO apr_steps(step_id,tenant_id,request_id,step_key,step_name,sequence_number,status,created_at) SELECT ?,42,?,step_key,step_name,sequence_number,'APPROVED',clock_timestamp()-interval '40 days' FROM apr_steps WHERE request_id=? LIMIT 1",step,request,template);
            jdbc.update("INSERT INTO apr_tasks(task_id,tenant_id,request_id,step_id,assignee_user_id,candidate_role,status,risk_score,completed_at,decision_actor_user_id,decision_payload_revision,decision_payload_sha256,created_at) SELECT ?,42,?,?,99,'APPROVAL_OPERATOR','APPROVED',70,clock_timestamp()-interval '40 days',99,1,payload_sha256,clock_timestamp()-interval '40 days' FROM apr_request_payloads WHERE tenant_id=42 AND request_id=?",task,request,step,request);
            jdbc.update("INSERT INTO apr_document_heads(tenant_id,request_id,retain_until) VALUES(42,?,clock_timestamp()-interval '1 day')",request);
            jdbc.update("INSERT INTO apr_retention_policy_heads(tenant_id,resource_set_key,policy_id,published_revision,version) VALUES(42,'RS_APPROVALS',?,1,2)",policy);
            var rules=new ApprovalRetentionRules(true,List.of("INTERNAL","CONFIDENTIAL","RESTRICTED"),1,30,1,1,1,50000,1000);
            jdbc.update("INSERT INTO apr_retention_policy_versions SELECT 42,?,1,r,encode(sha256(convert_to(r::text,'UTF8')),'hex'),99,clock_timestamp() FROM (SELECT ?::jsonb r) q",policy,canonical.json(rules));
            jdbc.update("INSERT INTO apr_retention_policy_publications VALUES(?,42,?,1,99,100,'Independent retention review',clock_timestamp())",UUID.randomUUID(),policy);return null;
        });
    }
    @AfterEach void after(){clear();}
    @Test void actualLegacyLiveControlsPreserveOwnerBodyAndServerItemsTotal() {
        assertThat(tx(()->queries.requestDetail(ApprovalRequestContext.require(),request)).request().title()).isEqualTo("Retained private title");
        assertThat(tx(()->queries.taskDetail(ApprovalRequestContext.require(),task)).summary().title()).isEqualTo("Retained private title");
        assertThat(tx(()->search.tasks(ApprovalWorkDtos.TaskView.COMPLETED,filter())).totalElements()).isEqualTo(1);
        assertThat(tx(()->search.requests(ApprovalWorkDtos.RequestView.ARCHIVE,filter())).totalElements()).isEqualTo(1);
        docContext(100);assertThat(tx(()->search.tasks(ApprovalWorkDtos.TaskView.COMPLETED,filter())).items()).isEmpty();
        assertThatThrownBy(()->tx(()->queries.requestDetail(ApprovalRequestContext.require(),request))).isInstanceOf(BaseException.class);
    }
    @ParameterizedTest @ValueSource(strings={"PREPARED","IRREVERSIBLE","IRREVERSIBLE_BLOCKED","OBJECTS_CONFIRMED","LOCAL_DB_PURGED","COMPLETE"})
    void nonLiveHeadClosesAllBodyNativeListsAndServerSearchItemsCount(String state) {
        jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id,state) VALUES(42,?,?)",request,state);
        assertThatThrownBy(()->tx(()->queries.requestPayload(42,request))).isInstanceOfSatisfying(BaseException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(()->tx(()->queries.requestFormSchema(42,request))).isInstanceOf(BaseException.class);
        assertThatThrownBy(()->tx(()->queries.timeline(42,request))).isInstanceOf(BaseException.class);
        assertThatThrownBy(()->tx(()->queries.taskDetail(ApprovalRequestContext.require(),task))).isInstanceOf(BaseException.class);
        assertThatThrownBy(()->tx(()->queries.request(ApprovalRequestContext.require(),request))).isInstanceOf(BaseException.class);
        assertThat(tx(()->queries.tasks(ApprovalRequestContext.require(),"COMPLETED",200))).isEmpty();
        assertThat(tx(()->queries.requests(ApprovalRequestContext.require(),"ARCHIVE",200))).isEmpty();
        var tasks=tx(()->search.tasks(ApprovalWorkDtos.TaskView.COMPLETED,filter()));assertThat(tasks.items()).isEmpty();assertThat(tasks.totalElements()).isZero();
        var requests=tx(()->search.requests(ApprovalWorkDtos.RequestView.ARCHIVE,filter()));assertThat(requests.items()).isEmpty();assertThat(requests.totalElements()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"payload","task","native-list","request-search","task-search","doc-request","doc-task","comments","comment-write","hold-write","export"})
    void transactionSnapshotBeforeActualPrivateClaimNeverProjectsAfterClaimWithInitiallyAbsentHead(String operation) throws Exception {
        var snapshotRead=new CountDownLatch(1);var beginRead=new CountDownLatch(1);var releaseClaim=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var reader=pool.submit(()->{docContext(99);try{return tx(()->{jdbc.queryForObject("SELECT count(*) FROM apr_record_retention_heads",Long.class);snapshotRead.countDown();await(beginRead);return outcome(operation);});}finally{clear();}});
            assertThat(snapshotRead.await(10,TimeUnit.SECONDS)).isTrue();
            var writer=pool.submit(()->workerTransaction.execute(status->{
                UUID claim=protocol.prepare(42,request,0);protocol.claim(claim,1);beginRead.countDown();await(releaseClaim);return claim;
            }));
            try {
                awaitBlockedReader();releaseClaim.countDown();assertThat(writer.get(10,TimeUnit.SECONDS)).isNotNull();
                assertThat(reader.get(10,TimeUnit.SECONDS)).isEqualTo(Set.of("native-list","request-search","task-search").contains(operation)?"0":"NOT_FOUND");
                assertThat(jdbc.queryForObject("SELECT state FROM apr_record_retention_heads WHERE request_id=?",String.class,request)).isEqualTo("IRREVERSIBLE");
            } finally {beginRead.countDown();releaseClaim.countDown();}
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void actualPrivateClaimCannotPassReadPrecheckBeforeSameTransactionBodyProjection(boolean existingLiveHead) throws Exception {
        if(existingLiveHead) jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id) VALUES(42,?)",request);
        var locked=new CountDownLatch(1);var releaseRead=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var reader=pool.submit(()->{docContext(99);try{return tx(()->{new ApprovalRetentionLiveGuard(new NamedParameterJdbcTemplate(jdbc)).request(42,request);locked.countDown();await(releaseRead);return queries.requestPayload(42,request);});}finally{clear();}});
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();var writer=pool.submit(()->protocol.prepare(42,request,0));
            try {awaitBlockedReader();assertThat(writer.isDone()).isFalse();releaseRead.countDown();assertThat(reader.get(10,TimeUnit.SECONDS)).isNotEmpty();assertThat(writer.get(10,TimeUnit.SECONDS)).isNotNull();}
            finally {releaseRead.countDown();}
        }
        assertThatThrownBy(()->tx(()->queries.requestPayload(42,request))).isInstanceOf(BaseException.class);
    }
    @ParameterizedTest @ValueSource(strings={"rr","readonly"})
    void inheritedStaleOrReadonlyParentFailsClosedWithoutRequiresNew(String mode) {
        if("rr".equals(mode)) transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);else transaction.setReadOnly(true);
        assertThatThrownBy(()->tx(()->queries.requestPayload(42,request))).isInstanceOfSatisfying(BaseException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }
    @Test void unrelatedDataSourceTransactionCannotReleaseOriginalReadLockAtAutoCommit() {
        var other=new PGSimpleDataSource();other.setURL(PG.getJdbcUrl());other.setUser(PG.getUsername());other.setPassword(PG.getPassword());
        var unrelated=new TransactionTemplate(new DataSourceTransactionManager(other));
        unrelated.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatThrownBy(()->unrelated.execute(status->queries.requestPayload(42,request)))
                .isInstanceOfSatisfying(BaseException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void documentOwnerUpdateLockOutlivesProjectionAndPreventsLatePrivateClaim(boolean taskOwner) throws Exception {
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var reader=pool.submit(()->{docContext(99);try{return tx(()->{
                var owner=new ApprovalDocumentOwnerRepository(new NamedParameterJdbcTemplate(jdbc)).lock(ApprovalRequestContext.require(),
                        taskOwner?ApprovalDocumentDtos.OwnerType.TASK:ApprovalDocumentDtos.OwnerType.REQUEST,taskOwner?task:request);
                locked.countDown();await(release);return owner.title();
            });}finally{clear();}});
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();var claim=pool.submit(()->protocol.prepare(42,request,0));
            try {awaitBlockedReader();assertThat(claim.isDone()).isFalse();release.countDown();
                assertThat(reader.get(10,TimeUnit.SECONDS)).isEqualTo("Retained private title");assertThat(claim.get(10,TimeUnit.SECONDS)).isNotNull();}
            finally {release.countDown();}
        }
        assertThatThrownBy(()->tx(()->documents.tools(ApprovalDocumentDtos.OwnerType.REQUEST,request)))
                .isInstanceOfSatisfying(BaseException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
    @ParameterizedTest @ValueSource(strings={"tools","comments","append","hold","export","archive"})
    void stagedNonLiveDocumentOwnerDeniesBeforeAnyCommandReceiptAuditOrHoldMutation(String operation) {
        jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id,state) VALUES(42,?,'IRREVERSIBLE')",request);
        String before=documentRows();
        assertThat(tx(()->outcome(operation))).isEqualTo("NOT_FOUND");assertThat(documentRows()).isEqualTo(before);
    }
    private String outcome(String operation) {
        try {return switch(operation) {
            case "payload"->queries.requestPayload(42,request).isEmpty()?"EMPTY":"BODY";
            case "task"->queries.taskDetail(ApprovalRequestContext.require(),task).summary().title();
            case "native-list"->Integer.toString(queries.requests(ApprovalRequestContext.require(),"ARCHIVE",200).size());
            case "request-search"->Long.toString(search.requests(ApprovalWorkDtos.RequestView.ARCHIVE,filter()).totalElements());
            case "task-search"->Long.toString(search.tasks(ApprovalWorkDtos.TaskView.COMPLETED,filter()).totalElements());
            case "doc-request","tools"->documents.tools(ApprovalDocumentDtos.OwnerType.REQUEST,request).requestId().toString();
            case "doc-task"->documents.tools(ApprovalDocumentDtos.OwnerType.TASK,task).requestId().toString();
            case "comments"->Long.toString(documents.comments(ApprovalDocumentDtos.OwnerType.REQUEST,request,0,25).totalElements());
            case "comment-write","append"->documents.append(ApprovalDocumentDtos.OwnerType.REQUEST,request,
                    new ApprovalDocumentDtos.AppendComment(0L,0L,"fenced-comment","Must not persist")).commentId().toString();
            case "hold-write","hold"->management.propose(request,new ApprovalDocumentDtos.HoldProposal(0L,
                    ApprovalDocumentDtos.HoldOperation.PLACE,"Must not falsely preserve erased record","fenced-hold")).requestId().toString();
            case "export"->documents.export(ApprovalDocumentDtos.OwnerType.REQUEST,request,
                    new ApprovalDocumentDtos.Export(0L,1,0L,ApprovalDocumentDtos.Intent.PRINT,"Controlled print","fenced-print")).exportId().toString();
            case "archive"->documents.archive(new ApprovalDocumentDtos.ArchiveExport(List.of(new ApprovalDocumentDtos.ArchiveItem(request,0L,1)),
                    0L,"Controlled archive","fenced-archive",UUID.randomUUID(),"RS_APPROVALS")).exportId().toString();
            default->throw new IllegalArgumentException();
        };}catch(BaseException denied){return denied.getErrorCode().name();}
    }
    private String documentRows(){return jdbc.queryForObject("SELECT jsonb_build_array((SELECT count(*) FROM apr_document_command_receipts),(SELECT count(*) FROM apr_document_comments),(SELECT count(*) FROM apr_document_hold_proposals),(SELECT count(*) FROM apr_document_hold_journal),(SELECT count(*) FROM sys_audit_outbox),(SELECT row_to_json(h) FROM apr_document_heads h WHERE request_id=?))::text",String.class,request);}
    private ApprovalWorkDtos.SearchFilter filter(){return new ApprovalWorkDtos.SearchFilter("Retained","","",null,ApprovalWorkDtos.DueFilter.ALL,0,25,ApprovalWorkDtos.Sort.NEWEST);}
    private static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Concurrent transaction timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    private void awaitBlockedReader() throws Exception {
        for(int i=0;i<500;i++){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Long.class)>0)return;Thread.sleep(20);}
        throw new IllegalStateException("Expected actual PostgreSQL lock wait was not observed");
    }
}

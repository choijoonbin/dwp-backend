package com.dwp.services.approval.security;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.approval.documentretention.management.*;
import com.dwp.services.approval.documentretention.management.receipt.*;
import com.dwp.services.approval.documentretention.*;
import com.dwp.services.approval.attachment.ApprovalAttachmentStorage.Stored;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import static com.dwp.services.approval.documentretention.management.receipt.ApprovalRetentionCommandProfile.Operation;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.dwp.services.approval.documentretention.management.ApprovalRetentionDtos.*;
import com.dwp.services.approval.documentretention.management.ApprovalRetentionDtos.Policy;
import static com.dwp.services.approval.documentretention.management.ApprovalRetentionForeignDtos.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@Testcontainers
class ApprovalRetentionManagementPostgresTest extends ApprovalDocumentPostgresFixture {
    static final ValidatorFactory VALIDATION=Validation.buildDefaultValidatorFactory();
    @AfterAll static void closeValidation() {VALIDATION.close();}
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    static final Set<String> PERMISSIONS=permissions();
    ApprovalRetentionManagementService retention;
    ApprovalRetentionManagementRepository repository;
    ApprovalRetentionInventory inventory;
    ApprovalRetentionForeignJournal foreign;
    ApprovalRetentionPolicyValidator validator;
    ApprovalRetentionReceiptService receipts;
    KeyPair ackKeys,executionKeys;
    UUID request;
    @BeforeEach void before() throws Exception {
        var admin=new PGSimpleDataSource();admin.setURL(PG.getJdbcUrl());admin.setUser(PG.getUsername());admin.setPassword(PG.getPassword());
        new JdbcTemplate(admin).execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        initializeDocuments(PG);var named=new NamedParameterJdbcTemplate(jdbc);
        validator=new ApprovalRetentionPolicyValidator();repository=new ApprovalRetentionManagementRepository(named,canonical,validator);
        inventory=new ApprovalRetentionInventory(named);
        ackKeys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();executionKeys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var ackVerifier=new ApprovalRetentionForeignAckVerifier(mapper,Clock.systemUTC(),Map.of(
                "AUDIT",new ApprovalRetentionForeignAckVerifier.TrustedKey("AUDIT","audit-owner","ack1",ackKeys.getPublic()),
                "NOTIFICATION",new ApprovalRetentionForeignAckVerifier.TrustedKey("NOTIFICATION","notification-owner","ack1",ackKeys.getPublic())));
        foreign=new ApprovalRetentionForeignJournal(named,inventory,canonical,ackVerifier);
        var audit=new ApprovalRetentionAudit(new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test"));
        retention=new ApprovalRetentionManagementService(new ApprovalRetentionAuthority(new ApprovalWorkAuthority(identities)),repository,validator,
                new ApprovalRetentionEligibility(named,inventory,repository),new ApprovalRetentionHighGuard(verifier,new ApprovalStepUpReplayRepository(named,mapper)),foreign,audit,
                new com.dwp.services.approval.documentretention.management.receipt.ApprovalRetentionCommandWitnessRepository(named,canonical,mapper,
                    new com.dwp.services.approval.documentretention.management.receipt.ApprovalRetentionCommandProfile(verifier,VALIDATION.getValidator(),mapper)));
        var witness=new ApprovalRetentionCommandWitnessRepository(named,canonical,mapper,
                new ApprovalRetentionCommandProfile(verifier,VALIDATION.getValidator(),mapper));
        receipts=new ApprovalRetentionReceiptService(new ApprovalRetentionAuthority(new ApprovalWorkAuthority(identities)),
                new ApprovalRetentionReceiptRepository(named,mapper,new ApprovalRetentionReceiptProfileParser(),witness,repository));
        for(long id:List.of(99L,100L,101L)) when(identities.require(42,id)).thenReturn(documentSubject(id,List.of("APPROVAL_OPERATOR"),PERMISSIONS));
        context(99);
    }
    @AfterEach void after(){clear();}
    static Set<String> permissions(){var result=new HashSet<>(DOC_PERMISSIONS);result.add("ADMIN.APPROVAL_OPERATIONS:VIEW");result.add("ADMIN.APPROVAL_OPERATIONS:EXECUTE");return Set.copyOf(result);}
    void context(long actor) {
        clear();ApprovalRequestContext.set(actor,42L,null,"Owner",Set.of("APPROVAL_OPERATOR"),PERMISSIONS);
        ApprovalManagementScopeContext.set("scope-doc","RS_APPROVALS");
    }
    void high(long actor,String route,String state,String cap) {
        context(actor);ApprovalDecisionRevisionContext.set("psr-"+"a".repeat(64),OffsetDateTime.now().plusMinutes(5),"context-doc","scope-doc",route,state);
        ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(route,"ACTION","APP_CONFIG_ADMIN",false,
                Set.of("predicate.approval.retention-policy.v1"),cap,"STEPUP-MGMT-HIGH-V1","SOD-RETENTION",true,null,null)));
    }
    @Test void getMissingPolicyNeverInitializesOrAudits() {
        String before=snapshot();absent(()->tx(retention::policy));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void nativeMissingPolicyMvcSerializesOnlyDistinctBusinessAbsenceWithoutPayload() throws Exception {
        String before=snapshot();var response=mvc().perform(get("/v1/admin/retention/policy")
                .header(HeaderConstants.X_CORRELATION_ID,"retention-absence")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(409);var body=mapper.readTree(response.getContentAsString());
        assertThat(body.path("errorCode").asText()).isEqualTo(ApprovalRetentionErrors.POLICY_NOT_CONFIGURED);
        assertThat(body.path("status").asText()).isEqualTo("ERROR");assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.has("data")).isFalse();assertThat(body.has("timestamp")).isTrue();
        assertThat(body.path("correlationId").asText()).isEqualTo("retention-absence");
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void nativeUnknownAuthorityMvcKeeps503AndNeverSignalsAbsence() throws Exception {
        String before=snapshot();when(identities.require(42,99)).thenReturn(null);
        var response=mvc().perform(get("/v1/admin/retention/policy")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(503);var body=mapper.readTree(response.getContentAsString());
        assertThat(body.path("errorCode").asText()).isEqualTo("AUTHORITY_RESOLUTION_UNAVAILABLE");
        assertThat(body.has("data")).isFalse();assertThat(snapshot()).isEqualTo(before);
    }
    @Test void nativeInvalidPolicyUuidMvcCannotBecomeBusinessAbsence() throws Exception {
        String before=snapshot();var response=mvc().perform(put("/v1/admin/retention/policies/not-a-uuid/draft")
                .contentType(MediaType.APPLICATION_JSON).content(canonical.json(new SavePolicy(0L,"invalid",rules())))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(mapper.readTree(response.getContentAsString()).path("errorCode").asText()).isNotEqualTo(ApprovalRetentionErrors.POLICY_NOT_CONFIGURED);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void nativeBootstrapReplayAndExpectedAbsentConflictKeepTheirOriginalCodes() throws Exception {
        var mvc=mvc();String body=canonical.json(new InitializePolicy(true,"mvc-init"));
        var first=mvc.perform(post("/v1/admin/retention/policies").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertThat(first.getStatus()).isEqualTo(200);String before=snapshot();
        var replay=mvc.perform(post("/v1/admin/retention/policies").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertThat(replay.getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(replay.getContentAsString()).path("data")).isEqualTo(mapper.readTree(first.getContentAsString()).path("data"));
        var conflict=mvc.perform(post("/v1/admin/retention/policies").contentType(MediaType.APPLICATION_JSON)
                .content(canonical.json(new InitializePolicy(true,"mvc-other")))).andReturn().getResponse();
        assertThat(conflict.getStatus()).isEqualTo(409);
        assertThat(mapper.readTree(conflict.getContentAsString()).path("errorCode").asText()).isEqualTo(ErrorCode.RESOURCE_CONFLICT.getCode());
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void anExplicitMissingPolicyUuidRemainsHiddenRatherThanConfiguredAbsence() {
        String before=snapshot();denied(ErrorCode.ENTITY_NOT_FOUND,()->tx(()->retention.save(UUID.randomUUID(),new SavePolicy(0L,"missing",rules()))));
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"revoked","unknown","actor","tenant","provider","suspended","missingTenant","scope"})
    void absentHeadNeverOverridesCurrentAuthorityOrTenantFailure(String failure) {
        ErrorCode expected=ErrorCode.FORBIDDEN;
        var otherTenant=new com.dwp.services.approval.integration.ApprovalIdentityDirectory.Subject(43L,99L,null,null,
                "Owner","owner@example.test",null,"ACTIVE",List.of("APPROVAL_OPERATOR"),List.copyOf(PERMISSIONS));
        switch(failure) {
            case "revoked"->when(identities.require(42,99)).thenReturn(documentSubject(99,List.of("APPROVAL_OPERATOR"),Set.of("APP.APPROVALS:VIEW")));
            case "unknown"->{when(identities.require(42,99)).thenReturn(null);expected=ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE;}
            case "actor"->when(identities.require(42,99)).thenReturn(documentSubject(100,List.of("APPROVAL_OPERATOR"),PERMISSIONS));
            case "tenant"->when(identities.require(42,99)).thenReturn(otherTenant);
            case "provider"->when(identities.require(42,99)).thenReturn(documentSubject(99,List.of("PROVIDER_OPERATOR"),PERMISSIONS));
            case "suspended"->jdbc.update("UPDATE apr_tenants SET lifecycle_state='SUSPENDED' WHERE tenant_id=42");
            case "missingTenant"->{ApprovalRequestContext.set(99L,43L,null,"Owner",Set.of("APPROVAL_OPERATOR"),PERMISSIONS);when(identities.require(43,99)).thenReturn(otherTenant);}
            case "scope"->{ApprovalManagementScopeContext.clear();expected=ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE;}
        }
        String before=snapshot();denied(expected,()->tx(retention::policy));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void explicitBootstrapIsDisabledWithoutFabricatedMakerOrPublicationAndReadsWriteNothing() {
        var policy=tx(()->retention.initialize(new InitializePolicy(true,"init")));
        assertThat(policy.version()).isZero();assertThat(policy.published().allowPurge()).isFalse();assertThat(policy.pending()).isNull();
        assertThat(jdbc.queryForObject("SELECT maker_user_id FROM apr_retention_policy_versions WHERE policy_id=?",Long.class,policy.policyId())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_policy_publications",Long.class)).isZero();
        String before=snapshot();assertThat(tx(retention::policy)).isEqualTo(policy);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void unknownBootstrapResponseReplaysExactReceiptAndDifferentFingerprintConflicts() {
        var input=new InitializePolicy(true,"unknown");var original=tx(()->retention.initialize(input));String before=snapshot();
        assertThat(tx(()->retention.initialize(input))).isEqualTo(original);assertThat(snapshot()).isEqualTo(before);
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->retention.initialize(new InitializePolicy(false,"unknown"))));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void otherActorCannotReplayAbsentBootstrapOrBorrowReceipt() {
        tx(()->retention.initialize(new InitializePolicy(true,"init")));context(100);String before=snapshot();
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->retention.initialize(new InitializePolicy(true,"init"))));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void concurrentUnknownBootstrapResponseCreatesOneImmutableReceiptAndOnePolicy() throws Exception {
        var input=new InitializePolicy(true,"concurrent");
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<UUID> action=()->{context(99);try{return tx(()->retention.initialize(input)).policyId();}finally{clear();}};
            var results=pool.invokeAll(List.of(action,action));assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_policy_heads",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_management_commands",Long.class)).isEqualTo(1);
    }
    @Test void saveVersionAndReplayAreBoundToCurrentSelectedPolicy() {
        var p=tx(()->retention.initialize(new InitializePolicy(true,"init")));var input=new SavePolicy(0L,"save",rules());
        var pending=tx(()->retention.save(p.policyId(),input));assertThat(pending.pendingRevision()).isEqualTo(1);assertThat(pending.pendingMakerUserId()).isEqualTo(99);
        String before=snapshot();assertThat(tx(()->retention.save(p.policyId(),input))).isEqualTo(pending);assertThat(snapshot()).isEqualTo(before);
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->retention.save(p.policyId(),new SavePolicy(0L,"stale",rules()))));
        denied(ErrorCode.ENTITY_NOT_FOUND,()->tx(()->retention.save(UUID.randomUUID(),input)));assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"000","100"})
    void destructivePublicationCannotUseLegacyBarePermission(String state) {
        var p=pending();high(100,ApprovalRetentionManagementService.POLICY_PUBLISH,state,"approvals.policy.publish");
        var input=new PublishPolicy(p.version(),"publish","Independent retention review");String before=snapshot();
        denied(ErrorCode.FORBIDDEN,()->tx(()->retention.publish(p.policyId(),input,null)));assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"110","111"})
    void independentSignedHighPublishPinsExactPolicyUuidAndNoViewExportGrant(String state) throws Exception {
        var p=pending();var route=ApprovalRetentionManagementService.POLICY_PUBLISH;high(100,route,state,"approvals.policy.publish");
        var input=new PublishPolicy(p.version(),"publish","Independent retention review");var headers=signed(route,"RETENTION_POLICY",p.policyId(),p.version(),publicPolicy(p.policyId()),input.idempotencyKey(),input);
        var result=tx(()->retention.publish(p.policyId(),input,headers));assertThat(result.publishedRevision()).isEqualTo(1);assertThat(result.pendingRevision()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_policy_publications",Long.class)).isEqualTo(1);
        String before=snapshot();assertThat(tx(()->retention.publish(p.policyId(),input,headers))).isEqualTo(result);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void selfPublishAndWrongPathTargetNeverConsumeChallengeOrReceipt() throws Exception {
        var p=pending();var route=ApprovalRetentionManagementService.POLICY_PUBLISH;high(99,route,"110","approvals.policy.publish");
        var input=new PublishPolicy(p.version(),"self","Independent retention review");var headers=signed(route,"RETENTION_POLICY",p.policyId(),p.version(),publicPolicy(p.policyId()),input.idempotencyKey(),input);String before=snapshot();
        denied(ErrorCode.FORBIDDEN,()->tx(()->retention.publish(p.policyId(),input,headers)));
        denied(ErrorCode.ENTITY_NOT_FOUND,()->tx(()->retention.publish(UUID.randomUUID(),input,headers)));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void revokedCurrentPermissionAndUnavailableIdentityCannotReplayWrite() {
        tx(()->retention.initialize(new InitializePolicy(true,"init")));String before=snapshot();
        when(identities.require(42,99)).thenReturn(documentSubject(99,List.of("APPROVAL_OPERATOR"),Set.of("APP.APPROVALS:VIEW")));
        denied(ErrorCode.FORBIDDEN,()->tx(()->retention.initialize(new InitializePolicy(true,"init"))));assertThat(snapshot()).isEqualTo(before);
        when(identities.require(42,99)).thenReturn(null);denied(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,()->tx(retention::policy));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void selectedOtherUnconfiguredScopeNeverExposesTheExistingPolicy() {
        var policy=tx(()->retention.initialize(new InitializePolicy(true,"init")));String before=snapshot();ApprovalManagementScopeContext.set("scope-other","RS_OTHER");
        denied(ErrorCode.ENTITY_NOT_FOUND,()->tx(()->retention.save(policy.policyId(),new SavePolicy(0L,"other-scope",rules()))));
        absent(()->tx(retention::policy));ApprovalManagementScopeContext.clear();
        denied(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,()->tx(retention::policy));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void publicRulesRejectUnknownNestedKeysEvenWithPermissiveMapperAndDoNotCoerceLargeIntegers() throws Exception {
        var permissive=mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        assertThatThrownBy(()->permissive.readValue(canonical.json(new SavePolicy(0L,"save",rules())).replace("\"rules\":{","\"rules\":{\"extra\":true,"),SavePolicy.class)).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->permissive.readValue(canonical.json(rules()).replace("\"maxInventoryRows\":50000","\"maxInventoryRows\":9999999999999999999999999999"),PublicRules.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        denied(ErrorCode.INVALID_INPUT_VALUE,()->validator.validate(new PublicRules(true,List.of("INTERNAL","INTERNAL"),1,1,1,1,1,1,1)));
    }
    @Test void actualCatalogHashExactlyMatchesPrivateOwnerCatalogWithoutGrantingPrivateHelpers() throws Exception {
        retainedRequest();var view=tx(()->retention.record(request));
        String privateHash=jdbc.queryForObject("SELECT encode(sha256(convert_to(string_agg(table_oid::oid::text||primary_key::text||row_sha256,'|' ORDER BY table_oid::oid,primary_key::text),'UTF8')),'hex') FROM apr_retention_internal.catalog_rows(42,?)",String.class,request);
        assertThat(view.inventorySha256()).isEqualTo(privateHash);assertThat(view.inventoryTables()).isEqualTo(44);assertThat(view.claimEligible()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace,LATERAL aclexplode(p.proacl) a WHERE n.nspname='apr_retention_internal' AND a.grantee=0 AND a.privilege_type='EXECUTE'",Long.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"hold","pending","policy","version","inventory"})
    void lateClaimDriftDeniesWithNoIntentAuditOrReceipt(String changed) throws Exception {
        retainedRequest();var record=tx(()->retention.record(request));var input=claimInput(record,"claim");
        switch(changed) {
            case "hold"->jdbc.update("UPDATE apr_document_heads SET hold_active=true,hold_version=hold_version+1 WHERE request_id=?",request);
            case "pending"->{UUID proposal=UUID.randomUUID();jdbc.update("INSERT INTO apr_document_hold_proposals(proposal_id,tenant_id,request_id,operation,reason,maker_user_id,base_version) VALUES(?,42,?,'PLACE','Retain original request',99,0)",proposal,request);jdbc.update("UPDATE apr_document_heads SET pending_hold_id=? WHERE request_id=?",proposal,request);}
            case "policy"->jdbc.update("UPDATE apr_retention_policy_heads SET version=version+1");
            case "version"->jdbc.update("UPDATE apr_requests SET version=version+1 WHERE request_id=?",request);
            case "inventory"->jdbc.update("UPDATE apr_requests SET summary='Changed summary' WHERE request_id=?",request);
        }
        var headers=claimHeaders(input);String before=snapshot();denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->retention.claim(request,input,headers)));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void acceptedSignedIntentHasNoPrematureIrreversibleOrForeignDeletionAndUnknownResponseReplays() throws Exception {
        retainedRequest();var record=tx(()->retention.record(request));var input=claimInput(record,"unknown");var headers=claimHeaders(input);
        var result=tx(()->retention.claim(request,input,headers));assertThat(result.state()).isEqualTo("QUEUED");assertThat(result.executionClaimId()).isNull();assertThat(result.foreignRequests()).isEqualTo(2);assertThat(result.verifiedAcknowledgements()).isZero();
        assertThat(tx(()->foreign.dispatchable(result.claimId(),"AUDIT"))).isEmpty();assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_claims",Long.class)).isZero();
        String before=snapshot();assertThat(tx(()->retention.claim(request,input,headers))).isEqualTo(result);assertThat(snapshot()).isEqualTo(before);
        context(101);assertThat(tx(()->retention.claim(result.claimId())).claimId()).isEqualTo(result.claimId());
        ApprovalManagementScopeContext.set("scope-other","RS_OTHER");denied(ErrorCode.ENTITY_NOT_FOUND,()->tx(()->retention.claim(result.claimId())));
    }
    @Test void actualDedicatedDispatchWaitsForOriginalReceiptDeadlineAndNeverClaimsErasureEarly() throws Exception {
        retainedRequest();var input=claimInput(tx(()->retention.record(request)),"dispatch");var headers=claimHeaders(input);
        var result=tx(()->retention.claim(request,input,headers));var ds=dedicated();
        var executionVerifier=new ApprovalRetentionExecutionVerifier(mapper,Clock.systemUTC(),executionKeys.getPublic(),"auth-owner","exec1");
        var dispatcher=new ApprovalRetentionIntentExecutor(ds,t->executionProof(t,true),executionVerifier);
        String before=snapshot();denied(ErrorCode.RESOURCE_CONFLICT,()->dispatcher.dispatch(result.claimId(),0));
        assertThat(snapshot()).isEqualTo(before);assertThat(tx(()->retention.claim(result.claimId())).state()).isEqualTo("QUEUED");
        var waiting=new ApprovalRetentionManagedExecutionRepository(ds).status(result.claimId());
        assertThat(waiting.state()).isEqualTo("READY");assertThat(waiting.reason()).isEqualTo("SOURCE_RETENTION_DEADLINE_PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_claims",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_tombstones",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT retain_until>=committed_at+interval '1 day' FROM apr_retention_original_command_witnesses WHERE result_reference_id=?",Boolean.class,result.claimId())).isTrue();
        assertThat(tx(()->foreign.dispatchable(result.claimId(),"AUDIT"))).isEmpty();
        assertThat(tx(()->foreign.dispatchable(result.claimId(),"NOTIFICATION"))).isEmpty();
        var pending=tx(()->retention.claim(result.claimId()));assertThat(pending.foreignCopyState()).isEqualTo("VERIFIED_FOREIGN_COPY_ACKS_PENDING");assertThat(pending.state()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_retention_heads WHERE state='COMPLETE'",Long.class)).isZero();
    }
    @Test void revokedFreshExecutorProofAndApplicationRoleCannotDispatch() throws Exception {
        retainedRequest();var input=claimInput(tx(()->retention.record(request)),"dispatch");var headers=claimHeaders(input);var intent=tx(()->retention.claim(request,input,headers));
        ageClaimSource(intent.claimId());
        var verifier=new ApprovalRetentionExecutionVerifier(mapper,Clock.systemUTC(),executionKeys.getPublic(),"auth-owner","exec1");var executor=new ApprovalRetentionIntentExecutor(dedicated(),t->executionProof(t,false),verifier);
        denied(ErrorCode.FORBIDDEN,()->executor.dispatch(intent.claimId(),0));
        assertThat(jdbc.queryForObject("SELECT state FROM apr_retention_dispatch_intents WHERE intent_id=?",String.class,intent.claimId())).isEqualTo("BLOCKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_claims",Long.class)).isZero();
        assertThatThrownBy(()->jdbc.queryForObject("SELECT apr_retention_internal.dispatch_record(?,0,repeat('a',64))",UUID.class,intent.claimId())).satisfies(error->{Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();assertThat(cause).isInstanceOf(java.sql.SQLException.class);assertThat(((java.sql.SQLException)cause).getSQLState()).isEqualTo("42501");});
    }
    @Test void managedExecutionCompletesOnlyAfterExactSignedOwnerAcksAndLocalTombstone() throws Exception {
        var fixture=managedFixture(false);int steps=0;
        while(fixture.worker().runOne() && !"COMPLETE".equals(fixture.executions().status(fixture.intent()).state())) {
            assertThat(++steps).isLessThan(12);
        }
        var status=fixture.executions().status(fixture.intent());
        assertThat(status.state()).isEqualTo("COMPLETE");assertThat(status.stage()).isEqualTo("COMPLETE");
        assertThat(status.outcomeSha256()).matches("[a-f0-9]{64}");
        assertThat(fixture.audit().deletes.get()).isEqualTo(1);assertThat(fixture.audit().reconciles.get()).isZero();
        assertThat(fixture.notification().deletes.get()).isEqualTo(1);assertThat(fixture.notification().reconciles.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_foreign_acknowledgements WHERE tenant_id=42",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT state FROM apr_record_retention_heads WHERE request_id=?",String.class,request)).isEqualTo("COMPLETE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_tombstones WHERE request_id=? AND inventory_sha256=?",Long.class,request,fixture.inventory())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_requests WHERE request_id=?",Long.class,request)).isZero();
        assertThat(jdbc.queryForObject("SELECT proof_sha256 FROM apr_record_purge_journal WHERE state='COMPLETE' AND reason_code='LOCAL_AND_DECLARED_FOREIGN_COPIES_CONFIRMED'",String.class)).isEqualTo(status.outcomeSha256());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_managed_execution_events WHERE intent_id=? AND outcome='SUCCEEDED'",Long.class,fixture.intent())).isGreaterThanOrEqualTo(6);
        assertThat(fixture.worker().runOne()).isFalse();
        assertThat(fixture.audit().deletes.get()).isEqualTo(1);assertThat(fixture.notification().deletes.get()).isEqualTo(1);
        sqlDenied("23514",()->jdbc.update("UPDATE apr_retention_managed_executions SET last_reason='TAMPERED',version=version+1 WHERE intent_id=?",fixture.intent()));
        sqlDenied("23514",()->jdbc.update("DELETE FROM apr_retention_managed_execution_events WHERE intent_id=?",fixture.intent()));
    }
    @Test void missingForeignOwnerBlocksBeforeIntentBecomesIrreversible() throws Exception {
        var fixture=managedFixture(false);
        var missingAudit=new ApprovalRetentionForeignPort() {
            public String consumerService(){return "AUDIT";}
            public boolean configured(){return false;}
            public SignedAck deleteDeclaredCopies(DeletionRequest request){throw new AssertionError("Owner must not be called");}
            public SignedAck reconcileDeclaredCopies(DeletionRequest request){throw new AssertionError("Owner must not be called");}
        };
        var verifier=new ApprovalRetentionExecutionVerifier(mapper,Clock.systemUTC(),executionKeys.getPublic(),"auth-owner","exec1");
        var worker=new ApprovalRetentionManagedWorker(fixture.executions(),target->executionProof(target,true),verifier,
                objectWorker(dedicated(),true),foreign,List.of(missingAudit,fixture.notification()),
                "retention-missing-owner",Duration.ofSeconds(90));
        assertThat(worker.runOne()).isTrue();
        var status=fixture.executions().status(fixture.intent());
        assertThat(status.state()).isEqualTo("BLOCKED");assertThat(status.stage()).isEqualTo("AUTHORITY");
        assertThat(status.reason()).isEqualTo("AUDIT_OWNER_NOT_CONFIGURED");
        assertThat(jdbc.queryForObject("SELECT state FROM apr_retention_dispatch_intents WHERE intent_id=?",String.class,fixture.intent())).isEqualTo("BLOCKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_claims",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_foreign_acknowledgements",Long.class)).isZero();
    }
    @Test void resumedIrreversibleExecutionBlocksBeforeObjectsWhenDownstreamConfigurationDisappears() throws Exception {
        var fixture=managedFixture(false);assertThat(fixture.worker().runOne()).isTrue();
        assertThat(fixture.executions().status(fixture.intent()).stage()).isEqualTo("OBJECTS");
        var missingStorage=objectWorker(dedicated(),false);
        var verifier=new ApprovalRetentionExecutionVerifier(mapper,Clock.systemUTC(),executionKeys.getPublic(),"auth-owner","exec1");
        var worker=new ApprovalRetentionManagedWorker(fixture.executions(),target->executionProof(target,true),verifier,
                missingStorage,foreign,List.of(fixture.audit(),fixture.notification()),
                "retention-resumed-worker",Duration.ofSeconds(90));
        assertThat(worker.runOne()).isTrue();
        var status=fixture.executions().status(fixture.intent());
        assertThat(status.state()).isEqualTo("BLOCKED");assertThat(status.stage()).isEqualTo("OBJECTS");
        assertThat(status.reason()).isEqualTo("OBJECT_STORAGE_NOT_CONFIGURED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_requests WHERE request_id=?",Long.class,request)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_tombstones WHERE request_id=?",Long.class,request)).isZero();
    }
    @Test void actualRetentionAuthorityClientUsesPurposeLocalSignedSingleAttemptWire() throws Exception {
        var fixture=managedFixture(false);
        var lease=fixture.executions().claim(fixture.intent(),0L,"retention-authority-wire",Duration.ofSeconds(90));
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        var ownerPair=generator.generateKeyPair();var transportPair=generator.generateKeyPair();
        var owner=new RSAKey.Builder((java.security.interfaces.RSAPublicKey)ownerPair.getPublic())
                .privateKey(ownerPair.getPrivate()).keyID("retention-owner-1").build();
        var transport=new RSAKey.Builder((java.security.interfaces.RSAPublicKey)transportPair.getPublic())
                .privateKey(transportPair.getPrivate()).keyID("retention-transport-1").build();
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var observed=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        server.createContext(AuthApprovalRetentionExecutionAuthorityPort.PATH,exchange->{
            try {
                byte[] body=exchange.getRequestBody().readAllBytes();var envelope=mapper.readTree(body);
                var ownerJwt=SignedJWT.parse(envelope.path("sourceProof").asText());
                var transportJwt=SignedJWT.parse(exchange.getRequestHeaders().getFirst(AuthApprovalRetentionExecutionAuthorityPort.HEADER));
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                assertThat(exchange.getRequestHeaders().getFirst("X-DWP-Service-Identity")).isEqualTo("dwp-approval-server");
                assertThat(ownerJwt.verify(new RSASSAVerifier(owner.toPublicJWK()))).isTrue();
                assertThat(transportJwt.verify(new RSASSAVerifier(transport.toPublicJWK()))).isTrue();
                assertThat(ownerJwt.getJWTClaimsSet().getStringClaim("purpose")).isEqualTo("APPROVAL_RETENTION_EXECUTION_SOURCE_V1");
                assertThat(transportJwt.getJWTClaimsSet().getStringClaim("purpose")).isEqualTo("APPROVAL_RETENTION_EXECUTION_TRANSPORT_V1");
                assertThat(envelope.path("bindings").path("target").path("intentId").asText()).isEqualTo(fixture.intent().toString());
                byte[] response=mapper.writeValueAsBytes(executionProof(lease.target(),true));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);
                exchange.getResponseBody().write(response);
            } catch(Throwable failure) {observed.set(failure);exchange.sendResponseHeaders(500,-1);}
            finally {exchange.close();}
        });server.start();
        try {
            var client=new AuthApprovalRetentionExecutionAuthorityPort(new NamedParameterJdbcTemplate(jdbc),mapper,Clock.systemUTC(),
                    URI.create("http://127.0.0.1:"+server.getAddress().getPort()+AuthApprovalRetentionExecutionAuthorityPort.PATH),owner,transport);
            var signed=client.current(lease.target());
            var verifier=new ApprovalRetentionExecutionVerifier(mapper,Clock.systemUTC(),executionKeys.getPublic(),"auth-owner","exec1");
            assertThat(verifier.verify(lease.target(),signed)).matches("[a-f0-9]{64}");
            assertThat(observed.get()).isNull();
        } finally {server.stop(0);fixture.executions().block(lease,"WIRE_TEST_COMPLETE");}
    }
    @Test void unknownOwnerResponseStopsUntilExactRecoveryAndThenUsesReconciliationNotAnotherDelete() throws Exception {
        var fixture=managedFixture(true);
        assertThat(fixture.worker().runOne()).isTrue(); // authority
        assertThat(fixture.worker().runOne()).isTrue(); // no objects
        assertThat(fixture.worker().runOne()).isTrue(); // owner response lost
        var unknown=fixture.executions().status(fixture.intent());
        assertThat(unknown.state()).isEqualTo("UNKNOWN");assertThat(unknown.reason()).isEqualTo("OWNER_RESULT_UNKNOWN");
        assertThat(fixture.audit().deletes.get()).isEqualTo(1);assertThat(fixture.audit().reconciles.get()).isZero();
        assertThat(fixture.worker().runOne()).isFalse();
        sqlDenied("40001",()->fixture.worker().recover(fixture.intent(),unknown.version()-1));
        fixture.worker().recover(fixture.intent(),unknown.version());
        assertThat(fixture.worker().runOne()).isTrue();
        assertThat(fixture.audit().deletes.get()).isEqualTo(1);assertThat(fixture.audit().reconciles.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM apr_retention_foreign_delivery_states WHERE consumer_service='AUDIT'",String.class)).isEqualTo("ACKNOWLEDGED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_managed_execution_events WHERE intent_id=? AND outcome='UNKNOWN'",Long.class,fixture.intent())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_managed_execution_events WHERE intent_id=? AND outcome='RECOVERY_REQUESTED'",Long.class,fixture.intent())).isEqualTo(1);
    }
    @Test void ownerUnknownOutcomeTransitionIsDurableWithoutRedispatch() throws Exception {
        var fixture=managedFixture(false);
        assertThat(fixture.worker().runOne()).isTrue();
        assertThat(fixture.worker().runOne()).isTrue();
        var lease=fixture.executions().claim(fixture.intent(),null,"retention-test-worker",Duration.ofSeconds(90));
        var work=fixture.executions().claimForeign(lease);
        assertThat(work).isNotNull();assertThat(work.recoveryMode()).isEqualTo("DISPATCH");
        fixture.executions().unknownForeign(lease,work.deletionRequestId(),"OWNER_RESULT_UNKNOWN");
        var unknown=fixture.executions().status(fixture.intent());
        assertThat(unknown.state()).isEqualTo("UNKNOWN");assertThat(unknown.reason()).isEqualTo("OWNER_RESULT_UNKNOWN");
    }
    @Test void foreignLeaseLossAfterOwnerCallRecoversByReconciliationWithoutSecondDelete() throws Exception {
        var fixture=managedFixture(false);
        assertThat(fixture.worker().runOne()).isTrue();assertThat(fixture.worker().runOne()).isTrue();
        var lease=fixture.executions().claim(fixture.intent(),null,"retention-test-worker",Duration.ofSeconds(90));
        var work=fixture.executions().claimForeign(lease);
        var request=foreign.request(fixture.intent(),work.deletionRequestId(),work.consumerService());
        fixture.audit().deleteDeclaredCopies(request);
        jdbc.update("UPDATE apr_retention_managed_executions SET lease_until=clock_timestamp()-interval '1 second',version=version+1 WHERE intent_id=?",fixture.intent());
        assertThat(fixture.executions().expireLostLeases()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM apr_retention_foreign_delivery_states WHERE deletion_request_id=?",String.class,work.deletionRequestId())).isEqualTo("UNKNOWN");
        var unknown=fixture.executions().status(fixture.intent());fixture.worker().recover(fixture.intent(),unknown.version());
        assertThat(fixture.worker().runOne()).isTrue();
        assertThat(fixture.audit().deletes.get()).isEqualTo(1);assertThat(fixture.audit().reconciles.get()).isEqualTo(1);
    }
    @Test void malformedForeignChunkInventoryBlocksBeforeAnyOwnerOrLocalMutation() throws Exception {
        var fixture=managedFixture(false);UUID extraId=UUID.randomUUID();
        String inventory=jdbc.queryForObject("SELECT inventory_sha256 FROM apr_retention_foreign_requests WHERE intent_id=? AND consumer_service='AUDIT'",String.class,fixture.intent());
        var extra=new DeletionRequest(extraId,42,fixture.intent(),request,"AUDIT",1,2,List.of(),inventory,ApprovalRetentionForeignAckVerifier.PURPOSE);
        jdbc.update("INSERT INTO apr_retention_foreign_requests(deletion_request_id,tenant_id,intent_id,consumer_service,chunk_index,chunk_count,producer_event_ids,inventory_sha256,request_sha256) VALUES(?,42,?,'AUDIT',1,2,'[]',?,?)",extraId,fixture.intent(),inventory,canonical.fingerprint(extra));
        assertThat(fixture.worker().runOne()).isTrue();assertThat(fixture.worker().runOne()).isTrue();
        assertThatThrownBy(()->fixture.worker().runOne()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var blocked=fixture.executions().status(fixture.intent());assertThat(blocked.state()).isEqualTo("BLOCKED");
        assertThat(fixture.audit().deletes.get()).isZero();assertThat(fixture.notification().deletes.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_requests WHERE request_id=?",Long.class,request)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_tombstones WHERE request_id=?",Long.class,request)).isZero();
    }
    @Test void expiredLeaseBecomesUnknownAndStaleFenceCannotAdvance() throws Exception {
        var fixture=managedFixture(false);
        var lease=fixture.executions().claim(fixture.intent(),0L,"retention-test-worker",Duration.ofSeconds(30));
        assertThat(lease).isNotNull();
        jdbc.update("UPDATE apr_retention_managed_executions SET lease_until=clock_timestamp()-interval '1 second',version=version+1 WHERE intent_id=?",fixture.intent());
        assertThat(fixture.executions().expireLostLeases()).isEqualTo(1);
        var unknown=fixture.executions().status(fixture.intent());assertThat(unknown.state()).isEqualTo("UNKNOWN");
        sqlDenied("40001",()->fixture.executions().dispatch(lease,"a".repeat(64)));
        fixture.executions().recover(fixture.intent(),unknown.version());
        var reclaimed=fixture.executions().claim(fixture.intent(),0L,"retention-test-worker",Duration.ofSeconds(30));
        assertThat(reclaimed.generation()).isGreaterThan(lease.generation());assertThat(reclaimed.token()).isNotEqualTo(lease.token());
    }
    @Test void currentAuthorityDenialBlocksBeforeAnyIrreversibleOrForeignMutation() throws Exception {
        var fixture=managedFixture(false);var verifier=new ApprovalRetentionExecutionVerifier(mapper,Clock.systemUTC(),executionKeys.getPublic(),"auth-owner","exec1");
        var dispatcher=new ApprovalRetentionIntentExecutor(dedicated(),target->executionProof(target,false),verifier);
        denied(ErrorCode.FORBIDDEN,()->dispatcher.dispatch(fixture.intent(),0));
        assertThat(jdbc.queryForObject("SELECT state FROM apr_retention_dispatch_intents WHERE intent_id=?",String.class,fixture.intent())).isEqualTo("BLOCKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_claims",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_foreign_acknowledgements",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM apr_retention_managed_executions WHERE intent_id=?",String.class,fixture.intent())).isEqualTo("BLOCKED");
    }
    @Test void dedicatedExecutorCanOnlyUseManagedFunctionsAndCannotBypassTheirStateMachine() {
        assertThat(jdbc.queryForObject("SELECT has_function_privilege('dwp_approval_retention_executor','apr_retention_internal.dispatch_record(uuid,bigint,text)','EXECUTE')",Boolean.class)).isFalse();
        for(String signature:List.of(
                "apr_retention_internal.claim_managed_retention_execution(uuid,bigint,text,integer)",
                "apr_retention_internal.recover_managed_retention_execution(uuid,bigint)",
                "apr_retention_internal.dispatch_managed_retention_record(uuid,bigint,bigint,uuid,text)",
                "apr_retention_internal.claim_managed_retention_foreign(uuid,bigint,uuid)",
                "apr_retention_internal.purge_managed_retention_local(uuid,bigint,uuid)",
                "apr_retention_internal.finalize_managed_retention_execution(uuid,bigint,uuid)"))
            assertThat(jdbc.queryForObject("SELECT has_function_privilege('dwp_approval_retention_executor',?,'EXECUTE')",Boolean.class,signature)).as(signature).isTrue();
        for(String table:List.of("apr_retention_managed_executions","apr_retention_managed_execution_events","apr_retention_foreign_delivery_states"))
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('dwp_approval_retention_executor',?,'SELECT,INSERT,UPDATE,DELETE')",Boolean.class,"public."+table)).as(table).isFalse();
    }
    @Test void signedForeignAckCannotEraseOrConfirmBeforeIrreversibilityOrForAnotherTenant() throws Exception {
        retainedRequest();var input=claimInput(tx(()->retention.record(request)),"dispatch");var headers=claimHeaders(input);var intent=tx(()->retention.claim(request,input,headers));
        var raw=jdbc.queryForMap("SELECT * FROM apr_retention_foreign_requests WHERE intent_id=? AND consumer_service='AUDIT'",intent.claimId());
        var c=new AckClaims((UUID)raw.get("deletion_request_id"),42,intent.claimId(),"AUDIT",(String)raw.get("request_sha256"),"b".repeat(64),"DECLARED_COPIES_DELETED","audit-owner","dwp-approval-server",ApprovalRetentionForeignAckVerifier.PURPOSE,"ack1",UUID.randomUUID(),Instant.now().minusSeconds(1),Instant.now().plusSeconds(200));
        var signed=signAck(c);String before=snapshot();denied(ErrorCode.FORBIDDEN,()->tx(()->{foreign.acknowledge("AUDIT",signed);return null;}));assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @EnumSource(Operation.class)
    void fourNativeReceiptGetsExposeOnlyCommittedOriginalFourteenFieldMetadata(Operation operation) throws Exception {
        var original=original(operation,"original:receipt-key");context(original.actor());String before=snapshot();
        var response=receiptMvc().perform(get(java.net.URI.create(receiptPath(original))))
                .andReturn().getResponse();assertThat(response.getStatus()).isEqualTo(200);
        var data=mapper.readTree(response.getContentAsString()).path("data");
        assertThat(data.size()).isEqualTo(14);assertThat(data.path("status").asText()).isEqualTo("COMMITTED");
        assertThat(data.path("operation").asText()).isEqualTo(operation.name());
        assertThat(data.path("requestBodySha256").asText()).isEqualTo(verifier.payloadSha256(original.body()));
        assertThat(data.path("resultReferenceId").asText()).isEqualTo(original.result().toString());
        assertThat(data.path("resultVersion").asLong()).isEqualTo(original.version());
        assertThat(data.path("actorUserId").asLong()).isEqualTo(original.actor());
        assertThat(data.path("originAuthorityProfile").isTextual()).isTrue();
        assertThat(data.path("originAuthorityProfile").asText()).isEqualTo(switch(operation) {
            case INITIALIZE_POLICY,SAVE_POLICY->"POLICY_UPDATE_TRUSTED";
            case PUBLISH_POLICY->"POLICY_PUBLISH_SIGNED_HIGH_INDEPENDENT_CHECKER";
            case CLAIM_RECORD->"RETENTION_RECORD_EXECUTE_SIGNED_HIGH";
        });
        assertThat(data.path("profileVersion").asText()).isEqualTo(ApprovalRetentionCommandProfile.VERSION);
        assertThat(data.has("reviewComment")).isFalse();assertThat(data.has("verifiedHighBinding")).isFalse();
        assertThat(snapshot()).isEqualTo(before);
        var first=tx(()->receipts.read(operation,original.target(),original.key()));
        for(String state:List.of("000","100","110","111")) {
            receiptContext(original,state);assertThat(tx(()->receipts.read(operation,original.target(),original.key()))).isEqualTo(first);
        }
        assertThat(snapshot()).isEqualTo(before);
        if(operation==Operation.CLAIM_RECORD) {
            assertThat(tx(()->repository.claim(ApprovalRequestContext.require(),"RS_APPROVALS",original.result())).executionClaimId()).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_claims",Long.class)).isZero();
        }
    }
    @ParameterizedTest @EnumSource(Operation.class)
    void metadataRequiresOnlyItsOriginalPermissionAndRechecksRevocationAfterProjection(Operation operation) throws Exception {
        var original=original(operation,"permission-key");context(original.actor());
        Set<String> exact=Set.of("APP.APPROVALS:VIEW",operation.permission());
        ApprovalRequestContext.set(original.actor(),42L,null,"Owner",Set.of("APPROVAL_OPERATOR"),exact);
        var entitled=documentSubject(original.actor(),List.of("APPROVAL_OPERATOR"),exact);
        when(identities.require(42,original.actor())).thenReturn(entitled);
        String before=snapshot();assertThat(tx(()->receipts.read(operation,original.target(),original.key())).resultReferenceId()).isEqualTo(original.result());
        var revoked=documentSubject(original.actor(),List.of("APPROVAL_OPERATOR"),Set.of("APP.APPROVALS:VIEW"));
        when(identities.require(42,original.actor())).thenReturn(entitled,revoked);
        denied(ErrorCode.FORBIDDEN,()->tx(()->receipts.read(operation,original.target(),original.key())));
        when(identities.require(42,original.actor())).thenReturn(revoked);
        assertThat(receiptMvc().perform(get(receiptPath(original))).andReturn().getResponse().getStatus()).isEqualTo(403);
        when(identities.require(42,original.actor())).thenReturn(null);
        assertThat(receiptMvc().perform(get(receiptPath(original))).andReturn().getResponse().getStatus()).isEqualTo(503);
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @EnumSource(Operation.class)
    void receiptCannotBorrowAnotherActorSelectedScopeOrTarget(Operation operation) throws Exception {
        var original=original(operation,"ownership-key");context(original.actor()==99?100:99);String before=snapshot();
        assertThat(receiptMvc().perform(get(receiptPath(original))).andReturn().getResponse().getStatus()).isEqualTo(403);
        context(original.actor());ApprovalManagementScopeContext.set("scope-other","RS_OTHER");
        assertThat(receiptMvc().perform(get(receiptPath(original))).andReturn().getResponse().getStatus()).isEqualTo(403);
        context(original.actor());
        if(original.target()!=null) {
            var wrong=new Original(operation,UUID.randomUUID(),original.key(),original.body(),original.result(),original.version(),original.actor());
            assertThat(receiptMvc().perform(get(receiptPath(wrong))).andReturn().getResponse().getStatus()).isEqualTo(404);
        }
        when(identities.require(42,original.actor())).thenReturn(documentSubject(original.actor(),List.of("PROVIDER_OPERATOR"),PERMISSIONS));
        assertThat(receiptMvc().perform(get(receiptPath(original))).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"alias","action","other-capability","missing","expired"})
    void newReceiptExactDataEvidenceCannotBorrowFrozenSourceOrActionBindings(String failure) throws Exception {
        var original=original(Operation.INITIALIZE_POLICY,"canonical-key");receiptContext(original,"110");
        String route=ApprovalRetentionReceiptService.route(original.operation());String cap=original.operation().capability();
        if(failure.equals("alias")) {route="route.approvals.admin.retention-policy.data";ApprovalDecisionRevisionContext.set("psr-"+"a".repeat(64),OffsetDateTime.now().plusMinutes(5),"context-doc","scope-doc",route,"110");}
        if(failure.equals("expired")) ApprovalDecisionRevisionContext.set("psr-"+"a".repeat(64),OffsetDateTime.now().minusSeconds(1),"context-doc","scope-doc",route,"110");
        if(failure.equals("missing")) ApprovalPilotAuthorizationContext.clear();
        else ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(route,
                failure.equals("action")?"ACTION":"DATA","APP_CONFIG_ADMIN",true,Set.of("predicate.approval.retention-policy.v1"),
                failure.equals("other-capability")?"approvals.policy.publish":cap,"NORMAL",null,false,null,null)));
        String before=snapshot();var response=receiptMvc().perform(get(receiptPath(original))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(failure.equals("expired")?503:403);
        assertThat(mapper.readTree(response.getContentAsString()).has("data")).isFalse();assertThat(snapshot()).isEqualTo(before);
    }
    @Test void legacyParentWithoutOriginalWitnessIsUnknownRatherThanAConfirmedMetadataReceipt() throws Exception {
        var policy=tx(()->retention.initialize(new InitializePolicy(true,"actual-init")));var body=new InitializePolicy(true,"legacy-parent");
        tx(()->{repository.complete(ApprovalRequestContext.require(),"RS_APPROVALS",Operation.INITIALIZE_POLICY.nativePath(null),body.idempotencyKey(),body,policy.policyId(),0);return null;});
        var legacy=new Original(Operation.INITIALIZE_POLICY,null,body.idempotencyKey(),body,policy.policyId(),0,99);
        String before=snapshot();var response=receiptMvc().perform(get(receiptPath(legacy)).header(HeaderConstants.X_CORRELATION_ID,"metadata-unknown")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(409);var json=mapper.readTree(response.getContentAsString());
        assertThat(json.path("errorCode").asText()).isEqualTo(ApprovalRetentionReceiptErrors.METADATA_UNAVAILABLE);
        assertThat(json.has("data")).isFalse();assertThat(json.path("correlationId").asText()).isEqualTo("metadata-unknown");
        assertThat(tx(()->retention.initialize(body))).isEqualTo(policy);assertThat(snapshot()).isEqualTo(before);
        assertThat(receiptMvc().perform(get("/v1/admin/retention/policy-initialization-commands/not-committed")).andReturn().getResponse().getStatus()).isEqualTo(404);
    }
    @Test void currentPolicyRevisionAndPendingMakerCannotHealTheOriginalPublicationReceipt() throws Exception {
        var original=original(Operation.PUBLISH_POLICY,"publication-key");context(100);
        var first=tx(()->receipts.read(original.operation(),original.target(),original.key()));
        var current=tx(retention::policy);tx(()->retention.save(original.target(),new SavePolicy(current.version(),"later-new-maker",rules())));
        String before=snapshot();assertThat(tx(()->receipts.read(original.operation(),original.target(),original.key()))).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT maker_user_id FROM apr_retention_policy_versions WHERE revision=2 AND policy_id=?",Long.class,original.target())).isEqualTo(100);
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={".","..","%2e","%252e","key;matrix=1","key?key=other&key=key"})
    void noncanonicalMetadataKeysAreRejectedBeforeTheNativeService(String key) throws Exception {
        var guarded=mock(ApprovalRetentionReceiptService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ApprovalRetentionReceiptController(guarded))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();
        var response=mvc.perform(get(java.net.URI.create("/v1/admin/retention/policy-initialization-commands/"+key))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);verifyNoInteractions(guarded);
    }
    @Test void frozenDotMutationKeyReplaysButMetadataDotNeverConfirmsIt() throws Exception {
        var input=new InitializePolicy(true,".");var original=tx(()->retention.initialize(input));String before=snapshot();
        assertThat(tx(()->retention.initialize(input))).isEqualTo(original);
        assertThat(receiptMvc().perform(get("/v1/admin/retention/policy-initialization-commands/.")).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"Review literal \\uD83D\\uDE80 and \\u000b","Review controls \u000B \u000E \u001A approved"})
    void nativePublishPreservesLiteralEscapesAndAllowedC0InTheOriginalReviewAndReceiptHash(String text) throws Exception {
        var policy=pending();high(100,ApprovalRetentionManagementService.POLICY_PUBLISH,"110","approvals.policy.publish");
        var input=new PublishPolicy(policy.version(),"text-publish",text);
        var header=signed(ApprovalRetentionManagementService.POLICY_PUBLISH,"RETENTION_POLICY",policy.policyId(),policy.version(),publicPolicy(policy.policyId()),input.idempotencyKey(),input);
        tx(()->retention.publish(policy.policyId(),input,header));context(100);
        assertThat(jdbc.queryForObject("SELECT review_comment FROM apr_retention_policy_publications WHERE policy_id=?",String.class,policy.policyId())).isEqualTo(text);
        assertThat(tx(()->receipts.read(Operation.PUBLISH_POLICY,policy.policyId(),input.idempotencyKey())).requestBodySha256()).isEqualTo(verifier.payloadSha256(input));
    }
    @ParameterizedTest @ValueSource(strings={"Review interior NUL \u0000 approved","Review lone high \uD800 approved","Review lone low \uDC00 approved"})
    void wholePublishRejectsInvalidTextAfterCurrentAuthButBeforeAnySqlOrHighDigest(String text) throws Exception {
        var policy=pending();high(100,ApprovalRetentionManagementService.POLICY_PUBLISH,"110","approvals.policy.publish");
        var nativeSql=mock(ApprovalRetentionManagementRepository.class);var guardedHigh=mock(ApprovalRetentionHighGuard.class);
        var digest=spy(verifier);var named=new NamedParameterJdbcTemplate(jdbc);
        var witness=new ApprovalRetentionCommandWitnessRepository(named,canonical,mapper,
                new ApprovalRetentionCommandProfile(digest,VALIDATION.getValidator(),mapper));
        var service=new ApprovalRetentionManagementService(new ApprovalRetentionAuthority(new ApprovalWorkAuthority(identities)),nativeSql,validator,
                new ApprovalRetentionEligibility(named,inventory,nativeSql),guardedHigh,foreign,
                new ApprovalRetentionAudit(new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test")),witness);
        String before=snapshot();denied(ErrorCode.INVALID_INPUT_VALUE,()->tx(()->service.publish(policy.policyId(),new PublishPolicy(policy.version(),"invalid-text",text),null)));
        verifyNoInteractions(nativeSql,guardedHigh,digest);assertThat(snapshot()).isEqualTo(before);
        var proxy=new ProxyFactory(service);proxy.setProxyTargetClass(true);var advice=new TransactionInterceptor();
        advice.setTransactionManager(transaction.getTransactionManager());advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());proxy.addAdvice(advice);
        var mvc=MockMvcBuilders.standaloneSetup(new ApprovalRetentionManagementController((ApprovalRetentionManagementService)proxy.getProxy()))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        byte[] originalWire=mapper.writeValueAsBytes(new PublishPolicy(policy.version(),"invalid-text",text));
        assertThat(mapper.readTree(originalWire).path("reviewComment").textValue()).isEqualTo(text);
        var response=mvc.perform(post("/v1/admin/retention/policies/"+policy.policyId()+"/publish").contentType(MediaType.APPLICATION_JSON)
                .content(originalWire)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);verifyNoInteractions(nativeSql,guardedHigh,digest);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void wholePublishAcceptsPairedUnicodeWithGenuineSignedDigestAndCommittedOriginalReceipt() throws Exception {
        var policy=pending();high(100,ApprovalRetentionManagementService.POLICY_PUBLISH,"110","approvals.policy.publish");
        var input=new PublishPolicy(policy.version(),"valid-pair","Review paired \uD83D\uDE80 approved");
        var header=signed(ApprovalRetentionManagementService.POLICY_PUBLISH,"RETENTION_POLICY",policy.policyId(),policy.version(),publicPolicy(policy.policyId()),input.idempotencyKey(),input);
        var published=tx(()->retention.publish(policy.policyId(),input,header));assertThat(published.publishedRevision()).isEqualTo(1);context(100);
        assertThat(tx(()->receipts.read(Operation.PUBLISH_POLICY,policy.policyId(),input.idempotencyKey())).requestBodySha256()).isEqualTo(verifier.payloadSha256(input));
    }
    private record Original(Operation operation,UUID target,String key,Object body,UUID result,long version,long actor) {}
    private Original original(Operation operation,String key) throws Exception {
        if(operation==Operation.INITIALIZE_POLICY) {
            var body=new InitializePolicy(true,key);var result=tx(()->retention.initialize(body));
            return new Original(operation,null,key,body,result.policyId(),result.version(),99);
        }
        if(operation==Operation.SAVE_POLICY) {
            var policy=tx(()->retention.initialize(new InitializePolicy(true,"seed-init")));var body=new SavePolicy(policy.version(),key,rules());
            var result=tx(()->retention.save(policy.policyId(),body));return new Original(operation,policy.policyId(),key,body,result.policyId(),result.version(),99);
        }
        if(operation==Operation.PUBLISH_POLICY) {
            var policy=pending();high(100,ApprovalRetentionManagementService.POLICY_PUBLISH,"110","approvals.policy.publish");
            var body=new PublishPolicy(policy.version(),key,"Independent original publication review");
            var header=signed(ApprovalRetentionManagementService.POLICY_PUBLISH,"RETENTION_POLICY",policy.policyId(),policy.version(),publicPolicy(policy.policyId()),key,body);
            var result=tx(()->retention.publish(policy.policyId(),body,header));return new Original(operation,policy.policyId(),key,body,result.policyId(),result.version(),100);
        }
        retainedRequest();var body=claimInput(tx(()->retention.record(request)),key);var header=claimHeaders(body);var result=tx(()->retention.claim(request,body,header));
        return new Original(operation,request,key,body,result.claimId(),result.version(),100);
    }
    private String receiptPath(Original original) {
        return switch(original.operation()) {
            case INITIALIZE_POLICY->"/v1/admin/retention/policy-initialization-commands/"+original.key();
            case SAVE_POLICY->"/v1/admin/retention/policies/"+original.target()+"/draft-commands/"+original.key();
            case PUBLISH_POLICY->"/v1/admin/retention/policies/"+original.target()+"/publication-commands/"+original.key();
            case CLAIM_RECORD->"/v1/admin/retention/records/"+original.target()+"/claim-commands/"+original.key();
        };
    }
    private void receiptContext(Original original,String state) {
        context(original.actor());if(state.equals("000") || state.equals("100")) return;
        var route=ApprovalRetentionReceiptService.route(original.operation());
        ApprovalDecisionRevisionContext.set("psr-"+"a".repeat(64),OffsetDateTime.now().plusMinutes(5),"context-doc","scope-doc",route,state);
        ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(
                route, "DATA", "approval.retention.command-receipt.original-authority.v1", true,
                Set.of("predicate.approval.retention-command-original-authority.v1"),
                original.operation().capability(), "NORMAL", null, false, null, null)));
    }
    private MockMvc receiptMvc() {
        var proxy=new ProxyFactory(receipts);proxy.setProxyTargetClass(true);var interceptor=new TransactionInterceptor();
        interceptor.setTransactionManager(transaction.getTransactionManager());interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());proxy.addAdvice(interceptor);
        return MockMvcBuilders.standaloneSetup(new ApprovalRetentionReceiptController((ApprovalRetentionReceiptService)proxy.getProxy()))
                .setControllerAdvice(new ApprovalRetentionReceiptAdvice(),new GlobalExceptionHandler(new StaticMessageSource()))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }
    private Policy pending(){context(99);var p=tx(()->retention.initialize(new InitializePolicy(true,"init")));return tx(()->retention.save(p.policyId(),new SavePolicy(p.version(),"save",rules())));}
    private PublicRules rules(){return new PublicRules(true,List.of("INTERNAL","CONFIDENTIAL","RESTRICTED"),1,30,1,1,1,50000,1000);}
    private String publicPolicy(UUID id){return "/api/approvals/v1/admin/retention/policies/"+id+"/publish";}
    private void retainedRequest() throws Exception {
        var p=pending();var route=ApprovalRetentionManagementService.POLICY_PUBLISH;high(100,route,"110","approvals.policy.publish");var input=new PublishPolicy(p.version(),"publish","Independent retention review");var signed=signed(route,"RETENTION_POLICY",p.policyId(),p.version(),publicPolicy(p.policyId()),input.idempotencyKey(),input);tx(()->retention.publish(p.policyId(),input,signed));context(99);
        UUID template=tx(()->drafts.create(body("Template"),"template",null)).requestId();request=UUID.randomUUID();
        tx(()->{management.policy();
            jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,summary,requester_user_id,status,management_resource_set_key,deleted_at,deleted_by,deletion_reason,created_at,updated_at) SELECT ?,42,?,workflow_version_id,form_version_id,'Private retained title','Private retained summary',99,'DRAFT','RS_APPROVALS',clock_timestamp()-interval '40 days',99,'Recovery expired',clock_timestamp()-interval '40 days',clock_timestamp()-interval '40 days' FROM apr_requests WHERE request_id=?",request,"RET-"+request,template);
            jdbc.update("INSERT INTO apr_request_payloads SELECT 42,?,payload,payload_sha256,schema_version,clock_timestamp()-interval '40 days',clock_timestamp()-interval '40 days' FROM apr_request_payloads WHERE request_id=?",request,template);
            jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type,changed_by,created_at) SELECT ?,42,?,1,payload,payload_sha256,'DRAFT_CREATED',99,clock_timestamp()-interval '40 days' FROM apr_request_payloads WHERE request_id=?",UUID.randomUUID(),request,request);
            jdbc.update("INSERT INTO apr_document_heads(tenant_id,request_id,retain_until) VALUES(42,?,clock_timestamp()-interval '1 day')",request);return null;});
    }
    private CreateClaim claimInput(ApprovalRetentionDtos.Record r,String key){return new CreateClaim(r.version(),r.policyId(),r.policyVersion(),r.holdVersion(),r.inventorySha256(),key);}
    private ApprovalStepUpHeaders claimHeaders(CreateClaim input) throws Exception {
        String route=ApprovalRetentionManagementService.RECORD_CLAIM;high(100,route,"110","approvals.operations.execute");
        // Existing helper signs a policy capability; replace only that immutable signed claim in a fresh signature.
        var original=signed(route,"RETENTION_RECORD",request,input.expectedVersion(),"/api/approvals/v1/admin/retention/records/"+request+"/claims",input.idempotencyKey(),input);
        String[] parts=original.challenge().split("\\.");var claims=mapper.readValue(Base64.getUrlDecoder().decode(parts[1]),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});claims.put("capability_contract_key","approvals.operations.execute");
        String material=parts[0]+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(claims));var sig=Signature.getInstance("SHA256withRSA");sig.initSign(keys.getPrivate());sig.update(material.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return new ApprovalStepUpHeaders(material+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign()),original.idempotencyKey(),original.decisionRevision(),original.expectedObjectVersion());
    }
    private PGSimpleDataSource dedicated(){jdbc.execute("ALTER ROLE dwp_approval_retention_executor LOGIN PASSWORD 'disposable-only'");var ds=new PGSimpleDataSource();ds.setURL(PG.getJdbcUrl());ds.setUser("dwp_approval_retention_executor");ds.setPassword("disposable-only");return ds;}
    private record ManagedFixture(UUID intent,String inventory,ApprovalRetentionManagedExecutionRepository executions,
            ApprovalRetentionManagedWorker worker,OwnerPort audit,OwnerPort notification) {}
    private ManagedFixture managedFixture(boolean unknownAuditDelete) throws Exception {
        retainedRequest();var record=tx(()->retention.record(request));var input=claimInput(record,"managed-"+UUID.randomUUID());
        var headers=claimHeaders(input);var accepted=tx(()->retention.claim(request,input,headers));ageClaimSource(accepted.claimId());
        var source=dedicated();var executions=new ApprovalRetentionManagedExecutionRepository(source);
        var objectWorker=objectWorker(source,true);
        var audit=new OwnerPort("AUDIT",unknownAuditDelete);
        var notification=new OwnerPort("NOTIFICATION",false);
        var executionVerifier=new ApprovalRetentionExecutionVerifier(mapper,Clock.systemUTC(),executionKeys.getPublic(),"auth-owner","exec1");
        var worker=new ApprovalRetentionManagedWorker(executions,target->executionProof(target,true),executionVerifier,
                objectWorker,foreign,List.of(audit,notification),"retention-test-worker",Duration.ofSeconds(90));
        String sealed=jdbc.queryForObject("SELECT inventory_sha256 FROM apr_retention_dispatch_intents WHERE intent_id=?",String.class,accepted.claimId());
        return new ManagedFixture(accepted.claimId(),sealed,executions,worker,audit,notification);
    }
    private ApprovalRetentionObjectWorker objectWorker(DataSource source,boolean configured) {
        var storage=new ApprovalRetentionStorage() {
            public boolean configured(){return configured;}
            public String locatorSha256(){return "c".repeat(64);}
            public Stored reconcile(String key,long size,String sha){throw new AssertionError("No attachment object expected");}
            public void verifyPresence(Stored stored){throw new AssertionError("No attachment object expected");}
            public boolean deleteAndConfirmAbsent(Stored stored){throw new AssertionError("No attachment object expected");}
        };
        return new ApprovalRetentionObjectWorker(new ApprovalRetentionObjectJobs(source),storage);
    }
    private final class OwnerPort implements ApprovalRetentionForeignPort {
        private final String service;private boolean unknownFirst;
        private final AtomicInteger deletes=new AtomicInteger(),reconciles=new AtomicInteger();
        private OwnerPort(String service,boolean unknownFirst){this.service=service;this.unknownFirst=unknownFirst;}
        public String consumerService(){return service;}
        public SignedAck deleteDeclaredCopies(DeletionRequest request){deletes.incrementAndGet();if(unknownFirst){unknownFirst=false;throw new IllegalStateException("Response lost");}return ownerAck(request);}
        public SignedAck reconcileDeclaredCopies(DeletionRequest request){reconciles.incrementAndGet();return ownerAck(request);}
        private SignedAck ownerAck(DeletionRequest request){try{return signedAck(request);}catch(Exception failure){throw new IllegalStateException(failure);}}
    }
    private void ageClaimSource(UUID intent) {
        jdbc.execute("ALTER TABLE apr_retention_original_command_witnesses DISABLE TRIGGER trg_retention_original_command");
        jdbc.execute("ALTER TABLE apr_retention_inventory_closures DISABLE TRIGGER trg_retention_closure_immutable");
        jdbc.execute("ALTER TABLE apr_retention_dispatch_intents DISABLE TRIGGER trg_retention_dispatch_source");
        try {
            jdbc.update("UPDATE apr_retention_original_command_witnesses SET committed_at=clock_timestamp()-interval '3 days',retain_until=clock_timestamp()-interval '2 days' WHERE result_reference_id=?",intent);
            String sealed=jdbc.queryForObject("SELECT encode(sha256(convert_to(COALESCE(string_agg(table_oid::oid::text||primary_key::text||row_sha256,'|' ORDER BY table_oid::oid,primary_key::text),''),'UTF8')),'hex') FROM apr_retention_internal.catalog_rows(42,?)",String.class,request);
            jdbc.update("UPDATE apr_retention_dispatch_intents SET inventory_sha256=? WHERE intent_id=?",sealed,intent);
            jdbc.update("UPDATE apr_retention_inventory_closures c SET dispatch_inventory_sha256=?,witness_sha256=(SELECT encode(sha256(convert_to(to_jsonb(w)::text,'UTF8')),'hex') FROM apr_retention_original_command_witnesses w WHERE w.command_id=c.command_id) WHERE intent_id=?",sealed,intent);
        } finally {
            jdbc.execute("ALTER TABLE apr_retention_dispatch_intents ENABLE TRIGGER trg_retention_dispatch_source");
            jdbc.execute("ALTER TABLE apr_retention_inventory_closures ENABLE TRIGGER trg_retention_closure_immutable");
            jdbc.execute("ALTER TABLE apr_retention_original_command_witnesses ENABLE TRIGGER trg_retention_original_command");
        }
    }
    private ApprovalRetentionExecutionAuthorityPort.SignedAuthorization executionProof(ApprovalRetentionExecutionAuthorityPort.Target t,boolean allowed) {
        try {byte[] bytes=mapper.writeValueAsBytes(new ApprovalRetentionExecutionVerifier.Claims(t,"auth-owner","dwp-approval-server","APPROVAL_RETENTION_EXECUTE_V1","exec1",UUID.randomUUID(),Instant.now().minusSeconds(1),Instant.now().plusSeconds(200),allowed));var sig=Signature.getInstance("Ed25519");sig.initSign(executionKeys.getPrivate());sig.update(bytes);var enc=Base64.getUrlEncoder().withoutPadding();return new ApprovalRetentionExecutionAuthorityPort.SignedAuthorization(enc.encodeToString(bytes),enc.encodeToString(sig.sign()));}catch(Exception e){throw new IllegalStateException(e);}
    }
    private SignedAck signedAck(DeletionRequest r) throws Exception {return signAck(new AckClaims(r.deletionRequestId(),r.tenantId(),r.intentId(),r.consumerService(),canonical.fingerprint(r),"b".repeat(64),"DECLARED_COPIES_DELETED",r.consumerService().toLowerCase(Locale.ROOT)+"-owner","dwp-approval-server",ApprovalRetentionForeignAckVerifier.PURPOSE,"ack1",UUID.randomUUID(),Instant.now().minusSeconds(1),Instant.now().plusSeconds(200)));}
    private SignedAck signAck(AckClaims c) throws Exception {byte[] bytes=mapper.writeValueAsBytes(c);var sig=Signature.getInstance("Ed25519");sig.initSign(ackKeys.getPrivate());sig.update(bytes);var enc=Base64.getUrlEncoder().withoutPadding();return new SignedAck(enc.encodeToString(bytes),enc.encodeToString(sig.sign()));}
    private MockMvc mvc() {
        var proxy=new ProxyFactory(retention);proxy.setProxyTargetClass(true);
        var interceptor=new TransactionInterceptor();interceptor.setTransactionManager(transaction.getTransactionManager());
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());proxy.addAdvice(interceptor);
        return MockMvcBuilders.standaloneSetup(new ApprovalRetentionManagementController((ApprovalRetentionManagementService)proxy.getProxy()))
                .setControllerAdvice(new ApprovalRetentionNotConfiguredAdvice(),new GlobalExceptionHandler(new StaticMessageSource()))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }
    private void absent(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApprovalRetentionErrors.NotConfigured.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }
    private String snapshot(){var result=new TreeMap<String,String>();for(String table:List.of("apr_retention_policy_heads","apr_retention_policy_versions","apr_retention_policy_publications","apr_retention_management_commands","apr_retention_original_command_witnesses","apr_retention_inventory_closures","apr_retention_dispatch_intents","apr_retention_foreign_requests","apr_retention_foreign_acknowledgements","apr_record_retention_heads","apr_record_purge_claims","sys_audit_outbox"))result.put(table,jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]')::text FROM "+table+" r",String.class));return canonical.json(result);}
    private void sqlDenied(String state,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).satisfies(error->{Throwable cause=error;while(cause.getCause()!=null) cause=cause.getCause();
            assertThat(cause).isInstanceOf(org.postgresql.util.PSQLException.class);
            assertThat(((org.postgresql.util.PSQLException)cause).getSQLState()).isEqualTo(state);});
    }
    private void denied(ErrorCode code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(BaseException.class).satisfies(e->assertThat(((BaseException)e).getErrorCode()).isEqualTo(code));}
}

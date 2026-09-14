package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.attachment.*;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import jakarta.validation.Validation;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ApprovalAttachmentPolicyInitializePostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalAttachmentManagementService service;
    ApprovalAttachmentPolicyRepository policies;
    ApprovalAttachmentProviderGate provider;
    jakarta.validation.ValidatorFactory validation;
    static final String PATH="/v1/admin/attachments/policies";
    static final String ROUTE="route.approvals.admin.attachment-policy-initialize.action";
    long initialAudits;

    @BeforeEach void before() throws Exception {
        initializeDocuments(PG);initialAudits=count("sys_audit_outbox");validation=Validation.buildDefaultValidatorFactory();
        var named=new NamedParameterJdbcTemplate(jdbc);
        var authority=new ApprovalDocumentAuthority(new ApprovalWorkAuthority(identities),identities,new ApprovalDocumentOwnerRepository(named),canonical);
        policies=new ApprovalAttachmentPolicyRepository(named,canonical,validation.getValidator());
        // Provider deployment is not enabled by first configuration; interoperability has its own actual suites.
        provider=mock(ApprovalAttachmentProviderGate.class);
        when(provider.readiness()).thenReturn("NOT_CONFIGURED");when(provider.downloadReadiness()).thenReturn("NOT_CONFIGURED");
        service=new ApprovalAttachmentManagementService(authority,policies,new ApprovalAttachmentCommands(named,canonical),
                new ApprovalDocumentPublishGuard(verifier,new ApprovalStepUpReplayRepository(named,mapper)),provider,
                new ApprovalAttachmentAudit(new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test")));
        ApprovalManagementScopeContext.set("scope-initialize","RS_APPROVALS");
    }
    @AfterEach void after(){validation.close();clear();}

    @ParameterizedTest @ValueSource(strings={"000","100","110","111"})
    void currentOwnerCommandProtocolIsTheSameAcrossRolloutsWithoutInventingAnInstalledV9Seal(String state) {
        if(state.charAt(1)=='1') {
            // The protocol is exercised with owner evidence, not claimed as full-chain installed v9 authorization.
            ApprovalDecisionRevisionContext.set("psr-"+"b".repeat(64),OffsetDateTime.now().plusMinutes(5),"approvals.admin","scope-initialize",ROUTE,state);
            ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(ROUTE,"ACTION","APP_CONFIG_ADMIN",false,
                    Set.of("predicate.approval.attachment-policy.v1"),"approvals.policy.update",null,null,false,null,null)));
        }
        var created=tx(()->service.initialize(input("first")));
        assertThat(created.resourceSetKey()).isEqualTo("RS_APPROVALS");assertThat(created.version()).isZero();
        assertThat(created.publishedRevision()).isZero();assertThat(created.pending()).isNull();assertThat(created.pendingRevision()).isNull();
        assertThat(created.pendingMakerUserId()).isNull();assertThat(created.published().allowUpload()).isFalse();assertThat(created.published().allowDownload()).isFalse();
        assertThat(created.providerReadiness()).isEqualTo("NOT_CONFIGURED");assertThat(created.downloadReadiness()).isEqualTo("NOT_CONFIGURED");
        assertThat(created.publishEligible()).isFalse();assertThat(created.publishedRulesSha256()).isEqualTo(canonical.fingerprint(created.published()));
        assertThat(mapper.valueToTree(created).size()).isEqualTo(14);oneCreation();
        assertThat(jdbc.queryForObject("SELECT maker_user_id FROM apr_attachment_policy_versions",Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT payload->>'targetType' FROM sys_audit_outbox WHERE payload->>'action'='APPROVAL_ATTACHMENT_POLICY_INITIALIZED'",String.class)).isEqualTo("APPROVAL_ATTACHMENT_POLICY");
        verify(provider,never()).requireIngestion();verify(provider,never()).requireDownload();
    }
    @Test void unknownResponseReplayKeepsOneImmutableReceiptRevisionAndAudit() {
        tx(()->service.initialize(input("unknown-response")));
        var rows=snapshot();var replay=tx(()->service.initialize(input("unknown-response")));
        assertThat(replay.policyId()).isEqualTo(jdbc.queryForObject("SELECT policy_id FROM apr_attachment_policy_heads",UUID.class));
        assertThat(snapshot()).isEqualTo(rows);oneCreation();
        String fingerprint=jdbc.queryForObject("SELECT fingerprint FROM apr_attachment_command_receipts",String.class);
        assertThat(fingerprint).isEqualTo(canonical.fingerprint(Map.of("resourceSetKey","RS_APPROVALS","input",input("unknown-response"))));
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM apr_attachment_command_receipts",String.class)).doesNotContain("secret","content","reviewComment");
    }
    @Test void replayReturnsTheCurrentlyVerifiedHeadAndDoesNotReinitializeAfterDraftChanges() {
        var created=tx(()->service.initialize(input("first")));
        var saved=tx(()->service.save(created.policyId(),new ApprovalAttachmentDtos.SavePolicy(0L,"draft",created.published())));
        var before=snapshot();var replay=tx(()->service.initialize(input("first")));
        assertThat(replay).isEqualTo(saved);assertThat(replay.version()).isEqualTo(1);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void anotherKeyCannotResetAnExistingDisabledPolicy() {
        tx(()->service.initialize(input("first")));var before=snapshot();
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->service.initialize(input("another"))));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void anotherActorCannotBorrowTheReceiptOrRecreateTheExistingHead() {
        tx(()->service.initialize(input("same")));var before=snapshot();docContext(100);ApprovalManagementScopeContext.set("scope-other","RS_APPROVALS");
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->service.initialize(input("same"))));assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM apr_attachment_command_receipts",Long.class)).isEqualTo(99);
    }
    @Test void sameKeyAnotherSelectedResourceSetIsADifferentFingerprintAndNeverCreatesThatPolicy() {
        tx(()->service.initialize(input("same")));var before=snapshot();ApprovalManagementScopeContext.set("scope-other","RS_OTHER_APPROVALS");
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->service.initialize(input("same"))));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void twoActorsWithDifferentKeysRaceOnAbsentHeadExactlyOneSucceeds() throws Exception {
        var results=race(99,"actor-99",100,"actor-100");
        assertThat(results.stream().filter(ApprovalAttachmentDtos.Policy.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(BaseException.class::isInstance).map(BaseException.class::cast).map(BaseException::getErrorCode).toList()).containsExactly(ErrorCode.RESOURCE_CONFLICT);
        oneCreation();
    }
    @Test void twoUnknownResponseReplaysWithTheSameActorAndKeyReturnTheSamePolicy() throws Exception {
        var results=race(99,"same",99,"same");assertThat(results).allMatch(ApprovalAttachmentDtos.Policy.class::isInstance);
        assertThat(results.get(0)).isEqualTo(results.get(1));oneCreation();
    }
    @Test void expectedAbsentMustBeExplicitTrueAndValidationRejectsMalformedOriginalKeys() {
        for(var value:Arrays.asList(null,Boolean.FALSE)) {
            var invalid=new ApprovalAttachmentDtos.InitializePolicy(value,"first");
            assertThat(validation.getValidator().validate(invalid)).isNotEmpty();
            denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->service.initialize(invalid)));
        }
        for(String key:Arrays.asList(null,"","a b","x".repeat(121))) {
            var invalid=input(key);assertThat(validation.getValidator().validate(invalid)).isNotEmpty();
            denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->service.initialize(invalid)));
        }
        noneCreated();
    }
    @Test void readPermissionCannotCreatePolicyAndCurrentPermissionRevocationBlocksReplay() {
        var permissions=new HashSet<>(DOC_PERMISSIONS);permissions.remove("ADMIN.APPROVAL_POLICY:UPDATE");
        when(identities.require(42,99)).thenReturn(documentSubject(99,List.of("APPROVAL_OPERATOR"),permissions));
        denied(ErrorCode.FORBIDDEN,()->tx(()->service.initialize(input("first"))));noneCreated();
        when(identities.require(42,99)).thenReturn(documentSubject(99,List.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS));tx(()->service.initialize(input("first")));
        var before=snapshot();when(identities.require(42,99)).thenReturn(documentSubject(99,List.of("APPROVAL_OPERATOR"),permissions));
        denied(ErrorCode.FORBIDDEN,()->tx(()->service.initialize(input("first"))));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void lateCurrentRoleRevocationRollsBackHeadRevisionReceiptAndAudit() {
        var permissions=new HashSet<>(DOC_PERMISSIONS);permissions.remove("ADMIN.APPROVAL_POLICY:UPDATE");
        when(identities.require(42,99)).thenReturn(documentSubject(99,List.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS),documentSubject(99,List.of("APPROVAL_OPERATOR"),permissions));
        denied(ErrorCode.FORBIDDEN,()->tx(()->service.initialize(input("first"))));noneCreated();
    }
    @Test void lateSelectedResourceSetDriftRollsBackAllWrites() {
        when(provider.readiness()).thenAnswer(ignored->{ApprovalManagementScopeContext.set("scope-other","RS_OTHER_APPROVALS");return "NOT_CONFIGURED";});
        denied(ErrorCode.FORBIDDEN,()->tx(()->service.initialize(input("first"))));noneCreated();
    }
    @Test void lateAuthUnavailableAndProviderUncertaintyRollBackAllWrites() {
        when(identities.require(42,99)).thenReturn(documentSubject(99,List.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS))
                .thenThrow(ApprovalDocumentCanonical.unavailable("Auth unavailable"));
        denied(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,()->tx(()->service.initialize(input("first"))));noneCreated();
        doReturn(documentSubject(99,List.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS)).when(identities).require(42,99);
        when(provider.readiness()).thenThrow(ApprovalDocumentCanonical.unavailable("Storage evidence unavailable"));
        denied(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,()->tx(()->service.initialize(input("provider"))));noneCreated();
    }
    @Test void currentProviderPlaneCannotInitializeEvenWithUpdatePermission() {
        when(identities.require(42,99)).thenReturn(documentSubject(99,List.of("PROVIDER_ADMIN"),DOC_PERMISSIONS));
        denied(ErrorCode.FORBIDDEN,()->tx(()->service.initialize(input("first"))));noneCreated();
    }
    @Test void inactiveTenantCannotBeProvisionedOrCreateAReceipt() {
        jdbc.update("UPDATE apr_tenants SET lifecycle_state='SUSPENDED' WHERE tenant_id=42");
        denied(ErrorCode.RESOURCE_NOT_AVAILABLE,()->tx(()->service.initialize(input("first"))));noneCreated();
    }
    @Test void anotherOrMissingTenantCannotUseTheReceiptOrProvisionAHead() {
        tx(()->service.initialize(input("first")));var before=snapshot();
        when(identities.require(43,99)).thenReturn(new ApprovalIdentityDirectory.Subject(43L,99L,null,null,"Owner","owner@test",null,"ACTIVE",List.of("APPROVAL_OPERATOR"),List.copyOf(DOC_PERMISSIONS)));
        ApprovalRequestContext.set(99L,43L,null,"Owner",Set.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS);
        denied(ErrorCode.RESOURCE_NOT_AVAILABLE,()->tx(()->service.initialize(input("first"))));assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_tenants WHERE tenant_id=43",Long.class)).isZero();
    }
    @Test void anExistingCorruptHeadIsNeverRepairedOrRelabeledAsInitialized() {
        UUID id=UUID.randomUUID();var rules=new ApprovalAttachmentDtos.Rules(false,false,1024,1,1024,1,List.of("text/plain"),300,365);
        tx(()->{jdbc.update("INSERT INTO apr_attachment_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(42,'RS_APPROVALS',?)",id);
            jdbc.update("INSERT INTO apr_attachment_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256) VALUES(42,?,0,?::jsonb,repeat('a',64))",id,canonical.json(rules));return null;});
        var before=snapshot();denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->service.initialize(input("first"))));assertThat(snapshot()).isEqualTo(before);
        assertThat(count("apr_attachment_command_receipts")).isZero();assertThat(count("sys_audit_outbox")).isEqualTo(initialAudits);
    }
    private ApprovalAttachmentDtos.InitializePolicy input(String key){return new ApprovalAttachmentDtos.InitializePolicy(true,key);}
    private void oneCreation(){
        assertThat(count("apr_attachment_policy_heads")).isEqualTo(1);assertThat(count("apr_attachment_policy_versions")).isEqualTo(1);
        assertThat(count("apr_attachment_command_receipts")).isEqualTo(1);assertThat(count("apr_attachment_policy_publications")).isZero();
        assertThat(count("sys_audit_outbox")).isEqualTo(initialAudits+1);
    }
    private void noneCreated(){
        for(String table:List.of("apr_attachment_policy_heads","apr_attachment_policy_versions","apr_attachment_command_receipts","apr_attachment_policy_publications")) assertThat(count(table)).isZero();
        assertThat(count("sys_audit_outbox")).isEqualTo(initialAudits);
    }
    private Map<String,Object> snapshot(){
        var result=new TreeMap<String,Object>();
        for(String table:List.of("apr_attachment_policy_heads","apr_attachment_policy_versions","apr_attachment_command_receipts","apr_attachment_policy_publications","sys_audit_outbox"))
            result.put(table,jdbc.queryForList("SELECT row_to_json(r)::text AS row FROM "+table+" r ORDER BY row_to_json(r)::text"));
        return result;
    }
    private List<Object> race(long firstActor,String firstKey,long secondActor,String secondKey) throws Exception {
        var executor=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try {
            var first=executor.submit(()->racingCommand(firstActor,firstKey,ready,start));
            var second=executor.submit(()->racingCommand(secondActor,secondKey,ready,start));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            return List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        } finally {start.countDown();executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    private Object racingCommand(long actor,String key,CountDownLatch ready,CountDownLatch start) throws Exception {
        docContext(actor);ApprovalManagementScopeContext.set("scope-race","RS_APPROVALS");ready.countDown();
        try {
            if(!start.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
            try{return tx(()->service.initialize(input(key)));}catch(BaseException failure){return failure;}
        } finally {clear();}
    }
    private void denied(ErrorCode code,org.assertj.core.api.ThrowableAssert.ThrowingCallable command){
        assertThatThrownBy(command).isInstanceOfSatisfying(BaseException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));
    }
}

package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalFormLegacySchemaValidationConfig;
import com.dwp.services.approval.domain.ApprovalQueryRepository;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.forms.*;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ApprovalFormRequestVersionPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
        .withLabel("dwp.approval.owner","apr12-v23-request-pins");
    private static final Set<String> PERMISSIONS=Set.of("APP.APPROVALS:VIEW","ACTION.APPROVAL_REQUEST:CREATE","ACTION.APPROVAL_REQUEST:UPDATE");
    private static final String REV="psr-"+"a".repeat(64);
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ApprovalFormWorkspaceRepository workspace;
    private ApprovalFormRequestVersionRepository requests;
    private ApprovalFormVersionPolicyRepository published;
    private ApprovalIdentityDirectory identities;
    private MockHttpServletRequest http;
    private UUID form,version,workflow,workflowVersion,request;
    private String originalHash,workflowHash;
    private jakarta.validation.ValidatorFactory validation;
    @BeforeEach void setUp() {
        var source=new PGSimpleDataSource();source.setURL(POSTGRES.getJdbcUrl());source.setUser(POSTGRES.getUsername());source.setPassword(POSTGRES.getPassword());
        new JdbcTemplate(source).execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        new JdbcTemplate(source).execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        var flyway=Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(false).load();flyway.clean();flyway.migrate();
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var named=new NamedParameterJdbcTemplate(source);var mapper=new ObjectMapper().findAndRegisterModules();
        new ApprovalQueryRepository(named,mapper).ensureTenant(42);
        form=jdbc.queryForObject("SELECT form_id FROM apr_forms WHERE tenant_id=42 AND form_key='ACCESS_EXCEPTION_FORM'",UUID.class);
        version=jdbc.queryForObject("SELECT v.form_version_id FROM apr_form_versions v JOIN apr_forms f ON f.form_id=v.form_id AND f.current_version=v.version_number WHERE f.form_id=?",UUID.class,form);
        workflow=jdbc.queryForObject("SELECT workflow_id FROM apr_workflow_definitions WHERE tenant_id=42 AND workflow_key='ACCESS_EXCEPTION'",UUID.class);
        workflowVersion=jdbc.queryForObject("SELECT v.workflow_version_id FROM apr_workflow_versions v JOIN apr_workflow_definitions w ON w.workflow_id=v.workflow_id AND w.current_version=v.version_number WHERE w.workflow_id=?",UUID.class,workflow);
        workflowHash=jdbc.queryForObject("SELECT definition_sha256 FROM apr_workflow_versions WHERE workflow_version_id=?",String.class,workflowVersion);
        originalHash=jdbc.queryForObject("SELECT schema_sha256 FROM apr_form_versions WHERE form_version_id=?",String.class,version);
        request=UUID.randomUUID();
        jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,requester_user_id,status,data_classification) "
            +"VALUES (?,42,'REQUEST-POLICY',?,?,'Original request',99,'DRAFT','RESTRICTED')",request,workflowVersion,version);
        identities=mock(ApprovalIdentityDirectory.class);when(identities.require(anyLong(),anyLong())).thenAnswer(call->subject(call.getArgument(0),call.getArgument(1),true));
        http=new MockHttpServletRequest();http.addHeader("X-DWP-Active-Access-Mode","NORMAL");
        @SuppressWarnings("unchecked") ObjectProvider<HttpServletRequest> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(http);
        validation=jakarta.validation.Validation.buildDefaultValidatorFactory();
        var codec=new ApprovalFormMaterialCodec(mapper,new ApprovalFormLegacySchemaValidationConfig().approvalFormLegacySchemaValidator(mapper,validation.getValidator()));
        workspace=new ApprovalFormWorkspaceRepository(named,codec);
        var authority=new ApprovalFormVersionPolicyAuthority(new ApprovalWorkAuthority(identities),provider);
        requests=new ApprovalFormRequestVersionRepository(named,codec,authority);
        published=new ApprovalFormVersionPolicyRepository(named,codec,authority);
        context("request-draft-update.action");
    }
    @AfterEach void clear() {
        validation.close();ApprovalRequestContext.clear();ApprovalManagementScopeContext.clear();ApprovalDecisionRevisionContext.clear();ApprovalPilotAuthorizationContext.clear();
    }
    @Test void unrecordedLegacyUsesOwnVersionBeforeCaptureEvenIfHeadAlreadyAdvanced() {
        UUID head=advanceWorkflow();String before=state();var pin=seal();
        assertOriginal(pin);assertThat(pin.metadata()).isEmpty();assertThat(pin.metadataProvenance()).isEqualTo("UNRECORDED_HISTORICAL_METADATA");
        assertThat(pin.capturedAt()).isNull();assertThat(pin.materialDigest()).isNull();assertThat(pin.workspaceRevision()).isNull();
        assertThat(pin.workflow().workflowVersionId()).isNotEqualTo(head);assertThat(state()).isEqualTo(before);
    }
    @Test void headAdvanceBeforeLegacyCaptureDoesNotRetargetOriginalRequest() {
        UUID current=advanceWorkflow();capture();String before=state();var pin=seal();assertOriginal(pin);
        assertThat(pin.metadataProvenance()).isEqualTo("LEGACY_CAPTURE_TIME");assertThat(pin.capturedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT route_payload->>'workflowVersionId' FROM apr_form_version_material WHERE form_version_id=?",String.class,version)).isEqualTo(current.toString());
        assertThat(pin.workflow().workflowVersionId()).isEqualTo(workflowVersion);assertThat(state()).isEqualTo(before);
    }
    @Test void headAdvanceAfterLegacyCapturePreservesPublishedAndRequestVersionPins() {
        capture();advanceWorkflow();String before=state();var pin=seal();assertOriginal(pin);
        context("request-create.action");var observed=run(()->published.newInitiation(ApprovalRequestContext.require(),form,workflow,Function.identity())).orElseThrow();
        assertThat(observed.workflow().workflowVersionId()).isEqualTo(workflowVersion);
        assertThat(observed.workflow().slaMinutes()).isEqualTo(240);assertThat(observed.workflow().dataClassification()).isEqualTo("RESTRICTED");
        assertThat(observed.workflow().capturedWorkflowRevision()).isLessThan(jdbc.queryForObject("SELECT version FROM apr_workflow_definitions WHERE workflow_id=?",Long.class,workflow));
        assertThat(state()).isEqualTo(before);
    }
    @Test void catalogRetirementBlocksOnlyNewInitiationNotPinnedMutationSeals() {
        capture();run(()->{var actor=ApprovalRequestContext.require();var head=workspace.head(actor,form,true);
            new ApprovalFormLifecycleStore(workspace).availability(actor,head,ApprovalFormLifecycleDtos.CatalogAvailability.RETIRED);return true;});
        String before=state();assertOriginal(seal());context("request-create.action");
        denied(()->published.newInitiation(ApprovalRequestContext.require(),form,workflow,Function.identity()));assertThat(state()).isEqualTo(before);
    }
    @Test void draftRecoveryAndSubmitKeepOriginalPinsAndCanonicalRoutes() {
        capture();advanceWorkflow();String before=state();
        for(String route:List.of("request-draft-update.action","request-draft-recover.action","request-submit.action")) {
            context(route);var pin=seal();assertOriginal(pin);assertThat(pin.actionRoute()).isEqualTo("route.approvals.work."+route);
        }
        assertThat(state()).isEqualTo(before);
    }
    @Test void informationResponseUsesNeedsInfoStateAndDurableOriginalStepBindings() {
        jdbc.update("UPDATE apr_requests SET status='NEEDS_INFO' WHERE request_id=?",request);
        UUID step=UUID.randomUUID();jdbc.update("INSERT INTO apr_steps(step_id,tenant_id,request_id,step_key,step_name,sequence_number,approval_mode,status,started_at,due_at) "
            +"VALUES (?,42,?,'PRIMARY_REVIEW','Original step',1,'ANY','IN_PROGRESS',clock_timestamp(),clock_timestamp()+interval '45 minute')",step,request);
        advanceWorkflow();context("request-information-response.action");String before=state();var pin=seal();assertOriginal(pin);
        assertThat(pin.requestStatus()).isEqualTo("NEEDS_INFO");assertThat(pin.storedStepsJson()).contains(step.toString(),"PRIMARY_REVIEW","dueAt","candidateRole","APPROVAL_OPERATOR");
        assertThat(state()).isEqualTo(before);
    }
    @Test void currentAuthorityRevocationBeforeReadAndAfterVerifierAreZeroWrite() {
        capture();String before=state();when(identities.require(42,99)).thenReturn(subject(42,99,false));
        denied(this::seal);assertThat(state()).isEqualTo(before);
        when(identities.require(42,99)).thenReturn(subject(42,99,true),subject(42,99,false));
        denied(()->requests.existingRequest(ApprovalRequestContext.require(),request,0,form,workflow,Function.identity()));assertThat(state()).isEqualTo(before);
    }
    @Test void tenantOwnerPersonAndAlternateImmutableIdsCannotSelectAnotherRequest() {
        capture();String before=state();
        denied(()->requests.existingRequest(ApprovalRequestContext.require(),request,1,form,workflow,Function.identity()));
        denied(()->requests.existingRequest(ApprovalRequestContext.require(),request,0,UUID.randomUUID(),workflow,Function.identity()));
        denied(()->requests.existingRequest(ApprovalRequestContext.require(),request,0,form,UUID.randomUUID(),Function.identity()));
        ApprovalRequestContext.set(100L,42L,null,Set.of("APPROVAL_OPERATOR"),PERMISSIONS);denied(this::seal);
        ApprovalRequestContext.set(99L,43L,null,Set.of("APPROVAL_OPERATOR"),PERMISSIONS);denied(this::seal);
        ApprovalRequestContext.set(99L,42L,UUID.randomUUID(),Set.of("APPROVAL_OPERATOR"),PERMISSIONS);denied(this::seal);
        assertThat(state()).isEqualTo(before);
    }
    @Test void missingVerifierCannotReadOrPretendToGrantWorkflowAuthority() {
        String before=state();denied(()->requests.existingRequest(ApprovalRequestContext.require(),request,0,form,workflow,null));
        assertThatThrownBy(()->requests.existingRequest(ApprovalRequestContext.require(),request,0,form,workflow,Function.identity())).isInstanceOf(BaseException.class);
        assertThat(state()).isEqualTo(before);
    }
    @Test void inactiveCategoryOrBindingOrWorkflowCannotUseHistoricalMetadataAsAuthority() {
        capture();
        for(String update:List.of("UPDATE apr_form_categories SET lifecycle_state='INACTIVE' WHERE category_id=(SELECT category_id FROM apr_forms WHERE form_id='"+form+"')",
                "UPDATE apr_form_workflow_bindings SET lifecycle_state='INACTIVE' WHERE form_id='"+form+"'",
                "UPDATE apr_workflow_definitions SET lifecycle_state='RETIRED' WHERE workflow_id='"+workflow+"'")) {
            assertThatThrownBy(()->run(()->{jdbc.update(update);String before=state();denied(this::seal);assertThat(state()).isEqualTo(before);throw new BaseException(ErrorCode.FORBIDDEN);})).isInstanceOf(BaseException.class);
        }
        assertOriginal(seal());
    }
    @Test void deletedOrInvalidStatusRequestDeniesWithoutChangingPins() {
        for(String update:List.of("UPDATE apr_requests SET deleted_at=clock_timestamp(),deleted_by=99,deletion_reason='Fixture deletion' WHERE request_id='"+request+"'",
            "UPDATE apr_requests SET status='WITHDRAWN' WHERE request_id='"+request+"'")) {
            assertThatThrownBy(()->run(()->{jdbc.update(update);String before=state();denied(this::seal);assertThat(state()).isEqualTo(before);throw new BaseException(ErrorCode.FORBIDDEN);})).isInstanceOf(BaseException.class);
        }
        assertOriginal(seal());
    }
    @Test void unmarkedUnknownSchemaCannotReceiveCompatibilityFallback() {
        jdbc.update("UPDATE apr_form_versions SET schema_payload=jsonb_set(schema_payload,'{schemaContract}','\"UNKNOWN\"') WHERE form_version_id=?",version);
        String before=state();denied(this::seal);assertThat(state()).isEqualTo(before);
    }
    @Test void fabricatedRecoveryRouteAndWrongTransportAreDenied() {
        String before=state();context("drafts.recover.action");denied(this::seal);
        context("request-draft-update.action");http.setMethod("POST");denied(this::seal);
        context("request-submit.action");http.setRequestURI("/v1/requests/"+UUID.randomUUID()+"/submit");denied(this::seal);
        assertThat(state()).isEqualTo(before);
    }
    @Test void modernPublishedRouteUsesItsOwnBindingWithoutInventingLegacyDefault() {
        UUID other=jdbc.queryForObject("SELECT workflow_id FROM apr_workflow_definitions WHERE tenant_id=42 AND workflow_key='CAPEX_PURCHASE'",UUID.class);
        UUID draft=run(()->{var actor=ApprovalRequestContext.require();var head=workspace.head(actor,form,true);
            return new ApprovalFormLifecycleStore(workspace).branch(actor,head,workspace.version(actor,form,version));});
        run(()->{var actor=ApprovalRequestContext.require();var head=workspace.head(actor,form,true);
            var schema=Map.<String,Object>of("schemaContract","DWP_APPROVAL_FORM_TYPED_V2","schemaVersion",2,
                "fields",List.of(Map.of("key","summary","type","TEXTAREA","labelKo","Summary","labelEn","Summary")));
            new ApprovalFormLifecycleStore(workspace).update(actor,head,new ApprovalFormLifecycleDtos.UpdateWorkingDraft(draft,head.revision(),head.workspaceRevision(),schema,head.metadata(),other));
            head=workspace.head(actor,form,true);new ApprovalFormLifecycleStore(workspace).publish(actor,head,workspace.version(actor,form,draft));return true;});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_form_workflow_bindings WHERE form_id=? AND workflow_id=?",Integer.class,form,other)).isZero();
        UUID modern=UUID.randomUUID();UUID otherVersion=jdbc.queryForObject("SELECT v.workflow_version_id FROM apr_workflow_versions v JOIN apr_workflow_definitions w "
            +"ON w.tenant_id=v.tenant_id AND w.workflow_id=v.workflow_id AND w.current_version=v.version_number WHERE w.workflow_id=?",UUID.class,other);
        jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,requester_user_id,status,data_classification) "
            +"VALUES (?,42,'MODERN-PIN',?,?,'Modern request',99,'DRAFT','CONFIDENTIAL')",modern,otherVersion,draft);
        http.setRequestURI("/v1/requests/"+modern+"/draft");String before=state();
        var pin=run(()->requests.existingRequest(ApprovalRequestContext.require(),modern,0,form,other,Function.identity()));
        assertThat(pin.workflow().workflowVersionId()).isEqualTo(otherVersion);assertThat(pin.formVersionId()).isEqualTo(draft);
        assertThat(pin.metadataProvenance()).isEqualTo("PUBLISH_SNAPSHOT");assertThat(pin.legacyBindingJson()).isNull();assertThat(state()).isEqualTo(before);
        jdbc.update("UPDATE apr_requests SET workflow_version_id=? WHERE request_id=?",workflowVersion,modern);String tampered=state();
        denied(()->requests.existingRequest(ApprovalRequestContext.require(),modern,0,form,workflow,Function.identity()));assertThat(state()).isEqualTo(tampered);
    }
    @Test void legacyEffectiveBindingStillRequiredAfterCaptureAndCatalogRetire() {
        capture();jdbc.update("UPDATE apr_form_workflow_bindings SET effective_to=clock_timestamp()-interval '1 second',effective_from=NULL WHERE form_id=?",form);
        String before=state();denied(this::seal);assertThat(state()).isEqualTo(before);
    }
    private ApprovalFormRequestVersionPin seal() { return run(()->requests.existingRequest(ApprovalRequestContext.require(),request,0,form,workflow,Function.identity())); }
    private void assertOriginal(ApprovalFormRequestVersionPin pin) {
        assertThat(pin.formVersionId()).isEqualTo(version);assertThat(pin.schemaSha256()).isEqualTo(originalHash);
        assertThat(pin.workflow().workflowVersionId()).isEqualTo(workflowVersion);assertThat(pin.workflow().definitionSha256()).isEqualTo(workflowHash);
        assertThat(pin.workflow().requestDataClassification()).isEqualTo("RESTRICTED");assertThat(pin.requestId()).isEqualTo(request);assertThat(pin.requestVersion()).isZero();
    }
    private UUID advanceWorkflow() {
        UUID id=UUID.randomUUID();int next=jdbc.queryForObject("SELECT MAX(version_number)+1 FROM apr_workflow_versions WHERE workflow_id=?",Integer.class,workflow);
        jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,definition_sha256,lifecycle_state,effective_from) "
            +"SELECT ?,tenant_id,workflow_id,?,definition,definition_sha256,'PUBLISHED',effective_from FROM apr_workflow_versions WHERE workflow_version_id=?",id,next,workflowVersion);
        jdbc.update("UPDATE apr_workflow_definitions SET current_version=?,version=version+1,sla_minutes=480,data_classification='INTERNAL' WHERE workflow_id=?",next,workflow);
        return id;
    }
    private void capture() { run(()->{var actor=ApprovalRequestContext.require();var head=workspace.head(actor,form,true);
        return new ApprovalFormLifecycleStore(workspace).branch(actor,head,workspace.version(actor,form,version));}); }
    private void context(String leaf) {
        ApprovalRequestContext.set(99L,42L,null,"Owner",Set.of("APPROVAL_OPERATOR"),PERMISSIONS);
        ApprovalManagementScopeContext.set("scope","RS_APPROVALS");String route="route.approvals.work."+leaf;
        ApprovalDecisionRevisionContext.set(REV,OffsetDateTime.now().plusMinutes(5),"work","scope",route,"110");
        boolean creating=leaf.equals("request-create.action");
        ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(route,"ACTION","full-work",false,
            creating?Set.of():Set.of("predicate.approval.own-request.v1"),creating?"approvals.work.request.create":"approvals.work.request.update",null,null,false,null,null)));
        http.setMethod(leaf.equals("request-draft-update.action")?"PUT":"POST");
        http.setRequestURI(creating?"/v1/requests":"/v1/requests/"+request+(leaf.equals("request-submit.action")?"/submit":leaf.equals("request-information-response.action")?"/information-response":leaf.equals("request-draft-recover.action")?"/draft/recover":"/draft"));
    }
    private ApprovalIdentityDirectory.Subject subject(long tenant,long actor,boolean allowed) {
        return new ApprovalIdentityDirectory.Subject(tenant,actor,null,null,"Owner",null,null,"ACTIVE",List.of("APPROVAL_OPERATOR"),allowed?List.copyOf(PERMISSIONS):List.of());
    }
    private <T> T run(Supplier<T> body) { return tx.execute(ignored->body.get()); }
    private void denied(Supplier<?> body) { assertThatThrownBy(()->run(body)).isInstanceOf(BaseException.class); }
    private String state() {
        return List.of("apr_forms","apr_form_versions","apr_requests","apr_request_payloads","apr_steps","apr_form_workspaces","apr_form_version_material",
            "apr_form_version_lineage","apr_form_lifecycle_events","apr_form_command_receipts","sys_audit_outbox","apr_integration_outbox")
            .stream().map(table->jdbc.queryForObject("SELECT COALESCE(jsonb_agg(row ORDER BY row::text),'[]'::jsonb)::text FROM (SELECT to_jsonb(t) AS row FROM "+table+" t) x",String.class))
            .collect(java.util.stream.Collectors.joining("\n"));
    }
}

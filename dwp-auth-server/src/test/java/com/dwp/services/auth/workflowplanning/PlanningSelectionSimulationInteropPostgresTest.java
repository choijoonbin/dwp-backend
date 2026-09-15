package com.dwp.services.auth.workflowplanning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2Compiler;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorum;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorumDefinition;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalPilotPepRegistry;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.WorkflowPlanningContextInteropFixture;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningAttestationVerifier;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningAuthorityClient;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningBody;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningFacade;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningInstalledContext;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningKeys;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningProofIssuer;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningProtocol;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningResult;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningRuntime;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningSelection;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningSelectionContext;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningSelectionFacade;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.service.PlanningActualAuthHarness;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real Auth PG/Redis/HTTP and Approval PG selection-to-simulation interop on the latest supported registry. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanningSelectionSimulationInteropPostgresTest {
    @Container static final PostgreSQLContainer<?> APPROVAL=new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp-owner","workflow-planning-native-interop");
    private static final ObjectMapper MAPPER=new ObjectMapper().findAndRegisterModules();
    private static final RSAKey OWNER=key("planning-interop-owner"),TRANSPORT=key("planning-interop-transport");
    private static final RSAKey ATTESTATION=key("planning-interop-attestation"),WRONG=key("planning-interop-wrong-trust");
    private static final RSAKey PROHIBITED=key("planning-interop-prohibited");
    private PlanningActualAuthHarness auth;
    private PlanningActualAuthHarness.Subject subject;
    private JdbcTemplate approval;
    private NamedParameterJdbcTemplate named;
    private PlatformTransactionManager transactions;
    private long tenant;
    private long role;

    @BeforeAll void start() throws Exception {
        auth=new PlanningActualAuthHarness(OWNER,TRANSPORT,ATTESTATION,14L);
        tenant=auth.tenantId();role=auth.role("PLANNING_APPROVER",3);
        DataSource source=new DriverManagerDataSource(APPROVAL.getJdbcUrl(),APPROVAL.getUsername(),APPROVAL.getPassword());
        var root=Files.isDirectory(Path.of("dwp-auth-server"))?Path.of("."):Path.of("..");
        var flyway=Flyway.configure().dataSource(source).locations(
                "filesystem:"+root.resolve("dwp-approval-server/src/main/resources/db/migration"),
                "filesystem:"+root.resolve("dwp-core/src/main/resources/db/migration")).load();
        flyway.migrate();flyway.validate();assertThat(flyway.info().pending()).isEmpty();
        approval=new JdbcTemplate(source);named=new NamedParameterJdbcTemplate(source);transactions=new DataSourceTransactionManager(source);
        approval.queryForObject("SELECT seed_approval_tenant(?)",Object.class,tenant);
    }
    @AfterAll void stop() {if(auth!=null) auth.close();}
    @BeforeEach void identity() {subject=auth.subject();auth.grantAppView(subject);}
    @AfterEach void clear() {
        WorkflowPlanningContextInteropFixture.clear();ApprovalRequestContext.clear();
    }

    @Test void currentV14AuthIssuesRealAttestationForApprovalSelectionThenSimulationWithoutWrites() throws Exception {
        var prepared=prepare();String before=business();var runtime=runtime(ATTESTATION);
        runtime.requireReady();
        assertThat(new PlanningReadiness(auth.service()).health().getStatus()).isEqualTo(Status.UP);
        var selection=select(prepared);var result=simulate(prepared,selection,runtime);
        assertThat(business()).isEqualTo(before);
        assertThat(prepared.current.policyRevision()).startsWith("policy-14-");
        assertThat(selection.workflowVersionId()).isEqualTo(prepared.workflowVersion);
        assertThat(selection.selectedFormId()).isEqualTo(prepared.form);
        assertThat(result.mode()).isEqualTo("ROLE_POOL_PREVIEW");
        assertThat(result.runtimeEligibility()).isEqualTo("NOT_EVALUATED");
        assertThat(result.requesterExclusion()).isEqualTo("NOT_EVALUATED");
        assertThat(result.stages()).singleElement().satisfies(stage->{
            assertThat(stage.roleCode()).isEqualTo("PLANNING_APPROVER");
            assertThat(stage.activeMemberCount()).isEqualTo(3);
            assertThat(stage.indicativeThreshold()).isEqualTo(3);
        });
    }

    @Test void currentDutyRevocationBeforeSecondAuthReadCannotReturnAStaleSimulation() throws Exception {
        var prepared=prepare();var selection=select(prepared);String before=business();var calls=new AtomicInteger();
        var client=spy(client(ATTESTATION));
        doAnswer(invocation->{
            if(calls.incrementAndGet()==2) auth.revoke(subject,"APPROVAL_FORM_REFERENCE_READ");
            return invocation.callRealMethod();
        }).when(client).evaluate(any());
        var exchange=new WorkflowPlanningRuntime(new WorkflowPlanningProofIssuer(keys(ATTESTATION),Clock.systemUTC()),client);
        assertThatThrownBy(()->simulate(prepared,selection,exchange)).isInstanceOfSatisfying(BaseException.class,
                failure->assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(calls).hasValue(2);assertThat(business()).isEqualTo(before);
    }

    @Test void mismatchedAttestationTrustFailsClosedAfterRealAuthHttpAndNeverWritesApprovalState() throws Exception {
        var prepared=prepare();var selection=select(prepared);String before=business();
        assertThatThrownBy(()->simulate(prepared,selection,runtime(WRONG))).isInstanceOfSatisfying(BaseException.class,
                failure->assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(business()).isEqualTo(before);
    }

    private Prepared prepare() {
        UUID workflow=approval.queryForObject("SELECT workflow_id FROM apr_workflow_definitions WHERE tenant_id=? AND workflow_key='ACCESS_EXCEPTION'",UUID.class,tenant);
        String scope=approval.queryForObject("SELECT management_resource_set_key FROM apr_workflow_definitions WHERE tenant_id=? AND workflow_id=?",String.class,tenant,workflow);
        UUID form=approval.queryForObject("SELECT form_id FROM apr_form_workflow_bindings WHERE tenant_id=? AND workflow_id=? AND binding_type='DEFAULT'",UUID.class,tenant,workflow);
        var definition=ApprovalWorkflowQuorumDefinition.fromStages(60,List.of(new ApprovalWorkflowQuorumDefinition.Stage(
                "PLANNING_REVIEW","Planning review","PLANNING_APPROVER",new ApprovalWorkflowQuorum.Rule(ApprovalWorkflowQuorum.Mode.ALL,null),15,List.of())));
        int workflowNumber=approval.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM apr_workflow_versions WHERE tenant_id=? AND workflow_id=?",Integer.class,tenant,workflow);
        UUID workflowVersion=UUID.randomUUID();
        approval.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,definition_sha256,lifecycle_state,effective_from,published_at,published_by) VALUES(?,?,?,?,?::jsonb,?,'PUBLISHED',now()-interval '1 minute',now(),1)",
                workflowVersion,tenant,workflow,workflowNumber,definition.canonicalJson(),definition.sha256());
        approval.update("UPDATE apr_workflow_definitions SET current_version=?,lifecycle_state='PUBLISHED',version=version+1 WHERE tenant_id=? AND workflow_id=?",workflowNumber,tenant,workflow);
        var schema=new ApprovalFormSchemaV2Compiler().compile(Map.of("schemaContract",ApprovalFormSchemaV2.CONTRACT,"schemaVersion",2,"fields",List.of(
                field("summary","TEXTAREA",true),field("amount","NUMBER",false))));
        int formNumber=approval.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM apr_form_versions WHERE tenant_id=? AND form_id=?",Integer.class,tenant,form);
        UUID formVersion=UUID.randomUUID();
        approval.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,schema_payload,schema_sha256,lifecycle_state,published_at,published_by,created_by) VALUES(?,?,?,?,?::jsonb,?,'PUBLISHED',now(),1,1)",
                formVersion,tenant,form,formNumber,schema.canonicalJson(),schema.sha256());
        approval.update("UPDATE apr_forms SET current_version=?,lifecycle_state='PUBLISHED',version=version+1 WHERE tenant_id=? AND form_id=?",formNumber,tenant,form);
        var identity=auth.identity(subject);var actor=new ApprovalRequestContext.Actor(identity.userId(),identity.tenantId(),identity.personPublicId(),null,identity.roles(),identity.permissions());
        var current=auth.current(subject);assertThat(current.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(current.effectiveReadOnly()).isTrue();assertThat(current.scopes()).singleElement();
        return new Prepared(actor,current,workflow,workflowVersion,form,formVersion,scope);
    }
    private WorkflowPlanningSelection select(Prepared value) {
        var request=install(value,"GET",WorkflowPlanningSelectionContext.ROUTE,value.form);
        var facade=new WorkflowPlanningSelectionFacade(true,named,MAPPER,transactions,work());
        return facade.select(request,value.workflow,value.form);
    }
    private WorkflowPlanningResult simulate(Prepared value,WorkflowPlanningSelection selection,WorkflowPlanningRuntime exchange) throws Exception {
        var request=install(value,"POST",WorkflowPlanningProtocol.ROUTE,null);
        var selected=selection.forms().stream().filter(form->value.form.equals(form.formId())).findFirst().orElseThrow();
        var body=new WorkflowPlanningBody(selection.workflowRevision(),selection.workflowSha256(),selected.formVersionId(),selected.formSchemaSha256(),
                selection.policy().version(),selection.policy().sha256(),selection.managementResourceSetKey(),Map.of("summary","Real interop sample","amount","20.00"));
        var facade=new WorkflowPlanningFacade(true,()->exchange,new WorkflowPlanningInstalledContext(),named,MAPPER,transactions,work());
        return facade.simulate(request,value.workflow,value.workflowVersion,MAPPER.writeValueAsBytes(body));
    }
    private MockHttpServletRequest install(Prepared value,String method,String route,UUID selectedForm) {
        String contextScope=value.current.scopes().getFirst().key();OffsetDateTime expiry=value.current.revalidateAt();
        if(value.current.validUntil()!=null && value.current.validUntil().isBefore(expiry)) expiry=value.current.validUntil();
        ApprovalRequestContext.set(value.actor.userId(),value.actor.tenantId(),value.actor.personPublicId(),value.actor.displayName(),value.actor.roles(),value.actor.permissions());
        boolean selection=WorkflowPlanningSelectionContext.ROUTE.equals(route);var profiles=List.of(
                profile(route,selection,"approvals.admin.workflow-planning-form.read","ACTION.APPROVAL_FORM:VIEW"),
                profile(route,selection,WorkflowPlanningProtocol.CAPABILITY,WorkflowPlanningProtocol.PERMISSION));
        WorkflowPlanningContextInteropFixture.install("psr-"+"a".repeat(64),expiry,value.current.contextKey(),contextScope,route,"111",value.scope,profiles);
        String path=selection?"/v1/admin/workflows/"+value.workflow+"/planning-selection"
                :"/v1/admin/workflows/"+value.workflow+"/versions/"+value.workflowVersion+"/simulation";
        var request=new MockHttpServletRequest(method,path);if(selection) request.setQueryString("formId="+selectedForm);
        request.setAttribute(ApprovalPilotPepRegistry.class.getName()+".authorities",profiles);
        request.addHeader("X-DWP-Active-Access-Mode","NORMAL");request.addHeader("X-DWP-Identity-Plane","TENANT");return request;
    }
    private static ApprovalPilotPepRegistry.RouteAuthority profile(String route,boolean selection,String capability,String permission) {
        String predicate=selection?WorkflowPlanningSelectionContext.PREDICATE:WorkflowPlanningProtocol.PREDICATE;
        String schema=selection?"ApprovalWorkflowPlanningSelection":"ApprovalWorkflowPlanningResult";
        return new ApprovalPilotPepRegistry.RouteAuthority(route,"DATA","full-management",true,Set.of(predicate),capability,null,null,false,
                route+".full-management.projection.v1",schema,1,"b".repeat(64),false,permission,"APP_CONFIG_ADMIN");
    }
    private ApprovalWorkAuthority work() {return new ApprovalWorkAuthority(new AuthDirectory());}
    private final class AuthDirectory implements ApprovalIdentityDirectory {
        @Override public Subject require(long requestedTenant,long user) {
            var identity=auth.identity(subject);
            if(requestedTenant!=identity.tenantId() || user!=identity.userId()) throw new BaseException(ErrorCode.FORBIDDEN);
            return new Subject(identity.tenantId(),identity.userId(),null,identity.personPublicId(),null,null,null,"ACTIVE",
                    identity.roles().stream().sorted().toList(),identity.permissions().stream().sorted().toList());
        }
        @Override public List<Subject> search(long tenant,String query,int limit) {throw new AssertionError("Planning cannot search identities.");}
        @Override public RoleEligibility requireRole(long tenant,String roleCode) {throw new AssertionError("Planning role counts are Auth-owned.");}
    }
    private WorkflowPlanningRuntime runtime(RSAKey trust) {return new WorkflowPlanningRuntime(new WorkflowPlanningProofIssuer(keys(trust),Clock.systemUTC()),client(trust));}
    private WorkflowPlanningAuthorityClient client(RSAKey trust) {
        return new WorkflowPlanningAuthorityClient(auth.endpoint(),new WorkflowPlanningAttestationVerifier(keys(trust),Clock.systemUTC()));
    }
    private static WorkflowPlanningKeys keys(RSAKey trust) {return new WorkflowPlanningKeys(OWNER,TRANSPORT,new JWKSet(trust.toPublicJWK()).toString(),new JWKSet(PROHIBITED.toPublicJWK()).toString());}
    private static Map<String,Object> field(String key,String type,boolean required) {
        var value=new LinkedHashMap<String,Object>();value.put("key",key);value.put("type",type);value.put("labelKo",key);value.put("labelEn",key);value.put("required",required);return value;
    }
    private String business() {
        var values=new LinkedHashMap<String,Object>();
        for(String table:List.of("apr_steps","apr_tasks","apr_quorum_stage_runtime","apr_quorum_candidates","apr_quorum_votes","apr_quorum_sla_timers","apr_request_events","apr_integration_outbox","sys_audit_outbox"))
            values.put(table,approval.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(row) ORDER BY to_jsonb(row)::text),'[]'::jsonb)::text FROM "+table+" row",String.class));
        return values.toString();
    }
    private static RSAKey key(String id) {try {return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();}
        catch(Exception failure) {throw new AssertionError(failure);}}
    private record Prepared(ApprovalRequestContext.Actor actor,ProductSurfaceAuthorityDtos.AuthorityResult current,UUID workflow,
            UUID workflowVersion,UUID form,UUID formVersion,String scope) { }
}

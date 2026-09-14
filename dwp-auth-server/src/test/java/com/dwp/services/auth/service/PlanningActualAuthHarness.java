package com.dwp.services.auth.service;

import com.dwp.services.auth.workflowplanning.*;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/** Genuine immutable10/current full migrations and explicit disposable scoped duties only. */
public final class PlanningActualAuthHarness implements AutoCloseable {
    public static final String CHECKSUM="1f97638c95a192f0ec7f01053c3965f79b7a3ee4eb9781ea56e3cf8eccc6889b";
    private static final AtomicLong USERS=new AtomicLong(92000000);
    private final WorkflowRuntimeActualAuthHarness auth;
    private final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
    private final AnnotationConfigApplicationContext parent;
    private PlanningEmbeddedServer http;
    private final UUID resourceSet;
    public PlanningActualAuthHarness(RSAKey owner,RSAKey transport,RSAKey attestation) throws Exception {
        auth=new WorkflowRuntimeActualAuthHarness(new RSAKeyGenerator(2048).keyID("unrelated-planning-runtime-owner").generate(),
                new RSAKeyGenerator(2048).keyID("unrelated-planning-runtime-transport").generate(),
                new RSAKeyGenerator(2048).keyID("unrelated-planning-runtime-attestation").generate());
        try {
            var field=WorkflowRuntimeActualAuthHarness.class.getDeclaredField("context");field.setAccessible(true);parent=(AnnotationConfigApplicationContext)field.get(auth);
            var repository=parent.getBean(ProductAuthorizationContractRepository.class);var ten=repository.find("product-surfaces",10).orElseThrow();
            var seal=new StoredDescriptorSeal(jdbc(),repository,parent.getBean(ProductAuthorizationContractValidator.class),parent.getBean(ObjectMapper.class));
            if(!CHECKSUM.equals(seal.loadVersion(ten).checksum())) throw new IllegalStateException("Genuine10 seal required.");
            var contracts=parent.getBean(ProductAuthorizationContractService.class);contracts.approve("product-surfaces",10,"planning-independent-disposable-checker");
            contracts.activate("product-surfaces",10,"planning-disposable-release",repository.findActivePointer("product-surfaces").orElseThrow().revision());
            resourceSet=jdbc().queryForObject("SELECT resource_set_id FROM com_admin_resource_sets WHERE tenant_id=? AND resource_set_key='RS_APPROVALS' AND lifecycle_state='ACTIVE'",UUID.class,tenantId());
            for(String resource:List.of("APP.APPROVALS","ADMIN.APPROVAL_WORKFLOW","ACTION.APPROVAL_FORM")) {
                if(!Boolean.TRUE.equals(jdbc().queryForObject("SELECT enabled FROM com_resources WHERE tenant_id=? AND key=?",Boolean.class,tenantId(),resource)))
                    throw new IllegalStateException("Actual enabled registered resource required; no catalog fixture insertion.");
                jdbc().update("INSERT INTO com_admin_resource_set_members(tenant_id,resource_set_id,resource_type,resource_key,lifecycle_state) VALUES(?,?,?,?,'ACTIVE') ON CONFLICT DO NOTHING",
                        tenantId(),resourceSet,resource.substring(0,resource.indexOf('.')),resource);
            }
            var factory=new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(parent.getBean(jakarta.persistence.EntityManagerFactory.class)));
            context.setParent(parent);String prefix="dwp.auth.approval-workflow-planning.";
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("planning-disposable-purpose",Map.of(prefix+"enabled",true,
                    prefix+"owner-public-jwks",new JWKSet(owner.toPublicJWK()).toString(),prefix+"transport-public-jwks",new JWKSet(transport.toPublicJWK()).toString(),
                    prefix+"attestation-private-jwk",attestation.toJSONString(),prefix+"attestation-public-jwks",new JWKSet(attestation.toPublicJWK()).toString())));
            context.registerBean(StringRedisTemplate.class,auth::redis);context.registerBean(RoleMemberRepository.class,()->factory.getRepository(RoleMemberRepository.class));
            context.register(PlanningConfiguration.class,PlanningIdentityAuthorityBridge.class,PlanningRoleRepository.class,PlanningRoleSources.class);context.refresh();
            http=new PlanningEmbeddedServer(service(),true);
        } catch(Exception error) {close();throw error;}
    }
    public long tenantId() {return auth.tenantId();}
    public JdbcTemplate jdbc() {return auth.jdbc();}
    public StringRedisTemplate redis() {return auth.redis();}
    public URI endpoint() {return http.endpoint();}
    public PlanningAuthorityService service() {return context.getBean(PlanningAuthorityService.class);}
    public PlanningIdentityAuthorityBridge bridge() {return context.getBean(PlanningIdentityAuthorityBridge.class);}
    public PlanningRoleRepository roles() {return context.getBean(PlanningRoleRepository.class);}
    public PlanningAuthorityService observing(PlanningAuthorityPort port) {
        return new PlanningAuthorityService(()->context.getBean(PlanningProofVerifier.class),port,roles(),context.getBean(PlanningReplayStore.class),
                context.getBean(PlanningAuthorityIssuer.class),context.getBean(PlanningJson.class),Clock.systemUTC(),true);
    }
    public ProductSurfaceAuthorityDtos.AuthorityResult current(Subject subject) {
        return parent.getBean(ProductSurfaceAuthorityService.class).evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(tenantId(),subject.userId(),
                "approvals","approvals.admin",ProductSurfaceAuthorityDtos.AccessMode.NORMAL,PlanningProtocol.ROUTE,null,null,null,null,List.of()));
    }
    public Identity identity(Subject subject) {
        var principal=principal(subject);
        var service=parent.getBean(ProductAuthorizationIdentityEvidenceService.class);
        var before=service.load(tenantId(),subject.userId());
        var after=service.load(tenantId(),subject.userId());
        if(!before.equals(after) || !principal.equals(principal(subject)))
            throw new IllegalStateException("Current disposable identity changed.");
        return new Identity(tenantId(),subject.userId(),subject.personPublicId(),principal.get("status"),principal.get("plane"),
                after.permissions(),after.roles(),after.revision());
    }
    private Map<String,String> principal(Subject subject) {
        return jdbc().queryForObject("SELECT person_public_id,status,identity_plane FROM com_users WHERE tenant_id=? AND user_id=?",(row,index)->{
            if(!subject.personPublicId().equals(row.getObject("person_public_id",UUID.class)))
                throw new IllegalStateException("Disposable person binding changed.");
            return Map.of("status",row.getString("status"),"plane",row.getString("identity_plane"));
        },tenantId(),subject.userId());
    }
    public PrincipalResourceGrantRepository.GrantRecord grantAppView(Subject subject) {
        return auth.grant(subject.userId(),"APP.APPROVALS","VIEW",subject.checkerId());
    }
    public Subject subject() {
        long user=USERS.incrementAndGet(),maker=USERS.incrementAndGet(),checker=USERS.incrementAndGet();UUID person=UUID.randomUUID();
        auth.subject(user,person);auth.subject(maker,UUID.randomUUID());auth.subject(checker,UUID.randomUUID());
        var now=OffsetDateTime.now();UUID responsibility=UUID.randomUUID();
        jdbc().update("""
                INSERT INTO com_admin_role_assignments(admin_role_assignment_id,tenant_id,principal_type,principal_ref,responsibility_code,
                  resource_set_id,assignment_source,lifecycle_state,valid_from,valid_to,review_due_at,justification,approved_by,approved_at,decision_reason)
                VALUES(?,?,'USER',?,'APP_CONFIG_ADMIN',?,'MANUAL','ACTIVE',?,?,?, ?,?,CURRENT_TIMESTAMP,?)
                """,responsibility,tenantId(),Long.toString(user),resourceSet,now.minusMinutes(1),now.plusMinutes(10),now.plusMinutes(5),
                "Explicit disposable current same-set responsibility.",checker,"Independent disposable approval.");
        var assignments=parent.getBean(ScopedAdminDutyAssignmentService.class);var duties=new ArrayList<ScopedAdminDutyAssignmentService.Assignment>();
        for(String code:List.of("APPROVAL_WORKFLOW_PLANNING","APPROVAL_FORM_REFERENCE_READ")) {
            var pending=assignments.request(new ScopedAdminDutyAssignmentService.Request(tenantId(),"USER",Long.toString(user),code,resourceSet,responsibility,
                    "MANUAL",now.minusMinutes(1),now.plusMinutes(10),now.plusMinutes(5),"Explicit independent Planning source fixture.",maker));
            duties.add(assignments.approve(tenantId(),pending.assignmentId(),checker,pending.version(),"Independent source fixture approval."));
        }
        return new Subject(user,person,checker,responsibility,duties);
    }
    public void revoke(Subject subject,String code) {
        var duty=subject.duties().stream().filter(value->code.equals(value.dutyCode())).findFirst().orElseThrow();
        parent.getBean(ScopedAdminDutyAssignmentService.class).revoke(tenantId(),duty.assignmentId(),subject.checkerId(),duty.version(),"Current disposable source revocation.");
    }
    public long role(String code,int count) {
        var users=new ArrayList<Long>();for(int index=0;index<count;index++) {long user=USERS.incrementAndGet();auth.subject(user,UUID.randomUUID());users.add(user);}
        return auth.role(code,users);
    }
    @Override public void close() {if(http!=null) http.close();if(context.isActive()) context.close();auth.close();}
    public record Subject(long userId,UUID personPublicId,long checkerId,UUID responsibilityId,List<ScopedAdminDutyAssignmentService.Assignment> duties) {
        public Subject {duties=List.copyOf(duties);}
    }
    public record Identity(long tenantId,long userId,UUID personPublicId,String status,String identityPlane,
            Set<String> permissions,Set<String> roles,String revision) {
        public Identity {permissions=Set.copyOf(permissions);roles=Set.copyOf(roles);}
    }
}

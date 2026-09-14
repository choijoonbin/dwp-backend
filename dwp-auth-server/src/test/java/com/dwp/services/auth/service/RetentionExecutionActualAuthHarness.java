package com.dwp.services.auth.service;

import com.dwp.services.auth.retentionexecutionauthority.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Auth-leg only: actual full migrations/current scopes/Redis, not an Approval-native factory proof. */
public final class RetentionExecutionActualAuthHarness implements AutoCloseable {
    private static final AtomicLong USERS=new AtomicLong(96000000);
    private final WorkflowRuntimeActualAuthHarness auth;private final AnnotationConfigApplicationContext parent;
    private final RetentionExecutionIdentityAuthorityBridge bridge;private final UUID resourceSet;
    public RetentionExecutionActualAuthHarness(RetentionExecutionJson json) throws Exception {
        auth=new WorkflowRuntimeActualAuthHarness(new RSAKeyGenerator(2048).keyID("unrelated-retention-runtime-owner").generate(),
                new RSAKeyGenerator(2048).keyID("unrelated-retention-runtime-trans").generate(),new RSAKeyGenerator(2048).keyID("unrelated-retention-runtime-auth").generate());
        try {
            var field=WorkflowRuntimeActualAuthHarness.class.getDeclaredField("context");field.setAccessible(true);parent=(AnnotationConfigApplicationContext)field.get(auth);
            var repository=parent.getBean(com.dwp.services.auth.repository.ProductAuthorizationContractRepository.class);
            var validator=parent.getBean(ProductAuthorizationContractValidator.class);var mapper=parent.getBean(ObjectMapper.class);
            var bundle=repository.find("product-surfaces",10).orElseThrow();new StoredDescriptorSeal(jdbc(),repository,validator,mapper).loadVersion(bundle);
            var contracts=parent.getBean(ProductAuthorizationContractService.class);contracts.approve("product-surfaces",10,"retention-independent-disposable-checker");
            contracts.activate("product-surfaces",10,"retention-disposable-release",repository.findActivePointer("product-surfaces").orElseThrow().revision());
            resourceSet=jdbc().queryForObject("SELECT resource_set_id FROM com_admin_resource_sets WHERE tenant_id=? AND resource_set_key='RS_APPROVALS' AND lifecycle_state='ACTIVE'",UUID.class,tenantId());
            for(String key:List.of("APP.APPROVALS","ADMIN.APPROVAL_OPERATIONS")) {
                if(!Boolean.TRUE.equals(jdbc().queryForObject("SELECT enabled FROM com_resources WHERE tenant_id=? AND key=?",Boolean.class,tenantId(),key)))
                    throw new IllegalStateException("Actual registered resource required; no catalog fixture insertion.");
                jdbc().update("INSERT INTO com_admin_resource_set_members(tenant_id,resource_set_id,resource_type,resource_key,lifecycle_state) VALUES(?,?,?,?,'ACTIVE') ON CONFLICT DO NOTHING",
                        tenantId(),resourceSet,key.substring(0,key.indexOf('.')),key);
            }
            var identity=parent.getBean(ProductAuthorizationIdentityEvidenceService.class);var ports=new StaticListableBeanFactory();
            ports.addBean("actual-configured-current-authority",new ProductAuthorizationAuthorityAdapter(repository,identity,"urn:dwp:acr:mfa"));
            var surfaces=new ProductSurfaceAuthorityService(ports.getBeanProvider(ProductSurfaceAuthorityPort.class));
            bridge=new RetentionExecutionIdentityAuthorityBridge(identity,surfaces,repository,validator,jdbc(),mapper,json);
        } catch(Exception failure) {auth.close();throw failure;}
    }
    public long tenantId() {return auth.tenantId();}
    public JdbcTemplate jdbc() {return auth.jdbc();}
    public StringRedisTemplate redis() {return auth.redis();}
    public RetentionExecutionIdentityAuthorityBridge bridge() {return bridge;}
    public Subject subject() {
        long actor=USERS.incrementAndGet(),maker=USERS.incrementAndGet(),checker=USERS.incrementAndGet();auth.subject(actor,UUID.randomUUID());auth.subject(maker,UUID.randomUUID());auth.subject(checker,UUID.randomUUID());
        UUID responsibility=UUID.randomUUID();var now=OffsetDateTime.now();
        jdbc().update("""
                INSERT INTO com_admin_role_assignments(admin_role_assignment_id,tenant_id,principal_type,principal_ref,responsibility_code,
                  resource_set_id,assignment_source,lifecycle_state,valid_from,valid_to,review_due_at,justification,approved_by,approved_at,decision_reason)
                VALUES(?,?,'USER',?,'APP_CONFIG_ADMIN',?,'MANUAL','ACTIVE',?,?,?, ?,?,CURRENT_TIMESTAMP,?)
                """,responsibility,tenantId(),Long.toString(actor),resourceSet,now.minusMinutes(1),now.plusMinutes(10),now.plusMinutes(5),
                "Explicit current same-set managed execution fixture.",checker,"Independent fixture approval.");
        var assignments=parent.getBean(ScopedAdminDutyAssignmentService.class);
        var pending=assignments.request(new ScopedAdminDutyAssignmentService.Request(tenantId(),"USER",Long.toString(actor),"APPROVAL_OPERATIONS_EXECUTE",resourceSet,responsibility,
                "MANUAL",now.minusMinutes(1),now.plusMinutes(10),now.plusMinutes(5),"Explicit disposable managed Ops authority.",maker));
        var duty=assignments.approve(tenantId(),pending.assignmentId(),checker,pending.version(),"Independent current source approval.");
        return new Subject(actor,checker,responsibility,duty);
    }
    public void revoke(Subject subject) {
        parent.getBean(ScopedAdminDutyAssignmentService.class).revoke(tenantId(),subject.duty.assignmentId(),subject.checker,subject.duty.version(),"Current managed source revoked.");
    }
    public void ambiguousResponsibility(Subject subject) {
        long group=jdbc().queryForObject("INSERT INTO com_groups(tenant_id,group_key,display_name,status) VALUES(?,?,?,'ACTIVE') RETURNING group_id",Long.class,
                tenantId(),"managed-retention-ambiguity-"+UUID.randomUUID(),"Private source fixture group");
        jdbc().update("INSERT INTO com_group_members(tenant_id,group_id,user_id) VALUES(?,?,?)",tenantId(),group,subject.actor);
        jdbc().update("""
                INSERT INTO com_admin_role_assignments(admin_role_assignment_id,tenant_id,principal_type,principal_ref,responsibility_code,
                  resource_set_id,assignment_source,lifecycle_state,valid_from,valid_to,review_due_at,justification,approved_by,approved_at,decision_reason)
                SELECT ?,tenant_id,'GROUP',?,responsibility_code,resource_set_id,assignment_source,lifecycle_state,
                  valid_from,valid_to+interval '1 second',review_due_at,justification,approved_by,approved_at,decision_reason
                FROM com_admin_role_assignments WHERE admin_role_assignment_id=?
                """,UUID.randomUUID(),Long.toString(group),subject.responsibility);
    }
    @Override public void close() {auth.close();}
    public record Subject(long actor,long checker,UUID responsibility,ScopedAdminDutyAssignmentService.Assignment duty) { }
}

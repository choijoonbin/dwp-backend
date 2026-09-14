package com.dwp.services.auth.service;

import com.dwp.services.auth.approvalpolicyimpact.*;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** Genuine final9 in a disposable Auth database; every scoped grant is explicit fixture authority. */
public final class PolicyImpactActualAuthHarness implements AutoCloseable {
    public static final String CHECKSUM = "02b19c4119e560b63d4054ec317fe7e4d694e402a5af03960c63b20db4b41ab7";
    private static final AtomicLong USERS = new AtomicLong(91000000);
    private final WorkflowRuntimeActualAuthHarness auth;
    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    private final AnnotationConfigApplicationContext parent;
    private final UUID resourceSet;

    public PolicyImpactActualAuthHarness(RSAKey owner, RSAKey transport, RSAKey attestation) throws Exception {
        auth = new WorkflowRuntimeActualAuthHarness(new RSAKeyGenerator(2048).keyID("unrelated-impact-runtime-owner").generate(),
                new RSAKeyGenerator(2048).keyID("unrelated-impact-runtime-transport").generate(),
                new RSAKeyGenerator(2048).keyID("unrelated-impact-runtime-attestation").generate());
        try {
            var field = WorkflowRuntimeActualAuthHarness.class.getDeclaredField("context"); field.setAccessible(true);
            parent = (AnnotationConfigApplicationContext) field.get(auth);
            var repository = parent.getBean(ProductAuthorizationContractRepository.class);
            var bundle = repository.find("product-surfaces", 9).orElseThrow();
            var seal = new StoredDescriptorSeal(jdbc(), repository, parent.getBean(ProductAuthorizationContractValidator.class),
                    parent.getBean(ObjectMapper.class));
            if (!CHECKSUM.equals(seal.loadVersion(bundle).checksum())) throw new IllegalStateException("Final9 checksum is not sealed.");
            var contracts = parent.getBean(ProductAuthorizationContractService.class);
            contracts.approve("product-surfaces", 9, "impact-isolated-independent-checker");
            contracts.activate("product-surfaces", 9, "impact-isolated-release", repository.findActivePointer("product-surfaces").orElseThrow().revision());
            if (!CHECKSUM.equals(seal.loadActive(repository.findActive("product-surfaces").orElseThrow(),
                    repository.findActivePointer("product-surfaces").orElseThrow()).checksum())) throw new IllegalStateException("Final9 active pointer drifted.");
            resourceSet = jdbc().queryForObject("SELECT resource_set_id FROM com_admin_resource_sets WHERE tenant_id=? AND resource_set_key='RS_APPROVALS' AND lifecycle_state='ACTIVE'", UUID.class, tenantId());
            for (String resource : List.of("APP.APPROVALS", "ADMIN.APPROVAL_POLICY", "ADMIN.APPROVAL_DESIGN", "ADMIN.APPROVAL_OPERATIONS")) {
                String type = resource.startsWith("APP.") ? "APP" : "ADMIN";
                if (!Boolean.TRUE.equals(jdbc().queryForObject("SELECT enabled FROM com_resources WHERE tenant_id=? AND type=? AND key=?", Boolean.class, tenantId(), type, resource)))
                    throw new IllegalStateException("Fixture authority requires an actual registered enabled resource.");
                jdbc().update("INSERT INTO com_admin_resource_set_members(tenant_id,resource_set_id,resource_type,resource_key,lifecycle_state) VALUES(?,?,?,?,'ACTIVE') ON CONFLICT DO NOTHING", tenantId(), resourceSet, type, resource);
            }
            context.setParent(parent);
            String prefix = "dwp.auth.approval-policy-impact.";
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("impact-isolated-purpose", Map.of(
                    prefix + "enabled", true, prefix + "owner-public-jwks", new JWKSet(owner.toPublicJWK()).toString(),
                    prefix + "transport-public-jwks", new JWKSet(transport.toPublicJWK()).toString(),
                    prefix + "attestation-private-jwk", attestation.toJSONString(),
                    prefix + "attestation-public-jwks", new JWKSet(attestation.toPublicJWK()).toString())));
            context.registerBean(StringRedisTemplate.class, auth::redis);
            context.register(PolicyImpactConfiguration.class, ApprovalPolicyImpactIdentityAuthorityBridge.class); context.refresh();
        } catch (Exception error) { close(); throw error; }
    }

    public long tenantId() { return auth.tenantId(); }
    public JdbcTemplate jdbc() { return auth.jdbc(); }
    public StringRedisTemplate redis() { return auth.redis(); }
    public PolicyImpactAuthorityService service() { return context.getBean(PolicyImpactAuthorityService.class); }
    public PolicyImpactAuthorityService observing(PolicyImpactAuthorityPort authority) {
        return new PolicyImpactAuthorityService(() -> context.getBean(PolicyImpactProofVerifier.class), authority,
                context.getBean(PolicyImpactReplayStore.class), context.getBean(PolicyImpactAuthorityIssuer.class), true);
    }
    public ApprovalPolicyImpactIdentityAuthorityBridge bridge() { return context.getBean(ApprovalPolicyImpactIdentityAuthorityBridge.class); }
    public boolean queryConstraintsAreBothAbsent() {
        var repository = parent.getBean(ProductAuthorizationContractRepository.class);
        var route = repository.loadContract(repository.findActive("product-surfaces").orElseThrow()).routes().stream()
                .filter(value -> PolicyImpactProtocol.ROUTE.equals(value.routeContractKey())).findFirst().orElseThrow();
        return route.gatewayApiBindings().getFirst().queryParameterConstraints() == null
                && route.servicePepBindings().getFirst().queryParameterConstraints() == null;
    }
    public void withMismatchedQueryConstraints(Runnable check) {
        parent.getBean(org.springframework.transaction.support.TransactionTemplate.class).execute(status -> {
            // An actual null/empty discrepancy is never allowed to pass the immutable source seal.
            jdbc().execute("ALTER TABLE auth_governed_route_contract DISABLE TRIGGER USER");
            int changed = jdbc().update("""
                    UPDATE auth_governed_route_contract SET descriptor=jsonb_set(descriptor,
                        '{servicePepBindings,0,queryParameterConstraints}','{}'::jsonb,true)
                    WHERE route_contract_key=? AND bundle_id=(SELECT bundle_id FROM auth_product_authorization_active WHERE bundle_key='product-surfaces')
                    """, PolicyImpactProtocol.ROUTE);
            if (changed != 1) throw new IllegalStateException("Query fixture did not corrupt exactly one own row.");
            try { check.run(); } finally { status.setRollbackOnly(); }
            return null;
        });
    }
    public ProductSurfaceAuthorityDtos.AuthorityResult current(Subject subject) {
        return parent.getBean(ProductSurfaceAuthorityService.class).evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(
                tenantId(), subject.userId(), "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                PolicyImpactProtocol.ROUTE, null, null, null, null, List.of()));
    }
    public Subject subject() {
        long user = USERS.incrementAndGet(), maker = USERS.incrementAndGet(), checker = USERS.incrementAndGet();
        UUID person = UUID.randomUUID(); auth.subject(user, person); auth.subject(maker, UUID.randomUUID()); auth.subject(checker, UUID.randomUUID());
        var now = OffsetDateTime.now(); var deadline = now.plusMinutes(10);
        UUID responsibility = UUID.randomUUID();
        jdbc().update("""
                INSERT INTO com_admin_role_assignments(admin_role_assignment_id,tenant_id,principal_type,principal_ref,
                    responsibility_code,resource_set_id,assignment_source,lifecycle_state,valid_from,valid_to,review_due_at,
                    justification,approved_by,approved_at,decision_reason)
                VALUES(?,?,'USER',?,'APP_CONFIG_ADMIN',?,'MANUAL','ACTIVE',?,?,?, ?,?,CURRENT_TIMESTAMP,?)
                """, responsibility, tenantId(), Long.toString(user), resourceSet, now.minusMinutes(1), deadline,
                now.plusMinutes(5), "Explicit disposable same-set configuration responsibility.", checker, "Independent fixture approval.");
        var assignments = parent.getBean(ScopedAdminDutyAssignmentService.class);
        var duties = new ArrayList<ScopedAdminDutyAssignmentService.Assignment>();
        for (String code : List.of("APPROVAL_POLICY_DRAFT", "APPROVAL_DESIGN_DRAFT", "APPROVAL_OPERATIONS_EXECUTE")) {
            var pending = assignments.request(new ScopedAdminDutyAssignmentService.Request(tenantId(), "USER", Long.toString(user),
                    code, resourceSet, responsibility, "MANUAL", now.minusMinutes(1), deadline,
                    now.plusMinutes(5), "Explicit independently approved PolicyImpact fixture duty.", maker));
            duties.add(assignments.approve(tenantId(), pending.assignmentId(), checker, pending.version(), "Independent fixture duty approval."));
        }
        return new Subject(user, person, checker, responsibility, duties);
    }
    public void revoke(Subject subject, String code) {
        var duty = subject.duties().stream().filter(value -> code.equals(value.dutyCode())).findFirst().orElseThrow();
        parent.getBean(ScopedAdminDutyAssignmentService.class).revoke(tenantId(), duty.assignmentId(), subject.checkerId(), duty.version(), "Actual disposable source duty revocation.");
    }
    public void replaceDuty(Subject subject, String code) {
        revoke(subject, code);
        var assignments = parent.getBean(ScopedAdminDutyAssignmentService.class); var now = OffsetDateTime.now();
        var pending = assignments.request(new ScopedAdminDutyAssignmentService.Request(tenantId(), "USER", Long.toString(subject.userId()),
                code, resourceSet, subject.responsibilityId(), "MANUAL", now.minusSeconds(1), now.plusMinutes(8),
                now.plusMinutes(4), "Actual same-count independently approved replacement.", subject.userId()));
        assignments.approve(tenantId(), pending.assignmentId(), subject.checkerId(), pending.version(), "Independent replacement approval.");
    }
    public void expireResponsibility(Subject subject) {
        int changed = jdbc().update("""
                UPDATE com_admin_role_assignments SET valid_to=CURRENT_TIMESTAMP-INTERVAL '1 second',
                    version=version+1,updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=? AND admin_role_assignment_id=? AND principal_ref=? AND lifecycle_state='ACTIVE'
                """, tenantId(), subject.responsibilityId(), Long.toString(subject.userId()));
        if (changed != 1) throw new IllegalStateException("Expiry fixture must change exactly its own linked responsibility.");
    }
    public List<ScopedAdminDutyEvidenceService.EffectiveDuty> duties(Subject subject) {
        return parent.getBean(ScopedAdminDutyEvidenceService.class).effectiveDuties(tenantId(), subject.userId());
    }
    @Override public void close() { if (context.isActive()) context.close(); auth.close(); }
    public record Subject(long userId, UUID personPublicId, long checkerId, UUID responsibilityId,
            List<ScopedAdminDutyAssignmentService.Assignment> duties) {
        public Subject { duties = List.copyOf(duties); }
    }
}

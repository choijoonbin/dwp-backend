package com.dwp.services.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.approvalpolicyimpact.PolicyImpactJson;
import com.dwp.services.auth.approvalsignatures.SignatureAuthorityBindings;
import com.dwp.services.auth.approvalsignatures.SignatureAuthorityJson;
import com.dwp.services.auth.approvalsignatures.SignatureAuthorityProtocol;
import com.dwp.services.auth.dto.ProductSurfaceStepUpDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.retentionexecutionauthority.RetentionExecutionJson;
import com.dwp.services.auth.workflowplanning.PlanningJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/** Exercises the real sealed v32 artifact through every release-gated operational validator. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductAuthorizationV32OperationalCompatibilityPostgresTest {
    private static final String BUNDLE_KEY = "product-surfaces";
    private WorkflowRuntimeActualAuthHarness auth;
    private ProductAuthorizationContractRepository repository;
    private ProductAuthorizationContractService contracts;
    private ProductAuthorizationIdentityEvidenceService identities;
    private ProductSurfaceAuthorityService surfaces;
    private ProductAuthorizationContractValidator validator;
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private RoleMemberRepository members;

    @BeforeAll
    void start() throws Exception {
        auth = new WorkflowRuntimeActualAuthHarness(
                new RSAKeyGenerator(2048).keyID("v32-runtime-owner").generate(),
                new RSAKeyGenerator(2048).keyID("v32-runtime-transport").generate(),
                new RSAKeyGenerator(2048).keyID("v32-runtime-attestation").generate());
        var context = auth.context();
        repository = context.getBean(ProductAuthorizationContractRepository.class);
        contracts = context.getBean(ProductAuthorizationContractService.class);
        identities = context.getBean(ProductAuthorizationIdentityEvidenceService.class);
        surfaces = context.getBean(ProductSurfaceAuthorityService.class);
        validator = context.getBean(ProductAuthorizationContractValidator.class);
        jdbc = context.getBean(JdbcTemplate.class);
        mapper = context.getBean(ObjectMapper.class);
        var factory = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(
                context.getBean(EntityManagerFactory.class)));
        members = factory.getRepository(RoleMemberRepository.class);
    }

    @AfterAll
    void stop() {
        if (auth != null) auth.close();
    }

    @Test
    @Order(2)
    void sealedV32PassesPlanningRetentionInformationReplayAndPolicyImpactValidators() {
        var active = repository.findActive(BUNDLE_KEY).orElseThrow();
        var pointer = repository.findActivePointer(BUNDLE_KEY).orElseThrow();
        assertThat(active.version()).isEqualTo(32);
        assertThat(new StoredDescriptorSeal(jdbc, repository, validator, mapper).loadActive(active, pointer).version())
                .isEqualTo(32);

        var planning = new PlanningIdentityAuthorityBridge(identities, surfaces, repository, jdbc,
                new PlanningJson(), validator, mapper);
        var retention = new RetentionExecutionIdentityAuthorityBridge(identities, surfaces, repository,
                validator, jdbc, mapper, new RetentionExecutionJson());
        var information = new InformationReplayIdentityAuthorityBridge(identities, surfaces, repository,
                validator, jdbc, members, mapper);
        var impact = new ApprovalPolicyImpactIdentityAuthorityBridge(identities, surfaces, repository,
                jdbc, new PolicyImpactJson(mapper), validator, mapper);

        assertThatCode(planning::requireRegistered).as("planning v32 registry").doesNotThrowAnyException();
        assertThatCode(() -> invoke(retention, "installed")).as("retention v32 registry").doesNotThrowAnyException();
        assertThatCode(() -> invoke(information, "registry")).as("information replay v32 registry").doesNotThrowAnyException();
        assertThatCode(impact::requireRegistered).as("policy impact v32 registry").doesNotThrowAnyException();
    }

    @Test
    @Order(3)
    void sealedV32PassesEverySignatureOperationValidator() {
        var bridge = signatureBridge();
        for (var operation : SignatureAuthorityProtocol.Operation.values())
            assertThatCode(() -> bridge.requireRegistered(signatureBinding(operation)))
                    .as("signature v32 " + operation).doesNotThrowAnyException();
    }

    @Test
    @Order(4)
    void sealedV32ResolvesTheExistingHighRiskStepUpContracts() {
        var resolver = new ProductSurfaceStepUpRouteResolver(repository, jdbc, validator, mapper);
        for (String[] operation : List.of(
                new String[]{"/api/approvals/v1/signature-requests/", "/sign", "APPROVAL_SIGNATURE_REQUEST",
                        "route.approvals.work.signature-sign.action", "approvals.work.signature.sign"},
                new String[]{"/api/approvals/v1/admin/retention/policies/", "/publish", "RETENTION_POLICY",
                        "route.approvals.admin.retention-policy-publish.action", "approvals.policy.publish"},
                new String[]{"/api/approvals/v1/admin/retention/records/", "/claims", "RETENTION_RECORD",
                        "route.approvals.admin.retention-record-claim.action", "approvals.operations.execute"})) {
            UUID target = UUID.randomUUID();
            var request = new ProductSurfaceStepUpDtos.IssueRequest("POST", operation[0] + target + operation[1],
                    null, null, operation[2], target.toString(), 7L, "v32-" + target,
                    mapper.createObjectNode().put("expectedVersion", 7), null, "/approvals");
            var resolved = resolver.resolve(request);
            assertThat(resolved.bundleVersion()).isEqualTo(32);
            assertThat(resolved.routeContractKey()).isEqualTo(operation[3]);
            assertThat(resolved.capabilityContractKey()).isEqualTo(operation[4]);
        }
    }

    @Test
    @Order(1)
    void unauditedIntermediateReleaseRemainsFailClosedAcrossEveryFence() {
        activate(31);
        try {
            var planning = new PlanningIdentityAuthorityBridge(identities, surfaces, repository, jdbc,
                    new PlanningJson(), validator, mapper);
            var retention = new RetentionExecutionIdentityAuthorityBridge(identities, surfaces, repository,
                    validator, jdbc, mapper, new RetentionExecutionJson());
            var information = new InformationReplayIdentityAuthorityBridge(identities, surfaces, repository,
                    validator, jdbc, members, mapper);
            var impact = new ApprovalPolicyImpactIdentityAuthorityBridge(identities, surfaces, repository,
                    jdbc, new PolicyImpactJson(mapper), validator, mapper);
            var resolver = new ProductSurfaceStepUpRouteResolver(repository, jdbc, validator, mapper);

            assertThatThrownBy(planning::requireRegistered).isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> invoke(retention, "installed")).isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> invoke(information, "registry")).isInstanceOf(BaseException.class);
            assertThatThrownBy(impact::requireRegistered).isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> signatureBridge().requireRegistered(
                    signatureBinding(SignatureAuthorityProtocol.Operation.CONTEXT))).isInstanceOf(BaseException.class);
            UUID target = UUID.randomUUID();
            assertThatThrownBy(() -> resolver.resolve(new ProductSurfaceStepUpDtos.IssueRequest("POST",
                    "/api/approvals/v1/signature-requests/" + target + "/sign", null, null,
                    "APPROVAL_SIGNATURE_REQUEST", target.toString(), 7L, "v31-" + target,
                    mapper.createObjectNode().put("expectedVersion", 7), null, "/approvals")))
                    .isInstanceOf(BaseException.class);
        } finally {
            activate(32);
        }
    }

    private ApprovalSignatureCurrentAuthorityBridge signatureBridge() {
        return new ApprovalSignatureCurrentAuthorityBridge(identities, surfaces, repository, jdbc, validator, mapper,
                new SignatureAuthorityJson(mapper), () -> {
                    throw new AssertionError("Registry validation must not resolve a step-up proof verifier.");
                }, Clock.systemUTC());
    }

    private SignatureAuthorityBindings signatureBinding(SignatureAuthorityProtocol.Operation operation) {
        var active = repository.findActive(BUNDLE_KEY).orElseThrow();
        UUID object = UUID.randomUUID();
        boolean receipt = operation == SignatureAuthorityProtocol.Operation.COMMAND_RECEIPT;
        return new SignatureAuthorityBindings(operation, 1, 1, UUID.randomUUID(), object,
                operation.mutation() ? 7L : null, operation.mutation() || receipt ? "v32-idempotency" : null,
                "a".repeat(64), "context", "scope", "RS_APPROVALS", "psr-" + "b".repeat(64),
                active.checksum(), "111", "NORMAL", Instant.now().plusSeconds(30), UUID.randomUUID(),
                mapper.createObjectNode(), mapper.createObjectNode(), "c".repeat(64),
                operation == SignatureAuthorityProtocol.Operation.SIGN ? "unused-registration-token" : null);
    }

    private void activate(long version) {
        contracts.approve(BUNDLE_KEY, version, "v32-operational-compatibility-checker");
        long revision = repository.findActivePointer(BUNDLE_KEY).map(ProductAuthorizationContractRepository.ActivePointer::revision).orElse(0L);
        contracts.activate(BUNDLE_KEY, version, "v32-operational-compatibility-release", revision);
    }

    private static Object invoke(Object target, String methodName) {
        try {
            var method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            if (error.getCause() instanceof Error failure) throw failure;
            throw new IllegalStateException(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }
}

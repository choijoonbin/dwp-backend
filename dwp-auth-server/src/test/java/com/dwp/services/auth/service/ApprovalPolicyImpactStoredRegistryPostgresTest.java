package com.dwp.services.auth.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.approvalpolicyimpact.PolicyImpactJson;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

/** Genuine v8 seals precede the v9 gate; neither catalog integrity nor this fixture grants authority. */
class ApprovalPolicyImpactStoredRegistryPostgresTest {
    static WorkflowRuntimeActualAuthHarness auth;
    static AnnotationConfigApplicationContext parent;
    static ProductAuthorizationContractRepository repository;
    static ProductAuthorizationContractValidator validator;
    static ObjectMapper mapper;
    static TransactionTemplate transactions;

    @BeforeAll static void start() throws Exception {
        auth = new WorkflowRuntimeActualAuthHarness(new RSAKeyGenerator(2048).keyID("policy-seal-owner").generate(),
                new RSAKeyGenerator(2048).keyID("policy-seal-transport").generate(),
                new RSAKeyGenerator(2048).keyID("policy-seal-attestation").generate());
        var field = WorkflowRuntimeActualAuthHarness.class.getDeclaredField("context"); field.setAccessible(true);
        parent = (AnnotationConfigApplicationContext) field.get(auth);
        repository = parent.getBean(ProductAuthorizationContractRepository.class);
        validator = parent.getBean(ProductAuthorizationContractValidator.class);
        mapper = parent.getBean(ObjectMapper.class); transactions = parent.getBean(TransactionTemplate.class);
    }
    @AfterAll static void close() { if (auth != null) auth.close(); }

    @Test void reorderedActualRowsSealExactlyBeforeMissingNineFailsWithoutIdentityReads() {
        var bundle = repository.findActive("product-surfaces").orElseThrow();
        assertThat(bundle.version()).isEqualTo(8);
        assertThat(validator.checksum(mapper.valueToTree(repository.loadContract(bundle)))).isNotEqualTo(bundle.checksum());
        var observed = spy(validator);
        var identities = mock(ProductAuthorizationIdentityEvidenceService.class);
        var surfaces = mock(ProductSurfaceAuthorityService.class);
        var bridge = bridge(identities, surfaces, observed);
        assertThatThrownBy(bridge::requireRegistered).isInstanceOf(BaseException.class)
                .hasMessageContaining("Policy impact source authority is unavailable");
        var documents = ArgumentCaptor.forClass(JsonNode.class);
        verify(observed, times(3)).validateDocument(documents.capture());
        for (var document : documents.getAllValues()) {
            assertThat(validator.checksum(document)).isEqualTo(bundle.checksum());
            assertThat(document.path("version").longValue()).isEqualTo(8);
        }
        verifyNoInteractions(identities, surfaces);
    }

    @Test void actualDescriptorTamperingFailsBeforeIdentityOrSurfaceEvaluation() {
        var bundle = repository.findActive("product-surfaces").orElseThrow();
        var identities = mock(ProductAuthorizationIdentityEvidenceService.class);
        var surfaces = mock(ProductSurfaceAuthorityService.class);
        var observed = spy(validator);
        transactions.execute(status -> {
            // Corruption is confined to this disposable database and restored by transaction rollback.
            auth.jdbc().execute("ALTER TABLE auth_product_capability_contract DISABLE TRIGGER USER");
            auth.jdbc().update("UPDATE auth_product_capability_contract SET descriptor=jsonb_set(descriptor,'{action}','\"MANAGE\"'::jsonb) WHERE bundle_id=?", bundle.bundleId());
            assertThatThrownBy(bridge(identities, surfaces, observed)::requireRegistered).isInstanceOf(BaseException.class);
            verify(observed, times(1)).validateDocument(any(JsonNode.class));
            verifyNoInteractions(identities, surfaces);
            status.setRollbackOnly(); return null;
        });
        assertThat(new StoredDescriptorSeal(auth.jdbc(), repository, validator, mapper).loadActive(bundle,
                repository.findActivePointer("product-surfaces").orElseThrow()).checksum()).isEqualTo(bundle.checksum());
    }

    @Test void actualSpringConstructionInjectsTheConfiguredMapperAndRetainsTheNineGate() {
        try (var child = new AnnotationConfigApplicationContext()) {
            child.setParent(parent);
            child.registerBean(PolicyImpactJson.class, () -> new PolicyImpactJson(mapper));
            child.register(ApprovalPolicyImpactIdentityAuthorityBridge.class); child.refresh();
            var bridge = child.getBean(ApprovalPolicyImpactIdentityAuthorityBridge.class);
            assertThatThrownBy(bridge::requireRegistered).isInstanceOf(BaseException.class)
                    .hasMessageContaining("Policy impact source authority is unavailable");
        }
    }

    static ApprovalPolicyImpactIdentityAuthorityBridge bridge(ProductAuthorizationIdentityEvidenceService identities,
            ProductSurfaceAuthorityService surfaces, ProductAuthorizationContractValidator observed) {
        return new ApprovalPolicyImpactIdentityAuthorityBridge(identities, surfaces, repository,
                auth.jdbc(), new PolicyImpactJson(mapper), observed, mapper);
    }
}

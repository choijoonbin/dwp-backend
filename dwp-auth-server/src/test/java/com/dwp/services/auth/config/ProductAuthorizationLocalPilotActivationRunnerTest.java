package com.dwp.services.auth.config;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.service.ProductAuthorizationContractService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductAuthorizationLocalPilotActivationRunnerTest {

    private static final String APPROVER = "local-bundle-approver";
    private static final String ACTIVATOR = "local-bundle-activator";
    private static final String CHECKSUM = "sha256:local-v3";

    @Mock
    private ProductAuthorizationContractService service;

    @Test
    void remainsOptInAndDoesNotReadAuthorizationStateWhenDisabled() {
        runner(false, "production", APPROVER, ACTIVATOR).run(null);

        verifyNoInteractions(service);
    }

    @Test
    void rejectsEnabledBootstrapOutsideTheExactLocalEnvironmentBeforeDatabaseAccess() {
        assertThatIllegalStateException()
                .isThrownBy(() -> runner(true, "development", APPROVER, ACTIVATOR))
                .withMessageContaining("forbidden outside");

        verifyNoInteractions(service);
    }

    @Test
    void rejectsEnabledBootstrapWhenTheEnvironmentMarkerIsMissing() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new ProductAuthorizationLocalPilotActivationRunner(
                        true, APPROVER, ACTIVATOR, new MockEnvironment(), service))
                .withMessageContaining("forbidden outside");

        verifyNoInteractions(service);
    }

    @Test
    void doesNotTrustTheCanonicalApplicationDefaultAsTheLocalMarker() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new ProductAuthorizationLocalPilotActivationRunner(
                        true, APPROVER, ACTIVATOR,
                        new MockEnvironment().withProperty("dwp.environment", "local"),
                        service))
                .withMessageContaining("forbidden outside");

        verifyNoInteractions(service);
    }

    @Test
    void rejectsCollapsedApprovalAndActivationActors() {
        assertThatIllegalStateException()
                .isThrownBy(() -> runner(true, "local", APPROVER, " " + APPROVER + " "))
                .withMessageContaining("actor references must differ");
    }

    @Test
    void approvesImportedV3ThenCasActivatesItWithDistinctActors() {
        ProductAuthorizationContractDtos.BundleView draft = bundle("DRAFT", 0);
        ProductAuthorizationContractDtos.BundleView approved = bundle("APPROVED", 0);
        when(service.version("product-surfaces", 3)).thenReturn(draft);
        when(service.approve("product-surfaces", 3, APPROVER)).thenReturn(approved);
        when(service.active("product-surfaces"))
                .thenThrow(new BaseException(ErrorCode.NOT_FOUND));
        when(service.activate("product-surfaces", 3, ACTIVATOR, 0))
                .thenReturn(new ProductAuthorizationContractDtos.ActivationResult(
                        "product-surfaces", 3, "ACTIVATE", 1, CHECKSUM));

        runner(true, "local", APPROVER, ACTIVATOR).run(null);

        InOrder order = inOrder(service);
        order.verify(service).version("product-surfaces", 3);
        order.verify(service).approve("product-surfaces", 3, APPROVER);
        order.verify(service).active("product-surfaces");
        order.verify(service).activate("product-surfaces", 3, ACTIVATOR, 0);
    }

    @Test
    void isIdempotentWhenTheExactV3PointerIsAlreadyActive() {
        ProductAuthorizationContractDtos.BundleView active = bundle("ACTIVE", 7);
        when(service.version("product-surfaces", 3)).thenReturn(active);
        when(service.active("product-surfaces")).thenReturn(active);

        runner(true, "local", APPROVER, ACTIVATOR).run(null);

        verify(service, never()).approve("product-surfaces", 3, APPROVER);
        verify(service, never()).activate("product-surfaces", 3, ACTIVATOR, 7);
    }

    @Test
    void treatsAConcurrentCasWinnerForTheSameImmutableV3AsSuccess() {
        ProductAuthorizationContractDtos.BundleView approved = bundle("APPROVED", 0);
        ProductAuthorizationContractDtos.BundleView v2 = bundle(2, "ACTIVE", 4, "sha256:v2");
        ProductAuthorizationContractDtos.BundleView v3 = bundle("ACTIVE", 5);
        when(service.version("product-surfaces", 3)).thenReturn(approved);
        when(service.active("product-surfaces")).thenReturn(v2, v3);
        when(service.activate("product-surfaces", 3, ACTIVATOR, 4))
                .thenThrow(new BaseException(
                        ErrorCode.RESOURCE_CONFLICT, "active pointer revision changed"));

        runner(true, "local", APPROVER, ACTIVATOR).run(null);

        verify(service).activate("product-surfaces", 3, ACTIVATOR, 4);
    }

    private ProductAuthorizationLocalPilotActivationRunner runner(
            boolean enabled,
            String environment,
            String approver,
            String activator) {
        return new ProductAuthorizationLocalPilotActivationRunner(
                enabled,
                approver,
                activator,
                new MockEnvironment().withProperty("DWP_ENVIRONMENT", environment),
                service);
    }

    private ProductAuthorizationContractDtos.BundleView bundle(String status, long revision) {
        return bundle(3, status, revision, CHECKSUM);
    }

    private ProductAuthorizationContractDtos.BundleView bundle(
            long version,
            String status,
            long revision,
            String checksum) {
        ProductAuthorizationContractDtos.BundleContract contract =
                new ProductAuthorizationContractDtos.BundleContract(
                        1, "product-surfaces", version, status, "identity-platform",
                        "SHA-256", checksum, List.of(), List.of(), List.of(), List.of(), List.of());
        return new ProductAuthorizationContractDtos.BundleView(
                null, "product-surfaces", version, status, revision, checksum,
                "identity-platform", null, null, null, contract);
    }
}

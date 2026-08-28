package com.dwp.services.auth.config;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.service.ProductAuthorizationContractService;
import com.dwp.services.auth.service.ProductAuthorizationContractValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductAuthorizationSeedLoaderTest {

    @Mock
    private ProductAuthorizationContractService service;

    @Test
    void readsAndValidatesTheVersionIndexAndLatestAlias() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        DefaultResourceLoader resources = new DefaultResourceLoader();
        ProductAuthorizationContractValidator validator =
                new ProductAuthorizationContractValidator(objectMapper);
        String indexLocation =
                "classpath:product-authorization/product-surfaces-v1.index.generated.json";
        ProductAuthorizationSeedLoader loader = new ProductAuthorizationSeedLoader(
                false, indexLocation, resources, validator, service);

        ProductAuthorizationContractDtos.SeedIndex index =
                loader.readIndex(resources.getResource(indexLocation));

        ProductAuthorizationContractDtos.BundleContract contract =
                loader.read(resources.getResource(
                        "classpath:product-authorization/product-surfaces-v1.generated.json"));

        assertThat(index.latestVersion()).isEqualTo(4);
        assertThat(index.versions()).extracting(ProductAuthorizationContractDtos.SeedIndexEntry::version)
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(contract.bundleStatus()).isEqualTo("DRAFT");
        assertThat(contract.version()).isEqualTo(4);
        assertThat(contract.routes()).hasSize(155);
    }

    @Test
    void importsAllImmutableSnapshotsAsDraftsWithoutApprovalOrActivation() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        DefaultResourceLoader resources = new DefaultResourceLoader();
        ProductAuthorizationContractValidator validator =
                new ProductAuthorizationContractValidator(objectMapper);
        String indexLocation =
                "classpath:product-authorization/product-surfaces-v1.index.generated.json";
        ProductAuthorizationSeedLoader loader = new ProductAuthorizationSeedLoader(
                true, indexLocation, resources, validator, service);
        when(service.importDraft(any())).thenAnswer(invocation -> {
            ProductAuthorizationContractDtos.BundleContract contract = invocation.getArgument(0);
            return new ProductAuthorizationContractDtos.BundleView(
                    null, contract.bundleKey(), contract.version(), contract.bundleStatus(),
                    0, contract.checksum(), contract.owner(), null, null, null, contract);
        });

        loader.run(null);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<ProductAuthorizationContractDtos.BundleContract> captor =
                org.mockito.ArgumentCaptor.forClass(ProductAuthorizationContractDtos.BundleContract.class);
        verify(service, org.mockito.Mockito.times(4)).importDraft(captor.capture());
        List<ProductAuthorizationContractDtos.BundleContract> imported = captor.getAllValues();
        assertThat(imported).extracting(ProductAuthorizationContractDtos.BundleContract::version)
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(imported).extracting(ProductAuthorizationContractDtos.BundleContract::bundleStatus)
                .containsOnly("DRAFT");
        verify(service, never()).approve(any(), anyLong(), any());
        verify(service, never()).activate(any(), anyLong(), any(), anyLong());
        verify(service, never()).rollback(any(), anyLong(), any(), anyLong());
    }
}

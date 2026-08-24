package com.dwp.services.auth.controller;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.service.ProductAuthorizationContractService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProductAuthorizationContractControllerTest {

    @Mock
    private ProductAuthorizationContractService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ProductAuthorizationContractController(service)).build();
    }

    @Test
    void exposesTheActiveImmutableBundleOnTheInternalReadPath() throws Exception {
        when(service.active("product-surfaces")).thenReturn(view("ACTIVE", 5));

        mockMvc.perform(get(
                        "/internal/auth/v1/product-authorization/bundles/product-surfaces/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bundleKey").value("product-surfaces"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.bundleStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.activeRevision").value(5));
    }

    @Test
    void exposesAnExactVersionWithoutChangingTheActivePointer() throws Exception {
        when(service.version("product-surfaces", 1)).thenReturn(view("APPROVED", 0));

        mockMvc.perform(get(
                        "/internal/auth/v1/product-authorization/bundles/product-surfaces/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bundleStatus").value("APPROVED"))
                .andExpect(jsonPath("$.activeRevision").value(0));
    }

    private ProductAuthorizationContractDtos.BundleView view(String status, long revision) {
        String checksum = "a".repeat(64);
        ProductAuthorizationContractDtos.BundleContract contract =
                new ProductAuthorizationContractDtos.BundleContract(
                        1, "product-surfaces", 1, status, "Identity + Security",
                        "SHA-256", checksum, List.of(), List.of(), List.of(), List.of(), List.of());
        return new ProductAuthorizationContractDtos.BundleView(
                UUID.randomUUID(), "product-surfaces", 1, status, revision,
                checksum, "Identity + Security", "reviewer", null, null, contract);
    }
}

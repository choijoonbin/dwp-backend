package com.dwp.services.auth.controller;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.auth.config.ProductAuthorizationOperationsSecurityConfig;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.service.ProductAuthorizationContractService;
import com.dwp.services.auth.service.ProductAuthorizationOperationsRequestParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProductAuthorizationOperationsControllerTest {

    private static final String CHECKSUM = "a".repeat(64);
    private static final String ROOT =
            ProductAuthorizationOperationsSecurityConfig.INTERNAL_PATH_PREFIX
                    + "/bundles/product-surfaces/versions/3";

    @Mock
    private ProductAuthorizationContractService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ProductAuthorizationOperationsController(
                        service,
                        new ProductAuthorizationOperationsRequestParser(
                                new ObjectMapper().findAndRegisterModules(),
                                Validation.buildDefaultValidatorFactory().getValidator())))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
    }

    @Test
    void delegatesExactApprovalEvidenceWithoutActivating() throws Exception {
        when(service.approveGoverned(
                "product-surfaces", 3, CHECKSUM,
                "change-owner", "security-approver", "CHG-1001"))
                .thenReturn(null);

        mockMvc.perform(post(ROOT + "/approval")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PROVIDER_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.APPROVAL_TOKEN_HEADER,
                                "provider-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checksum": "%s",
                                  "requestedBy": "change-owner",
                                  "approvedBy": "security-approver",
                                  "changeRef": "CHG-1001"
                                }
                                """.formatted(CHECKSUM)))
                .andExpect(status().isOk());

        verify(service).approveGoverned(
                "product-surfaces", 3, CHECKSUM,
                "change-owner", "security-approver", "CHG-1001");
    }

    @Test
    void exposesPlatformPreflightReadsWithoutAWriteSideEffect() throws Exception {
        when(service.active("product-surfaces")).thenReturn(null);
        when(service.governedReleaseVersion("product-surfaces", 3)).thenReturn(null);

        mockMvc.perform(get(
                        ProductAuthorizationOperationsSecurityConfig.INTERNAL_PATH_PREFIX
                                + "/bundles/product-surfaces/active")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                                "platform-secret"))
                .andExpect(status().isOk());
        mockMvc.perform(get(
                        ProductAuthorizationOperationsSecurityConfig.INTERNAL_PATH_PREFIX
                                + "/bundles/product-surfaces/versions/3")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                                "platform-secret"))
                .andExpect(status().isOk());

        verify(service).active("product-surfaces");
        verify(service).governedReleaseVersion("product-surfaces", 3);
    }

    @Test
    void delegatesExactActivationChecksumAndCasRevision() throws Exception {
        when(service.activateGoverned(
                "product-surfaces", 3, CHECKSUM,
                "platform-release-manager", 7, "CHG-1001"))
                .thenReturn(new ProductAuthorizationContractDtos.ActivationResult(
                        "product-surfaces", 3, "ACTIVATE", 8, CHECKSUM));

        mockMvc.perform(post(ROOT + "/activation")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                                "platform-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checksum": "%s",
                                  "expectedRevision": 7,
                                  "activatedBy": "platform-release-manager",
                                  "changeRef": "CHG-1001"
                                }
                                """.formatted(CHECKSUM)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("ACTIVATE"))
                .andExpect(jsonPath("$.revision").value(8));
    }

    @Test
    void delegatesRollbackReasonToTheExactApprovedTarget() throws Exception {
        when(service.rollbackGoverned(
                "product-surfaces", 3, CHECKSUM,
                "platform-release-manager", 8, "INC-2002",
                "Rollback after failed release gate."))
                .thenReturn(new ProductAuthorizationContractDtos.ActivationResult(
                        "product-surfaces", 3, "ROLLBACK", 9, CHECKSUM));

        mockMvc.perform(post(ROOT + "/rollback")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                                "platform-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checksum": "%s",
                                  "expectedRevision": 8,
                                  "rolledBackBy": "platform-release-manager",
                                  "changeRef": "INC-2002",
                                  "reason": "Rollback after failed release gate."
                                }
                                """.formatted(CHECKSUM)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("ROLLBACK"))
                .andExpect(jsonPath("$.revision").value(9));
    }

    @Test
    void rejectsMalformedChecksumBeforeTheServiceBoundary() throws Exception {
        mockMvc.perform(post(ROOT + "/activation")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                                "platform-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checksum": "not-a-checksum",
                                  "expectedRevision": 7,
                                  "activatedBy": "platform-release-manager",
                                  "changeRef": "CHG-1001"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownFieldsInEveryOperationsCommand() throws Exception {
        mockMvc.perform(post(ROOT + "/approval")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PROVIDER_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.APPROVAL_TOKEN_HEADER,
                                "provider-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checksum": "%s",
                                  "requestedBy": "change-owner",
                                  "approvedBy": "security-approver",
                                  "changeRef": "CHG-1001",
                                  "activateImmediately": true
                                }
                                """.formatted(CHECKSUM)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(ROOT + "/activation")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                                "platform-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checksum": "%s",
                                  "expectedRevision": 7,
                                  "activatedBy": "platform-release-manager",
                                  "changeRef": "CHG-1001",
                                  "force": true
                                }
                                """.formatted(CHECKSUM)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(ROOT + "/rollback")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                                "platform-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checksum": "%s",
                                  "expectedRevision": 8,
                                  "rolledBackBy": "platform-release-manager",
                                  "changeRef": "INC-2002",
                                  "reason": "Rollback after failed release gate.",
                                  "skipPrevious": true
                                }
                                """.formatted(CHECKSUM)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDuplicateKeysTrailingRootsAndScalarCoercion() throws Exception {
        mockMvc.perform(post(ROOT + "/approval")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PROVIDER_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.APPROVAL_TOKEN_HEADER,
                                "provider-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checksum": "%s",
                                  "requestedBy": "change-owner",
                                  "approvedBy": "security-approver",
                                  "approvedBy": "different-approver",
                                  "changeRef": "CHG-1001"
                                }
                                """.formatted(CHECKSUM)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(ROOT + "/activation")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                                "platform-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checksum": "%s",
                                  "checksum": "%s",
                                  "expectedRevision": 7,
                                  "activatedBy": "platform-release-manager",
                                  "changeRef": "CHG-1001"
                                }
                                """.formatted(CHECKSUM, CHECKSUM)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(ROOT + "/rollback")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                                "platform-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checksum": "%s",
                                  "expectedRevision": 8,
                                  "expectedRevision": 9,
                                  "rolledBackBy": "platform-release-manager",
                                  "changeRef": "INC-2002",
                                  "reason": "Rollback after failed release gate."
                                }
                                """.formatted(CHECKSUM)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(ROOT + "/approval")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PROVIDER_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.APPROVAL_TOKEN_HEADER,
                                "provider-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checksum": "%s",
                                  "requestedBy": 123,
                                  "approvedBy": "security-approver",
                                  "changeRef": "CHG-1001"
                                }
                                """.formatted(CHECKSUM)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(ROOT + "/rollback")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                                "platform-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checksum": "%s",
                                  "expectedRevision": 8,
                                  "rolledBackBy": "platform-release-manager",
                                  "changeRef": "INC-2002",
                                  "reason": "%s"
                                }
                                """.formatted(CHECKSUM, "x".repeat(4_100))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(ROOT + "/activation")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                                "platform-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checksum": "%s",
                                  "expectedRevision": "7",
                                  "activatedBy": "platform-release-manager",
                                  "changeRef": "CHG-1001"
                                }
                                """.formatted(CHECKSUM)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(ROOT + "/activation")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                                "platform-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checksum": "%s",
                                  "expectedRevision": 7,
                                  "activatedBy": "platform-release-manager",
                                  "changeRef": "CHG-1001"
                                }
                                {"trailing": true}
                                """.formatted(CHECKSUM)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsWrongOrMissingContentTypeBeforeParsing() throws Exception {
        String body = """
                {
                  "checksum": "%s",
                  "requestedBy": "change-owner",
                  "approvedBy": "security-approver",
                  "changeRef": "CHG-1001"
                }
                """.formatted(CHECKSUM);

        mockMvc.perform(post(ROOT + "/approval")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PROVIDER_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.APPROVAL_TOKEN_HEADER,
                                "provider-secret")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(body))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(post(ROOT + "/approval")
                        .header(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig.PROVIDER_SERVICE_IDENTITY)
                        .header(ProductAuthorizationOperationsSecurityConfig.APPROVAL_TOKEN_HEADER,
                                "provider-secret")
                        .content(body))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(service);
    }
}

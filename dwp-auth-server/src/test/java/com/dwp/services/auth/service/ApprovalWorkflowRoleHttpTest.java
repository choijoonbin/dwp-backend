package com.dwp.services.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.config.ApprovalWorkflowRoleSecurityConfig;
import com.dwp.services.auth.controller.ApprovalWorkflowRoleController;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.repository.RoleRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class ApprovalWorkflowRoleHttpTest {
    MockMvc mvc;
    ApprovalWorkflowRoleBindingService service;
    ApprovalWorkflowRoleCurrentAuthority authority;
    ApprovalWorkflowRoleReplayStore replay;
    RoleRepository roles;
    String token;
    String body;

    @BeforeAll static void keys() throws Exception { ApprovalWorkflowRoleBindingServiceTest.keys(); }
    @BeforeEach void initialize() throws Exception {
        var proofs = new ApprovalWorkflowRoleProofVerifier(ApprovalWorkflowRoleProofVerifierTest.JSON,
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.roleKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.userKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.transportKey), ApprovalWorkflowRoleProofVerifierTest.CLOCK);
        authority = mock(ApprovalWorkflowRoleCurrentAuthority.class); replay = mock(ApprovalWorkflowRoleReplayStore.class); roles = mock(RoleRepository.class);
        var issuer = new ApprovalWorkflowRoleAttestationIssuer(ApprovalWorkflowRoleProofVerifierTest.JSON,
                ApprovalWorkflowRoleBindingServiceTest.mappingKey.toJSONString(), ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.roleKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.userKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.transportKey), ApprovalWorkflowRoleProofVerifierTest.CLOCK);
        service = new ApprovalWorkflowRoleBindingService(proofs, authority, replay, roles, issuer, ApprovalWorkflowRoleProofVerifierTest.CLOCK);
        when(authority.require(any())).thenReturn(new ApprovalWorkflowRoleCurrentAuthority.Evidence("auth-1", "policy-1", ApprovalWorkflowRoleProofVerifierTest.NOW.plusSeconds(20)));
        when(roles.findByTenantIdAndCodeIn(42L, List.of("APPROVAL_REVIEWER"))).thenReturn(List.of(
                Role.builder().roleId(7L).tenantId(42L).code("APPROVAL_REVIEWER").version(3L).status("ACTIVE").build()));
        var binding = ApprovalWorkflowRoleProofVerifierTest.binding();
        token = ApprovalWorkflowRoleProofVerifierTest.sign(ApprovalWorkflowRoleProofVerifierTest.claims(binding), ApprovalWorkflowRoleProofVerifierTest.roleKey);
        body = ApprovalWorkflowRoleProofVerifierTest.body(token, binding);
        token = ApprovalWorkflowRoleProofVerifierTest.transport(body);
        mvc = mvc(true);
    }
    MockMvc mvc(boolean enabled) {
        return MockMvcBuilders.standaloneSetup(new ApprovalWorkflowRoleController(service)).setControllerAdvice(new Errors())
                .addFilters(new ApprovalWorkflowRoleSecurityConfig.WorkloadFilter(ApprovalWorkflowRoleProofVerifierTest.JSON, enabled)).build();
    }
    MockHttpServletRequestBuilder request() {
        return post(ApprovalWorkflowRoleSecurityConfig.PATH).header(ApprovalWorkflowRoleSecurityConfig.TOKEN_HEADER, token)
                .header("X-DWP-Service-Identity", "dwp-approval-server").contentType(MediaType.APPLICATION_JSON).content(body);
    }
    @RestControllerAdvice static class Errors {
        @ExceptionHandler(BaseException.class) ResponseEntity<Void> reject(BaseException exception) {
            return ResponseEntity.status(exception.getErrorCode().getHttpStatus()).build();
        }
    }

    @Test void actualSignedHttpPlanningResponseContainsMappingAttestationNotPeople() throws Exception {
        var response = mvc.perform(request()).andReturn().getResponse(); assertEquals(200, response.getStatus());
        var json = ApprovalWorkflowRoleProofVerifierTest.JSON.readTree(response.getContentAsString()).get("data");
        assertEquals(3, json.size()); assertTrue(json.has("attestationToken")); assertFalse(json.has("people"));
    }

    @Test void otherwiseCorrectSignedFabricatedRecoveryAliasReturns403WithZeroAuthorityReplayRoleReads() throws Exception {
        var binding = ApprovalWorkflowRoleProofVerifierTest.binding(); binding.put("routeContractKey", "route.approvals.work.drafts.recover.action");
        binding.put("path", "/v1/requests/" + ApprovalWorkflowRoleProofVerifierTest.REQUEST + "/draft/recover");
        token = ApprovalWorkflowRoleProofVerifierTest.sign(ApprovalWorkflowRoleProofVerifierTest.claims(binding), ApprovalWorkflowRoleProofVerifierTest.roleKey);
        body = ApprovalWorkflowRoleProofVerifierTest.body(token, binding);
        token = ApprovalWorkflowRoleProofVerifierTest.transport(body);
        assertEquals(403, mvc.perform(request()).andReturn().getResponse().getStatus()); verifyNoInteractions(authority, replay, roles);
    }

    @Test void borrowedTokenAuthorityHeadersDuplicateTransportAndWrongIdentityFailBeforeController() throws Exception {
        assertEquals(401, mvc.perform(request().header("Authorization", "Bearer user-token")).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(request().header("X-DWP-Approval-Form-User-Token", "borrowed")).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(request().header(ApprovalWorkflowRoleSecurityConfig.TOKEN_HEADER, token)).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(request().header("X-DWP-Service-Identity", "dwp-gateway")).andReturn().getResponse().getStatus());
        verifyNoInteractions(authority, replay, roles);
    }

    @Test void rawPathAliasWrongVerbQueryAndDuplicateBodyAreForbiddenWithoutRoleReads() throws Exception {
        assertEquals(403, mvc.perform(request().queryParam("roleId", "7")).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(request().with(raw -> { raw.setMethod("HEAD"); return raw; })).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(request().with(raw -> { raw.setRequestURI(ApprovalWorkflowRoleSecurityConfig.PATH + ";alias=1"); return raw; })).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(request().content(body.replaceFirst("\\{", "{\"operation\":\"ROLE_BINDINGS\","))).andReturn().getResponse().getStatus());
        verifyNoInteractions(authority, replay, roles);
    }

    @Test void defaultActivationRemains503BeforeAuthorityReplayOrRoleReads() throws Exception {
        mvc = mvc(false); assertEquals(503, mvc.perform(request()).andReturn().getResponse().getStatus()); verifyNoInteractions(authority, replay, roles);
    }
}

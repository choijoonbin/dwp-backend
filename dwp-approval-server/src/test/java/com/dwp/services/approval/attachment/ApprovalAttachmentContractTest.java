package com.dwp.services.approval.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ApprovalAttachmentContractTest {
    private static final String ID="00000000-0000-0000-0000-000000000099";
    @Test void exactFifteenCatalogMatchesActualControllersWithoutNoIdAliases() {
        var actual=new HashSet<String>();
        for(var type:List.of(ApprovalAttachmentController.class,ApprovalAttachmentManagementController.class)) {
            String prefix=type.getAnnotation(RequestMapping.class).value()[0];
            for(var method:type.getDeclaredMethods()) {
                if(method.isAnnotationPresent(GetMapping.class)) actual.add("GET "+prefix+method.getAnnotation(GetMapping.class).value()[0]);
                if(method.isAnnotationPresent(PostMapping.class)) actual.add("POST "+prefix+method.getAnnotation(PostMapping.class).value()[0]);
                if(method.isAnnotationPresent(PutMapping.class)) actual.add("PUT "+prefix+method.getAnnotation(PutMapping.class).value()[0]);
            }
        }
        assertThat(actual).hasSize(15).containsExactlyInAnyOrderElementsOf(ApprovalAttachmentEndpointPolicy.ENDPOINTS.stream().map(endpoint->endpoint.method()+" "+endpoint.path()).toList());
        assertThat(ApprovalAttachmentEndpointPolicy.ENDPOINTS.stream().map(ApprovalAttachmentEndpointPolicy.Endpoint::routeKey).distinct().count()).isEqualTo(15);
        assertThat(actual).doesNotContain("PUT /v1/admin/attachments/policy/draft","POST /v1/admin/attachments/policy/publish");
    }
    @Test void rawCanonicalMethodAndUuidAreRequiredWhileMvcAliasesRemainCandidates() {
        for(var endpoint:ApprovalAttachmentEndpointPolicy.ENDPOINTS) {
            String path=endpoint.path().replaceAll("\\{[^}]+}",ID);var request=new MockHttpServletRequest(endpoint.method(),path);
            assertThat(ApprovalAttachmentEndpointPolicy.exact(request)).isEqualTo(endpoint);
            assertThat(ApprovalAttachmentEndpointPolicy.exact(new MockHttpServletRequest("HEAD",path))).isNull();
            if(path.contains(ID)) {String alias=path.replace(ID,ID+";mode=1");assertThat(ApprovalAttachmentEndpointPolicy.matches(new MockHttpServletRequest(endpoint.method(),alias))).isTrue();assertThat(ApprovalAttachmentEndpointPolicy.exact(new MockHttpServletRequest(endpoint.method(),alias))).isNull();}
        }
    }
    @Test void bareLegacyPublishIsAlwaysClosedEvenForManageRole() {
        assertThat(ApprovalAttachmentEndpointPolicy.legacyAuthorized(new MockHttpServletRequest("POST","/v1/admin/attachments/policies/"+ID+"/publish"),Set.of("APP_CONFIG_ADMIN"),Set.of("APP.APPROVALS:VIEW","ADMIN.APPROVAL_POLICY:MANAGE","ADMIN.APPROVAL_POLICY:PUBLISH"))).isFalse();
    }
    @Test void explicitInitializationRequiresUpdateAndNeverBorrowsReadOrProviderAuthority() {
        var request=new MockHttpServletRequest("POST","/v1/admin/attachments/policies");
        assertThat(ApprovalAttachmentEndpointPolicy.exact(request).routeKey()).isEqualTo("route.approvals.admin.attachment-policy-initialize.action");
        assertThat(ApprovalAttachmentEndpointPolicy.legacyAuthorized(request,Set.of("APP_CONFIG_ADMIN"),Set.of("APP.APPROVALS:VIEW","ADMIN.APPROVAL_POLICY:VIEW"))).isFalse();
        assertThat(ApprovalAttachmentEndpointPolicy.legacyAuthorized(request,Set.of("APP_CONFIG_ADMIN"),Set.of("APP.APPROVALS:VIEW","ADMIN.APPROVAL_POLICY:UPDATE"))).isTrue();
        assertThat(ApprovalAttachmentEndpointPolicy.legacyAuthorized(request,Set.of("PROVIDER_ADMIN"),Set.of("APP.APPROVALS:VIEW","ADMIN.APPROVAL_POLICY:UPDATE"))).isFalse();
    }
    @Test void workExportAndProviderPlaneDoNotBorrowViewAuthority() {
        var request=new MockHttpServletRequest("POST","/v1/requests/"+ID+"/attachments/"+ID+"/downloads");
        assertThat(ApprovalAttachmentEndpointPolicy.legacyAuthorized(request,Set.of("APPROVAL_OPERATOR"),Set.of("APP.APPROVALS:VIEW","ACTION.APPROVAL_REQUEST:VIEW"))).isFalse();
        assertThat(ApprovalAttachmentEndpointPolicy.legacyAuthorized(request,Set.of("APPROVAL_OPERATOR"),Set.of("APP.APPROVALS:VIEW","ACTION.APPROVAL_REQUEST:VIEW","ACTION.APPROVAL_REQUEST:EXPORT"))).isTrue();
        assertThat(ApprovalAttachmentEndpointPolicy.legacyAuthorized(request,Set.of("PROVIDER_ADMIN"),Set.of("APP.APPROVALS:VIEW","ACTION.APPROVAL_REQUEST:VIEW","ACTION.APPROVAL_REQUEST:EXPORT"))).isFalse();
    }
    @Test void publicUploadAndManifestDoNotContainStoragePinsOrRolePii() throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();
        String json=mapper.writeValueAsString(new ApprovalAttachmentDtos.Upload(UUID.randomUUID(),UUID.randomUUID(),ApprovalAttachmentDtos.State.QUARANTINED,0,"AWAITING_SCAN","NOT_CHECKED","NOT_CHECKED",1,"a".repeat(64),Instant.now()));
        assertThat(json).doesNotContain("objectKey","objectVersion","bucket","role","secret");
    }
    @Test void runtimeUnknownFieldsAreRejectedRatherThanSilentlyIgnored() {
        assertThatThrownBy(()->new ObjectMapper().readValue("{\"expectedVersion\":0,\"idempotencyKey\":\"safe\",\"secret\":\"bad\"}",ApprovalAttachmentDtos.Cancel.class)).isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
    }
    @Test void absentProviderNeverBecomesReadyOrAcceptsIngestion() {
        var gate=new ApprovalAttachmentProviderGate(Optional.empty(),Optional.empty());assertThat(gate.readiness()).isEqualTo("NOT_CONFIGURED");
        assertThatThrownBy(gate::requireIngestion).isInstanceOf(com.dwp.core.exception.BaseException.class);
    }
}

package com.dwp.services.approval.document;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Set;

import static com.dwp.services.approval.document.ApprovalDocumentDtos.*;
import static org.assertj.core.api.Assertions.*;

class ApprovalDocumentContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    @Test void managementMutationsRequireExactPolicyUuidAndRemoveUnreleasedNoIdAliases() {
        String base="/v1/admin/document-tools/policies/"+java.util.UUID.randomUUID();
        assertThat(ApprovalDocumentEndpointPolicy.ENDPOINTS).hasSize(15);
        assertThat(ApprovalDocumentEndpointPolicy.exact(new MockHttpServletRequest("PUT",base+"/draft")).routeKey())
                .isEqualTo("route.approvals.admin.document-policy-draft.action");
        assertThat(ApprovalDocumentEndpointPolicy.exact(new MockHttpServletRequest("POST",base+"/publish")).publish()).isTrue();
        for(String path:List.of("/v1/admin/document-tools/policy/draft","/v1/admin/document-tools/policy/publish")) {
            assertThat(ApprovalDocumentEndpointPolicy.exact(new MockHttpServletRequest("PUT",path))).isNull();
            assertThat(ApprovalDocumentEndpointPolicy.exact(new MockHttpServletRequest("POST",path))).isNull();
        }
        assertThat(ApprovalDocumentEndpointPolicy.exact(new MockHttpServletRequest("PUT",base+"/draft;alias=1"))).isNull();
    }
    @Test void nestedUnknownInputsFailClosedEvenWithPermissiveGlobalMapper() {
        assertThatThrownBy(()->mapper.readValue("{\"expectedVersion\":0,\"expectedCommentsVersion\":0,\"idempotencyKey\":\"c\",\"text\":\"Hello\",\"secret\":true}",AppendComment.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(()->mapper.readValue("{\"key\":\"name\",\"type\":\"STRING\",\"maxLength\":10,\"children\":[],\"secret\":true}",FieldRule.class)).isInstanceOf(Exception.class);
    }
    @Test void policyFieldsAreTypedNestedBoundedAndNeverRenderUnknownOrNonfiniteValues() {
        var policy=new ApprovalDocumentPolicy(); var renderer=new ApprovalDocumentRenderer(new ApprovalDocumentCanonical(mapper));
        var child=new FieldRule("name",FieldType.STRING,10,List.of());
        var fields=List.of(new FieldRule("nested",FieldType.OBJECT,10,List.of(child)));
        var defaults=ApprovalDocumentPolicy.defaults();
        policy.validate(new Rules(true,true,false,false,false,false,List.of("INTERNAL"),fields,20,1048576,300,365));
        var result=renderer.fields("{\"nested\":{\"name\":\"Known\",\"secret\":\"Excluded\"},\"unknown\":\"Excluded\"}",fields);
        assertThat(new ApprovalDocumentCanonical(mapper).json(result)).contains("Known").doesNotContain("Excluded","secret","unknown");
        assertThatThrownBy(()->renderer.fields("{\"number\":\"NaN\"}",List.of(new FieldRule("number",FieldType.NUMBER,10,List.of())))).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThatThrownBy(()->policy.validate(new Rules(true,true,false,false,false,false,List.of(),fields,20,1048576,300,365))).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThat(defaults.allowPrint()).isFalse();
    }
    @Test void legacyRoutePermissionCannotSubstituteManageForExportOrUpdate() {
        var request=new MockHttpServletRequest("POST","/v1/requests/"+java.util.UUID.randomUUID()+"/document-exports");
        assertThat(ApprovalDocumentEndpointPolicy.legacyAuthorized(request,Set.of("EMPLOYEE"),Set.of("APP.APPROVALS:VIEW","ACTION.APPROVAL_REQUEST:VIEW","ACTION.APPROVAL_REQUEST:MANAGE"))).isFalse();
        assertThat(ApprovalDocumentEndpointPolicy.legacyAuthorized(request,Set.of("EMPLOYEE"),Set.of("APP.APPROVALS:VIEW","ACTION.APPROVAL_REQUEST:VIEW","ACTION.APPROVAL_REQUEST:EXPORT"))).isTrue();
    }
    @Test void decimalsAndRowsRequireExplicitTypesAndActualMatchingSchema() {
        var renderer=new ApprovalDocumentRenderer(new ApprovalDocumentCanonical(mapper));
        var fields=List.of(new FieldRule("amount",FieldType.DECIMAL_STRING,40,List.of()));
        String numberSchema="{\"schemaVersion\":2,\"fields\":[{\"key\":\"amount\",\"type\":\"NUMBER\"}]}";
        var result=renderer.fields("{\"amount\":\"1234567890123456789012345678\"}",numberSchema,fields);
        assertThat(result.getFirst().stringValue()).isEqualTo("1234567890123456789012345678");
        for(String value:List.of("NaN","Infinity","1e2","12345678901234567890123456789","0.123456789")) {
            assertThatThrownBy(()->renderer.fields("{\"amount\":\""+value+"\"}",numberSchema,fields)).isInstanceOf(com.dwp.core.exception.BaseException.class);
        }
        assertThatThrownBy(()->renderer.fields("{\"amount\":\"123\"}","{\"schemaVersion\":2,\"fields\":[{\"key\":\"amount\",\"type\":\"TEXT\"}]}",fields)).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThatThrownBy(()->renderer.fields("{\"amount\":123}",numberSchema,fields)).isInstanceOf(com.dwp.core.exception.BaseException.class);
        var rows=List.of(new FieldRule("rows",FieldType.OBJECT_LIST,100,List.of(new FieldRule("name",FieldType.STRING,10,List.of())),50));
        String tooMany=new ApprovalDocumentCanonical(mapper).json(java.util.Map.of("rows",java.util.Collections.nCopies(51,java.util.Map.of("name","name"))));
        assertThatThrownBy(()->renderer.fields(tooMany,rows)).isInstanceOf(com.dwp.core.exception.BaseException.class);
    }
    @Test void canonicalGuardRejectsDuplicateIdentityPlaneProviderModeAndAliasesBeforeController() throws Exception {
        var filter=new ApprovalDocumentTrustedHeaderFilter(mapper);
        for(String fault:List.of("duplicate","provider","support","alias","head","person","display","utf8","rollout","control")) {
            var request=new MockHttpServletRequest("GET","/v1/requests/"+java.util.UUID.randomUUID()+"/document-tools");
            request.addHeader("X-DWP-Service-Token","trusted");request.addHeader("X-DWP-User-ID","99");request.addHeader("X-DWP-Tenant-ID","42");request.addHeader("X-DWP-Identity-Plane","TENANT");
            request.addHeader("X-DWP-Roles","EMPLOYEE");request.addHeader("X-DWP-Permissions","APP.APPROVALS:VIEW,ACTION.APPROVAL_REQUEST:VIEW");
            if(fault.equals("duplicate"))request.addHeader("X-DWP-User-ID","99");
            if(fault.equals("provider")){request.removeHeader("X-DWP-Identity-Plane");request.addHeader("X-DWP-Identity-Plane","PROVIDER");}
            if(fault.equals("support"))request.addHeader("X-DWP-Support-Session-ID","support");
            if(fault.equals("alias"))request.setRequestURI(request.getRequestURI()+";matrix=1");
            if(fault.equals("head"))request.setMethod("HEAD");
            if(fault.equals("person"))request.addHeader("X-DWP-Person-Public-ID","not-a-uuid");
            if(fault.equals("display"))request.addHeader("X-DWP-Display-Name-B64","%%%invalid");
            if(fault.equals("utf8"))request.addHeader("X-DWP-Display-Name-B64","_w");
            if(fault.equals("rollout"))request.addHeader("X-DWP-Rollout-State","101");
            if(fault.equals("control"))request.addHeader("X-DWP-Context-Key","context\u0000");
            var response=new MockHttpServletResponse();var invoked=new java.util.concurrent.atomic.AtomicBoolean();
            filter.doFilter(request,response,(r,s)->invoked.set(true));
            assertThat(invoked).as(fault).isFalse();assertThat(response.getStatus()).isIn(401,403);
        }
    }
    @Test void canonicalOptionalIdentityHeadersKeepActualGatewayEncodingCompatible() throws Exception {
        var filter=new ApprovalDocumentTrustedHeaderFilter(mapper);
        var request=new MockHttpServletRequest("GET","/v1/requests/"+java.util.UUID.randomUUID()+"/document-tools");
        request.addHeader("X-DWP-Service-Token","trusted");request.addHeader("X-DWP-User-ID","99");request.addHeader("X-DWP-Tenant-ID","42");request.addHeader("X-DWP-Identity-Plane","TENANT");
        request.addHeader("X-DWP-Roles","EMPLOYEE");request.addHeader("X-DWP-Permissions","APP.APPROVALS:VIEW,ACTION.APPROVAL_REQUEST:VIEW");
        request.addHeader("X-DWP-Person-Public-ID",java.util.UUID.randomUUID().toString());
        request.addHeader("X-DWP-Display-Name-B64",java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("Owner Name".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        request.addHeader("X-DWP-Rollout-State","000");
        var invoked=new java.util.concurrent.atomic.AtomicBoolean();filter.doFilter(request,new MockHttpServletResponse(),(r,s)->invoked.set(true));
        assertThat(invoked).isTrue();
    }
}

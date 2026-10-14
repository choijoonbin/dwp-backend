package com.dwp.services.platform.auditcontrol;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HcmAuditEvidenceControllerTest {

    private final HcmAuditEvidenceService service = mock(HcmAuditEvidenceService.class);
    private final HcmAuditEvidenceController controller =
            new HcmAuditEvidenceController(service);

    @Test
    void pageBuildsOnlyTrustedAccessContextAndDisablesCaching() {
        MockHttpServletRequest request = request(HcmAuditEvidenceController.PAGE_ROUTE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        HcmAuditEvidenceDtos.Page page = new HcmAuditEvidenceDtos.Page(
                List.of(), 0, Instant.parse("2026-10-14T06:00:00Z"), null);
        when(service.page(any(), eq(AuditWindow.D7), eq("ALL"), eq("ALL"),
                eq("ALL"), isNull(), isNull(), eq(50), isNull())).thenReturn(page);

        var result = controller.page(
                request, "HCM", AuditWindow.D7, "ALL", "ALL", "ALL",
                null, null, 50, null, response);

        assertThat(result.getData()).isEqualTo(page);
        assertThat(response.getHeader("Cache-Control"))
                .isEqualTo("private, no-store, max-age=0");
        verify(service).page(
                org.mockito.ArgumentMatchers.argThat(access ->
                        access.tenantId().equals(7L)
                                && access.routeContractKey().equals(
                                HcmAuditEvidenceController.PAGE_ROUTE)
                                && access.contextKey().equals("hcm.management")
                                && access.fieldPolicyRevision() == 17
                                && access.authorizationRevision() == 29),
                eq(AuditWindow.D7), eq("ALL"), eq("ALL"), eq("ALL"),
                isNull(), isNull(), eq(50), isNull());
    }

    @Test
    void routeHeaderMismatchStopsBeforeService() {
        MockHttpServletRequest request = request(HcmAuditEvidenceController.DETAIL_ROUTE);

        assertThatThrownBy(() -> controller.page(
                request, "HCM", AuditWindow.D7, "ALL", "ALL", "ALL",
                null, null, 50, null, new MockHttpServletResponse()))
                .isInstanceOf(BaseException.class);
        verifyNoInteractions(service);
    }

    @Test
    void genericAndHcmMappingsAreExplicitlySplitByProduct() {
        Set<String> genericConditions = Arrays.stream(
                        AdminAuditControlController.class.getDeclaredMethods())
                .filter(method -> Set.of("events", "event").contains(method.getName()))
                .flatMap(method -> Arrays.stream(
                        method.getAnnotation(GetMapping.class).params()))
                .collect(Collectors.toSet());
        Set<String> hcmConditions = Arrays.stream(
                        HcmAuditEvidenceController.class.getDeclaredMethods())
                .filter(method -> Set.of("page", "detail").contains(method.getName()))
                .flatMap(method -> Arrays.stream(
                        method.getAnnotation(GetMapping.class).params()))
                .collect(Collectors.toSet());

        assertThat(genericConditions).containsExactly("!product");
        assertThat(hcmConditions).containsExactly("product=HCM");
    }

    @Test
    void evidenceRecordContainsOnlyTheSealedAllowlist() {
        Set<String> fields = Arrays.stream(
                        HcmAuditEvidenceDtos.Evidence.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        assertThat(fields).containsExactlyInAnyOrder(
                "eventId", "occurredAt", "category", "action", "outcome", "severity",
                "actorType", "sourceService", "sourceModule", "aggregateType",
                "aggregateRevision", "effectiveCapability", "purposeCode",
                "authorizationDecisionRef", "scopeRevision", "fieldPolicyRevision",
                "approvalReceiptId", "reasonCode", "beforeDigest", "afterDigest",
                "evidenceDigest", "classification", "retentionClass", "causationId",
                "correlationId", "recordHash");
        assertThat(fields).doesNotContain(
                "tenantId", "actorId", "aggregateRef", "metadata",
                "beforeState", "afterState", "totalElements");
    }

    private MockHttpServletRequest request(String route) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-DWP-Tenant-ID", "7");
        request.addHeader("X-DWP-Route-Contract-Key", route);
        request.addHeader("X-DWP-Context-Key", "hcm.management");
        request.addHeader("X-DWP-Context-Scope-Key", "hcm-scope-" + "b".repeat(40));
        request.addHeader("X-DWP-Subject-Principal-Public-ID",
                UUID.fromString("10000000-0000-4000-8000-000000000001").toString());
        request.addHeader("X-DWP-Field-Policy-Revision", "17");
        request.addHeader("X-DWP-Authorization-Revision", "29");
        return request;
    }
}

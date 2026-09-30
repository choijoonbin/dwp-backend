package com.dwp.services.provider.settings;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TenantOwnerProjectionControllerContractTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void publishedDtosExcludeProviderMappingSecretsAndRawPolicyRules() {
        Map<String, Schema> schemas = new LinkedHashMap<>(ModelConverters.getInstance()
                .readAll(TenantOwnerProjectionDtos.DataGovernanceProjection.class));
        schemas.putAll(ModelConverters.getInstance()
                .readAll(TenantOwnerProjectionDtos.DomainProjection.class));
        schemas.putAll(ModelConverters.getInstance()
                .readAll(TenantOwnerProjectionDtos.PlanEligibilityProjection.class));

        List<String> domainProperties = schemas.get("DomainObservation")
                .getProperties().keySet().stream().map(Object::toString).toList();
        List<String> policyProperties = schemas.get("PolicyObservation")
                .getProperties().keySet().stream().map(Object::toString).toList();
        assertThat(domainProperties)
                .contains("domainName", "verificationState", "lastCheckedAt")
                .doesNotContain(
                        "providerTenantId", "authTenantId", "verificationTokenHash",
                        "verificationRecordValue", "challenge");
        assertThat(policyProperties)
                .contains("policyType", "coverage", "effectiveState", "freshnessState")
                .doesNotContain(
                        "policyRule", "scopeRef", "justification", "requestedBy", "approvedBy");
        List<String> planProperties = schemas.get("PlanObservation")
                .getProperties().keySet().stream().map(Object::toString).toList();
        List<String> productProperties = schemas.get("ProductEligibilityObservation")
                .getProperties().keySet().stream().map(Object::toString).toList();
        assertThat(planProperties)
                .contains("subscriptionState", "planKey", "planVersion")
                .doesNotContain("organizationId", "contractReference", "commercialMetadata");
        assertThat(productProperties)
                .contains("productKey", "entitlementKey", "eligibilityState")
                .doesNotContain("providerTenantId", "entitlementId", "configuration");
    }

    @Test
    void controllerMarksBothTenantOwnerResponsesNoStore() throws Exception {
        TenantOwnerProjectionService service = mock(TenantOwnerProjectionService.class);
        Instant observedAt = Instant.parse("2026-09-29T02:00:00Z");
        when(service.domains()).thenReturn(new TenantOwnerProjectionDtos.DomainProjection(
                "provider-control-plane", "LIVE_OWNER_READ", observedAt, null,
                "CURRENT_TENANT_NON_REVOKED_DOMAINS", List.of(), List.of()));
        when(service.dataGovernance()).thenReturn(
                new TenantOwnerProjectionDtos.DataGovernanceProjection(
                        "provider-control-plane", "LIVE_OWNER_READ", observedAt, null,
                        "GLOBAL_POLICIES_AND_CURRENT_TENANT_LIFECYCLE_EVALUATIONS",
                        List.of(), List.of(), List.of()));
        when(service.planEligibility()).thenReturn(
                new TenantOwnerProjectionDtos.PlanEligibilityProjection(
                        "provider-control-plane", "LIVE_OWNER_READ", observedAt, null,
                        "CURRENT_SUBSCRIPTION_AND_TENANT_ENTITLEMENTS",
                        List.of(), null, List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new TenantOwnerProjectionController(service)).build();

        mvc.perform(get("/v1/tenant/settings/provider-domains"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.observationState").value("LIVE_OWNER_READ"));
        mvc.perform(get("/v1/tenant/settings/data-governance-observation"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.coverageState")
                        .value("GLOBAL_POLICIES_AND_CURRENT_TENANT_LIFECYCLE_EVALUATIONS"));
        mvc.perform(get("/v1/tenant/settings/plan-eligibility"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.coverageState")
                        .value("CURRENT_SUBSCRIPTION_AND_TENANT_ENTITLEMENTS"));
    }
}

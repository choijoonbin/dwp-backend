package com.dwp.services.payroll.foundation;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PayrollFoundationSettingsAuthorityContractTest {

    private static final Instant NOW = Instant.parse("2026-10-17T04:00:00Z");
    private static final UUID PERSON =
            UUID.fromString("10000000-0000-0000-0000-000000000001");

    private PayrollFoundationService service;
    private PayrollFoundationSettingsController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        PayrollFoundationSettingsV48PepRegistry registry =
                new PayrollFoundationSettingsV48PepRegistry(mapper);
        service = mock(PayrollFoundationService.class);
        controller = new PayrollFoundationSettingsController(
                service,
                tenantId -> PayrollFoundationAccess.compatibilityPolicy(),
                subject -> new PayrollLegalEntityScopeResolver.Resolution(
                        "settings-authority-r1", Set.of(PERSON)));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new PayrollFoundationSettingsSecurityFilter(
                        "verified-test-token", true, registry, mapper,
                        Clock.fixed(NOW, ZoneOffset.UTC)))
                .build();
    }

    @Test
    void immutableV48PepContainsEveryExactSettingsAlias() {
        PayrollFoundationSettingsV48PepRegistry registry =
                new PayrollFoundationSettingsV48PepRegistry(
                        JsonMapper.builder().findAndAddModules().build());
        for (PayrollFoundationSettingsRoute route : PayrollFoundationSettingsRoute.values()) {
            PayrollFoundationSettingsV48PepRegistry.Binding binding = registry.binding(route);
            assertThat(binding.routeContractKey()).isEqualTo(route.routeContractKey());
            assertThat(binding.servicePath()).isEqualTo(route.pathTemplate());
            assertThat(binding.capabilityCode()).isEqualTo(route.capability());
            assertThat(binding.targetBindingKinds()).isEqualTo(route.targetBindingKinds());
            assertThat(binding.predicatePolicyKeys()).isEqualTo(route.predicatePolicyKeys());
        }
    }

    @Test
    void exactManagementReadDelegatesToExistingBusinessService() throws Exception {
        mvc.perform(verified(get("/v1/settings/payroll/foundation/configurations"),
                        PayrollFoundationSettingsRoute.LIST,
                        "APP.HCM:VIEW,CONFIG.HRIS_WORKBENCH:VIEW"))
                .andExpect(status().isOk());
        verify(service).list(any());
    }

    @Test
    void operationsAliasOrWrongCapabilityCannotAuthorizeSettingsRead() throws Exception {
        mvc.perform(verified(get("/v1/settings/payroll/foundation/configurations"),
                        PayrollFoundationSettingsRoute.LIST,
                        "APP.HCM:VIEW,DATA.HR_PAY:VIEW"))
                .andExpect(status().isForbidden());
        mvc.perform(verified(get("/v1/settings/payroll/foundation/configurations"),
                        PayrollFoundationSettingsRoute.CREATE,
                        "APP.HCM:VIEW,CONFIG.HRIS_WORKBENCH:VIEW"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(service);
    }

    @Test
    void everySettingsOperationDelegatesToTheExistingBusinessService() {
        PayrollFoundationAccess.Actor actor = mock(PayrollFoundationAccess.Actor.class);
        UUID configurationId = UUID.fromString("20000000-0000-4000-8000-000000000001");
        UUID commandId = UUID.fromString("30000000-0000-4000-8000-000000000001");

        controller.configurations(actor);
        controller.create(actor, commandId, "settings-test", null);
        controller.configuration(actor, configurationId);
        controller.versions(actor, configurationId);
        controller.update(actor, configurationId, commandId, "settings-test", null);
        controller.simulate(actor, configurationId, commandId, "settings-test", null);
        controller.publish(actor, configurationId, commandId, "settings-test", null);
        controller.reverse(actor, configurationId, commandId, "settings-test", null);
        controller.receipt(actor, commandId);
        controller.reconcile(actor, commandId);

        verify(service).list(actor);
        verify(service).create(actor, commandId, "settings-test", null);
        verify(service).get(actor, configurationId);
        verify(service).versions(actor, configurationId);
        verify(service).update(actor, configurationId, commandId, "settings-test", null);
        verify(service).simulate(actor, configurationId, commandId, "settings-test", null);
        verify(service).publish(actor, configurationId, commandId, "settings-test", null);
        verify(service).reverse(actor, configurationId, commandId, "settings-test", null);
        verify(service).receipt(actor, commandId);
        verify(service).reconcile(actor, commandId);
    }

    private MockHttpServletRequestBuilder verified(
            MockHttpServletRequestBuilder request,
            PayrollFoundationSettingsRoute route,
            String permissions) {
        return request
                .header("Authorization", "Bearer test-user-token")
                .header("X-DWP-Service-Token", "verified-test-token")
                .header("X-DWP-Tenant-ID", "1")
                .header("X-DWP-User-ID", "101")
                .header("X-DWP-Subject-Principal-Public-ID", PERSON.toString())
                .header("X-DWP-Permissions", permissions)
                .header("X-DWP-Route-Contract-Key", route.routeContractKey())
                .header("X-DWP-Context-Key", "psc-" + "a".repeat(64))
                .header("X-DWP-Context-Scope-Key", "hcm-scope-" + "b".repeat(40))
                .header("X-DWP-Active-Access-Mode", "NORMAL")
                .header("X-DWP-Current-Decision-Revision", "psr-" + "c".repeat(64))
                .header("X-DWP-Current-Revalidate-At", NOW.plusSeconds(300).toString())
                .header("X-DWP-Field-Policy-Revision", "field-policy-7")
                .header("X-DWP-Authorization-Revision", "authorization-8")
                .header("X-Purpose-Code", "PAYROLL_CONFIGURATION")
                .header("X-DWP-Rollout-State", "110")
                .header("X-DWP-Rollout-Revision", "rollout-" + "d".repeat(64))
                .header("X-DWP-Rollout-Cohort", "eligible-10");
    }
}

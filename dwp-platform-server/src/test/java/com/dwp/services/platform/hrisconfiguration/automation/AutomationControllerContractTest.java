package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationOwnerAuthorityFilter.Authority;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutomationControllerContractTest {

    private static final OffsetDateTime AS_OF =
            OffsetDateTime.parse("2026-10-21T00:00:00Z");
    private static final UUID RUN =
            UUID.fromString("00000000-0000-4000-8000-000000000641");

    @Test
    void catalogForwardsTheClosedResourceTypeFilterAndRejectsUnknownQueryKeys() {
        AutomationService service = mock(AutomationService.class);
        AutomationController controller = new AutomationController(service);
        MockHttpServletRequest valid = request("/v1/admin/hris/automations");
        valid.addParameter("asOf", AS_OF.toString());
        valid.addParameter("fields", "automationKey");
        valid.addParameter("freshness", "FRESH_ONLY");
        valid.addParameter("limit", "20");
        valid.addParameter("resourceTypes", "AUTOMATION_DEFINITION");
        List<String> fields = List.of("automationKey");
        List<String> types = List.of("AUTOMATION_DEFINITION");

        controller.catalog(
                AS_OF, fields, "FRESH_ONLY", 20, null, null, types, valid);
        verify(service).catalog(
                authority(), AS_OF, fields, "FRESH_ONLY", 20,
                null, null, types);

        MockHttpServletRequest unknown = request("/v1/admin/hris/automations");
        unknown.addParameter("asOf", AS_OF.toString());
        unknown.addParameter("fields", "automationKey");
        unknown.addParameter("freshness", "FRESH_ONLY");
        unknown.addParameter("debug", "true");
        assertThatThrownBy(() -> controller.catalog(
                AS_OF, fields, "FRESH_ONLY", 20,
                null, null, null, unknown))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("AUTOMATION_UNKNOWN_QUERY_PARAMETER");
    }

    @Test
    void runDetailAcceptsOnlyTheThreeIndependentlyBoundCursorNames() {
        AutomationService service = mock(AutomationService.class);
        AutomationController controller = new AutomationController(service);
        MockHttpServletRequest valid = request(
                "/v1/admin/hris/automation-runs/" + RUN);
        valid.addParameter("asOf", AS_OF.toString());
        valid.addParameter("freshness", "FRESH_ONLY");
        valid.addParameter("limit", "20");
        valid.addParameter("attemptCursor", "attempt.cursor");
        valid.addParameter("itemCursor", "item.cursor");
        valid.addParameter("checkpointCursor", "checkpoint.cursor");

        controller.runDetail(
                RUN, AS_OF, "FRESH_ONLY", 20, "attempt.cursor", "item.cursor",
                "checkpoint.cursor", valid);
        verify(service).runDetail(
                authority(), RUN, AS_OF, "FRESH_ONLY", 20, "attempt.cursor",
                "item.cursor", "checkpoint.cursor");

        MockHttpServletRequest unknown = request(
                "/v1/admin/hris/automation-runs/" + RUN);
        unknown.addParameter("asOf", AS_OF.toString());
        unknown.addParameter("freshness", "FRESH_ONLY");
        unknown.addParameter("limit", "20");
        unknown.addParameter("cursor", "ambiguous");
        assertThatThrownBy(() -> controller.runDetail(
                RUN, AS_OF, "FRESH_ONLY", 20, null, null, null, unknown))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("AUTOMATION_UNKNOWN_QUERY_PARAMETER");
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setAttribute(
                AutomationOwnerAuthorityFilter.AUTHORITY_ATTRIBUTE, authority());
        return request;
    }

    private static Authority authority() {
        return new Authority(
                42, 84,
                UUID.fromString("00000000-0000-4000-8000-000000000642"),
                Set.of(), "route.hcm.configuration.automation-catalog.data",
                "psr-" + "a".repeat(64), AS_OF.plusMinutes(5),
                "b".repeat(64), 7, 11, "psc-" + "c".repeat(64),
                "tenant:42", UUID.fromString(
                "00000000-0000-4000-8000-000000000643"),
                "HRIS_SYSTEM_ADMIN", null, null, null, Set.of(), Set.of());
    }
}

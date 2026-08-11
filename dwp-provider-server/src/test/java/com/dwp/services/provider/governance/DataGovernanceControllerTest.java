package com.dwp.services.provider.governance;

import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.security.ProviderRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataGovernanceControllerTest {

    private final DataGovernanceService service = mock(DataGovernanceService.class);
    private final ProviderAuditService audit = mock(ProviderAuditService.class);
    private final DataGovernanceController controller = new DataGovernanceController(service, audit);
    private final DataGovernanceDtos.Snapshot snapshot = snapshot();

    @AfterEach
    void clearContext() {
        ProviderRequestContext.clear();
    }

    @Test
    void returnsTheGlobalCatalogForAnAuthorizedProviderAuditor() {
        ProviderRequestContext.set(actor("PROVIDER_AUDITOR", Set.of("DATA_GOVERNANCE_READ")));
        when(service.snapshot()).thenReturn(snapshot);

        assertThat(controller.snapshot().getData()).isSameAs(snapshot);
        verify(service).snapshot();
    }

    @Test
    void rejectsProviderSupportWithoutTheGovernancePermission() {
        ProviderRequestContext.set(actor("PROVIDER_SUPPORT", Set.of("SUPPORT_SESSION_WRITE")));

        assertThatThrownBy(controller::snapshot).isInstanceOf(BaseException.class);
        verify(service, never()).snapshot();
    }

    @Test
    void auditsManualMetadataRefresh() {
        ProviderRequestContext.set(actor("PROVIDER_OPERATOR", Set.of("DATA_GOVERNANCE_READ")));
        when(service.refresh()).thenReturn(snapshot);

        assertThat(controller.refresh("correlation-17").getData()).isSameAs(snapshot);
        verify(audit).success(
                eq("provider.data-governance.refreshed"),
                eq("DATA_CATALOG"),
                eq("global"),
                eq("correlation-17"),
                argThat(value -> value instanceof Map<?, ?> map
                        && map.get("databases").equals(4)
                        && map.get("logicalTables").equals(150)
                        && map.get("findings").equals(12)));
    }

    private ProviderRequestContext.Actor actor(String role, Set<String> permissions) {
        return new ProviderRequestContext.Actor(
                9L, 17L, 3L, "Provider operator", Set.of(role), permissions);
    }

    private DataGovernanceDtos.Snapshot snapshot() {
        return new DataGovernanceDtos.Snapshot(
                Instant.parse("2026-08-11T11:00:00Z"),
                new DataGovernanceDtos.Summary(4, 4, 150, 7, 2286, 201, 38, 12, 1024L),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }
}

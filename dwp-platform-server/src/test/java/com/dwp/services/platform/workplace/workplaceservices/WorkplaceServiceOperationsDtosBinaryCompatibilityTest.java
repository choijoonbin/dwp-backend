package com.dwp.services.platform.workplace.workplaceservices;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkplaceServiceOperationsDtosBinaryCompatibilityTest {

    @Test
    void preservesProviderProfileCanonicalDescriptor() throws Exception {
        Class<WorkplaceServiceOperationsDtos.ProviderProfile> type =
                WorkplaceServiceOperationsDtos.ProviderProfile.class;

        assertThat(type.isRecord()).isTrue();
        assertThat(type.getDeclaredConstructor(
                UUID.class,
                String.class,
                String.class,
                String.class,
                String.class,
                WorkplaceServiceOperationsDtos.ProviderLifecycleState.class,
                List.class,
                List.class,
                JsonNode.class,
                boolean.class,
                long.class,
                WorkplaceServicesDtos.ProviderState.class,
                Long.class,
                String.class,
                OffsetDateTime.class,
                OffsetDateTime.class,
                String.class,
                long.class,
                OffsetDateTime.class)).isNotNull();
        assertThat(type.getDeclaredMethod("readiness").getReturnType())
                .isEqualTo(WorkplaceServicesDtos.ProviderState.class);
    }

    @Test
    void preservesOperationsCommandReceiptCanonicalDescriptor() throws Exception {
        Class<WorkplaceServiceOperationsDtos.OperationsCommandReceipt> type =
                WorkplaceServiceOperationsDtos.OperationsCommandReceipt.class;

        assertThat(type.isRecord()).isTrue();
        assertThat(type.getDeclaredConstructor(
                UUID.class,
                WorkplaceServicesDtos.CommandState.class,
                String.class,
                boolean.class,
                String.class,
                OffsetDateTime.class)).isNotNull();
        assertThat(type.getDeclaredMethod("state").getReturnType())
                .isEqualTo(WorkplaceServicesDtos.CommandState.class);
    }
}

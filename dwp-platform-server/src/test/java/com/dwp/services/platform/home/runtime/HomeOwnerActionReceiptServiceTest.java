package com.dwp.services.platform.home.runtime;

import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HomeOwnerActionReceiptServiceTest {

    @Test
    void ownerReceiptReplaysSameResponseAndRejectsPayloadReuse() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        HomeOwnerActionReceiptRepository repository =
                mock(HomeOwnerActionReceiptRepository.class);
        AtomicReference<HomeOwnerActionReceipt> stored = new AtomicReference<>();
        when(repository.findByTenantIdAndUserIdAndCommandId(any(), any(), any()))
                .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            HomeOwnerActionReceipt receipt = invocation.getArgument(0);
            stored.set(receipt);
            return receipt;
        });
        HomeOwnerActionReceiptService service = new HomeOwnerActionReceiptService(
                repository, new HomeCanonicalJson(objectMapper), objectMapper);
        HomeRuntimeContext context = TestFixtures.context();
        UUID commandId = UUID.randomUUID();
        HomeOwnerActionContracts.Contract contract =
                HomeOwnerActionContracts.DISMISS_RECOMMENDATION;
        HomeWidgetProviderContract.CommandRequest request = request(
                commandId, contract, "work-due-soon");
        AtomicInteger mutations = new AtomicInteger();
        HomeWidgetProviderContract.CommandResponse response = response(context, request);

        HomeWidgetProviderContract.CommandResponse first = service.execute(
                context, contract.contractId(), request, () -> {
                    mutations.incrementAndGet();
                    return response;
                });
        HomeWidgetProviderContract.CommandResponse replay = service.execute(
                context, contract.contractId(), request, () -> {
                    mutations.incrementAndGet();
                    return response;
                });

        assertThat(first).isEqualTo(replay);
        assertThat(mutations).hasValue(1);
        assertThatThrownBy(() -> service.execute(
                context, contract.contractId(),
                request(commandId, contract, "calendar-conflicts"),
                () -> response)).isInstanceOf(BaseException.class);
    }

    private HomeWidgetProviderContract.CommandRequest request(
            UUID commandId,
            HomeOwnerActionContracts.Contract contract,
            String recommendationKey) {
        return new HomeWidgetProviderContract.CommandRequest(
                HomeWidgetProviderContract.SCHEMA_VERSION,
                commandId,
                UUID.randomUUID(),
                contract.definitionKey(),
                contract.definitionManifestHash(),
                "binding-revision-1234567890",
                contract.actionId(),
                contract.commandKey(),
                "result-1",
                Map.of("recommendationKey", recommendationKey));
    }

    private HomeWidgetProviderContract.CommandResponse response(
            HomeRuntimeContext context,
            HomeWidgetProviderContract.CommandRequest request) {
        return new HomeWidgetProviderContract.CommandResponse(
                HomeWidgetProviderContract.SCHEMA_VERSION,
                context.tenantId(), context.userId(), context.authorityDecisionRevision(),
                UUID.randomUUID(), request.commandId(), request.actionId(), request.commandKey(),
                HomeWidgetProviderContract.CommandStatus.COMPLETED, "/home",
                OffsetDateTime.now(ZoneOffset.UTC), "result-2");
    }
}

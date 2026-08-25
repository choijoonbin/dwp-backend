package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HomeCommandReceiptServiceTest {

    @Test
    void replaysTheExactOriginalResponseAndRejectsCrossOperationReuse() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HomeCommandReceiptRepository repository = mock(HomeCommandReceiptRepository.class);
        AtomicReference<HomeCommandReceipt> stored = new AtomicReference<>();
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            HomeCommandReceipt receipt = invocation.getArgument(0);
            stored.set(receipt);
            return receipt;
        });
        UUID commandId = UUID.randomUUID();
        when(repository.findByTenantIdAndActorIdAndCommandId(7L, 11L, commandId))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        HomeCommandReceiptService service =
                new HomeCommandReceiptService(repository, mapper, 168);
        OffsetDateTime originalUpdatedAt = OffsetDateTime.parse("2026-08-21T05:00:00Z");
        HomeViewDtos.HomeViewResponse original = new HomeViewDtos.HomeViewResponse(
                UUID.randomUUID(), "default", "workspace-home", "Original", true, true, 5,
                null, 4L, originalUpdatedAt.minusHours(1), originalUpdatedAt, Map.of());

        service.record(7L, 11L, commandId, "UPDATE_VIEW", original.viewId().toString(),
                "a".repeat(64), original);
        assertThat(stored.get().getExpiresAt())
                .isEqualTo(stored.get().getCreatedAt().plusHours(168));
        HomeViewDtos.HomeViewResponse replay = service.replay(
                7L, 11L, commandId, "UPDATE_VIEW", original.viewId().toString(),
                "a".repeat(64), HomeViewDtos.HomeViewResponse.class);

        assertThat(replay).isEqualTo(original);
        assertThatThrownBy(() -> service.replay(
                7L, 11L, commandId, "RESTORE_VIEW", original.viewId().toString(),
                "a".repeat(64), HomeViewDtos.HomeViewResponse.class))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                        ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void rejectsAnExpiredReceiptInsteadOfSilentlyExecutingTheCommandAgain() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HomeCommandReceiptRepository repository = mock(HomeCommandReceiptRepository.class);
        UUID commandId = UUID.randomUUID();
        HomeCommandReceipt expired = HomeCommandReceipt.builder()
                .receiptId(UUID.randomUUID()).tenantId(7L).actorId(11L)
                .commandId(commandId).operation("RESET_VIEW").targetKey("view-1")
                .requestFingerprint("a".repeat(64))
                .responseType(HomeViewDtos.HomeViewResponse.class.getName())
                .responsePayload(mapper.createObjectNode())
                .createdAt(OffsetDateTime.now().minusHours(2))
                .expiresAt(OffsetDateTime.now().minusHours(1)).build();
        when(repository.findByTenantIdAndActorIdAndCommandId(7L, 11L, commandId))
                .thenReturn(Optional.of(expired));
        HomeCommandReceiptService service =
                new HomeCommandReceiptService(repository, mapper, 24);

        assertThatThrownBy(() -> service.replay(
                7L, 11L, commandId, "RESET_VIEW", "view-1",
                "a".repeat(64), HomeViewDtos.HomeViewResponse.class))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("expired");
    }
}

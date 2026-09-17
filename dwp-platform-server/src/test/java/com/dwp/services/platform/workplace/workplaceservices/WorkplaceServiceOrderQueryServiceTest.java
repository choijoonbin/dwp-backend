package com.dwp.services.platform.workplace.workplaceservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesRepository.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkplaceServiceOrderQueryServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-17T00:00:00Z");

    @Test
    void oneHundredOrderPageUsesOnlyBatchHydrationQueries() {
        WorkplaceServicesRepository repository = mock(WorkplaceServicesRepository.class);
        WorkplaceServicesCursorCodec cursorCodec = mock(WorkplaceServicesCursorCodec.class);
        WorkplaceServiceOrderQueryService subject = new WorkplaceServiceOrderQueryService(
                repository, new ObjectMapper().findAndRegisterModules(), cursorCodec,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        List<OrderRow> rows = IntStream.range(0, 100)
                .mapToObj(WorkplaceServiceOrderQueryServiceTest::order)
                .toList();
        List<UUID> orderIds = rows.stream().map(OrderRow::orderId).toList();
        when(repository.userOrderPage(42, 99, null, null, 101)).thenReturn(rows);
        when(repository.linesForOrders(42, orderIds)).thenReturn(List.of());
        when(repository.tasksForOrders(42, orderIds)).thenReturn(List.of());
        when(repository.catalogItems(42, List.of())).thenReturn(Map.of());
        when(repository.reservationsForOrders(42, orderIds)).thenReturn(Map.of());
        when(repository.collectionCountsForOrders(42, orderIds)).thenReturn(Map.of());

        ServiceOrders page = subject.ownOrders(42, 99, null, 100);

        assertThat(page.items()).hasSize(100);
        assertThat(page.hasMore()).isFalse();
        verify(repository).linesForOrders(42, orderIds);
        verify(repository).tasksForOrders(42, orderIds);
        verify(repository).catalogItems(42, List.of());
        verify(repository).reservationsForOrders(42, orderIds);
        verify(repository).collectionCountsForOrders(42, orderIds);
        verify(repository, never()).lines(anyLong(), any());
        verify(repository, never()).tasks(anyLong(), any());
        verify(repository, never()).reservation(anyLong(), anyLong(), any(), any());
        verify(repository, never()).adminCatalogItem(anyLong(), any());
        verify(repository, never()).collectionCounts(anyLong(), any());
    }

    private static OrderRow order(int index) {
        UUID orderId = new UUID(0, index + 1L);
        return new OrderRow(orderId, 42, 99, UUID.randomUUID(),
                ReservationAuthority.WORKPLACE, UUID.randomUUID(), 1,
                NOW.plusDays(1), NOW.plusDays(1).plusHours(1), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), 1, null, BigDecimal.ZERO, "KRW", null,
                OrderState.SUBMITTED, ReservationImpact.NONE, false, null, null,
                1, NOW.minusMinutes(index), NOW.minusMinutes(index));
    }
}

package com.dwp.services.platform.widgetregistry;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WidgetRegistryAuditService {
    private final WidgetRegistryEventRepository events;
    private final WidgetRegistryLedger ledger;

    public WidgetRegistryAuditService(
            WidgetRegistryEventRepository events, WidgetRegistryLedger ledger) {
        this.events = events;
        this.ledger = ledger;
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.RegistryEventPage list(int page, int size) {
        List<WidgetRegistryDtos.RegistryEventResponse> all = events
                .findTop100ByOrderByRegistryRevisionDesc().stream()
                .map(this::response).toList();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        int from = Math.min(all.size(), safePage * safeSize);
        int to = Math.min(all.size(), from + safeSize);
        return new WidgetRegistryDtos.RegistryEventPage(
                all.subList(from, to), safePage, safeSize, all.size(), to < all.size(),
                Long.toString(ledger.state().getRegistryRevision()));
    }

    private WidgetRegistryDtos.RegistryEventResponse response(WidgetRegistryEvent value) {
        return new WidgetRegistryDtos.RegistryEventResponse(
                value.getEventId(), value.getRegistryRevision(), value.getTenantId(),
                value.getAggregateType(), value.getAggregateId(), value.getEventType(),
                value.getCommandId(), "actor:" + value.getActorId(), value.getCorrelationId(),
                value.getBeforeSnapshot(), value.getAfterSnapshot(), value.getEvidenceRefs(),
                value.getOccurredAt());
    }
}

package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class WidgetRegistryLedger {
    private final WidgetRegistryStateRepository states;
    private final WidgetRegistryEventRepository events;
    private final ObjectMapper objectMapper;

    public WidgetRegistryLedger(
            WidgetRegistryStateRepository states,
            WidgetRegistryEventRepository events,
            ObjectMapper objectMapper) {
        this.states = states;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    public WidgetRegistryState state() {
        return states.findById("GLOBAL")
                .orElseThrow(() -> new BaseException(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    public long append(
            Long tenantId,
            String aggregateType,
            String aggregateId,
            String eventType,
            UUID commandId,
            Long actorId,
            String correlationId,
            Object before,
            Object after,
            List<UUID> evidenceIds,
            RevisionAxis axis) {
        WidgetRegistryState state = states.lockGlobal()
                .orElseThrow(() -> new BaseException(ErrorCode.INTERNAL_SERVER_ERROR));
        if (state.getRegistryRevision() == Long.MAX_VALUE) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Widget Registry revision is exhausted.");
        }
        long revision = state.getRegistryRevision() + 1;
        state.setRegistryRevision(revision);
        if (axis == RevisionAxis.POLICY) state.setPolicyRevision(state.getPolicyRevision() + 1);
        if (axis == RevisionAxis.SAFETY) state.setSafetyRevision(state.getSafetyRevision() + 1);
        states.save(state);
        events.save(WidgetRegistryEvent.builder()
                .eventId(UUID.randomUUID())
                .registryRevision(revision)
                .tenantId(tenantId)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .commandId(commandId)
                .actorId(actorId)
                .correlationId(correlationId)
                .beforeSnapshot(tree(before))
                .afterSnapshot(tree(after))
                .evidenceRefs(objectMapper.valueToTree(evidenceIds == null ? List.of() : evidenceIds))
                .build());
        return revision;
    }

    private JsonNode tree(Object value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }

    public enum RevisionAxis { REGISTRY, POLICY, SAFETY }
}

package com.dwp.services.platform.workplace;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class WorkplaceFacilityClosureNotificationEvents {
    static final String EXECUTED = "workplace.facility-closure.executed.v1";
    static final String TYPE_KEY = "WORKPLACE.FACILITY_CLOSURE_IMPACT";
    private static final String SOURCE = "urn:dwp:platform:workplace";

    private final DomainEventRecorder recorder;
    private final ObjectMapper json;

    WorkplaceFacilityClosureNotificationEvents(DomainEventRecorder recorder,
                                                DomainEventContractRegistry contracts,
                                                ObjectMapper json) {
        this.recorder = recorder;
        this.json = json;
        contracts.register(EXECUTED, 1, 1);
    }

    UUID recipientImpacted(long tenant, long actor, long recipient, UUID commandId,
                           UUID closureId, UUID resourceId, String resourceName,
                           UUID bookingId, String action, UUID replacementResourceId,
                           String startsAt, String endsAt, long sequence,
                           String correlationId) {
        ObjectNode data = json.createObjectNode()
                .put("commandId", commandId.toString())
                .put("closureId", closureId.toString())
                .put("resourceId", resourceId.toString())
                .put("bookingId", bookingId.toString())
                .put("action", action);
        ArrayNode intents = data.putArray("notificationIntents");
        ObjectNode intent = intents.addObject()
                .put("typeKey", TYPE_KEY)
                .put("threadKey", "workplace-booking:" + bookingId)
                .put("locale", "ko-KR")
                .put("reasonCode", "OWNER")
                .put("actorReference", "user:" + actor)
                .put("subjectReference", "workplace-booking:" + bookingId)
                .put("targetReference", "/workplace/reservations?booking=" + bookingId)
                .put("actionRequired", false);
        intent.putArray("recipientUserIds").add(recipient);
        intent.putArray("contexts").addObject()
                .put("kind", "WORK_ITEM")
                .put("key", "workplace-booking:" + bookingId)
                .put("matchable", true);
        intent.putObject("variables")
                .put("resourceName", resourceName)
                .put("bookingId", bookingId.toString())
                .put("impactAction", action)
                .put("startsAt", startsAt)
                .put("endsAt", endsAt)
                .put("commandId", commandId.toString())
                .put("closureId", closureId.toString());
        if (replacementResourceId != null) {
            intent.withObject("variables").put("replacementResourceId", replacementResourceId.toString());
        }
        String correlation = correlationId == null || correlationId.isBlank()
                ? "workplace-facility-closure:" + commandId : correlationId.trim();
        return recorder.record(DomainEventEnvelope.create(
                SOURCE, EXECUTED, 1, tenant, "FACILITY_CLOSURE_COMMAND",
                commandId.toString(), sequence, correlation, null, null, data));
    }
}

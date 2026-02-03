package com.dwp.services.synapsex.audit;

import java.util.Map;

/**
 * Aura event_type → stage 매핑 (Aura-Synapse 계약)
 */
public final class AuraEventStageMapper {

    private AuraEventStageMapper() {}

    private static final Map<String, String> EVENT_TO_STAGE = Map.ofEntries(
            Map.entry("SCAN_STARTED", "SCAN"),
            Map.entry("SCAN_COMPLETED", "SCAN"),
            Map.entry("DETECTION_FOUND", "DETECT"),
            Map.entry("RAG_QUERIED", "ANALYZE"),
            Map.entry("REASONING_COMPOSED", "ANALYZE"),
            Map.entry("DECISION_MADE", "ANALYZE"),
            Map.entry("SIMULATION_RUN", "SIMULATE"),
            Map.entry("ACTION_PROPOSED", "EXECUTE"),
            Map.entry("ACTION_APPROVED", "EXECUTE"),
            Map.entry("ACTION_EXECUTED", "EXECUTE"),
            Map.entry("ACTION_ROLLED_BACK", "EXECUTE")
    );

    /**
     * Aura event_type → stage 반환. 매핑 없으면 ANALYZE.
     */
    public static String toStage(String eventType) {
        if (eventType == null || eventType.isBlank()) return "ANALYZE";
        String stage = EVENT_TO_STAGE.get(eventType.toUpperCase());
        return stage != null ? stage : "ANALYZE";
    }
}

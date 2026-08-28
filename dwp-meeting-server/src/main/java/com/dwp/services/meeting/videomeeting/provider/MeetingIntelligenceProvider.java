package com.dwp.services.meeting.videomeeting.provider;

import java.util.List;
import java.util.UUID;

/**
 * Boundary for a governed, zero-retention meeting-intelligence provider.
 * Implementations must not perform biometric or person-level emotion inference.
 */
public interface MeetingIntelligenceProvider {

    Capability capability(ExecutionContext context);

    Analysis analyze(ExecutionContext context, Request request);

    record ExecutionContext(
            long tenantId,
            UUID meetingId,
            UUID runId,
            String correlationId) {
    }

    record Capability(
            boolean available,
            String providerCode,
            String model,
            String processingRegion,
            boolean customerDataTrainingDisabled,
            boolean providerRetentionDisabled,
            List<String> schemaVersions) {

        public static Capability unavailable() {
            return new Capability(
                    false, "disabled", "none", "none", false, false, List.of());
        }

        public boolean enterpriseSafe(String requiredRegion) {
            return available
                    && customerDataTrainingDisabled
                    && providerRetentionDisabled
                    && processingRegion != null
                    && processingRegion.equals(requiredRegion)
                    && schemaVersions != null
                    && schemaVersions.contains("meeting-intelligence-v1");
        }
    }

    record Request(
            String analysisProfile,
            String outputLanguage,
            String sourceSha256,
            List<TranscriptSegment> transcript) {
    }

    record TranscriptSegment(
            String segmentId,
            long startMillis,
            long endMillis,
            String text) {
    }

    record Analysis(
            CitedText executiveSummary,
            List<CitedText> topics,
            List<CitedText> decisions,
            List<CitedText> actionItems,
            List<CitedText> openQuestions,
            List<CitedText> risks,
            ConversationClimate conversationClimate) {
    }

    record CitedText(String text, List<Citation> citations) {
    }

    record Citation(String segmentId, long startMillis, long endMillis) {
    }

    /** Meeting-level conversational signals only. No person or speaker field is permitted. */
    record ConversationClimate(
            ClimateLabel label,
            List<ClimateSignal> signals,
            List<Citation> citations) {
    }

    enum ClimateLabel {
        ALIGNED, MIXED, CONTESTED, INSUFFICIENT_EVIDENCE
    }

    enum ClimateSignal {
        BALANCED_TURN_TAKING,
        CONSTRUCTIVE_DISAGREEMENT,
        UNRESOLVED_DISAGREEMENT,
        DOMINANT_MONOLOGUE_PATTERN,
        LOW_TRANSCRIPT_EVIDENCE
    }
}

package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Analysis;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Citation;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.CitedText;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ClimateLabel;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ClimateSignal;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.TranscriptSegment;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MeetingIntelligenceOutputValidator {

    private static final int MAX_SEGMENTS = 500;
    private static final int MAX_SEGMENT_TEXT = 4_000;
    private static final int MAX_TRANSCRIPT_TEXT = 300_000;
    private static final int MAX_ITEMS = 100;
    private static final int MAX_TEXT = 8_000;

    public void validateTranscript(List<TranscriptSegment> transcript) {
        if (transcript == null || transcript.isEmpty() || transcript.size() > MAX_SEGMENTS) {
            throw invalid("Transcript segment count is invalid.");
        }
        Set<String> ids = new HashSet<>();
        long previousStart = -1;
        int transcriptTextLength = 0;
        for (TranscriptSegment segment : transcript) {
            if (segment == null || !safeId(segment.segmentId())
                    || !ids.add(segment.segmentId())
                    || segment.startMillis() < 0
                    || segment.endMillis() <= segment.startMillis()
                    || segment.startMillis() < previousStart
                    || !safeText(segment.text(), MAX_SEGMENT_TEXT)) {
                throw invalid("Transcript segment structure is invalid.");
            }
            transcriptTextLength += segment.text().length();
            if (transcriptTextLength > MAX_TRANSCRIPT_TEXT) {
                throw invalid("Transcript text exceeds the governed input limit.");
            }
            previousStart = segment.startMillis();
        }
    }

    public void validate(Analysis analysis, List<TranscriptSegment> transcript) {
        if (analysis == null) throw invalid("Provider analysis is missing.");
        Map<String, TranscriptSegment> segments = new HashMap<>();
        transcript.forEach(segment -> segments.put(segment.segmentId(), segment));
        citedText(analysis.executiveSummary(), segments);
        citedList(analysis.topics(), segments);
        citedList(analysis.decisions(), segments);
        citedList(analysis.actionItems(), segments);
        citedList(analysis.openQuestions(), segments);
        citedList(analysis.risks(), segments);
        climate(analysis, segments);
    }

    private void citedList(List<CitedText> items, Map<String, TranscriptSegment> segments) {
        if (items == null || items.size() > MAX_ITEMS) {
            throw invalid("Provider insight collection is invalid.");
        }
        items.forEach(item -> citedText(item, segments));
    }

    private void citedText(CitedText item, Map<String, TranscriptSegment> segments) {
        if (item == null || !safeText(item.text(), MAX_TEXT)
                || item.citations() == null || item.citations().isEmpty()
                || item.citations().size() > 20) {
            throw invalid("Every generated statement must contain transcript evidence.");
        }
        citations(item.citations(), segments);
    }

    private void climate(Analysis analysis, Map<String, TranscriptSegment> segments) {
        var climate = analysis.conversationClimate();
        if (climate == null || climate.label() == null || climate.signals() == null
                || climate.citations() == null || climate.signals().size() > 5
                || new HashSet<>(climate.signals()).size() != climate.signals().size()) {
            throw invalid("Conversation climate structure is invalid.");
        }
        if (climate.label() == ClimateLabel.INSUFFICIENT_EVIDENCE) {
            if (!climate.signals().contains(ClimateSignal.LOW_TRANSCRIPT_EVIDENCE)) {
                throw invalid("Insufficient climate evidence must be explicit.");
            }
        } else if (climate.citations().isEmpty()
                || climate.signals().contains(ClimateSignal.LOW_TRANSCRIPT_EVIDENCE)) {
            throw invalid("Conversation climate must be supported by transcript evidence.");
        }
        citations(climate.citations(), segments);
    }

    private void citations(
            List<Citation> citations, Map<String, TranscriptSegment> segments) {
        for (Citation citation : citations) {
            TranscriptSegment segment = citation == null
                    ? null : segments.get(citation.segmentId());
            if (segment == null || citation.startMillis() < segment.startMillis()
                    || citation.endMillis() > segment.endMillis()
                    || citation.endMillis() <= citation.startMillis()) {
                throw invalid("Provider citation does not match the trusted transcript.");
            }
        }
    }

    private boolean safeId(String value) {
        return value != null && value.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    }

    private boolean safeText(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength
                && value.chars().noneMatch(character -> Character.isISOControl(character)
                        && character != '\n' && character != '\t');
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}

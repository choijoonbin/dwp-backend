package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Analysis;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Citation;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.CitedText;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ClimateLabel;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ClimateSignal;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ConversationClimate;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.TranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeetingIntelligenceOutputValidatorTest {

    private final MeetingIntelligenceOutputValidator validator =
            new MeetingIntelligenceOutputValidator();

    @Test
    void acceptsFullyCitedMeetingLevelAnalysis() {
        assertThatCode(() -> validator.validate(validAnalysis(), transcript()))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsExplicitInsufficientClimateEvidenceWithoutCitation() {
        Analysis analysis = withClimate(new ConversationClimate(
                ClimateLabel.INSUFFICIENT_EVIDENCE,
                List.of(ClimateSignal.LOW_TRANSCRIPT_EVIDENCE), List.of()));

        assertThatCode(() -> validator.validate(analysis, transcript()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsEmptyTranscript() {
        assertThatThrownBy(() -> validator.validateTranscript(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullTranscript() {
        assertThatThrownBy(() -> validator.validateTranscript(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateSegmentIdentifiers() {
        List<TranscriptSegment> duplicate = List.of(
                segment("s1", 0, 1000, "first"),
                segment("s1", 1000, 2000, "second"));

        assertThatThrownBy(() -> validator.validateTranscript(duplicate))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOutOfOrderTranscript() {
        List<TranscriptSegment> reversed = List.of(
                segment("s1", 1000, 2000, "first"),
                segment("s2", 500, 900, "second"));

        assertThatThrownBy(() -> validator.validateTranscript(reversed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroLengthSegment() {
        assertThatThrownBy(() -> validator.validateTranscript(List.of(
                segment("s1", 1000, 1000, "text"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsafeSegmentIdentifier() {
        assertThatThrownBy(() -> validator.validateTranscript(List.of(
                segment("segment id", 0, 1000, "text"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAgentContractSegmentIdentifierBoundary() {
        assertThatCode(() -> validator.validateTranscript(List.of(
                segment("s".repeat(80), 0, 1000, "text"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSegmentIdentifierAboveAgentContractBoundary() {
        assertThatThrownBy(() -> validator.validateTranscript(List.of(
                segment("s".repeat(81), 0, 1000, "text"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankSegmentText() {
        assertThatThrownBy(() -> validator.validateTranscript(List.of(
                segment("s1", 0, 1000, "   "))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTranscriptAboveBound() {
        List<TranscriptSegment> segments = new ArrayList<>();
        for (int index = 0; index < 501; index++) {
            segments.add(segment("s" + index, index, index + 1L, "x"));
        }

        assertThatThrownBy(() -> validator.validateTranscript(segments))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAgentContractSegmentAndTextBoundaries() {
        List<TranscriptSegment> segments = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            segments.add(segment("s" + index, index, index + 1L,
                    index == 0 ? "x".repeat(4_000) : "x"));
        }

        assertThatCode(() -> validator.validateTranscript(segments))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSegmentAboveAgentCharacterLimit() {
        assertThatThrownBy(() -> validator.validateTranscript(List.of(
                segment("s1", 0, 1000, "x".repeat(4_001)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTranscriptAboveAgentAggregateCharacterLimit() {
        List<TranscriptSegment> segments = new ArrayList<>();
        for (int index = 0; index < 76; index++) {
            segments.add(segment("s" + index, index, index + 1L, "x".repeat(4_000)));
        }

        assertThatThrownBy(() -> validator.validateTranscript(segments))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingAnalysis() {
        assertThatThrownBy(() -> validator.validate(null, transcript()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUncitedSummary() {
        Analysis analysis = new Analysis(
                new CitedText("summary", List.of()), List.of(), List.of(),
                List.of(), List.of(), List.of(), validClimate());

        assertThatThrownBy(() -> validator.validate(analysis, transcript()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownCitationSegment() {
        Analysis analysis = new Analysis(
                new CitedText("summary", List.of(new Citation("unknown", 0, 10))),
                List.of(), List.of(), List.of(), List.of(), List.of(), validClimate());

        assertThatThrownBy(() -> validator.validate(analysis, transcript()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCitationOutsideTrustedTimeRange() {
        Analysis analysis = new Analysis(
                new CitedText("summary", List.of(new Citation("s1", 0, 5000))),
                List.of(), List.of(), List.of(), List.of(), List.of(), validClimate());

        assertThatThrownBy(() -> validator.validate(analysis, transcript()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingConversationClimate() {
        Analysis analysis = new Analysis(
                cited("summary"), List.of(), List.of(), List.of(), List.of(), List.of(), null);

        assertThatThrownBy(() -> validator.validate(analysis, transcript()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUncitedDefinitiveClimate() {
        Analysis analysis = withClimate(new ConversationClimate(
                ClimateLabel.ALIGNED,
                List.of(ClimateSignal.CONSTRUCTIVE_DISAGREEMENT), List.of()));

        assertThatThrownBy(() -> validator.validate(analysis, transcript()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInsufficientClimateWithoutLowEvidenceSignal() {
        Analysis analysis = withClimate(new ConversationClimate(
                ClimateLabel.INSUFFICIENT_EVIDENCE, List.of(), List.of()));

        assertThatThrownBy(() -> validator.validate(analysis, transcript()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLowEvidenceSignalWithDefinitiveLabel() {
        Analysis analysis = withClimate(new ConversationClimate(
                ClimateLabel.MIXED,
                List.of(ClimateSignal.LOW_TRANSCRIPT_EVIDENCE), citations()));

        assertThatThrownBy(() -> validator.validate(analysis, transcript()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateClimateSignals() {
        Analysis analysis = withClimate(new ConversationClimate(
                ClimateLabel.ALIGNED,
                List.of(ClimateSignal.CONSTRUCTIVE_DISAGREEMENT,
                        ClimateSignal.CONSTRUCTIVE_DISAGREEMENT), citations()));

        assertThatThrownBy(() -> validator.validate(analysis, transcript()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullInsightCollection() {
        Analysis analysis = new Analysis(
                cited("summary"), null, List.of(), List.of(), List.of(), List.of(),
                validClimate());

        assertThatThrownBy(() -> validator.validate(analysis, transcript()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTopicCollectionAboveAgentContractLimit() {
        Analysis valid = validAnalysis();
        Analysis analysis = new Analysis(
                valid.executiveSummary(), java.util.Collections.nCopies(21, cited("topic")),
                valid.decisions(), valid.actionItems(), valid.openQuestions(), valid.risks(),
                valid.conversationClimate());

        assertThatThrownBy(() -> validator.validate(analysis, transcript()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsGeneratedTextAboveAgentContractLimit() {
        Analysis valid = validAnalysis();
        Analysis analysis = new Analysis(
                cited("x".repeat(2_001)), valid.topics(), valid.decisions(),
                valid.actionItems(), valid.openQuestions(), valid.risks(),
                valid.conversationClimate());

        assertThatThrownBy(() -> validator.validate(analysis, transcript()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsClimateCitationsAboveAgentContractLimit() {
        Analysis analysis = withClimate(new ConversationClimate(
                ClimateLabel.ALIGNED,
                List.of(ClimateSignal.CONSTRUCTIVE_DISAGREEMENT),
                java.util.Collections.nCopies(21, new Citation("s1", 0, 900))));

        assertThatThrownBy(() -> validator.validate(analysis, transcript()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Analysis validAnalysis() {
        return new Analysis(
                cited("Meeting agreed on the release gate."),
                List.of(cited("Release readiness")),
                List.of(cited("Use the governed gate")),
                List.of(cited("Complete the verification")),
                List.of(cited("Confirm the deployment window")),
                List.of(cited("Provider readiness may delay the release")),
                validClimate());
    }

    private Analysis withClimate(ConversationClimate climate) {
        Analysis valid = validAnalysis();
        return new Analysis(
                valid.executiveSummary(), valid.topics(), valid.decisions(),
                valid.actionItems(), valid.openQuestions(), valid.risks(), climate);
    }

    private ConversationClimate validClimate() {
        return new ConversationClimate(
                ClimateLabel.MIXED,
                List.of(ClimateSignal.CONSTRUCTIVE_DISAGREEMENT), citations());
    }

    private CitedText cited(String text) {
        return new CitedText(text, citations());
    }

    private List<Citation> citations() {
        return List.of(new Citation("s1", 0, 900));
    }

    private List<TranscriptSegment> transcript() {
        return List.of(
                segment("s1", 0, 1000, "We agree on the release gate."),
                segment("s2", 1000, 2000, "The deployment window remains open."));
    }

    private TranscriptSegment segment(String id, long start, long end, String text) {
        return new TranscriptSegment(id, start, end, text);
    }
}

package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingTranscriptAccessDtos;
import com.dwp.services.meeting.videomeeting.api.MeetingTranscriptAccessDtos.QueryCommand;
import com.dwp.services.meeting.videomeeting.api.MeetingTranscriptAccessDtos.QueryResponse;
import com.dwp.services.meeting.videomeeting.api.MeetingTranscriptAccessDtos.SegmentResponse;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptAccessModels.PreparedQuery;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.TranscriptSegment;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource.ReadContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class MeetingTranscriptAccessService {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAXIMUM_PAGE_TEXT_LENGTH = 100_000;

    private final MeetingTranscriptSource transcripts;
    private final MeetingIntelligenceOutputValidator validator;
    private final MeetingTranscriptAccessTransactions transactions;

    public MeetingTranscriptAccessService(
            MeetingTranscriptSource transcripts,
            MeetingIntelligenceOutputValidator validator,
            MeetingTranscriptAccessTransactions transactions) {
        this.transcripts = transcripts;
        this.validator = validator;
        this.transactions = transactions;
    }

    public QueryResponse query(
            UUID meetingId,
            UUID artifactId,
            QueryCommand request,
            String correlationId) {
        ValidQuery valid = validate(request);
        if (!transcripts.available()) throw unavailable();
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        String correlation = safeCorrelation(correlationId);
        PreparedQuery prepared = transactions.prepare(
                subject, meetingId, artifactId,
                request.expectedArtifactVersion(), correlation);
        List<TranscriptSegment> source;
        try {
            source = transcripts.read(new ReadContext(
                    subject.tenantId(), meetingId, UUID.randomUUID(), artifactId,
                    prepared.artifact().sourceSha256(), correlation));
            validator.validateTranscript(source);
        } catch (RuntimeException exception) {
            throw unavailable();
        }
        List<TranscriptSegment> matches = valid.query() == null
                ? source
                : source.stream().filter(segment -> contains(
                        segment.text(), valid.query())).toList();
        int from = Math.min(valid.cursor(), matches.size());
        int to = boundedEnd(matches, from, valid.pageSize());
        List<SegmentResponse> page = matches.subList(from, to).stream()
                .map(segment -> new SegmentResponse(
                        segment.segmentId(), segment.startMillis(),
                        segment.endMillis(), segment.text()))
                .toList();
        boolean hasMore = to < matches.size();
        transactions.complete(prepared, page.size(), valid.query() != null, hasMore);
        return new QueryResponse(
                artifactId, prepared.artifact().version(), page,
                hasMore ? to : null, hasMore, valid.query() != null,
                prepared.artifact().retentionUntil());
    }

    private ValidQuery validate(QueryCommand request) {
        if (request == null || request.expectedArtifactVersion() == null
                || request.expectedArtifactVersion() < 0) {
            throw invalid("The expected transcript artifact version is required.");
        }
        int cursor = request.cursor() == null ? 0 : request.cursor();
        int pageSize = request.pageSize() == null
                ? DEFAULT_PAGE_SIZE : request.pageSize();
        if (cursor < 0 || cursor > 500 || pageSize < 1 || pageSize > 50) {
            throw invalid("The transcript page boundary is invalid.");
        }
        String query = request.query() == null ? null : request.query().trim();
        if (query != null && query.isBlank()) query = null;
        if (query != null && (query.length() < 2 || query.length() > 80
                || query.codePoints().anyMatch(Character::isISOControl))) {
            throw invalid("The transcript search query is invalid.");
        }
        return new ValidQuery(cursor, pageSize, query);
    }

    private boolean contains(String text, String query) {
        return text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private int boundedEnd(List<TranscriptSegment> segments, int from, int pageSize) {
        int to = Math.min(from + pageSize, segments.size());
        int textLength = 0;
        for (int index = from; index < to; index++) {
            int nextLength = textLength + segments.get(index).text().length();
            if (nextLength > MAXIMUM_PAGE_TEXT_LENGTH) return index;
            textLength = nextLength;
        }
        return to;
    }

    private String safeCorrelation(String value) {
        String candidate = value == null ? "" : value.trim();
        return candidate.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$")
                ? candidate : "meeting-transcript:" + UUID.randomUUID();
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException unavailable() {
        return new BaseException(
                ErrorCode.EXTERNAL_SERVICE_ERROR,
                "The governed transcript service is unavailable.");
    }

    private record ValidQuery(int cursor, int pageSize, String query) {
    }
}

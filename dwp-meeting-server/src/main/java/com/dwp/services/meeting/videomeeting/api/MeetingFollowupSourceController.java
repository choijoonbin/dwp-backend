package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.domain.MeetingFollowupAssertionVerifier;
import com.dwp.services.meeting.videomeeting.domain.MeetingFollowupSourceIngressService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

@Hidden
@RestController
public class MeetingFollowupSourceController {

    private static final int MAX_BODY_BYTES = 16_384;
    private static final int MAX_ASSERTION_LENGTH = 4_096;

    private final MeetingFollowupAssertionVerifier verifier;
    private final MeetingFollowupSourceIngressService ingress;
    private final ObjectMapper mapper;

    public MeetingFollowupSourceController(
            MeetingFollowupAssertionVerifier verifier,
            MeetingFollowupSourceIngressService ingress,
            ObjectMapper mapper) {
        this.verifier = verifier;
        this.ingress = ingress;
        this.mapper = mapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    @PostMapping(
            path = MeetingFollowupAssertionVerifier.PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MeetingFollowupSourceDtos.Response> resolve(
            @RequestBody byte[] exactBody,
            HttpServletRequest servletRequest) {
        String assertion = exactAssertion(servletRequest);
        MeetingFollowupSourceDtos.Request request = request(exactBody);
        MeetingFollowupAssertionVerifier.VerifiedAssertion verified =
                verifier.verify(assertion, exactBody, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .body(ingress.resolve(verified, request));
    }

    private String exactAssertion(HttpServletRequest request) {
        var headers = Collections.list(
                request.getHeaders(MeetingFollowupAssertionVerifier.HEADER));
        if (headers.size() != 1 || headers.getFirst() == null
                || headers.getFirst().isBlank()
                || headers.getFirst().length() > MAX_ASSERTION_LENGTH
                || !headers.getFirst().equals(headers.getFirst().trim())) {
            throw denied();
        }
        return headers.getFirst();
    }

    private MeetingFollowupSourceDtos.Request request(byte[] exactBody) {
        if (exactBody == null || exactBody.length == 0 || exactBody.length > MAX_BODY_BYTES) {
            throw denied();
        }
        try {
            MeetingFollowupSourceDtos.Request request = mapper.readValue(
                    exactBody, MeetingFollowupSourceDtos.Request.class);
            if (request == null || request.tenantId() <= 0 || request.actorUserId() <= 0
                    || request.source() == null || request.source().meetingId() == null
                    || request.source().reportId() == null
                    || request.source().candidateId() == null || request.action() == null) {
                throw denied();
            }
            return request;
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw denied();
        }
    }

    private BaseException denied() {
        return new BaseException(
                ErrorCode.UNAUTHORIZED,
                "Trusted Platform Work source assertion is required.");
    }
}

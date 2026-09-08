package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordBookmarkDtos.BookmarkInput;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordBookmarkDtos.BookmarkPage;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordBookmarkDtos.BookmarkState;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordBookmarkService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class MeetingRecordBookmarkController {
    private final MeetingRecordBookmarkService service;

    public MeetingRecordBookmarkController(MeetingRecordBookmarkService service) { this.service = service; }

    @GetMapping("/history/bookmarks")
    public ApiResponse<BookmarkPage> read(@RequestParam
            @Parameter(description = "1 to 100 unique record IDs from the current page, in display order")
            List<UUID> meetingIds) {
        return ApiResponse.success(service.read(meetingIds));
    }

    @PutMapping("/meetings/{meetingId}/bookmark")
    public ApiResponse<BookmarkState> update(@PathVariable UUID meetingId,
            @Valid @RequestBody BookmarkInput input, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation) {
        return ApiResponse.success(service.update(meetingId, input, key, correlation));
    }

    /** Do not log or echo unexpected caller content in malformed commands/IDs. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Object>> malformedCommand() {
        return ResponseEntity.badRequest().body(ApiResponse.error(
                ErrorCode.INVALID_INPUT_VALUE, "The record bookmark input is invalid."));
    }
}

package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordRetentionDtos.ControlInput;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordRetentionDtos.ControlState;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordRetentionControlService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/record-retention/meetings")
public class MeetingRecordRetentionController {
    private final MeetingRecordRetentionControlService service;
    public MeetingRecordRetentionController(MeetingRecordRetentionControlService service) { this.service = service; }
    @GetMapping("/{meetingId}")
    public ApiResponse<ControlState> read(@PathVariable UUID meetingId) {
        return ApiResponse.success(service.read(meetingId));
    }
    @PutMapping("/{meetingId}")
    public ApiResponse<ControlState> update(@PathVariable UUID meetingId, @Valid @RequestBody ControlInput input,
            @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.success(service.update(meetingId, input, key));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Object>> malformedCommand() {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE,
                "The record retention input is invalid."));
    }
}

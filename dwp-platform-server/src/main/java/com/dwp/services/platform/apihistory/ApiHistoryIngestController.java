package com.dwp.services.platform.apihistory;

import com.dwp.core.common.ApiResponse;
import com.dwp.observability.api.ApiHistoryEvent;
import com.dwp.observability.api.HttpApiHistoryPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/observability/api-history")
public class ApiHistoryIngestController {

    private final ApiHistoryService service;

    public ApiHistoryIngestController(ApiHistoryService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<IngestResult> ingest(
            @RequestHeader(HttpApiHistoryPublisher.SERVICE_NAME_HEADER) String claimedService,
            @RequestBody List<ApiHistoryEvent> events) {
        return ApiResponse.success(new IngestResult(service.ingest(claimedService, events)));
    }

    public record IngestResult(int accepted) {
    }
}

package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.*;
import com.dwp.services.meeting.videomeeting.domain.MeetingTemplateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Validated
@RestController
public class MeetingTemplateController {
    private final MeetingTemplateService service;
    public MeetingTemplateController(MeetingTemplateService service) { this.service = service; }

    @GetMapping({"/v1/templates", "/v1/admin/templates"})
    public ApiResponse<TemplatePage> list(
            @RequestParam(defaultValue = "ALL") TemplateFilter scope,
            @RequestParam(defaultValue = "") @Size(max = 160) String q,
            @RequestParam(defaultValue = "") @Size(max = 40) String category,
            @RequestParam(defaultValue = "false") boolean favoritesOnly,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int pageSize,
            HttpServletRequest request) {
        return ApiResponse.success(service.list(scope, q, category, favoritesOnly, page, pageSize, admin(request)));
    }

    @GetMapping({"/v1/templates/{id}", "/v1/admin/templates/{id}"})
    public ApiResponse<TemplateResponse> get(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(service.get(id, admin(request)));
    }

    @PostMapping({"/v1/templates", "/v1/admin/templates"})
    public ApiResponse<TemplateResponse> create(@Valid @RequestBody TemplateInput input,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation,
            HttpServletRequest request) {
        return ApiResponse.success(service.create(input, key, correlation, admin(request)));
    }

    @PutMapping({"/v1/templates/{id}", "/v1/admin/templates/{id}"})
    public ApiResponse<TemplateResponse> update(@PathVariable UUID id,
            @Valid @RequestBody TemplateUpdate input,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation,
            HttpServletRequest request) {
        return ApiResponse.success(service.update(id, input, key, correlation, admin(request)));
    }

    @DeleteMapping({"/v1/templates/{id}", "/v1/admin/templates/{id}"})
    public ApiResponse<DeleteResponse> delete(@PathVariable UUID id,
            @RequestParam @Min(0) long expectedVersion,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation,
            HttpServletRequest request) {
        return ApiResponse.success(service.delete(id, expectedVersion, key, correlation, admin(request)));
    }

    @PostMapping("/v1/templates/{id}/clone")
    public ApiResponse<TemplateResponse> cloneTemplate(@PathVariable UUID id,
            @Valid @RequestBody CloneCommand input,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation) {
        return ApiResponse.success(service.cloneTemplate(id, input, key, correlation));
    }

    @PutMapping("/v1/templates/{id}/favorite")
    public ApiResponse<TemplateResponse> favorite(@PathVariable UUID id,
            @Valid @RequestBody FavoriteCommand input,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation) {
        return ApiResponse.success(service.favorite(id, input, key, correlation));
    }

    @PostMapping("/v1/templates/{id}/apply")
    public ApiResponse<ScheduleDraft> apply(@PathVariable UUID id,
            @Valid @RequestBody VersionCommand input,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation) {
        return ApiResponse.success(service.apply(id, input, key, correlation));
    }

    private boolean admin(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/v1/admin/templates");
    }
}

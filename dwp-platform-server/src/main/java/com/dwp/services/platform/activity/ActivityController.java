package com.dwp.services.platform.activity;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.platform.workspace.WorkspaceDtos;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/v1/workspace/activity")
public class ActivityController {
    private final ActivityService service;
    public ActivityController(ActivityService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<ApiResponse<WorkspaceDtos.ActivityFeed>> list(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-User-ID") Long user,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestParam(required = false) String actor, @RequestParam(required = false) String state,
            @RequestParam(required = false) String query, @RequestParam(required = false) String source,
            @RequestParam(required = false) String objectType, @RequestParam(required = false) String objectId,
            @RequestParam(required = false) String executionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(defaultValue = "false") Boolean includeUsage) {
        return noStore(service.list(tenant, user, permissions, locale, new ActivityQuery(
                actor, state, query, source, objectType, objectId, executionId, from, to, cursor, limit, includeUsage)));
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<ApiResponse<WorkspaceDtos.ActivityEvent>> detail(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant, @RequestHeader("X-DWP-User-ID") Long user,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @PathVariable UUID id) {
        return noStore(service.detail(tenant, user, permissions, locale, id));
    }

    @GetMapping("/executions/summary")
    public ResponseEntity<ApiResponse<WorkspaceDtos.ExecutionSummary>> summary(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant, @RequestHeader("X-DWP-User-ID") Long user,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader(value = "Accept-Language", required = false) String locale) {
        return noStore(service.summary(tenant, user, permissions, locale));
    }

    private <T> ResponseEntity<ApiResponse<T>> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(ApiResponse.success(value));
    }
}

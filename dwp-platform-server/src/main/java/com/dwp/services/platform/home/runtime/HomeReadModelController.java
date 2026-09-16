package com.dwp.services.platform.home.runtime;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.UUID;

@RestController
@RequestMapping("/v2/home")
@Validated
public class HomeReadModelController {

    static final String CACHE_CONTROL = "private, max-age=0, must-revalidate";
    static final String VARY = String.join(", ",
            "Accept-Language",
            "X-DWP-Tenant-ID",
            "X-DWP-User-ID",
            "X-DWP-Person-Public-ID",
            "X-DWP-Permissions",
            "X-DWP-Roles",
            "X-DWP-Group-Refs",
            "X-DWP-Current-Decision-Revision");

    private final HomeReadModelService service;
    private final HomeWidgetCommandService commands;
    private final HomeRuntimeProperties properties;

    public HomeReadModelController(
            HomeReadModelService service,
            HomeWidgetCommandService commands,
            HomeRuntimeProperties properties) {
        this.service = service;
        this.commands = commands;
        this.properties = properties;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<HomeReadModelDtos.HomeReadModel>> read(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false)
            UUID personPublicId,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions,
            @RequestHeader(value = "X-DWP-Roles", required = false) String roles,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader("X-DWP-Current-Decision-Revision") String decisionRevision,
            @RequestHeader("X-DWP-Current-Revalidate-At") String revalidateAt,
            @RequestHeader(value = "Accept-Language", defaultValue = "ko-KR") String locale,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @RequestParam(required = false)
            @Pattern(regexp = "CLASSIC|FLOW_V1") String mode,
            @RequestParam
            @Pattern(regexp = "DESKTOP_WIDE|DESKTOP_STANDARD|MOBILE_STANDARD|MOBILE_COMPACT")
            String deviceClass,
            @RequestParam(defaultValue = "Asia/Seoul") String timeZone) {
        requireAvailable();
        HomeRuntimeContext context = HomeRuntimeContext.create(
                tenantId, userId, personPublicId, permissions, roles, groupRefs,
                decisionRevision, revalidateAt, locale, timeZone);
        HomeReadModelDtos.ReadResult result = service.read(context, mode, deviceClass);
        HttpHeaders headers = responseHeaders(result.etag());
        if (matches(ifNoneMatch, result.etag())) {
            return new ResponseEntity<>(null, headers, HttpStatus.NOT_MODIFIED);
        }
        return new ResponseEntity<>(ApiResponse.success(result.model()), headers, HttpStatus.OK);
    }

    @PostMapping("/widget-actions:execute")
    public ResponseEntity<ApiResponse<HomeReadModelDtos.CommandReceipt>> execute(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false)
            UUID personPublicId,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions,
            @RequestHeader(value = "X-DWP-Roles", required = false) String roles,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader("X-DWP-Current-Decision-Revision") String decisionRevision,
            @RequestHeader("X-DWP-Current-Revalidate-At") String revalidateAt,
            @RequestHeader(value = "Accept-Language", defaultValue = "ko-KR") String locale,
            @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestParam(required = false)
            @Pattern(regexp = "CLASSIC|FLOW_V1") String mode,
            @RequestParam
            @Pattern(regexp = "DESKTOP_WIDE|DESKTOP_STANDARD|MOBILE_STANDARD|MOBILE_COMPACT")
            String deviceClass,
            @RequestParam(defaultValue = "Asia/Seoul") String timeZone,
            @Valid @RequestBody HomeReadModelDtos.CommandRequest request) {
        requireCommandsEnabled();
        HomeRuntimeContext context = HomeRuntimeContext.create(
                tenantId, userId, personPublicId, permissions, roles, groupRefs,
                decisionRevision, revalidateAt, locale, timeZone);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        headers.set("X-DWP-Home-Runtime-Mode", properties.enabled() ? "ACTIVE" : "SHADOW");
        headers.set("X-DWP-Home-Commands-Enabled",
                Boolean.toString(properties.commandsEnabled()));
        headers.set("X-DWP-Widget-Registry-Authoritative", "false");
        return new ResponseEntity<>(ApiResponse.success(commands.execute(
                context, mode, deviceClass, commandId, request)), headers, HttpStatus.ACCEPTED);
    }

    private void requireAvailable() {
        if (!properties.enabled() && !properties.shadowEnabled()) {
            throw new BaseException(
                    ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "Home Runtime v2 is disabled for this deployment.");
        }
    }

    private void requireCommandsEnabled() {
        if (!properties.commandsEnabled()) {
            throw new BaseException(
                    ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "Home Runtime commands remain disabled until the owner idempotency gate is promoted.");
        }
    }

    private HttpHeaders responseHeaders(String etag) {
        HttpHeaders headers = new HttpHeaders();
        headers.setETag(etag);
        headers.set(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
        headers.set(HttpHeaders.VARY, VARY);
        headers.set("X-DWP-Home-Runtime-Mode", properties.enabled() ? "ACTIVE" : "SHADOW");
        headers.set("X-DWP-Home-Commands-Enabled",
                Boolean.toString(properties.commandsEnabled()));
        headers.set("X-DWP-Widget-Registry-Authoritative", "false");
        return headers;
    }

    private boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) return false;
        return Arrays.stream(ifNoneMatch.split(","))
                .map(String::trim)
                .anyMatch(candidate -> "*".equals(candidate)
                        || etag.equals(candidate)
                        || etag.equals(candidate.startsWith("W/")
                        ? candidate.substring(2) : candidate));
    }
}

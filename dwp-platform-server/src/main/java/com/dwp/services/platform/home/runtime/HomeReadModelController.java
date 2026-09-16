package com.dwp.services.platform.home.runtime;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
            "X-DWP-Current-Decision-Revision",
            "X-DWP-Current-Revalidate-At",
            "X-DWP-Home-Runtime-State",
            "X-DWP-Home-Rollout-Ring",
            "X-DWP-Home-Rollout-Revision");

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
    @Operation(
            operationId = "readHomeV2",
            summary = "Read the recipient-bound Home v2 projection",
            description = "Returns a private conditional-read model. ETag identity is bound to "
                    + "tenant, recipient, authority revision, mode, device, locale and time zone.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Recipient-bound Home read model",
                    useReturnTypeSchema = true,
                    headers = {
                            @Header(name = "ETag", description = "Recipient and authority-bound entity tag",
                                    schema = @Schema(type = "string")),
                            @Header(name = "Cache-Control", description = "private, max-age=0, must-revalidate",
                                    schema = @Schema(type = "string")),
                            @Header(name = "Vary", description = "Trusted identity, authority and locale dimensions",
                                    schema = @Schema(type = "string")),
                            @Header(name = "X-DWP-Home-Runtime-Mode", schema = @Schema(type = "string")),
                            @Header(name = "X-DWP-Home-Runtime-State", schema = @Schema(type = "string")),
                            @Header(name = "X-DWP-Home-Rollout-Ring", schema = @Schema(type = "string")),
                            @Header(name = "X-DWP-Home-Rollout-Revision", schema = @Schema(type = "string")),
                            @Header(name = "X-DWP-Home-Commands-Enabled", schema = @Schema(type = "boolean")),
                            @Header(name = "X-DWP-Widget-Registry-Authoritative", schema = @Schema(type = "boolean"))
                    }),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "304", description = "Entity tag is current; response has no body",
                    content = @Content,
                    headers = {
                            @Header(name = "ETag", schema = @Schema(type = "string")),
                            @Header(name = "Cache-Control", schema = @Schema(type = "string")),
                            @Header(name = "Vary", schema = @Schema(type = "string")),
                            @Header(name = "X-DWP-Home-Runtime-Mode", schema = @Schema(type = "string")),
                            @Header(name = "X-DWP-Home-Runtime-State", schema = @Schema(type = "string")),
                            @Header(name = "X-DWP-Home-Rollout-Ring", schema = @Schema(type = "string")),
                            @Header(name = "X-DWP-Home-Rollout-Revision", schema = @Schema(type = "string")),
                            @Header(name = "X-DWP-Home-Commands-Enabled", schema = @Schema(type = "boolean")),
                            @Header(name = "X-DWP-Widget-Registry-Authoritative", schema = @Schema(type = "boolean"))
                    }),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Invalid mode, device, locale or time zone", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "Trusted recipient identity is missing", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Current authority does not permit a requested source", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503", description = "Runtime or trusted authority is unavailable", content = @Content)
    })
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
            @RequestHeader("X-DWP-Home-Runtime-State") String runtimeState,
            @RequestHeader("X-DWP-Home-Rollout-Ring") String rolloutRing,
            @RequestHeader("X-DWP-Home-Rollout-Revision") String rolloutRevision,
            @RequestHeader(value = "Accept-Language", defaultValue = "ko-KR") String locale,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @RequestParam(required = false)
            @Pattern(regexp = "CLASSIC|FLOW_V1") String mode,
            @RequestParam
            @Pattern(regexp = "DESKTOP_WIDE|DESKTOP_STANDARD|MOBILE_STANDARD|MOBILE_COMPACT")
            String deviceClass,
            @RequestParam(defaultValue = "Asia/Seoul") String timeZone) {
        requireDeploymentAvailable();
        HomeRuntimeContext context = HomeRuntimeContext.create(
                tenantId, userId, personPublicId, permissions, roles, groupRefs,
                decisionRevision, revalidateAt, locale, timeZone);
        HomeRuntimeRolloutDecision.TrustedInput trustedRollout =
                HomeRuntimeRolloutDecision.TrustedInput.parse(
                        runtimeState, rolloutRing, rolloutRevision);
        HomeReadModelDtos.ReadResult result = service.read(
                context, trustedRollout, mode, deviceClass);
        HttpHeaders headers = responseHeaders(result.etag(), result.decision());
        if (matches(ifNoneMatch, result.etag())) {
            return new ResponseEntity<>(null, headers, HttpStatus.NOT_MODIFIED);
        }
        return new ResponseEntity<>(ApiResponse.success(result.model()), headers, HttpStatus.OK);
    }

    /** Direct-call compatibility for pre-Wave 6 tests; HTTP requests must supply trusted headers. */
    ResponseEntity<ApiResponse<HomeReadModelDtos.HomeReadModel>> read(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String permissions,
            String roles,
            String groupRefs,
            String decisionRevision,
            String revalidateAt,
            String locale,
            String ifNoneMatch,
            String mode,
            String deviceClass,
            String timeZone) {
        if (!properties.enabled() && !properties.shadowEnabled()) {
            throw new com.dwp.core.exception.BaseException(
                    com.dwp.core.common.ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "Home Runtime v2 is disabled for this deployment.");
        }
        HomeRuntimeContext context = HomeRuntimeContext.create(
                tenantId, userId, personPublicId, permissions, roles, groupRefs,
                decisionRevision, revalidateAt, locale, timeZone);
        HomeReadModelDtos.ReadResult result = service.read(context, mode, deviceClass);
        HttpHeaders headers = responseHeaders(result.etag(), result.decision());
        if (matches(ifNoneMatch, result.etag())) {
            return new ResponseEntity<>(null, headers, HttpStatus.NOT_MODIFIED);
        }
        return new ResponseEntity<>(ApiResponse.success(result.model()), headers, HttpStatus.OK);
    }

    @PostMapping("/widget-actions:execute")
    @Operation(
            operationId = "executeHomeWidgetActionV2",
            summary = "Execute an idempotent Home widget action",
            description = "Disabled until the Wave 6 command promotion gate. When enabled, owner-side "
                    + "idempotency and an audit receipt are mandatory.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "202", description = "Command accepted with an audit receipt",
                    useReturnTypeSchema = true,
                    headers = {
                            @Header(name = "Cache-Control", description = "private, no-store, max-age=0",
                                    schema = @Schema(type = "string")),
                            @Header(name = "X-DWP-Home-Runtime-Mode", schema = @Schema(type = "string")),
                            @Header(name = "X-DWP-Home-Runtime-State", schema = @Schema(type = "string")),
                            @Header(name = "X-DWP-Home-Rollout-Ring", schema = @Schema(type = "string")),
                            @Header(name = "X-DWP-Home-Rollout-Revision", schema = @Schema(type = "string")),
                            @Header(name = "X-DWP-Home-Commands-Enabled", schema = @Schema(type = "boolean")),
                            @Header(name = "X-DWP-Widget-Registry-Authoritative", schema = @Schema(type = "boolean"))
                    }),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Invalid action contract or idempotency key", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "Trusted recipient identity is missing", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Action is not authorized", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "Idempotency key was reused with a different command", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503", description = "Commands are disabled or the owner is unavailable", content = @Content)
    })
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
            @RequestHeader("X-DWP-Home-Runtime-State") String runtimeState,
            @RequestHeader("X-DWP-Home-Rollout-Ring") String rolloutRing,
            @RequestHeader("X-DWP-Home-Rollout-Revision") String rolloutRevision,
            @RequestHeader(value = "Accept-Language", defaultValue = "ko-KR") String locale,
            @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestParam(required = false)
            @Pattern(regexp = "CLASSIC|FLOW_V1") String mode,
            @RequestParam
            @Pattern(regexp = "DESKTOP_WIDE|DESKTOP_STANDARD|MOBILE_STANDARD|MOBILE_COMPACT")
            String deviceClass,
            @RequestParam(defaultValue = "Asia/Seoul") String timeZone,
            @Valid @RequestBody HomeReadModelDtos.CommandRequest request) {
        requireDeploymentAvailable();
        HomeRuntimeContext context = HomeRuntimeContext.create(
                tenantId, userId, personPublicId, permissions, roles, groupRefs,
                decisionRevision, revalidateAt, locale, timeZone);
        HomeRuntimeRolloutDecision.TrustedInput trustedRollout =
                HomeRuntimeRolloutDecision.TrustedInput.parse(
                        runtimeState, rolloutRing, rolloutRevision);
        HomeWidgetCommandService.ExecutionResult result = commands.execute(
                context, trustedRollout, mode, deviceClass, commandId, request);
        HttpHeaders headers = runtimeHeaders(result.decision());
        headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        return new ResponseEntity<>(
                ApiResponse.success(result.receipt()), headers, HttpStatus.ACCEPTED);
    }

    /** Direct-call compatibility for pre-Wave 6 tests; HTTP requests use the trusted overload. */
    ResponseEntity<ApiResponse<HomeReadModelDtos.CommandReceipt>> execute(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String permissions,
            String roles,
            String groupRefs,
            String decisionRevision,
            String revalidateAt,
            String locale,
            UUID commandId,
            String mode,
            String deviceClass,
            String timeZone,
            HomeReadModelDtos.CommandRequest request) {
        if (!properties.commandsEnabled()) {
            throw new com.dwp.core.exception.BaseException(
                    com.dwp.core.common.ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "Home Runtime commands remain disabled.");
        }
        HomeRuntimeContext context = HomeRuntimeContext.create(
                tenantId, userId, personPublicId, permissions, roles, groupRefs,
                decisionRevision, revalidateAt, locale, timeZone);
        HomeReadModelDtos.CommandReceipt receipt = commands.execute(
                context, mode, deviceClass, commandId, request);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        return new ResponseEntity<>(ApiResponse.success(receipt), headers, HttpStatus.ACCEPTED);
    }

    private HttpHeaders responseHeaders(
            String etag,
            HomeRuntimeRolloutDecision decision) {
        HttpHeaders headers = runtimeHeaders(decision);
        headers.setETag(etag);
        headers.set(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
        headers.set(HttpHeaders.VARY, VARY);
        return headers;
    }

    private void requireDeploymentAvailable() {
        if (!properties.enabled() && !properties.shadowEnabled()) {
            throw new com.dwp.core.exception.BaseException(
                    com.dwp.core.common.ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "Home Runtime v2 is disabled for this deployment.");
        }
    }

    private HttpHeaders runtimeHeaders(HomeRuntimeRolloutDecision decision) {
        HttpHeaders headers = new HttpHeaders();
        boolean active = decision.state().ordinal()
                >= HomeRuntimeRolloutDecision.State.READ_ONLY_ACTIVE.ordinal();
        headers.set("X-DWP-Home-Runtime-Mode", active ? "ACTIVE" : "SHADOW");
        headers.set("X-DWP-Home-Runtime-State", decision.state().name());
        headers.set("X-DWP-Home-Rollout-Ring", decision.ring().name());
        headers.set("X-DWP-Home-Rollout-Revision", decision.revision());
        headers.set("X-DWP-Home-Commands-Enabled",
                Boolean.toString(decision.commandsEnabled()));
        headers.set("X-DWP-Widget-Registry-Authoritative",
                Boolean.toString(decision.registryAuthoritative()));
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

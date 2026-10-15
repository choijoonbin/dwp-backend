package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.auth.config.HrisRecipientAuthoritySecurityConfig;
import com.dwp.services.auth.dto.HrisRecipientAuthorityDtos;
import com.dwp.services.auth.service.HrisRecipientAuthorityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/auth/v1/hris-recipient-authority")
public class HrisRecipientAuthorityController {

    private static final String UNAVAILABLE = "Recipient authority is unavailable.";

    private final HrisRecipientAuthorityService service;

    public HrisRecipientAuthorityController(HrisRecipientAuthorityService service) {
        this.service = service;
    }

    @PostMapping(
            path = "/resolve",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "resolveHrisRecipientAuthority",
            parameters = {
                    @Parameter(
                            name = HrisRecipientAuthoritySecurityConfig.TOKEN_HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", minLength = 1,
                                    maxLength = 2048)),
                    @Parameter(
                            name = HrisRecipientAuthoritySecurityConfig.SERVICE_IDENTITY_HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", allowableValues =
                                    HrisRecipientAuthoritySecurityConfig
                                            .PEOPLE_SERVICE_IDENTITY))
            })
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Exact active tenant recipient authority",
                    content = @Content(schema = @Schema(
                            implementation = HrisRecipientAuthorityDtos
                                    .ResolveResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Recipient authority is unavailable",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(ref = "#/components/schemas/ApiResponseVoid")))
    })
    public ResponseEntity<HrisRecipientAuthorityDtos.ResolveResponse> resolve(
            @Valid @RequestBody HrisRecipientAuthorityDtos.ResolveRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.resolve(request));
    }

    @ExceptionHandler(
            HrisRecipientAuthorityService.RecipientAuthorityUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.error(ErrorCode.NOT_FOUND, UNAVAILABLE));
    }
}

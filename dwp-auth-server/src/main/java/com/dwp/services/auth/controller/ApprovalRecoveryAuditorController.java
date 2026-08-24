package com.dwp.services.auth.controller;

import com.dwp.services.auth.dto.ApprovalRecoveryAuditorDtos;
import com.dwp.services.auth.service.ApprovalRecoveryAuditorRequestParser;
import com.dwp.services.auth.service.ApprovalRecoveryAuditorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/auth/v1/approval-recovery-auditor")
public class ApprovalRecoveryAuditorController {

    private final ApprovalRecoveryAuditorService service;
    private final ApprovalRecoveryAuditorRequestParser parser;

    public ApprovalRecoveryAuditorController(
            ApprovalRecoveryAuditorService service,
            ApprovalRecoveryAuditorRequestParser parser) {
        this.service = service;
        this.parser = parser;
    }

    @PostMapping("/resolve")
    @Operation(
            operationId = "resolveApprovalRecoveryAuditorInternal",
            parameters = {
                    @Parameter(
                            name = "X-DWP-Approval-Recovery-Token",
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", minLength = 1,
                                    maxLength = 512)),
                    @Parameter(
                            name = "X-DWP-Service-Identity",
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string",
                                    allowableValues = "dwp-approval-server"))
            },
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(
                            implementation = ApprovalRecoveryAuditorDtos.ResolveRequest.class))))
    public ApprovalRecoveryAuditorDtos.ResolveResponse resolve(
            @RequestBody String body) {
        return service.resolve(parser.parse(body));
    }
}

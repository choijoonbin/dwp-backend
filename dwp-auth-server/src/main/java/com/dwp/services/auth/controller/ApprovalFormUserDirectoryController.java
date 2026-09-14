package com.dwp.services.auth.controller;

import com.dwp.services.auth.dto.ApprovalFormUserDirectoryDtos.Response;
import com.dwp.services.auth.service.ApprovalFormUserDirectoryService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/auth/v1/approval-form-user-directory")
public class ApprovalFormUserDirectoryController {
    private final ApprovalFormUserDirectoryService service;

    public ApprovalFormUserDirectoryController(ApprovalFormUserDirectoryService service) { this.service = service; }

    @PostMapping(path = "/search", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "searchApprovalFormUserDirectoryInternal")
    public Response search(@RequestBody String body) { return service.search(body); }

    @PostMapping(path = "/resolve", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "resolveApprovalFormUserDirectoryInternal")
    public Response resolve(@RequestBody String body) { return service.resolve(body); }
}

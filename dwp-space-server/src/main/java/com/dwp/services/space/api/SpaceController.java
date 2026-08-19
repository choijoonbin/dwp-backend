package com.dwp.services.space.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.space.domain.SpaceDtos;
import com.dwp.services.space.domain.SpaceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1")
public class SpaceController {

    private final SpaceService service;

    public SpaceController(SpaceService service) {
        this.service = service;
    }

    @GetMapping("/home")
    public ApiResponse<SpaceDtos.HomeResponse> home() {
        return ApiResponse.success(service.home());
    }

    @GetMapping("/spaces")
    public ApiResponse<List<SpaceDtos.SpaceSummary>> spaces(
            @RequestParam(defaultValue = "MY") String scope,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(service.spaces(scope, q, limit));
    }

    @GetMapping("/spaces/{spaceKey}")
    public ApiResponse<SpaceDtos.SpaceDetail> space(@PathVariable String spaceKey) {
        return ApiResponse.success(service.space(spaceKey));
    }

    @GetMapping("/spaces/{spaceKey}/content")
    public ApiResponse<List<SpaceDtos.ContentSummary>> content(@PathVariable String spaceKey) {
        return ApiResponse.success(service.content(spaceKey));
    }

    @PostMapping("/spaces/{spaceKey}/content")
    public ApiResponse<SpaceDtos.ContentSummary> createContent(
            @PathVariable String spaceKey,
            @Valid @RequestBody SpaceDtos.CreateContentRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.createContent(spaceKey, request, correlationId));
    }

    @GetMapping("/spaces/{spaceKey}/owner/members")
    public ApiResponse<List<SpaceDtos.MemberSummary>> members(@PathVariable String spaceKey) {
        return ApiResponse.success(service.members(spaceKey));
    }

    @GetMapping("/access-requests")
    public ApiResponse<List<SpaceDtos.AccessRequestSummary>> accessRequests(
            @RequestParam(defaultValue = "ALL") String status) {
        return ApiResponse.success(service.myAccessRequests(status));
    }

    @PostMapping("/spaces/{spaceKey}/access-requests")
    public ApiResponse<SpaceDtos.AccessRequestSummary> requestAccess(
            @PathVariable String spaceKey,
            @Valid @RequestBody SpaceDtos.CreateAccessRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.requestAccess(spaceKey, request, correlationId));
    }

    @GetMapping("/spaces/{spaceKey}/owner/access-requests")
    public ApiResponse<List<SpaceDtos.AccessRequestSummary>> ownerAccessRequests(
            @PathVariable String spaceKey,
            @RequestParam(defaultValue = "ALL") String status) {
        return ApiResponse.success(service.ownerAccessRequests(spaceKey, status));
    }

    @PostMapping("/spaces/{spaceKey}/owner/access-requests/{requestId}/decision")
    public ApiResponse<Void> decideAccessRequest(
            @PathVariable String spaceKey,
            @PathVariable java.util.UUID requestId,
            @Valid @RequestBody SpaceDtos.AccessDecision request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        service.decideAccessRequest(spaceKey, requestId, request, correlationId);
        return ApiResponse.success();
    }

    @PostMapping("/spaces/{spaceKey}/owner/members")
    public ApiResponse<List<SpaceDtos.MemberSummary>> saveMember(
            @PathVariable String spaceKey,
            @Valid @RequestBody SpaceDtos.SaveMemberRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.saveMember(spaceKey, request, correlationId));
    }

    @PutMapping("/spaces/{spaceKey}/owner/members/{membershipId}")
    public ApiResponse<List<SpaceDtos.MemberSummary>> updateMember(
            @PathVariable String spaceKey,
            @PathVariable java.util.UUID membershipId,
            @Valid @RequestBody SpaceDtos.UpdateMemberRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(
                service.updateMember(spaceKey, membershipId, request, correlationId));
    }

    @DeleteMapping("/spaces/{spaceKey}/owner/members/{membershipId}")
    public ApiResponse<List<SpaceDtos.MemberSummary>> revokeMember(
            @PathVariable String spaceKey,
            @PathVariable java.util.UUID membershipId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.revokeMember(spaceKey, membershipId, correlationId));
    }

    @PutMapping("/spaces/{spaceKey}/owner/policies")
    public ApiResponse<SpaceDtos.SpaceDetail> updatePolicies(
            @PathVariable String spaceKey,
            @Valid @RequestBody SpaceDtos.UpdatePolicyRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.updatePolicies(spaceKey, request, correlationId));
    }

    @GetMapping("/templates")
    public ApiResponse<List<SpaceDtos.TemplateSummary>> templates() {
        return ApiResponse.success(service.templates(false));
    }

    @GetMapping("/requests")
    public ApiResponse<List<SpaceDtos.RequestSummary>> requests(
            @RequestParam(defaultValue = "ALL") String status) {
        return ApiResponse.success(service.myRequests(status));
    }

    @PostMapping("/requests")
    public ApiResponse<SpaceDtos.RequestSummary> createRequest(
            @Valid @RequestBody SpaceDtos.CreateSpaceRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.createRequest(request, correlationId));
    }
}

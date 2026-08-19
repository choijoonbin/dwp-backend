package com.dwp.services.messaging.collaboration;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

@RestController
@RequestMapping("/v1")
@Validated
public class CollaborationController {

    private final CollaborationService service;
    private final ConversationMembershipService membershipService;

    public CollaborationController(
            CollaborationService service,
            ConversationMembershipService membershipService) {
        this.service = service;
        this.membershipService = membershipService;
    }

    @PostMapping("/conversations")
    public ApiResponse<CollaborationDtos.ConversationCreationResponse> createConversation(
            @Valid @RequestBody CollaborationDtos.CreateConversationRequest request) {
        return ApiResponse.success(service.createConversation(request));
    }

    @GetMapping("/search")
    public ApiResponse<CollaborationDtos.SearchResponse> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "") String types,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(service.search(q, types, limit));
    }

    @GetMapping("/conversations/{conversationId}/members")
    public ApiResponse<CollaborationDtos.ConversationMembersResponse> members(
            @PathVariable UUID conversationId) {
        return ApiResponse.success(membershipService.members(conversationId));
    }

    @PostMapping("/conversations/{conversationId}/members")
    public ApiResponse<CollaborationDtos.MembershipMutationResponse> addMember(
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody CollaborationDtos.AddConversationMemberRequest request) {
        return ApiResponse.success(
                membershipService.addMember(conversationId, request, correlationId));
    }

    @PutMapping("/conversations/{conversationId}/members/{userId}/role")
    public ApiResponse<CollaborationDtos.MembershipMutationResponse> updateMemberRole(
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID conversationId,
            @PathVariable long userId,
            @Valid @RequestBody CollaborationDtos.UpdateConversationMemberRoleRequest request) {
        return ApiResponse.success(
                membershipService.updateRole(conversationId, userId, request, correlationId));
    }

    @DeleteMapping("/conversations/{conversationId}/members/{userId}")
    public ApiResponse<CollaborationDtos.MembershipMutationResponse> removeMember(
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID conversationId,
            @PathVariable long userId,
            @RequestParam @PositiveOrZero long version) {
        return ApiResponse.success(
                membershipService.removeMember(conversationId, userId, version, correlationId));
    }

    @PostMapping("/conversations/{conversationId}/leave")
    public ApiResponse<CollaborationDtos.MembershipMutationResponse> leave(
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody CollaborationDtos.LeaveConversationRequest request) {
        return ApiResponse.success(
                membershipService.leave(conversationId, request, correlationId));
    }
}

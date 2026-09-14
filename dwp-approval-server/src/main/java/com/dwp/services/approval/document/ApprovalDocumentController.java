package com.dwp.services.approval.document;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.dwp.services.approval.document.ApprovalDocumentDtos.*;

@RestController
@RequestMapping("/v1")
public class ApprovalDocumentController {
    private final ApprovalDocumentService documents;
    public ApprovalDocumentController(ApprovalDocumentService documents) { this.documents = documents; }
    @GetMapping("/requests/{requestId}/document-tools")
    public ApiResponse<Tools> requestTools(@PathVariable UUID requestId) { return ApiResponse.success(documents.tools(OwnerType.REQUEST, requestId)); }
    @GetMapping("/tasks/{taskId}/document-tools")
    public ApiResponse<Tools> taskTools(@PathVariable UUID taskId) { return ApiResponse.success(documents.tools(OwnerType.TASK, taskId)); }
    @GetMapping("/requests/{requestId}/comments")
    public ApiResponse<Comments> requestComments(@PathVariable UUID requestId, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size) { return ApiResponse.success(documents.comments(OwnerType.REQUEST, requestId, page, size)); }
    @GetMapping("/tasks/{taskId}/comments")
    public ApiResponse<Comments> taskComments(@PathVariable UUID taskId, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size) { return ApiResponse.success(documents.comments(OwnerType.TASK, taskId, page, size)); }
    @PostMapping("/requests/{requestId}/comments")
    public ApiResponse<Comment> appendRequestComment(@PathVariable UUID requestId, @Valid @RequestBody AppendComment input) {
        return ApiResponse.success(documents.append(OwnerType.REQUEST, requestId, input));
    }
    @PostMapping("/tasks/{taskId}/comments")
    public ApiResponse<Comment> appendTaskComment(@PathVariable UUID taskId, @Valid @RequestBody AppendComment input) {
        return ApiResponse.success(documents.append(OwnerType.TASK, taskId, input));
    }
    @PostMapping("/requests/{requestId}/document-exports")
    public ApiResponse<GeneratedDocument> exportRequest(@PathVariable UUID requestId, @Valid @RequestBody Export input) {
        return ApiResponse.success(documents.export(OwnerType.REQUEST, requestId, input));
    }
    @PostMapping("/tasks/{taskId}/document-exports")
    public ApiResponse<GeneratedDocument> exportTask(@PathVariable UUID taskId, @Valid @RequestBody Export input) {
        return ApiResponse.success(documents.export(OwnerType.TASK, taskId, input));
    }
    @PostMapping("/requests/archive/document-exports")
    public ApiResponse<GeneratedDocument> archive(@Valid @RequestBody ArchiveExport input) { return ApiResponse.success(documents.archive(input)); }
}

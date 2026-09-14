package com.dwp.services.approval.attachment;

import com.dwp.core.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static com.dwp.services.approval.attachment.ApprovalAttachmentDtos.*;
import static com.dwp.services.approval.document.ApprovalDocumentDtos.OwnerType;

@RestController
@RequestMapping("/v1")
public class ApprovalAttachmentController {
    private final ApprovalAttachmentIntakeCommands commands;
    private final ApprovalAttachmentIntake intake;
    private final ApprovalAttachmentViews views;
    private final ApprovalAttachmentDownloadCommands grants;
    private final ApprovalAttachmentDownloads downloads;
    public ApprovalAttachmentController(ApprovalAttachmentIntakeCommands commands,ApprovalAttachmentIntake intake,ApprovalAttachmentViews views,
            ApprovalAttachmentDownloadCommands grants,ApprovalAttachmentDownloads downloads){this.commands=commands;this.intake=intake;this.views=views;this.grants=grants;this.downloads=downloads;}
    @PostMapping("/requests/{requestId}/attachment-uploads") public ApiResponse<Upload> reserve(@PathVariable UUID requestId,@Valid @RequestBody Reserve input){return ApiResponse.success(commands.reserve(requestId,input));}
    @GetMapping("/attachment-uploads/{uploadId}") public ApiResponse<Upload> status(@PathVariable UUID uploadId){return ApiResponse.success(commands.status(uploadId));}
    @PutMapping(value="/attachment-uploads/{uploadId}/content",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @io.swagger.v3.oas.annotations.Operation(requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,
            content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/octet-stream",schema=@io.swagger.v3.oas.annotations.media.Schema(type="string",format="binary"))))
    public ApiResponse<Upload> upload(@PathVariable UUID uploadId,@RequestHeader("X-DWP-Expected-Object-Version") Long version,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) throws IOException {
        return ApiResponse.success(intake.upload(uploadId,version,key,request.getInputStream()));
    }
    @PostMapping("/attachment-uploads/{uploadId}/reconcile") public ApiResponse<Upload> reconcile(@PathVariable UUID uploadId,@Valid @RequestBody Cancel input){return ApiResponse.success(intake.reconcile(uploadId,input.expectedVersion(),input.idempotencyKey()));}
    @PostMapping("/attachment-uploads/{uploadId}/cancel") public ApiResponse<Upload> cancel(@PathVariable UUID uploadId,@Valid @RequestBody Cancel input){return ApiResponse.success(commands.cancel(uploadId,input));}
    @PutMapping("/requests/{requestId}/attachments") public ApiResponse<Attachments> select(@PathVariable UUID requestId,@Valid @RequestBody Selection input){return ApiResponse.success(views.select(requestId,input));}
    @GetMapping("/requests/{requestId}/attachments") public ApiResponse<Attachments> request(@PathVariable UUID requestId){return ApiResponse.success(views.read(OwnerType.REQUEST,requestId));}
    @GetMapping("/tasks/{taskId}/attachments") public ApiResponse<Attachments> task(@PathVariable UUID taskId){return ApiResponse.success(views.read(OwnerType.TASK,taskId));}
    @PostMapping("/requests/{requestId}/attachments/{attachmentId}/downloads") public ApiResponse<Grant> requestGrant(@PathVariable UUID requestId,@PathVariable UUID attachmentId,@Valid @RequestBody Download input){return ApiResponse.success(grants.issue(OwnerType.REQUEST,requestId,attachmentId,input));}
    @PostMapping("/tasks/{taskId}/attachments/{attachmentId}/downloads") public ApiResponse<Grant> taskGrant(@PathVariable UUID taskId,@PathVariable UUID attachmentId,@Valid @RequestBody Download input){return ApiResponse.success(grants.issue(OwnerType.TASK,taskId,attachmentId,input));}
    @GetMapping(value="/attachment-downloads/{grantId}/content",produces=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @io.swagger.v3.oas.annotations.Operation(responses=@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",
            content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/octet-stream",
                    schema=@io.swagger.v3.oas.annotations.media.Schema(type="string",format="binary"))))
    public ResponseEntity<byte[]> content(@PathVariable UUID grantId){
        var result=downloads.load(grantId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(result.fileName(),StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options","nosniff").header("X-Content-SHA256",result.sha256()).contentLength(result.bytes().length).body(result.bytes());
    }
}

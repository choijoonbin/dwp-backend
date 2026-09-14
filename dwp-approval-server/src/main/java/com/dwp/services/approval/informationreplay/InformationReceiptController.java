package com.dwp.services.approval.informationreplay;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InformationReceiptController {
    private final InformationReceiptFacade facade;
    public InformationReceiptController(InformationReceiptFacade facade) {this.facade=facade;}
    @Operation(summary="Read a verified completed information-command receipt without executing the command")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,
            content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=InformationReceiptBody.class)))
    @PostMapping(value="/v1/requests/{requestId}/information-commands/{originalKey}/receipt",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<InformationCommandReceipt> receipt(@PathVariable UUID requestId,@PathVariable String originalKey,
            HttpServletRequest request) throws java.io.IOException {
        byte[] body=request.getInputStream().readNBytes(InformationReceiptBody.LOOKUP_MAX+1);
        return ApiResponse.success(facade.read(request,requestId,originalKey,body));
    }
}

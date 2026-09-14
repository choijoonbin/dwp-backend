package com.dwp.services.approval.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.*;
import com.dwp.services.approval.forms.ApprovalFormLifecycleFacade;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/admin/forms/{formId}")
public class ApprovalFormLifecycleController {
    private final ApprovalFormLifecycleFacade forms;
    public ApprovalFormLifecycleController(ApprovalFormLifecycleFacade forms) { this.forms=forms; }
    @GetMapping("/versions") public ApiResponse<History> history(@PathVariable UUID formId,
            @RequestParam(defaultValue="50") int size,HttpServletRequest http) {
        query(http,Set.of("size"));return ApiResponse.success(forms.history(formId,size));
    }
    @GetMapping("/versions/{formVersionId}") public ApiResponse<Version> version(@PathVariable UUID formId,
            @PathVariable UUID formVersionId,HttpServletRequest http) {
        query(http,Set.of());return ApiResponse.success(forms.version(formId,formVersionId));
    }
    @GetMapping("/diff") public ApiResponse<Diff> diff(@PathVariable UUID formId,@RequestParam UUID fromVersionId,
            @RequestParam UUID toVersionId,HttpServletRequest http) {
        query(http,Set.of("fromVersionId","toVersionId"));return ApiResponse.success(forms.diff(formId,fromVersionId,toVersionId));
    }
    @GetMapping("/working-draft") public ApiResponse<Workspace> draft(@PathVariable UUID formId,HttpServletRequest http) {
        query(http,Set.of());return ApiResponse.success(forms.workingDraft(formId));
    }
    @Operation(parameters=@Parameter(name="Idempotency-Key",in=ParameterIn.HEADER,required=true,schema=@Schema(type="string",maxLength=200)))
    @PostMapping("/versions/{formVersionId}/branch") public ApiResponse<Workspace> branch(@PathVariable UUID formId,
            @PathVariable UUID formVersionId,@Valid @RequestBody Branch body,HttpServletRequest http) {
        query(http,Set.of());return ApiResponse.success(forms.branch(formId,formVersionId,body,key(http),correlation(http)));
    }
    @Operation(parameters=@Parameter(name="Idempotency-Key",in=ParameterIn.HEADER,required=true,schema=@Schema(type="string",maxLength=200)))
    @PutMapping("/working-draft") public ApiResponse<Workspace> update(@PathVariable UUID formId,
            @Valid @RequestBody UpdateWorkingDraft body,HttpServletRequest http) {
        query(http,Set.of());return ApiResponse.success(forms.update(formId,body,key(http),correlation(http)));
    }
    @Operation(parameters=@Parameter(name="Idempotency-Key",in=ParameterIn.HEADER,required=true,schema=@Schema(type="string",maxLength=200)))
    @PostMapping("/retire") public ApiResponse<Workspace> retire(@PathVariable UUID formId,
            @Valid @RequestBody AvailabilityChange body,HttpServletRequest http) {
        query(http,Set.of());return ApiResponse.success(forms.retire(formId,body,key(http),correlation(http)));
    }
    @Operation(parameters=@Parameter(name="Idempotency-Key",in=ParameterIn.HEADER,required=true,schema=@Schema(type="string",maxLength=200)))
    @PostMapping("/reinstate") public ApiResponse<Workspace> reinstate(@PathVariable UUID formId,
            @Valid @RequestBody AvailabilityChange body,HttpServletRequest http) {
        query(http,Set.of());return ApiResponse.success(forms.reinstate(formId,body,key(http),correlation(http)));
    }
    @GetMapping("/publish-review") public ApiResponse<Review> review(@PathVariable UUID formId,HttpServletRequest http) {
        query(http,Set.of());return ApiResponse.success(forms.review(formId));
    }
    @Operation(parameters={
            @Parameter(name="Idempotency-Key",in=ParameterIn.HEADER,required=true,schema=@Schema(type="string",maxLength=200)),
            @Parameter(name="X-DWP-Step-Up-Challenge",in=ParameterIn.HEADER,required=true,schema=@Schema(type="string",maxLength=16384)),
            @Parameter(name="X-DWP-Expected-Decision-Revision",in=ParameterIn.HEADER,required=true,schema=@Schema(type="string")),
            @Parameter(name="X-DWP-Expected-Object-Version",in=ParameterIn.HEADER,required=true,
                    schema=@Schema(type="integer",format="int64",minimum="0",maximum="9007199254740991"))})
    @PostMapping("/publish-reviewed") public ApiResponse<Workspace> publish(@PathVariable UUID formId,
            @Valid @RequestBody PublishReviewed body,HttpServletRequest http) {
        query(http,Set.of());
        var headers=new ApprovalStepUpHeaders(header(http,"X-DWP-Step-Up-Challenge",true),key(http),
                header(http,"X-DWP-Expected-Decision-Revision",true),number(header(http,"X-DWP-Expected-Object-Version",true)));
        return ApiResponse.success(forms.publish(formId,body,headers,correlation(http)));
    }
    private String key(HttpServletRequest http) { return header(http,"Idempotency-Key",true); }
    private String correlation(HttpServletRequest http) { return header(http,"X-Correlation-ID",false); }
    private String header(HttpServletRequest http,String name,boolean required) {
        var values=Collections.list(http.getHeaders(name));
        if(values.isEmpty()&&!required) return null;
        if(values.size()!=1||values.getFirst().isBlank()||!values.getFirst().equals(values.getFirst().trim())) throw invalid();
        return values.getFirst();
    }
    private Long number(String value) {
        if(!value.matches("0|[1-9][0-9]{0,15}")) throw invalid();
        try { long revision=Long.parseLong(value);if(revision>9007199254740991L) throw invalid();return revision; }
        catch(NumberFormatException exception) { throw invalid(); }
    }
    private void query(HttpServletRequest http,Set<String> allowed) {
        if(!allowed.containsAll(http.getParameterMap().keySet())||http.getParameterMap().values().stream().anyMatch(value->value.length!=1)) throw invalid();
        Object variables=http.getAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if(!(variables instanceof java.util.Map<?,?> paths)||paths.values().stream().anyMatch(value->!canonicalUuid(value))) throw invalid();
        for(String name:Set.of("fromVersionId","toVersionId")) {
            String value=http.getParameter(name);if(value!=null&&!canonicalUuid(value)) throw invalid();
        }
    }
    private boolean canonicalUuid(Object value) { return value instanceof String text&&text.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"); }
    private BaseException invalid() { return new BaseException(ErrorCode.INVALID_INPUT_VALUE); }
}

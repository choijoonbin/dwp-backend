package com.dwp.services.approval.informationreplay;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.denied;

import com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Base64;
import java.util.Set;

/** Original bytes are transport input, not authority. All digests are computed by the server. */
@Schema(name="ApprovalInformationReceiptBody",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record InformationReceiptBody(
        @NotBlank @Pattern(regexp="REQUEST_INFO|REPLY") @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"REQUEST_INFO","REPLY"}) String operation,
        @NotBlank @Size(max=349528) @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Canonical padded Base64 of the exact original HTTP JSON body, at most 262144 decoded bytes") String originalBodyBase64) {
    public static final int ORIGINAL_MAX=262144,LOOKUP_MAX=350000;
    public static InformationReceiptBody parse(byte[] original) {
        var json=new WorkflowRuntimeJson();var node=json.parse(original,LOOKUP_MAX);exact(node,Set.of("operation","originalBodyBase64"));
        var body=new InformationReceiptBody(text(node,"operation",12),text(node,"originalBodyBase64",349528));body.originalBytes();return body;
    }
    public byte[] originalBytes() {
        if(operation==null || !Set.of("REQUEST_INFO","REPLY").contains(operation) || originalBodyBase64==null || originalBodyBase64.isEmpty()
                || originalBodyBase64.length()>349528 || originalBodyBase64.length()%4!=0) throw denied();
        try {
            byte[] bytes=Base64.getDecoder().decode(originalBodyBase64);
            if(bytes.length==0 || bytes.length>ORIGINAL_MAX || !Base64.getEncoder().encodeToString(bytes).equals(originalBodyBase64)) throw denied();
            var json=new WorkflowRuntimeJson();var parsed=json.parse(bytes,ORIGINAL_MAX);
            if(!parsed.isObject()) throw denied();bounded(parsed);json.bytes(parsed);return bytes;
        } catch(IllegalArgumentException error) {throw denied();}
    }
    private static void bounded(com.fasterxml.jackson.databind.JsonNode root) {
        record Node(com.fasterxml.jackson.databind.JsonNode value,int depth) { }
        var remaining=new java.util.ArrayDeque<Node>();remaining.add(new Node(root,0));int count=0,characters=0;
        while(!remaining.isEmpty()) {
            var next=remaining.removeLast();var value=next.value();
            if(++count>50000 || next.depth()>32) throw denied();
            if(value.isTextual()) characters+=value.textValue().length();
            if(value.isObject()) {var fields=value.fields();while(fields.hasNext()) {var field=fields.next();characters+=field.getKey().length();remaining.add(new Node(field.getValue(),next.depth()+1));}}
            else if(value.isArray()) for(var child:value) remaining.add(new Node(child,next.depth()+1));
            if(characters>200000) throw denied();
        }
    }
}

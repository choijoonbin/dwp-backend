package com.dwp.services.approval.workflowplanning;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.denied;

import com.dwp.services.approval.domain.ApprovalWorkflowStudioSource;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Schema(name="ApprovalWorkflowPlanningBody",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record WorkflowPlanningBody(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotNull @Min(0) Long workflowRevision,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank @Pattern(regexp="[a-f0-9]{64}") String workflowSha256,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotNull UUID formVersionId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank @Pattern(regexp="[a-f0-9]{64}") String formSchemaSha256,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotNull @Min(1) Long policyVersion,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank @Pattern(regexp="[a-f0-9]{64}") String policySha256,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{2,79}") String managementResourceSetKey,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotNull @Size(max=100) Map<String,Object> samplePayload) {
    static WorkflowPlanningBody parse(byte[] raw) {
        if(raw==null || raw.length==0 || raw.length>WorkflowPlanningProtocol.LOOKUP_MAX) throw denied();
        var mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        final com.fasterxml.jackson.databind.JsonNode body;
        try {body=mapper.readTree(raw);} catch(java.io.IOException invalid) {throw denied();}
        exact(body,Set.of("workflowRevision","workflowSha256","formVersionId","formSchemaSha256","policyVersion","policySha256","managementResourceSetKey","samplePayload"));
        var sample=body.get("samplePayload");if(!sample.isObject() || sample.size()>100) throw denied();
        Map<String,Object> values=mapper.convertValue(sample,new TypeReference<>(){});
        return new WorkflowPlanningBody(integer(body,"workflowRevision",0),hash(body,"workflowSha256"),uuid(body,"formVersionId"),
                hash(body,"formSchemaSha256"),integer(body,"policyVersion",1),hash(body,"policySha256"),text(body,"managementResourceSetKey",80),values);
    }
    ApprovalWorkflowStudioSource.Selection selection(UUID workflowId,UUID versionId) {
        if(managementResourceSetKey==null || !managementResourceSetKey.matches("[A-Z][A-Z0-9_]{2,79}")) throw denied();
        return new ApprovalWorkflowStudioSource.Selection(workflowId,workflowRevision,versionId,workflowSha256,formVersionId,
                formSchemaSha256,policyVersion,policySha256,managementResourceSetKey,samplePayload);
    }
}

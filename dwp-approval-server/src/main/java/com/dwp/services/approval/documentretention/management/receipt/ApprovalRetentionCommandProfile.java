package com.dwp.services.approval.documentretention.management.receipt;

import com.dwp.services.approval.documentretention.management.ApprovalRetentionDtos;
import com.dwp.services.approval.documentretention.management.ApprovalRetentionErrors;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The original typed body uses the existing StepUp digest, not a reconstructed receipt fingerprint. */
@Component
public final class ApprovalRetentionCommandProfile {
    public static final String ALGORITHM="APPROVAL_STEP_UP_TYPED_JSON_SHA256_V1";
    public static final String VERSION="RETENTION_COMMAND_RECEIPT_STEP_UP_TYPED_JSON_V1";
    private static final long MAX_SAFE_INTEGER=9_007_199_254_740_991L;
    private final ApprovalStepUpVerifier verifier;
    private final Validator validator;
    private final ObjectMapper mapper;

    public ApprovalRetentionCommandProfile(ApprovalStepUpVerifier verifier,Validator validator,ObjectMapper mapper) {
        this.verifier=verifier;this.validator=validator;this.mapper=mapper;
    }

    public enum Operation {
        INITIALIZE_POLICY("ADMIN.APPROVAL_POLICY:UPDATE","approvals.policy.update"),
        SAVE_POLICY("ADMIN.APPROVAL_POLICY:UPDATE","approvals.policy.update"),
        PUBLISH_POLICY("ADMIN.APPROVAL_POLICY:PUBLISH","approvals.policy.publish"),
        CLAIM_RECORD("ADMIN.APPROVAL_OPERATIONS:EXECUTE","approvals.operations.execute");

        private final String permission;
        private final String capability;
        Operation(String permission,String capability) {this.permission=permission;this.capability=capability;}
        public String permission() {return permission;}
        public String capability() {return capability;}
        public boolean high() {return this==PUBLISH_POLICY || this==CLAIM_RECORD;}
        public String nativePath(UUID target) {
            if((this==INITIALIZE_POLICY)!=(target==null)) throw ApprovalRetentionErrors.invalid();
            return switch(this) {
                case INITIALIZE_POLICY -> "/v1/admin/retention/policies";
                case SAVE_POLICY -> "/v1/admin/retention/policies/"+target+"/draft";
                case PUBLISH_POLICY -> "/v1/admin/retention/policies/"+target+"/publish";
                case CLAIM_RECORD -> "/v1/admin/retention/records/"+target+"/claims";
            };
        }
    }

    public record Prepared(Operation operation,UUID originalTargetId,String nativePath,
            String idempotencyKey,String requestBodySha256,Long originalExpectedVersion,String algorithm) {}

    public Prepared prepare(Operation operation,UUID target,Object originalBody) {
        if(operation==null || originalBody==null) throw ApprovalRetentionErrors.invalid();
        String path=operation.nativePath(target);
        String key;
        Long version;
        switch(operation) {
            case INITIALIZE_POLICY -> {
                if(!(originalBody instanceof ApprovalRetentionDtos.InitializePolicy body)) throw ApprovalRetentionErrors.invalid();
                key=body.idempotencyKey();version=null;
            }
            case SAVE_POLICY -> {
                if(!(originalBody instanceof ApprovalRetentionDtos.SavePolicy body)) throw ApprovalRetentionErrors.invalid();
                key=body.idempotencyKey();version=body.expectedVersion();
            }
            case PUBLISH_POLICY -> {
                if(!(originalBody instanceof ApprovalRetentionDtos.PublishPolicy body)) throw ApprovalRetentionErrors.invalid();
                key=body.idempotencyKey();version=body.expectedVersion();
            }
            case CLAIM_RECORD -> {
                if(!(originalBody instanceof ApprovalRetentionDtos.CreateClaim body)) throw ApprovalRetentionErrors.invalid();
                key=body.idempotencyKey();version=body.expectedVersion();
            }
            default -> throw ApprovalRetentionErrors.invalid();
        }
        if(!validator.validate(originalBody).isEmpty()) throw ApprovalRetentionErrors.invalid();
        safeScalars(mapper.valueToTree(originalBody));
        if((operation==Operation.SAVE_POLICY || operation==Operation.PUBLISH_POLICY) && version==MAX_SAFE_INTEGER) {
            throw ApprovalRetentionErrors.invalid();
        }
        return new Prepared(operation,target,path,key,verifier.payloadSha256(originalBody),version,ALGORITHM);
    }

    private void safeScalars(JsonNode value) {
        if(value.isNumber() && (!value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue()<0 || value.longValue()>MAX_SAFE_INTEGER)) throw ApprovalRetentionErrors.invalid();
        if(value.isTextual()) safeText(value.textValue());
        if(value.isContainerNode()) value.forEach(this::safeScalars);
    }

    public void validateText(Object originalBody) {
        if(originalBody==null) throw ApprovalRetentionErrors.invalid();
        textScalars(mapper.valueToTree(originalBody));
    }

    private void textScalars(JsonNode value) {
        if(value.isTextual()) safeText(value.textValue());
        if(value.isContainerNode()) value.forEach(this::textScalars);
    }

    private void safeText(String value) {
        for(int i=0;i<value.length();i++) {
            char current=value.charAt(i);
            if(current==0) throw ApprovalRetentionErrors.invalid();
            if(Character.isHighSurrogate(current)) {
                if(i+1>=value.length() || !Character.isLowSurrogate(value.charAt(i+1))) throw ApprovalRetentionErrors.invalid();
                i++;
            } else if(Character.isLowSurrogate(current)) throw ApprovalRetentionErrors.invalid();
        }
    }

    public Operation operation(String name) {
        if(name==null) throw ApprovalRetentionErrors.unavailable();
        try {return Operation.valueOf(name);}
        catch(IllegalArgumentException invalid) {throw ApprovalRetentionErrors.unavailable();}
    }
}

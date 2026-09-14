package com.dwp.services.approval.documentretention.management.receipt;

import com.dwp.services.approval.documentretention.management.ApprovalRetentionErrors;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import static com.dwp.services.approval.documentretention.management.receipt.ApprovalRetentionCommandProfile.Operation;

/** A closed, versioned parser. Stored context is evidence, never fresh entitlement. */
@Component
public final class ApprovalRetentionReceiptProfileParser {
    private static final long MAX_SAFE=9_007_199_254_740_991L;
    private static final Set<String> KEYS=Set.of("algorithm","operation","permission","capability","tenantId","actorUserId",
            "resourceSetKey","idempotencyKey","requestBodySha256","originalTargetId","originalExpectedVersion",
            "originPlane","decisionRevision","originalPublication","verifiedHighBinding");
    private static final Set<String> BINDING_KEYS=Set.of("actorUserId","tenantId","commandContractKey","contextKey",
            "activationPolicy","capabilityContractKey","scopeRef","targetType","targetId","targetVersion",
            "commandMethod","commandPath","idempotencyKey","payloadSha256","decisionRevision");

    public String validate(JsonNode source,ApprovalRequestContext.Actor actor,String scope,Operation operation,
            UUID target,String key,String body,Long expected) {
        closed(source,KEYS);bounded(source,0);
        equal(source,"algorithm",ApprovalRetentionCommandProfile.ALGORITHM);equal(source,"operation",operation.name());
        equal(source,"permission",operation.permission());equal(source,"capability",operation.capability());
        integer(source,"tenantId",actor.tenantId());integer(source,"actorUserId",actor.userId());
        equal(source,"resourceSetKey",scope);equal(source,"idempotencyKey",key);equal(source,"requestBodySha256",body);
        if(target==null) nullField(source,"originalTargetId");else equal(source,"originalTargetId",target.toString());
        if(expected==null) nullField(source,"originalExpectedVersion");else integer(source,"originalExpectedVersion",expected);
        if(!source.path("originPlane").isTextual() || !Set.of("TRUSTED_COMPAT","000","100","110","111").contains(source.path("originPlane").textValue())) fail();
        if(!source.path("decisionRevision").isNull() && (!source.path("decisionRevision").isTextual()
                || !source.path("decisionRevision").textValue().matches("psr-[a-f0-9]{64}"))) fail();
        if(!operation.high()) {
            nullField(source,"originalPublication");nullField(source,"verifiedHighBinding");return "POLICY_UPDATE_TRUSTED";
        }
        if(!Set.of("110","111").contains(source.path("originPlane").textValue()) || source.path("decisionRevision").isNull()) fail();
        var binding=source.path("verifiedHighBinding");closed(binding,BINDING_KEYS);
        integer(binding,"actorUserId",actor.userId());integer(binding,"tenantId",actor.tenantId());
        equal(binding,"targetId",target.toString());integer(binding,"targetVersion",expected);
        equal(binding,"capabilityContractKey",operation.capability());equal(binding,"activationPolicy","STEPUP-MGMT-HIGH-V1");
        equal(binding,"commandMethod","POST");equal(binding,"commandPath","/api/approvals"+operation.nativePath(target));
        equal(binding,"idempotencyKey",key);equal(binding,"payloadSha256",body);
        equal(binding,"decisionRevision",source.path("decisionRevision").textValue());
        equal(binding,"commandContractKey",operation==Operation.PUBLISH_POLICY?
                "route.approvals.admin.retention-policy-publish.action":"route.approvals.admin.retention-record-claim.action");
        equal(binding,"targetType",operation==Operation.PUBLISH_POLICY?"RETENTION_POLICY":"RETENTION_RECORD");
        for(String field:Set.of("scopeRef","contextKey")) if(!binding.path(field).isTextual() || binding.path(field).textValue().isBlank()) fail();
        if(operation==Operation.PUBLISH_POLICY) {
            var publication=source.path("originalPublication");closed(publication,Set.of("makerUserId","revision"));
            long maker=number(publication.path("makerUserId"));
            if(maker<1 || maker==actor.userId() || number(publication.path("revision"))<1) fail();
            return "POLICY_PUBLISH_SIGNED_HIGH_INDEPENDENT_CHECKER";
        }
        nullField(source,"originalPublication");return "RETENTION_RECORD_EXECUTE_SIGNED_HIGH";
    }
    private void closed(JsonNode value,Set<String> expected) {
        if(!value.isObject()) fail();var actual=new HashSet<String>();value.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(expected)) fail();
    }
    private void bounded(JsonNode value,int depth) {
        if(depth>5 || (value.isTextual() && value.textValue().length()>512)) fail();
        if(value.isNumber()) number(value);
        if(value.isArray() || value.isBoolean() || value.isMissingNode()) fail();
        if(value.isContainerNode()) value.forEach(child->bounded(child,depth+1));
    }
    private long number(JsonNode value) {
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<0 || value.longValue()>MAX_SAFE) fail();
        return value.longValue();
    }
    private void integer(JsonNode value,String key,Long expected) {if(expected==null || number(value.path(key))!=expected) fail();}
    private void equal(JsonNode value,String key,String expected) {if(!value.path(key).isTextual() || !java.util.Objects.equals(value.path(key).textValue(),expected)) fail();}
    private void nullField(JsonNode value,String key) {if(!value.path(key).isNull()) fail();}
    private void fail() {throw ApprovalRetentionErrors.unavailable();}
}

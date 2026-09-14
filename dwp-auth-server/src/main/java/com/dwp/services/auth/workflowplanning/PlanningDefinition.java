package com.dwp.services.auth.workflowplanning;

import static com.dwp.services.auth.workflowplanning.PlanningJson.*;
import static com.dwp.services.auth.workflowplanning.PlanningProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Validates the exact compiler-shaped owner source, without treating its preview as voter eligibility. */
final class PlanningDefinition {
    private PlanningDefinition() { }
    static void validate(PlanningJson json, JsonNode source, long tenant, UUID workflow, UUID version, UUID form, String rs) {
        var snapshot=source.get("snapshot");
        exact(snapshot, Set.of("tenantId", "workflowId", "workflowVersionId", "formVersionId", "workflow_revision", "workflow_head",
                "workflow_parent_state", "workflow_version", "workflow_state", "form_revision", "form_head", "form_parent_state",
                "form_version", "form_state", "formId", "workflowDefinitionSha256", "formSchemaSha256", "managementResourceSetKey",
                "policyVersion", "policySha256", "policyReferences", "samplePayload", "samplePayloadSha256", "effectiveFrom", "effectiveTo"));
        if (tenant!=integer(snapshot,"tenantId",1) || !workflow.equals(uuid(snapshot,"workflowId")) || !version.equals(uuid(snapshot,"workflowVersionId"))
                || !form.equals(uuid(snapshot,"formVersionId")) || !rs.equals(text(snapshot,"managementResourceSetKey",80))) throw denied();
        uuid(snapshot,"formId"); hash(snapshot,"formSchemaSha256");
        for (String prefix : Set.of("workflow", "form")) {
            integer(snapshot,prefix+"_revision",0); long head=integer(snapshot,prefix+"_head",1), selected=integer(snapshot,prefix+"_version",1);
            String state=text(snapshot,prefix+"_state",20);
            if (!Set.of("DRAFT","PUBLISHED").contains(state) || !Set.of("DRAFT","PUBLISHED").contains(text(snapshot,prefix+"_parent_state",20))
                    || selected>head || "DRAFT".equals(state) && selected!=head) throw denied();
        }
        for (String key : Set.of("effectiveFrom", "effectiveTo")) {
            var value=snapshot.get(key); if (!value.isNull()) {
                try { java.time.Instant.parse(text(snapshot,key,40)); } catch (java.time.DateTimeException invalid) { throw denied(); }
            }
        }
        var sample=snapshot.get("samplePayload"); if (!sample.isObject() || !json.digest(sample).equals(hash(snapshot,"samplePayloadSha256"))) throw denied();
        var refs=snapshot.get("policyReferences"); if (!refs.isArray() || refs.size()!=3
                || !json.digest(java.util.Map.of("references",refs)).equals(hash(snapshot,"policySha256"))) throw denied();
        var policies=new HashSet<String>(); String previous=""; long sla=-1;
        for (var ref : refs) {
            exact(ref,Set.of("policyId","key","rowVersion","enforcement","rule")); uuid(ref,"policyId");
            String key=text(ref,"key",50); long rowVersion=integer(ref,"rowVersion",0);
            if (!Set.of("BLOCK_SELF_APPROVAL","REQUIRE_REJECT_REASON","SLA_ESCALATION").contains(key) || !policies.add(key)
                    || key.compareTo(previous)<=0 || !ref.get("rule").isObject()) throw denied();
            text(ref,"enforcement",30); previous=key; if (key.equals("SLA_ESCALATION")) sla=rowVersion;
        }
        if (integer(snapshot,"policyVersion",1)!=sla+1) throw denied();
        String raw=text(source,"definition",131072);
        if (raw.getBytes(StandardCharsets.UTF_8).length>131072 || !sha(raw).equals(hash(snapshot,"workflowDefinitionSha256"))) throw denied();
        var definition=json.parse(raw.getBytes(StandardCharsets.UTF_8),131072);
        exact(definition,Set.of("schemaContract","schemaVersion","slaMinutes","stages"));
        if (!"DWP_APPROVAL_WORKFLOW_QUORUM_V2".equals(text(definition,"schemaContract",80)) || integer(definition,"schemaVersion",2)!=2
                || !raw.equals(new String(json.bytes(definition),StandardCharsets.UTF_8))) throw denied();
        long workflowSla=bounded(definition,"slaMinutes",15,525600);
        var stages=definition.get("stages"); if (!stages.isArray() || stages.isEmpty() || stages.size()>64) throw denied();
        var byKey=new HashMap<String,JsonNode>(); var roles=new TreeSet<String>();
        for (var stage : stages) {
            exact(stage,stage.has("routeCondition") ? Set.of("key","name","candidateRole","quorum","slaMinutes","predecessors","routeCondition")
                    : Set.of("key","name","candidateRole","quorum","slaMinutes","predecessors"));
            String key=code(stage,"key"), role=code(stage,"candidateRole"), name=text(stage,"name",120);
            if (!name.equals(name.strip()) || role.startsWith("PROVIDER_") || byKey.putIfAbsent(key,stage)!=null) throw denied(); roles.add(role);
            bounded(stage,"slaMinutes",15,525600); var quorum=stage.get("quorum");
            exact(quorum,quorum!=null && quorum.has("value") ? Set.of("mode","value") : Set.of("mode"));
            switch (text(quorum,"mode",10)) {
                case "ANY","ALL" -> { if (quorum.has("value")) throw denied(); }
                case "COUNT" -> bounded(quorum,"value",1,1000);
                case "PERCENT" -> bounded(quorum,"value",1,100);
                default -> throw denied();
            }
            var predecessors=stage.get("predecessors"); if (!predecessors.isArray() || predecessors.size()>64) throw denied();
            var seen=new HashSet<String>();
            for (var prior : predecessors) if (!prior.isTextual() || !prior.textValue().matches("[A-Z][A-Z0-9_]{1,49}")
                    || prior.textValue().equals(key) || !seen.add(prior.textValue())) throw denied();
            if (stage.has("routeCondition")) condition(stage.get("routeCondition"));
        }
        var complete=new HashMap<String,Long>();
        while (complete.size()<byKey.size()) {
            int before=complete.size();
            for (String key : new TreeSet<>(byKey.keySet())) {
                if (complete.containsKey(key)) continue; long path=0; boolean ready=true;
                for (var prior : byKey.get(key).get("predecessors")) {
                    if (!byKey.containsKey(prior.textValue())) throw denied(); var previousPath=complete.get(prior.textValue());
                    if (previousPath==null) { ready=false; break; } path=Math.max(path,previousPath);
                }
                if (ready) { path+=integer(byKey.get(key),"slaMinutes",15); if (path>workflowSla) throw denied(); complete.put(key,path); }
            }
            if (before==complete.size()) throw denied();
        }
        var supplied=source.get("roleCodes"); if (!supplied.isArray() || supplied.size()!=roles.size()) throw denied();
        int index=0; for (String role : roles) if (!supplied.get(index).isTextual() || !role.equals(supplied.get(index++).textValue())) throw denied();
        var topology=source.get("topology"); if (!topology.isArray() || topology.size()!=byKey.size()) throw denied();
        var visited=new HashSet<String>();
        for (var stage : topology) {
            exact(stage,Set.of("stepKey","selected","predecessors")); String key=code(stage,"stepKey"); var definitionStage=byKey.get(key);
            if (definitionStage==null || !visited.add(key) || !stage.get("selected").isBoolean()
                    || !definitionStage.get("predecessors").equals(stage.get("predecessors"))) throw denied();
            for (var prior : stage.get("predecessors")) if (!visited.contains(prior.textValue())) throw denied();
        }
    }
    private static String code(JsonNode value,String field) { String text=text(value,field,50); if (!text.matches("[A-Z][A-Z0-9_]{1,49}")) throw denied(); return text; }
    private static long bounded(JsonNode value,String field,long min,long max) { long n=integer(value,field,min); if(n>max) throw denied(); return n; }
    private static void condition(JsonNode value) {
        exact(value,Set.of("all")); var clauses=value.get("all"); if (!clauses.isArray() || clauses.isEmpty() || clauses.size()>50) throw denied();
        for(var clause : clauses) {
            exact(clause,Set.of("field","operator","value"));
            if(!text(clause,"field",80).matches("[a-zA-Z][a-zA-Z0-9_]{0,79}")) throw denied(); String op=text(clause,"operator",5); var operand=clause.get("value");
            if (!Set.of("EQ","IN","GT","GTE","LT","LTE").contains(op)) throw denied();
            if (op.equals("IN")) { if(!operand.isArray() || operand.isEmpty() || operand.size()>50) throw denied(); operand.forEach(PlanningDefinition::scalar); }
            else scalar(operand);
        }
    }
    private static void scalar(JsonNode value) {
        if (value.isBoolean() || value.isIntegralNumber()) return;
        if (value.isTextual() && value.textValue().length()<=2000 && value.textValue().codePoints().noneMatch(Character::isISOControl)) return; throw denied();
    }
}

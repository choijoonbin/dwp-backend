package com.dwp.services.time.absence.contracts.v4;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.dwp.platform.contracts.hris.identity.v2.VerifiedCurrentHrisAuthorizationV2;

/**
 * J1 local candidate carrier/codec. Constructors confer NO authority.
 * Producer publication, transport PEP, neutral receipts and native DML remain OPEN.
 * The frozen v4 source alias is not a deployment stream; the explicit mapping is proposed ABI material.
 */
public final class AbsOwnerResponseV4 {
    public static final String WIRE_ID = "TIM.AbsOwnerJ1.NativeInt64DecimalString.proposal.v1";
    public static final String BINDING_RECIPE_ID = "TIM.J1.SelectedTargetBindingSha256.proposal.v1";
    public static final String FROZEN_SCHEMA_SHA = "9841d3d99bbc486e8d6ec41bb63d5b164aa23f2cba9d4c74d7e2a95ddbf8e14d";
    public static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern INTEGER = Pattern.compile("0|[1-9][0-9]*");
    private static final Set<String> NATIVE_FIELDS = Set.of("tenantId","tenant_id","revision","businessRevision",
            "rowVersion","row_version","leaseVersion","lease_version","cancelFence","cancel_fence",
            "actorUserRowVersion","actorAccessRevision","personVersion","workerVersion","relationshipVersion",
            "assignmentVersion","ledgerRevision","reservationRevision","enrollmentRevision","calendarRevision",
            "enrollment_revision","calendar_revision","subject_row_version","source_revision");
    private AbsOwnerResponseV4() {}
    public enum OwnerKind {
        EMPLOYMENT("HRM.NativeEmploymentSnapshot.proposal.v4","people-employment","people-main",
                "HRM.EffectiveAssignmentSnapshot.v1","dwp-people-server"),
        SYS_CALENDAR("SYS.TimeCalendarSnapshot.proposal.v4","configuration-calendar","platform-hris-configuration",
                "SYS.TimeCalendarSnapshot.v1","dwp-platform-server"),
        TIME_CALENDAR("TIME.PublishedCalendarSnapshot.proposal.v4","time-calendar","time-main",
                "TIM.EffectiveCalendarSnapshot.proposal.v1","dwp-time-server"),
        TIME_SCHEDULE("TIME.PublishedScheduleSnapshot.proposal.v4","time-schedule","time-main",
                "TIM.CanonicalScheduleSnapshot.v1","dwp-time-server"),
        ABS_GOVERNANCE("SYS.AbsGovernanceDecision.proposal.v4","configuration-leave-governance",
                "platform-hris-configuration",null,"dwp-platform-server"),
        TIME_CONFIGURATION("SYS.EffectiveTimeConfigurationSnapshot.proposal.v4","configuration-time",
                "platform-hris-configuration","SYS.EffectiveTimeConfigurationSnapshot.v1","dwp-platform-server");
        public final String contractId, logicalSourceAlias, deploymentStreamKey, referenceContractId, ownerService;
        OwnerKind(String contractId,String alias,String deployment,String reference,String owner) {
            this.contractId=contractId;this.logicalSourceAlias=alias;this.deploymentStreamKey=deployment;
            this.referenceContractId=reference;this.ownerService=owner;
        }
    }
    @FunctionalInterface public interface SchemaValidator { void validate(String schema, JsonNode fullValue); }
    public static void validate(SchemaValidator validator,String schema,JsonNode value) {
        if (validator==null || value==null) throw denied("REQUIRED_SCHEMA_PROVIDER_MISSING");
        validator.validate(schema,value.deepCopy());
    }
    public static IllegalArgumentException denied(String code) { return new IllegalArgumentException(code); }
    public static long nativeLong(JsonNode value) {
        if (value==null || !value.isTextual()) throw denied("NATIVE_INT64_REQUIRES_DECIMAL_STRING");
        BigInteger integer=canonicalBigInteger(value.textValue());
        if(integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0) throw denied("NATIVE_INT64_OVERFLOW");
        return integer.longValueExact();
    }
    /** Arbitrary local arithmetic parser is deliberately NOT the PostgreSQL BIGINT transport parser. */
    public static BigInteger canonicalBigInteger(String value) {
        if(value==null || !INTEGER.matcher(value).matches()) throw denied("NON_CANONICAL_UNSIGNED_INTEGER");
        return new BigInteger(value);
    }
    private static boolean nativeField(String name) {
        return NATIVE_FIELDS.contains(name) || name.endsWith("_id") || name.endsWith("_revision")
                || name.endsWith("_version") || name.endsWith("Revision")
                || name.endsWith("Version")&&!name.equals("schemaVersion");
    }
    public static JsonNode toInt64Wire(JsonNode input) { return wire(input,null); }
    private static JsonNode wire(JsonNode node,String field) {
        if(node==null) throw denied("NULL_DOCUMENT");
        if(node.isObject()) {
            ObjectNode out=JSON.createObjectNode();
            node.fields().forEachRemaining(e->out.set(e.getKey(),wire(e.getValue(),e.getKey())));
            return out;
        }
        if(node.isArray()) {ArrayNode out=JSON.createArrayNode();node.forEach(n->out.add(wire(n,field)));return out;}
        if(nativeField(field==null?"":field) && node.isNumber()) {
            if(!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue()<0)
                throw denied("UNSAFE_NATIVE_NUMBER");
            return TextNode.valueOf(Long.toString(node.longValue()));
        }
        return node.deepCopy();
    }
    /** Explicit sidecar ABI, never a canonical overlay approval or mutation of historical schemas. */
    public static ObjectNode int64Schema(byte[] frozenSchema) {
        if(!FROZEN_SCHEMA_SHA.equals(sha256(frozenSchema))) throw denied("FROZEN_SCHEMA_PIN_MISMATCH");
        try {
            ObjectNode out=(ObjectNode)JSON.readTree(frozenSchema);
            rewriteSchema(out,null);
            ObjectNode defs=(ObjectNode)out.get("definitions");
            ObjectNode header=(ObjectNode)defs.path("V4.Header");
            ((ObjectNode)header.get("properties")).set("selectedScopeKey",JSON.createObjectNode().put("type","string").put("minLength",1).put("maxLength",500));
            ((ObjectNode)header.get("properties")).set("populationBindingRecipeId",JSON.createObjectNode().put("const",BINDING_RECIPE_ID));
            ((ArrayNode)header.get("required")).add("selectedScopeKey").add("populationBindingRecipeId");
            ObjectNode schemas=(ObjectNode)out.get("schemas");
            Map.of("LEAVE_POLICY","TIM.LeavePolicyContent.v1","POLICY_REFERENCE","Ref.TIM.LeavePolicyArtifactSnapshot.v1",
                    "TERMINATION","HRM.EmploymentTerminationSnapshot.v2","LEDGER_SUMMARY","TIM.TerminationLedgerSummary.v2",
                    "SETTLEMENT_COMMAND","TIM.TerminationSettlementCommand.v2").forEach((name,ref)->
                    schemas.set(name,JSON.createObjectNode().put("$ref","#/definitions/"+ref)));
            for(String name:List.of("TIM.EntitlementInputDocument.v2","V4.TerminationInstruction")) {
                ObjectNode definition=(ObjectNode)defs.get(name);
                ((ObjectNode)definition.get("properties")).set("wireContractId",JSON.createObjectNode().put("const",WIRE_ID));
                ((ArrayNode)definition.get("required")).add("wireContractId");
            }
            for(JsonNode branch:defs.path("V4.OwnerResponse").path("oneOf")) {
                ObjectNode props=(ObjectNode)branch.get("properties");
                String id=props.path("ownerContractId").path("const").asText();
                OwnerKind kind=Arrays.stream(OwnerKind.values()).filter(k->k.contractId.equals(id)).findFirst().orElseThrow();
                props.set("deploymentStreamKey",JSON.createObjectNode().put("const",kind.deploymentStreamKey));
                props.set("wireContractId",JSON.createObjectNode().put("const",WIRE_ID));
                ((ArrayNode)branch.get("required")).add("deploymentStreamKey").add("wireContractId");
            }
            out.put("wireContractId",WIRE_ID);
            return out;
        } catch (java.io.IOException ex) { throw denied("FROZEN_SCHEMA_PARSE_FAILED"); }
    }
    private static void rewriteSchema(JsonNode node,String field) {
        if(node.isArray()) {node.forEach(n->rewriteSchema(n,field));return;}
        if(!node.isObject())return;
        ObjectNode object=(ObjectNode)node;
        if(nativeField(field==null?"":field) && ("integer".equals(object.path("type").asText())
                ||object.path("const").isIntegralNumber())) {
            boolean positive=object.path("minimum").asLong(0)>0;
            object.put("type","string");object.put("format","native-int64");object.put("pattern","^(0|[1-9][0-9]*)$");
            object.put("maxLength",19);
            if(positive)object.put("pattern","^[1-9][0-9]*$");
            object.remove(List.of("minimum","maximum","exclusiveMinimum","exclusiveMaximum"));
            if(object.has("const")) object.put("const",object.get("const").asText());
        }
        object.fields().forEachRemaining(e->{
            if(e.getKey().equals("properties"))e.getValue().fields().forEachRemaining(p->rewriteSchema(p.getValue(),p.getKey()));
            else rewriteSchema(e.getValue(),field);
        });
    }
    public static String sha256(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    public static JsonNode canonical(JsonNode value) {
        if(value.isObject()) {
            ObjectNode out=JSON.createObjectNode();TreeMap<String,JsonNode> sorted=new TreeMap<>();
            value.fields().forEachRemaining(e->sorted.put(e.getKey(),e.getValue()));
            sorted.forEach((k,v)->out.set(k,canonical(v)));return out;
        }
        if(value.isArray()){ArrayNode out=JSON.createArrayNode();value.forEach(v->out.add(canonical(v)));return out;}
        if(value.isFloatingPointNumber())throw denied("FLOAT_NOT_CANONICAL_DOMAIN_VALUE");
        return value.deepCopy();
    }
    public static String digest(JsonNode value) {
        try{return sha256(JSON.writeValueAsBytes(canonical(value)));}
        catch(java.io.IOException ex){throw new IllegalStateException(ex);}
    }
    public static String ownSlotDigest(ObjectNode value,String ownSlot) {
        ObjectNode unsigned=value.deepCopy();unsigned.remove(ownSlot);return digest(unsigned);
    }
    /** Local candidate binding checksum, NOT native population admission/signed owner proof. */
    public static String candidateTargetBindingDigest(VerifiedCurrentHrisAuthorizationV2 a) {
        require(a!=null,"GUARDED_TARGET_MISSING");var t=a.target();
        ObjectNode v=JSON.createObjectNode();v.put("recipeId",BINDING_RECIPE_ID);
        v.put("tenantId",Long.toString(t.tenantId()));v.put("selectedScopeKey",a.selectedScopeKey());
        v.put("operationId",a.requirements().operationId());v.put("purpose",a.requirements().purpose());v.put("audience",a.requirements().audience());
        v.put("targetKind",t.kind().name());v.put("personPublicId",t.personPublicId().toString());v.put("personVersion",Long.toString(t.personVersion()));
        if(t.workerPublicId()!=null){v.put("workerPublicId",t.workerPublicId().toString());v.put("workerVersion",Long.toString(t.workerVersion()));
            v.put("workRelationshipPublicId",t.workRelationshipPublicId().toString());v.put("relationshipVersion",Long.toString(t.workRelationshipVersion()));
            v.put("assignmentPublicId",t.assignmentPublicId().toString());v.put("assignmentVersion",Long.toString(t.assignmentVersion()));}
        v.put("relationshipRevision",a.relationshipRevision().value());v.put("populationRevision",a.populationRevision().value());
        v.put("fieldPolicyRevision",a.fieldPolicyRevision().value());v.put("purposePolicyRevision",a.purposePolicyRevision().value());
        v.put("dynamicSodRevision",a.dynamicSodRevision().value());return digest(v);
    }
    public static ObjectNode seal(ObjectNode value,String slot) {
        ObjectNode out=value.deepCopy();out.put(slot,ownSlotDigest(out,slot));return out;
    }
    public static String text(JsonNode node,String field) {
        JsonNode value=node.get(field);if(value==null||!value.isTextual())throw denied("MISSING_TYPED_FIELD:"+field);
        return value.textValue();
    }
    public static UUID uuid(JsonNode node,String field) {
        try {String value=text(node,field);UUID parsed=UUID.fromString(value);
            if(!parsed.toString().equals(value))throw denied("NON_CANONICAL_UUID");return parsed;
        }catch(RuntimeException ex){throw denied("INVALID_UUID:"+field);}
    }
    public static void require(boolean condition,String code){if(!condition)throw denied(code);}
    public enum Phase { GUARDED_AUTHORITY,NORMALIZED_REQUEST,OWNER_REFETCH,LOCAL_TENANT_RESOLVE,
        ID_ALLOCATION,TRANSACTION_CLOCK,INSERT_RETURNING,DATABASE_DEFAULT_RETURNING,LIFECYCLE_CONSTRUCTOR,
        IMMUTABLE_CONTENT,CAS_RETURNING,GUARDED_OUTCOME,OWNER_RECEIPT,NULL_INAPPLICABLE }
    public record Parent(String table,long tenantId,long internalId,UUID publicId) {
        public Parent {require(table!=null&&tenantId>0&&internalId>0&&publicId!=null,"INVALID_PARENT");}
    }
    public record Binding(String column,String sqlType,boolean nullable,Object value,Phase phase,
            String sourceExpression,Parent parent) {
        public Binding {
            require(column!=null&&phase!=null&&sourceExpression!=null&&!sourceExpression.isBlank(),"MISSING_SOURCE");
            require(nullable||value!=null,"NONNULL_SLOT_MISSING:"+column);
            if(value!=null) {
                boolean valid=switch(sqlType) {
                    case "BIGINT" -> value instanceof Long l && l>=0;
                    case "INTEGER" -> value instanceof Integer i && i>=0;
                    case "UUID" -> value instanceof UUID;
                    case "DATE" -> value instanceof LocalDate;
                    case "TIMESTAMPTZ" -> value instanceof Instant;
                    case "JSONB" -> value instanceof JsonNode;
                    case "NUMERIC(19,6)" -> value instanceof BigDecimal d && d.scale()<=6&&d.precision()-d.scale()<=13;
                    case "CHAR(64)" -> value instanceof String s&&s.matches("[a-f0-9]{64}");
                    default -> sqlType.matches("VARCHAR\\([0-9]+\\)")&&value instanceof String s
                        &&s.length()<=Integer.parseInt(sqlType.substring(8,sqlType.length()-1));
                };
                require(valid,"SQL_TYPE_VALUE_MISMATCH:"+column);
            }
            if(value instanceof JsonNode j)value=j.deepCopy();
            if(parent!=null)require(value instanceof Long l&&l==parent.internalId()
                    ||value instanceof UUID u&&u.equals(parent.publicId()),"PARENT_KEY_VALUE_MISMATCH:"+column);
        }
        @Override public Object value(){return value instanceof JsonNode j?j.deepCopy():value;}
    }
    public record Row(String table,List<Binding> bindings) {
        public Row {
            bindings=List.copyOf(bindings);
            require(table!=null&&bindings.stream().map(Binding::column).distinct().count()==bindings.size(),"DUPLICATE_SLOT");
            Long tenant=(Long)bindings.stream().filter(b->b.column.equals("tenant_id")).findFirst().orElseThrow().value;
            bindings.forEach(b->{if(b.parent!=null)require(b.parent.tenantId==tenant,"CROSS_TENANT_PARENT");});
        }
        public Binding get(String name){return bindings.stream().filter(b->b.column.equals(name)).findFirst().orElseThrow();}
        public ObjectNode wireValues() {
            ObjectNode out=JSON.createObjectNode();
            for(Binding b:bindings) {
                Object v=b.value;
                if(v==null)out.putNull(b.column);
                else if(v instanceof Long l)out.put(b.column,Long.toString(l));
                else if(v instanceof JsonNode j)out.set(b.column,j.deepCopy());
                else if(v instanceof Integer i)out.put(b.column,i);
                else out.put(b.column,v.toString());
            }
            return out;
        }
    }
    public static Binding bind(String column,String type,boolean nullable,Object value,Phase phase,String expression) {
        return new Binding(column,type,nullable,value,phase,expression,null);
    }
    public static Binding parent(String column,boolean nullable,Parent value,Phase phase,String expression) {
        return new Binding(column,"BIGINT",nullable,value==null?null:value.internalId,phase,expression,value);
    }
}

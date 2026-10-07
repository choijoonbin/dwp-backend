package com.dwp.services.time.absence.source;

import static com.dwp.services.time.absence.contracts.v4.AbsOwnerResponseV4.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.dwp.platform.contracts.hris.identity.v2.VerifiedCurrentHrisAuthorizationV2;
import com.dwp.services.time.absence.ports.AbsOwnerSnapshotRefetchPortV4;
import com.dwp.services.time.absence.ports.AbsOwnerSnapshotRefetchPortV4.*;

/** J1 pure assembler. Supplied RETURNING tuples are synthetic until a registered native writer exists. */
public final class EntitlementInputAssemblerV4 {
    public enum Mode { DRY_RUN, POST }
    public record Request(LocalDate periodStart,LocalDate periodEnd,Instant asOf,Mode mode) {}
    public record Enrollment(Parent local,Parent planVersion,long revision,UUID worker,UUID assignment,
            LocalDate effectiveFrom,LocalDate effectiveTo,String status) {
        public ObjectNode wire() {
            ObjectNode e=JSON.createObjectNode();e.put("enrollmentPublicId",local.publicId().toString());
            e.put("rowVersion",Long.toString(revision));e.put("workerPublicId",worker.toString());
            e.put("assignmentPublicId",assignment.toString());e.put("effectiveFrom",effectiveFrom.toString());
            if(effectiveTo==null)e.putNull("effectiveTo");else e.put("effectiveTo",effectiveTo.toString());return e;
        }
    }
    /** Local allocator/RETURNING identity is never copied from the external header snapshot UUID. */
    public record OwnerArtifactReturning(Parent local,UUID allocatedPublicId,String ownerContractId,
            UUID snapshotPublicId,long snapshotRevision,String contentDigest) {}
    public record CalendarArtifactReturning(Parent local,UUID allocatedPublicId,OwnerArtifactReturning ownerArtifact,
            long ownerSnapshotIdReturning,UUID calendarVersionPublicId,long calendarRevision,String contentDigest) {}
    public record Selection(UUID allocatedPublicId,Enrollment enrollment,VerifiedOwner employment,
            OwnerArtifactReturning employmentArtifact,VerifiedOwner calendar,CalendarArtifactReturning calendarArtifact,VerifiedOwner configuration,
            String unit,long ledgerRevision,long reservationRevision,UUID originalGrant,Parent originalRun) {}
    public record Allocations(UUID runPublicId,UUID inputPublicId) {}
    public static final class NewInsertSpec {
        private final ObjectNode input;private final VerifiedPolicy policy;private final List<Selection> selections;
        private final VerifiedCurrentHrisAuthorizationV2 authority;
        private NewInsertSpec(ObjectNode input,VerifiedPolicy policy,List<Selection> selections,
                VerifiedCurrentHrisAuthorizationV2 authority) {
            this.input=input.deepCopy();this.policy=policy;this.selections=List.copyOf(selections);
            this.authority=authority;
        }
        public ObjectNode input(){return input.deepCopy();}
        public VerifiedCurrentHrisAuthorizationV2 authority(){return authority;}
        public List<Selection> selections(){return selections;}
    }
    public record InsertReturning(Parent input,Parent run,long runDatabaseDefaultRowVersion,
            Map<UUID,Parent> selected) {
        public InsertReturning {selected=Map.copyOf(selected);}
    }
    public record PersistedInput(Row run,Row input,List<Row> selected,NewInsertSpec source,InsertReturning returning) {
        public PersistedInput {selected=List.copyOf(selected);}
    }
    public record LoadedRun(Parent run,long rowVersion,long leaseVersion,String status,
            UUID leaseOwner,Instant leaseExpiresAt,Long cancelFence) {}
    public record ItemReturning(UUID allocatedPublicId,Parent row) {}
    public record ExistingCasMutation(LoadedRun before,long expectedRowVersion,long expectedLeaseVersion,
            LoadedRun returning) {}
    private final AbsOwnerSnapshotRefetchPortV4 owners;private final SchemaValidator schema;private final Clock clock;
    public EntitlementInputAssemblerV4(AbsOwnerSnapshotRefetchPortV4 owners,SchemaValidator schema,Clock clock) {
        this.owners=owners;this.schema=schema;this.clock=clock;
    }
    public NewInsertSpec prepare(Request body,Allocations ids,VerifiedPolicy policy,List<Selection> selected,
            VerifiedCurrentHrisAuthorizationV2 authority) {
        current(authority);
        require(body!=null&&body.periodStart!=null&&body.periodEnd!=null&&body.asOf!=null&&body.mode!=null,
                "MISSING_NORMALIZED_REQUEST");
        require(body.periodEnd.isAfter(body.periodStart),"INVALID_EXCLUSIVE_PERIOD");
        require(policy!=null&&policy.authority()==authority&&ids!=null&&ids.runPublicId!=null&&ids.inputPublicId!=null
                &&!ids.runPublicId.equals(ids.inputPublicId),"MISSING_POLICY_OR_ALLOCATION");
        require(text(policy.reference(),"asOf").equals(body.asOf.toString())
                &&clock.instant().isBefore(Instant.parse(text(policy.reference(),"validUntil"))),"STALE_POLICY_AT_INPUT_CAPTURE");
        require(selected!=null&&!selected.isEmpty()&&selected.size()<=10000,"INVALID_SELECTION_COUNT");
        require(selected.stream().allMatch(s->s!=null&&s.enrollment!=null&&s.enrollment.local!=null),"MISSING_SELECTED_RELATION");
        require(selected.stream().map(s->s.enrollment.local.publicId()).distinct().count()==selected.size()
                &&selected.stream().map(Selection::allocatedPublicId).distinct().count()==selected.size(),"DUPLICATE_SELECTION");
        List<Selection> sorted=selected.stream().sorted(Comparator.comparing(s->s.enrollment.local.publicId().toString())).toList();
        ObjectNode doc=JSON.createObjectNode();doc.put("wireContractId",WIRE_ID);
        doc.put("inputVersionPublicId",ids.inputPublicId.toString());doc.put("runPublicId",ids.runPublicId.toString());
        doc.put("revision","1");doc.put("capturedAt",clock.instant().toString());doc.put("asOf",body.asOf.toString());
        doc.put("periodStart",body.periodStart.toString());doc.put("periodEnd",body.periodEnd.toString());doc.put("mode",body.mode.name());
        doc.set("policyRef",policy.reference());doc.set("policyContent",policy.fullPayload());doc.put("selectedCount",sorted.size());
        ArrayNode rows=JSON.createArrayNode();int ordinal=0;
        for(Selection s:sorted) {
            require(s.allocatedPublicId!=null&&s.enrollment!=null&&s.enrollment.local!=null&&s.enrollment.revision>=0
                    &&"ACTIVE".equals(s.enrollment.status),"INVALID_LOADED_ENROLLMENT");
            require(s.enrollment.local.table().equals("abs_worker_plan_enrollments")
                    &&s.enrollment.local.tenantId()==authority.actor().tenantId(),"WRONG_ENROLLMENT_PARENT");
            require(s.enrollment.planVersion!=null&&s.enrollment.planVersion.equals(policy.localParent()),
                    "ENROLLMENT_PLAN_VERSION_PARENT_MISMATCH");
            require(s.enrollment.worker.equals(authority.target().workerPublicId())
                    &&s.enrollment.assignment.equals(authority.target().assignmentPublicId()),"UNAUTHORIZED_SELECTED_TARGET");
            require(s.enrollment.effectiveFrom!=null
                    &&(s.enrollment.effectiveTo==null||s.enrollment.effectiveTo.isAfter(s.enrollment.effectiveFrom)),
                    "INVALID_ENROLLMENT_INTERVAL");
            checkOwner(s.employment,OwnerKind.EMPLOYMENT,authority,body.asOf);
            require(s.calendar!=null&&(s.calendar.kind()==OwnerKind.SYS_CALENDAR||s.calendar.kind()==OwnerKind.TIME_CALENDAR),
                    "WRONG_CALENDAR_OWNER");
            checkOwner(s.calendar,s.calendar.kind(),authority,body.asOf);checkOwner(s.configuration,OwnerKind.TIME_CONFIGURATION,authority,body.asOf);
            ObjectNode configuration=s.configuration.payload(),policyRef=policy.reference();
            require("PUBLISHED".equals(text(configuration,"status"))
                    &&"TIM_LEAVE_POLICY".equals(text(configuration,"configurationKind"))
                    &&uuid(configuration,"timeArtifactPublicId").equals(uuid(policyRef,"snapshotPublicId"))
                    &&nativeLong(configuration.get("timeArtifactRevision"))==nativeLong(policyRef.get("revision"))
                    &&Objects.equals(text(configuration,"timeArtifactSha256"),text(policyRef,"contentSha256")),
                    "CONFIGURATION_POLICY_IMMUTABLE_ARTIFACT_MISMATCH");
            artifact(s.employmentArtifact,s.employment,authority.actor().tenantId());
            require(s.calendarArtifact!=null,"CALENDAR_ARTIFACT_RETURNING_MISSING");
            artifact(s.calendarArtifact.ownerArtifact,s.calendar,authority.actor().tenantId());
            returnedParent(s.calendarArtifact.local,"abs_calendar_version_snapshots",authority.actor().tenantId(),s.calendarArtifact.allocatedPublicId);
            require(s.calendarArtifact.ownerSnapshotIdReturning==s.calendarArtifact.ownerArtifact.local.internalId()
                    &&s.calendarArtifact.calendarVersionPublicId!=null
                    &&s.calendarArtifact.calendarVersionPublicId.equals(uuid(s.calendar.payload(),"calendarVersionPublicId"))
                    &&s.calendarArtifact.calendarRevision==nativeLong(s.calendar.payload().get("revision"))
                    &&Objects.equals(s.calendarArtifact.contentDigest,text(s.calendar.header(),"contentSha256")),
                    "CALENDAR_LOCAL_PROJECTION_SOURCE_MISMATCH");
            require(s.unit!=null&&s.unit.equals(text(policy.fullPayload(),"unit"))&&s.ledgerRevision>=0&&s.reservationRevision>=0,
                    "UNIT_OR_NATIVE_REVISION_MISMATCH");
            require((s.originalGrant==null)==(s.originalRun==null),"CORRECTION_ORIGINAL_PAIR_MISMATCH");
            if(s.originalRun!=null)require(s.originalRun.table().equals("abs_entitlement_runs")
                    &&s.originalRun.tenantId()==authority.actor().tenantId(),"WRONG_CORRECTIVE_RUN_PARENT");
            ObjectNode item=JSON.createObjectNode();item.put("selectedItemPublicId",s.allocatedPublicId.toString());
            item.put("inputVersionPublicId",ids.inputPublicId.toString());item.put("ordinal",++ordinal);
            item.set("enrollment",s.enrollment.wire());item.set("employmentRef",s.employment.reference());
            item.set("calendarRef",s.calendar.reference());item.set("configurationRef",s.configuration.reference());
            item.put("unit",s.unit);item.put("ledgerRevision",Long.toString(s.ledgerRevision));
            item.put("reservationRevision",Long.toString(s.reservationRevision));
            item.put("correctionMode",s.originalGrant==null?"NONE":"REVERSE_AND_REGRANT");
            if(s.originalGrant==null)item.putNull("originalGrantPublicId");else item.put("originalGrantPublicId",s.originalGrant.toString());
            if(s.originalRun==null)item.putNull("originalRunPublicId");else item.put("originalRunPublicId",s.originalRun.publicId().toString());
            rows.add(item);
        }
        doc.set("selectedItems",rows);doc.put("selectionDigest",digest(rows));doc=seal(doc,"contentSha256");
        validate(schema,"INPUT",doc);current(authority);return new NewInsertSpec(doc,policy,sorted,authority);
    }
    private void checkOwner(VerifiedOwner owner,OwnerKind kind,VerifiedCurrentHrisAuthorizationV2 a,Instant asOf) {
        require(owner!=null&&owner.kind()==kind&&owner.authority()==a,"MISSING_OR_WRONG_OWNER_PROOF");
        require(text(owner.header(),"asOf").equals(asOf.toString())
                &&clock.instant().isBefore(Instant.parse(text(owner.header(),"validUntil"))),"STALE_REFETCHED_OWNER");
    }
    private void current(VerifiedCurrentHrisAuthorizationV2 a) {
        require(owners!=null&&clock!=null,"REQUIRED_ASSEMBLER_SOURCE_MISSING");owners.current(a,clock);
        require("tim.leave.entitlement.run".equals(a.requirements().operationId())
                &&"TIM_ENTITLEMENT_PREPARE".equals(a.requirements().purpose()),"WRONG_ENTITLEMENT_OPERATION_AUTHORITY");
    }
    private static void artifact(OwnerArtifactReturning local,VerifiedOwner owner,long tenant) {
        require(local!=null&&owner!=null,"OWNER_ARTIFACT_RETURNING_MISSING");
        returnedParent(local.local,"abs_owner_artifact_snapshots",tenant,local.allocatedPublicId);
        require(Objects.equals(local.ownerContractId,owner.kind().contractId)
                &&Objects.equals(local.snapshotPublicId,uuid(owner.header(),"snapshotPublicId"))
                &&local.snapshotRevision==nativeLong(owner.header().get("businessRevision"))
                &&Objects.equals(local.contentDigest,text(owner.header(),"contentSha256")),"OWNER_ARTIFACT_SOURCE_TUPLE_MISMATCH");
    }
    public PersistedInput persist(NewInsertSpec source,InsertReturning returned) {
        require(source!=null&&returned!=null,"INSERT_RETURNING_MISSING");current(source.authority);
        ObjectNode doc=source.input();long tenant=source.authority.actor().tenantId();
        returnedParent(returned.input,"abs_entitlement_input_versions",tenant,uuid(doc,"inputVersionPublicId"));
        returnedParent(returned.run,"abs_entitlement_runs",tenant,uuid(doc,"runPublicId"));
        require(returned.runDatabaseDefaultRowVersion==0,"NEW_RUN_DB_DEFAULT_RETURNING_REQUIRED");
        require(returned.selected.size()==source.selections.size(),"SELECTED_RETURNING_CARDINALITY");
        Instant transactionAt=clock.instant();
        List<Binding> input=base(source,"input_version_id",returned.input,transactionAt);
        input.add(new Binding("run_public_id","UUID",false,returned.run.publicId(),Phase.ID_ALLOCATION,
                "allocated.runPublicId = INSERT_RETURNING.run.public_id; deferred tenant/public FK",returned.run));
        input.add(bind("revision","BIGINT",false,1L,Phase.LIFECYCLE_CONSTRUCTOR,"IMMUTABLE_INPUT_FIRST_REVISION"));
        input.add(bind("selected_count","INTEGER",false,source.selections.size(),Phase.IMMUTABLE_CONTENT,"unsignedInput.selectedItems.length"));
        input.add(bind("selection_digest","CHAR(64)",false,text(doc,"selectionDigest"),Phase.IMMUTABLE_CONTENT,"SHA256(unsignedInput.selectedItems)"));
        input.add(bind("input_payload","JSONB",false,doc,Phase.IMMUTABLE_CONTENT,"closed unsigned input incl full policy and owner references"));
        input.add(bind("content_digest","CHAR(64)",false,text(doc,"contentSha256"),Phase.IMMUTABLE_CONTENT,"SHA256(input excluding contentSha256)"));
        List<Binding> run=base(source,"entitlement_run_id",returned.run,transactionAt);
        run.add(parent("leave_plan_version_id",false,source.policy.localParent(),Phase.LOCAL_TENANT_RESOLVE,"current ABS policy tenant/public UUID resolved internal parent"));
        run.add(bind("period_start","DATE",false,LocalDate.parse(text(doc,"periodStart")),Phase.NORMALIZED_REQUEST,"normalized.body.periodStart"));
        run.add(bind("period_end","DATE",false,LocalDate.parse(text(doc,"periodEnd")),Phase.NORMALIZED_REQUEST,"normalized.body.periodEnd EXCLUSIVE"));
        run.add(bind("run_mode","VARCHAR(10)",false,text(doc,"mode"),Phase.NORMALIZED_REQUEST,"normalized.body.mode"));
        run.add(bind("status","VARCHAR(24)",false,"QUEUED",Phase.LIFECYCLE_CONSTRUCTOR,"frozen initial state QUEUED; NEW only"));
        run.add(bind("input_snapshot_digest","CHAR(64)",false,text(doc,"contentSha256"),Phase.IMMUTABLE_CONTENT,"input_payload.contentSha256"));
        run.add(bind("rule_snapshot_digest","CHAR(64)",false,text(source.policy.reference(),"contentSha256"),Phase.OWNER_REFETCH,"current ABS policy full payload digest"));
        Parent previous=source.selections.getFirst().originalRun;
        require(source.selections.stream().allMatch(s->Objects.equals(s.originalRun,previous)),"CORRECTIVE_ORIGINAL_RUN_NOT_SINGLE");
        run.add(parent("previous_run_id",true,previous,previous==null?Phase.NULL_INAPPLICABLE:Phase.LOCAL_TENANT_RESOLVE,"NONE or originalRun tenant/public UUID resolved existing run"));
        run.add(bind("row_version","BIGINT",false,returned.runDatabaseDefaultRowVersion,Phase.DATABASE_DEFAULT_RETURNING,"NEW INSERT RETURNING row_version; planned DEFAULT 0"));
        run.add(bind("lease_version","BIGINT",false,0L,Phase.LIFECYCLE_CONSTRUCTOR,"NEW_UNCLAIMED_LEASE epoch0 technical constructor; no DB default"));
        run.add(bind("lease_expires_at","TIMESTAMPTZ",true,null,Phase.NULL_INAPPLICABLE,"NEW has no acquired lease"));
        run.add(parent("input_version_id",false,returned.input,Phase.INSERT_RETURNING,"immutable INPUT INSERT RETURNING input_version_id"));
        run.add(bind("selected_count","INTEGER",false,source.selections.size(),Phase.IMMUTABLE_CONTENT,"immutableInput.selectedCount"));
        run.add(bind("lease_owner_public_id","UUID",true,null,Phase.NULL_INAPPLICABLE,"NEW has no acquired lease owner"));
        run.add(bind("cancel_requested_at","TIMESTAMPTZ",true,null,Phase.NULL_INAPPLICABLE,"NEW has no cancellation command"));
        List<Row> selectedRows=new ArrayList<>();int i=0;
        for(Selection s:source.selections) {
            Parent local=returned.selected.get(s.allocatedPublicId);returnedParent(local,"abs_entitlement_selected_items",tenant,s.allocatedPublicId);
            ObjectNode item=(ObjectNode)doc.path("selectedItems").get(i++);
            List<Binding> row=base(source,"selected_item_id",local,transactionAt);
            row.add(parent("input_version_id",false,returned.input,Phase.INSERT_RETURNING,"INPUT INSERT RETURNING exact parent"));
            row.add(bind("ordinal","INTEGER",false,i,Phase.IMMUTABLE_CONTENT,"canonical sorted selectedItems index+1"));
            row.add(parent("enrollment_id",false,s.enrollment.local,Phase.LOCAL_TENANT_RESOLVE,"current tenant/public enrollment tuple"));
            row.add(bind("enrollment_revision","BIGINT",false,s.enrollment.revision,Phase.LOCAL_TENANT_RESOLVE,"loaded enrollment.row_version"));
            row.add(parent("employment_snapshot_id",false,s.employmentArtifact.local,Phase.INSERT_RETURNING,"local allocated UUID / RETURNING; distinct external snapshot_public_id + contract/revision/digest tuple"));
            row.add(parent("calendar_snapshot_id",false,s.calendarArtifact.local,Phase.INSERT_RETURNING,"local calendar RETURNING -> owner_snapshot_id -> verified external header and calendar payload tuple"));
            row.add(bind("selected_payload","JSONB",false,item,Phase.IMMUTABLE_CONTENT,"closed immutable selectedItems[ordinal-1] full source vector"));
            row.add(bind("content_digest","CHAR(64)",false,digest(item),Phase.IMMUTABLE_CONTENT,"SHA256(full selected_payload)"));
            selectedRows.add(new Row("abs_entitlement_selected_items",row));
        }
        current(source.authority);
        return new PersistedInput(new Row("abs_entitlement_runs",run),new Row("abs_entitlement_input_versions",input),selectedRows,source,returned);
    }
    public LoadedRun claim(ExistingCasMutation cas,VerifiedCurrentHrisAuthorizationV2 a) {
        current(a);require(cas!=null&&cas.before!=null&&cas.returning!=null,"CAS_LOADED_AND_RETURNING_REQUIRED");
        var old=cas.before;var next=cas.returning;
        require(old.run.tenantId()==a.actor().tenantId()&&old.run.equals(next.run),"CAS_PARENT_MISMATCH");
        require(cas.expectedRowVersion==old.rowVersion&&cas.expectedLeaseVersion==old.leaseVersion
                &&old.rowVersion<Long.MAX_VALUE&&old.leaseVersion<Long.MAX_VALUE,"CAS_PRESTATE_CONFLICT");
        require("QUEUED".equals(old.status)&&"RUNNING".equals(next.status)&&next.rowVersion==old.rowVersion+1
                &&next.leaseVersion==old.leaseVersion+1&&a.actor().principalPublicId().equals(next.leaseOwner)
                &&next.leaseExpiresAt!=null&&clock.instant().isBefore(next.leaseExpiresAt),"INVALID_CAS_CLAIM_RETURNING");
        return next;
    }
    public Row outcome(PersistedInput persisted,int ordinal,ItemReturning itemInsert,ObjectNode outcome,LoadedRun current) {
        require(persisted!=null&&persisted.source!=null,"IMMUTABLE_INPUT_SOURCE_REQUIRED");
        current(persisted.source.authority);validate(schema,"OUTCOME",outcome);
        require(current!=null&&current.run.equals(persisted.returning.run)&&"RUNNING".equals(current.status)
                &&current.leaseVersion>0&&persisted.source.authority.actor().principalPublicId().equals(current.leaseOwner)
                &&current.leaseExpiresAt!=null&&clock.instant().isBefore(current.leaseExpiresAt),
                "CURRENT_RUN_LEASE_REQUIRED");
        require(ordinal>0&&ordinal<=persisted.selected.size(),"UNKNOWN_SELECTED_ORDINAL");
        Selection selected=persisted.source.selections.get(ordinal-1);
        require(uuid(outcome,"selectedItemPublicId").equals(selected.allocatedPublicId)
                &&text(outcome,"inputSha256").equals(text(persisted.source.input,"contentSha256")),"OUTCOME_INPUT_BINDING");
        String kind=text(outcome,"kind");boolean cancelled="CANCELLED".equals(kind);
        long fence=cancelled?nativeLong(outcome.get("cancelFence")):nativeLong(outcome.get("leaseVersion"));
        require(cancelled?current.cancelFence!=null&&current.cancelFence==fence&&fence>0:fence==current.leaseVersion,
                "OUTCOME_FENCE_MISMATCH");
        boolean computed=Set.of("CALCULATED","POSTED").contains(kind);
        require(!computed||text(outcome,"unit").equals(selected.unit),"OUTCOME_UNIT_MISMATCH");
        require(!"CALCULATED".equals(kind)||"DRY_RUN".equals(text(persisted.source.input,"mode")),"DRY_RUN_OUTCOME_ONLY");
        require(!"POSTED".equals(kind)||"POST".equals(text(persisted.source.input,"mode")),"POST_OUTCOME_ONLY");
        require(itemInsert!=null&&itemInsert.allocatedPublicId!=null,"ITEM_ALLOCATION_AND_RETURNING_REQUIRED");
        Parent itemReturning=itemInsert.row;
        returnedParent(itemReturning,"abs_entitlement_run_items",current.run.tenantId(),itemInsert.allocatedPublicId);
        List<Binding> row=base(persisted.source,"entitlement_run_item_id",itemReturning,clock.instant());
        row.add(parent("entitlement_run_id",false,current.run,Phase.CAS_RETURNING,"current acquired lease CAS RETURNING run"));
        row.add(parent("enrollment_id",false,selected.enrollment.local,Phase.LOCAL_TENANT_RESOLVE,"selected immutable tenant/enrollment tuple"));
        row.add(parent("employment_snapshot_id",false,selected.employmentArtifact.local,Phase.INSERT_RETURNING,"selected local artifact RETURNING, external snapshot tuple verified separately"));
        row.add(parent("calendar_snapshot_id",false,selected.calendarArtifact.local,Phase.INSERT_RETURNING,"selected local calendar RETURNING with owner_snapshot_id parent"));
        ArrayNode refs=JSON.createArrayNode();refs.add(selected.employment.reference()).add(selected.calendar.reference()).add(selected.configuration.reference());
        row.add(bind("source_refs","JSONB",false,refs,Phase.IMMUTABLE_CONTENT,"immutable selected exact owner ref vector"));
        for(String field:List.of("numerator","denominator","quantity"))row.add(bind(field,"NUMERIC(19,6)",true,
                computed?new BigDecimal(text(outcome,field)):null,Phase.GUARDED_OUTCOME,"guarded "+kind+" outcome."+field+"; error/cancel NULL not zero"));
        row.add(bind("unit","VARCHAR(12)",false,selected.unit,Phase.IMMUTABLE_CONTENT,"selected_payload.unit"));
        row.add(bind("suppressed_quantity","NUMERIC(19,6)",true,computed?new BigDecimal(text(outcome,"suppressedQuantity")):null,
                Phase.GUARDED_OUTCOME,"computed suppressedQuantity or inapplicable NULL"));
        row.add(bind("rounding_trace","JSONB",true,computed?outcome.get("roundingTrace"):null,Phase.GUARDED_OUTCOME,"typed computed rounding trace or NULL"));
        row.add(bind("status","VARCHAR(16)",false,kind,Phase.GUARDED_OUTCOME,"guarded discriminated outcome.kind"));
        row.add(bind("result_digest","CHAR(64)",false,digest(outcome),Phase.GUARDED_OUTCOME,"SHA256(full guarded outcome)"));
        row.add(parent("input_version_id",false,persisted.returning.input,Phase.INSERT_RETURNING,"immutable input exact parent"));
        Parent selectedParent=persisted.returning.selected.get(selected.allocatedPublicId);
        row.add(parent("selected_item_id",false,selectedParent,Phase.INSERT_RETURNING,"selected exact tenant/input/enrollment composite parent"));
        row.add(bind("outcome_payload","JSONB",false,outcome,Phase.GUARDED_OUTCOME,"closed complete outcome discriminated payload"));
        row.add(bind("error_code","VARCHAR(80)",true,computed||cancelled?null:text(outcome,"errorCode"),Phase.GUARDED_OUTCOME,"error branch.errorCode only"));
        row.add(bind("lease_version","BIGINT",false,current.leaseVersion,Phase.CAS_RETURNING,"current lease CAS-returned epoch, not initial epoch"));
        row.add(bind("cancel_fence","BIGINT",true,cancelled?fence:null,Phase.GUARDED_OUTCOME,"cancel command matching durable fence; otherwise NULL"));
        Row result=new Row("abs_entitlement_run_items",row);validate(schema,"V4.SqlBind.abs_entitlement_run_items",result.wireValues());
        current(persisted.source.authority);return result;
    }
    /** Pure integrity verification only: not a native query, read-purpose authorization or transport PEP. */
    public ObjectNode refetchInput(ObjectNode full,List<ObjectNode> selectedRows,UUID expectedRun,String expectedDigest) {
        validate(schema,"INPUT",full);require(uuid(full,"runPublicId").equals(expectedRun)&&"1".equals(text(full,"revision"))
                &&text(full,"contentSha256").equals(expectedDigest)&&ownSlotDigest(full,"contentSha256").equals(expectedDigest),
                "IMMUTABLE_INPUT_CONTENT_MISMATCH");
        require(selectedRows!=null&&selectedRows.size()==full.path("selectedCount").intValue()
                &&full.path("selectedItems").size()==selectedRows.size(),"IMMUTABLE_SELECTION_CARDINALITY");
        ArrayNode exact=JSON.createArrayNode();int ordinal=0;String previous="";
        Set<String> ids=new HashSet<>();
        for(ObjectNode item:selectedRows) {
            String key=text(item.path("enrollment"),"enrollmentPublicId");
            require(item.path("ordinal").intValue()==++ordinal&&key.compareTo(previous)>0
                    &&ids.add(text(item,"selectedItemPublicId"))
                    &&text(item,"inputVersionPublicId").equals(text(full,"inputVersionPublicId")),"IMMUTABLE_SELECTION_PARENT_ORDER");
            previous=key;exact.add(item);
        }
        require(exact.equals(full.get("selectedItems"))&&digest(exact).equals(text(full,"selectionDigest")),"IMMUTABLE_SELECTED_ROWS_MISMATCH");
        return full.deepCopy();
    }
    private static void returnedParent(Parent p,String table,long tenant,UUID publicId) {
        require(p!=null&&p.table().equals(table)&&p.tenantId()==tenant&&p.publicId().equals(publicId),"INSERT_RETURNING_PARENT_MISMATCH");
    }
    private static List<Binding> base(NewInsertSpec source,String idColumn,Parent returning,Instant transactionAt) {
        List<Binding> row=new ArrayList<>();long tenant=source.authority.actor().tenantId();
        row.add(bind(idColumn,"BIGINT",false,returning.internalId(),Phase.INSERT_RETURNING,"native INSERT RETURNING "+idColumn));
        row.add(bind("tenant_id","BIGINT",false,tenant,Phase.GUARDED_AUTHORITY,"guarded acting Auth.tenantId (not target Auth)"));
        row.add(bind("public_id","UUID",false,returning.publicId(),Phase.ID_ALLOCATION,"explicit allocator UUID matched INSERT RETURNING public_id"));
        row.add(bind("created_at","TIMESTAMPTZ",false,transactionAt,Phase.TRANSACTION_CLOCK,"current row transaction Clock.instant; not immutable input capture clock for posting"));
        row.add(bind("created_by","UUID",false,source.authority.actor().principalPublicId(),Phase.GUARDED_AUTHORITY,"guarded acting Auth.principalPublicId"));
        return row;
    }
}

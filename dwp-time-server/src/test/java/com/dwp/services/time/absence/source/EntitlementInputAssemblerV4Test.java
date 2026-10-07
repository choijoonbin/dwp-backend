package com.dwp.services.time.absence.source;

import static org.junit.jupiter.api.Assertions.*;
import static com.dwp.services.time.absence.contracts.v4.AbsOwnerResponseV4.*;
import static com.dwp.services.time.absence.contracts.v4.AbsOwnerResponseV4.JSON;
import static com.dwp.services.time.absence.contracts.v4.AbsOwnerResponseV4Test.*;
import static com.dwp.services.time.absence.ports.AbsOwnerSnapshotRefetchPortV4Test.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.*;
import com.dwp.platform.contracts.hris.identity.v2.VerifiedCurrentHrisAuthorizationV2;
import com.dwp.services.time.absence.ports.AbsOwnerSnapshotRefetchPortV4.*;
import com.dwp.services.time.absence.source.EntitlementInputAssemblerV4.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class EntitlementInputAssemblerV4Test {
    // Frozen source metadata plus separately approved phase design; never computed from production bindings.
    public static final String FIXED_95 = """
abs_entitlement_runs;entitlement_run_id;BIGINT;false;INSERT_RETURNING;-
abs_entitlement_runs;tenant_id;BIGINT;false;GUARDED_AUTHORITY;-
abs_entitlement_runs;public_id;UUID;false;ID_ALLOCATION;-
abs_entitlement_runs;created_at;TIMESTAMPTZ;false;TRANSACTION_CLOCK;-
abs_entitlement_runs;created_by;UUID;false;GUARDED_AUTHORITY;-
abs_entitlement_runs;leave_plan_version_id;BIGINT;false;LOCAL_TENANT_RESOLVE;abs_leave_plan_versions
abs_entitlement_runs;period_start;DATE;false;NORMALIZED_REQUEST;-
abs_entitlement_runs;period_end;DATE;false;NORMALIZED_REQUEST;-
abs_entitlement_runs;run_mode;VARCHAR(10);false;NORMALIZED_REQUEST;-
abs_entitlement_runs;status;VARCHAR(24);false;LIFECYCLE_CONSTRUCTOR;-
abs_entitlement_runs;input_snapshot_digest;CHAR(64);false;IMMUTABLE_CONTENT;-
abs_entitlement_runs;rule_snapshot_digest;CHAR(64);false;OWNER_REFETCH;-
abs_entitlement_runs;previous_run_id;BIGINT;true;NULL_INAPPLICABLE|LOCAL_TENANT_RESOLVE;abs_entitlement_runs
abs_entitlement_runs;row_version;BIGINT;false;DATABASE_DEFAULT_RETURNING;-
abs_entitlement_runs;lease_version;BIGINT;false;LIFECYCLE_CONSTRUCTOR;-
abs_entitlement_runs;lease_expires_at;TIMESTAMPTZ;true;NULL_INAPPLICABLE;-
abs_entitlement_runs;input_version_id;BIGINT;false;INSERT_RETURNING;abs_entitlement_input_versions
abs_entitlement_runs;selected_count;INTEGER;false;IMMUTABLE_CONTENT;-
abs_entitlement_runs;lease_owner_public_id;UUID;true;NULL_INAPPLICABLE;-
abs_entitlement_runs;cancel_requested_at;TIMESTAMPTZ;true;NULL_INAPPLICABLE;-
abs_entitlement_run_items;entitlement_run_item_id;BIGINT;false;INSERT_RETURNING;-
abs_entitlement_run_items;tenant_id;BIGINT;false;GUARDED_AUTHORITY;-
abs_entitlement_run_items;public_id;UUID;false;ID_ALLOCATION;-
abs_entitlement_run_items;created_at;TIMESTAMPTZ;false;TRANSACTION_CLOCK;-
abs_entitlement_run_items;created_by;UUID;false;GUARDED_AUTHORITY;-
abs_entitlement_run_items;entitlement_run_id;BIGINT;false;CAS_RETURNING;abs_entitlement_runs
abs_entitlement_run_items;enrollment_id;BIGINT;false;LOCAL_TENANT_RESOLVE;abs_worker_plan_enrollments
abs_entitlement_run_items;employment_snapshot_id;BIGINT;false;INSERT_RETURNING;abs_owner_artifact_snapshots
abs_entitlement_run_items;calendar_snapshot_id;BIGINT;false;INSERT_RETURNING;abs_calendar_version_snapshots
abs_entitlement_run_items;source_refs;JSONB;false;IMMUTABLE_CONTENT;-
abs_entitlement_run_items;numerator;NUMERIC(19,6);true;GUARDED_OUTCOME;-
abs_entitlement_run_items;denominator;NUMERIC(19,6);true;GUARDED_OUTCOME;-
abs_entitlement_run_items;quantity;NUMERIC(19,6);true;GUARDED_OUTCOME;-
abs_entitlement_run_items;unit;VARCHAR(12);false;IMMUTABLE_CONTENT;-
abs_entitlement_run_items;suppressed_quantity;NUMERIC(19,6);true;GUARDED_OUTCOME;-
abs_entitlement_run_items;rounding_trace;JSONB;true;GUARDED_OUTCOME;-
abs_entitlement_run_items;status;VARCHAR(16);false;GUARDED_OUTCOME;-
abs_entitlement_run_items;result_digest;CHAR(64);false;GUARDED_OUTCOME;-
abs_entitlement_run_items;input_version_id;BIGINT;false;INSERT_RETURNING;abs_entitlement_input_versions
abs_entitlement_run_items;selected_item_id;BIGINT;false;INSERT_RETURNING;abs_entitlement_selected_items
abs_entitlement_run_items;outcome_payload;JSONB;false;GUARDED_OUTCOME;-
abs_entitlement_run_items;error_code;VARCHAR(80);true;GUARDED_OUTCOME;-
abs_entitlement_run_items;lease_version;BIGINT;false;CAS_RETURNING;-
abs_entitlement_run_items;cancel_fence;BIGINT;true;GUARDED_OUTCOME;-
abs_entitlement_input_versions;input_version_id;BIGINT;false;INSERT_RETURNING;-
abs_entitlement_input_versions;tenant_id;BIGINT;false;GUARDED_AUTHORITY;-
abs_entitlement_input_versions;public_id;UUID;false;ID_ALLOCATION;-
abs_entitlement_input_versions;created_at;TIMESTAMPTZ;false;TRANSACTION_CLOCK;-
abs_entitlement_input_versions;created_by;UUID;false;GUARDED_AUTHORITY;-
abs_entitlement_input_versions;run_public_id;UUID;false;ID_ALLOCATION;abs_entitlement_runs
abs_entitlement_input_versions;revision;BIGINT;false;LIFECYCLE_CONSTRUCTOR;-
abs_entitlement_input_versions;selected_count;INTEGER;false;IMMUTABLE_CONTENT;-
abs_entitlement_input_versions;selection_digest;CHAR(64);false;IMMUTABLE_CONTENT;-
abs_entitlement_input_versions;input_payload;JSONB;false;IMMUTABLE_CONTENT;-
abs_entitlement_input_versions;content_digest;CHAR(64);false;IMMUTABLE_CONTENT;-
abs_entitlement_selected_items;selected_item_id;BIGINT;false;INSERT_RETURNING;-
abs_entitlement_selected_items;tenant_id;BIGINT;false;GUARDED_AUTHORITY;-
abs_entitlement_selected_items;public_id;UUID;false;ID_ALLOCATION;-
abs_entitlement_selected_items;created_at;TIMESTAMPTZ;false;TRANSACTION_CLOCK;-
abs_entitlement_selected_items;created_by;UUID;false;GUARDED_AUTHORITY;-
abs_entitlement_selected_items;input_version_id;BIGINT;false;INSERT_RETURNING;abs_entitlement_input_versions
abs_entitlement_selected_items;ordinal;INTEGER;false;IMMUTABLE_CONTENT;-
abs_entitlement_selected_items;enrollment_id;BIGINT;false;LOCAL_TENANT_RESOLVE;abs_worker_plan_enrollments
abs_entitlement_selected_items;enrollment_revision;BIGINT;false;LOCAL_TENANT_RESOLVE;-
abs_entitlement_selected_items;employment_snapshot_id;BIGINT;false;INSERT_RETURNING;abs_owner_artifact_snapshots
abs_entitlement_selected_items;calendar_snapshot_id;BIGINT;false;INSERT_RETURNING;abs_calendar_version_snapshots
abs_entitlement_selected_items;selected_payload;JSONB;false;IMMUTABLE_CONTENT;-
abs_entitlement_selected_items;content_digest;CHAR(64);false;IMMUTABLE_CONTENT;-
abs_enrollment_termination_workflows;termination_workflow_id;BIGINT;false;INSERT_RETURNING;-
abs_enrollment_termination_workflows;tenant_id;BIGINT;false;GUARDED_AUTHORITY;-
abs_enrollment_termination_workflows;public_id;UUID;false;ID_ALLOCATION;-
abs_enrollment_termination_workflows;created_at;TIMESTAMPTZ;false;TRANSACTION_CLOCK;-
abs_enrollment_termination_workflows;created_by;UUID;false;GUARDED_AUTHORITY;-
abs_enrollment_termination_workflows;enrollment_id;BIGINT;false;LOCAL_TENANT_RESOLVE;abs_worker_plan_enrollments
abs_enrollment_termination_workflows;employment_ended_on;DATE;false;OWNER_REFETCH;-
abs_enrollment_termination_workflows;policy_treatment;VARCHAR(32);false;OWNER_REFETCH;-
abs_enrollment_termination_workflows;instruction_payload;JSONB;false;IMMUTABLE_CONTENT;-
abs_enrollment_termination_workflows;instruction_digest;CHAR(64);false;IMMUTABLE_CONTENT;-
abs_enrollment_termination_workflows;status;VARCHAR(32);false;LIFECYCLE_CONSTRUCTOR;-
abs_enrollment_termination_workflows;corrective_run_id;BIGINT;true;INSERT_RETURNING|NULL_INAPPLICABLE;abs_entitlement_runs
abs_enrollment_termination_workflows;settlement_intent_id;BIGINT;true;INSERT_RETURNING|NULL_INAPPLICABLE;abs_termination_settlement_intents
abs_enrollment_termination_workflows;final_settlement_public_id;UUID;true;NULL_INAPPLICABLE;-
abs_enrollment_termination_workflows;row_version;BIGINT;false;LIFECYCLE_CONSTRUCTOR;-
abs_termination_settlement_intents;settlement_intent_id;BIGINT;false;INSERT_RETURNING;-
abs_termination_settlement_intents;tenant_id;BIGINT;false;GUARDED_AUTHORITY;-
abs_termination_settlement_intents;public_id;UUID;false;ID_ALLOCATION;-
abs_termination_settlement_intents;created_at;TIMESTAMPTZ;false;TRANSACTION_CLOCK;-
abs_termination_settlement_intents;created_by;UUID;false;GUARDED_AUTHORITY;-
abs_termination_settlement_intents;termination_workflow_id;BIGINT;false;INSERT_RETURNING;abs_enrollment_termination_workflows
abs_termination_settlement_intents;revision;BIGINT;false;LIFECYCLE_CONSTRUCTOR;-
abs_termination_settlement_intents;command_payload;JSONB;false;IMMUTABLE_CONTENT;-
abs_termination_settlement_intents;command_digest;CHAR(64);false;IMMUTABLE_CONTENT;-
abs_termination_settlement_intents;owner_receipt_payload;JSONB;true;NULL_INAPPLICABLE;-
abs_termination_settlement_intents;status;VARCHAR(24);false;LIFECYCLE_CONSTRUCTOR;-
abs_termination_settlement_intents;row_version;BIGINT;false;LIFECYCLE_CONSTRUCTOR;-
        """;
    public static void oracle(Row row) {
        List<String[]> expected=FIXED_95.lines().map(s->s.split(";")).filter(p->p[0].equals(row.table())).toList();
        assertEquals(95,FIXED_95.lines().count());
        assertEquals(expected.size(),row.bindings().size(),"missing/extra slot");
        assertEquals(new HashSet<>(expected.stream().map(p->p[1]).toList()),
                new HashSet<>(row.bindings().stream().map(Binding::column).toList()));
        for(String[] e:expected) {
            Binding b=row.get(e[1]);assertEquals(e[2],b.sqlType(),e[1]+" SQL type");assertEquals(Boolean.parseBoolean(e[3]),b.nullable(),e[1]+" nullable");
            assertTrue(Arrays.asList(e[4].split("\\|")).contains(b.phase().name()),e[1]+" phase");
            assertFalse(b.sourceExpression().isBlank());
            if(!"-".equals(e[5])&&b.value()!=null) {
                assertNotNull(b.parent(),e[1]+" parent");assertEquals(e[5],b.parent().table(),e[1]+" parent table");
                assertEquals(row.get("tenant_id").value(),b.parent().tenantId(),e[1]+" tenant prefix");
            }
        }
    }
    public record Fixture(ObjectNode original,VerifiedCurrentHrisAuthorizationV2 authority,EntitlementInputAssemblerV4 assembler,
            NewInsertSpec spec,InsertReturning returning,Enrollment enrollment,VerifiedPolicy policy) {}
    public static Fixture setup(int n,String treatment,Mode mode) throws Exception {
        ObjectNode input=wire(fixture("inputsAB",n));var a=proof(input);var policy=policy(input,a,treatment);
        ObjectNode item=(ObjectNode)input.path("selectedItems").get(0),e=(ObjectNode)item.get("enrollment");
        Enrollment enrollment=new Enrollment(new Parent("abs_worker_plan_enrollments",41,71+n,uuid(e,"enrollmentPublicId")),policy.localParent(),
                nativeLong(e.get("rowVersion")),uuid(e,"workerPublicId"),uuid(e,"assignmentPublicId"),
                LocalDate.parse(text(e,"effectiveFrom")),null,"ACTIVE");
        UUID employmentId=uuid(item.path("employmentRef"),"snapshotPublicId"),calendarId=uuid(item.path("calendarRef"),"snapshotPublicId"),
                configurationId=uuid(item.path("configurationRef"),"snapshotPublicId"),selectedId=uuid(item,"selectedItemPublicId");
        VerifiedOwner employment=owner(OwnerKind.EMPLOYMENT,employmentId,a,employment(a,input));
        VerifiedOwner calendar=owner(OwnerKind.SYS_CALENDAR,calendarId,a,calendar());
        OwnerArtifactReturning employmentLocal=artifact(employment,101+n,"f0000000-0000-4000-8000-00000000010"+n);
        OwnerArtifactReturning calendarOwnerLocal=artifact(calendar,111+n,"f0000000-0000-4000-8000-00000000011"+n);
        UUID localCalendar=UUID.fromString("f0000000-0000-4000-8000-00000000012"+n);
        Selection s=new Selection(selectedId,enrollment,employment,employmentLocal,calendar,
                new CalendarArtifactReturning(new Parent("abs_calendar_version_snapshots",41,121+n,localCalendar),localCalendar,
                        calendarOwnerLocal,calendarOwnerLocal.local().internalId(),uuid(calendar.payload(),"calendarVersionPublicId"),nativeLong(calendar.payload().get("revision")),
                        text(calendar.header(),"contentSha256")),owner(OwnerKind.TIME_CONFIGURATION,configurationId,a,configuration(input,policy)),
                text(item,"unit"),nativeLong(item.get("ledgerRevision")),nativeLong(item.get("reservationRevision")),null,null);
        var assembler=new EntitlementInputAssemblerV4(port(),engine(),CLOCK);
        UUID run=uuid(input,"runPublicId"),inputId=uuid(input,"inputVersionPublicId");
        NewInsertSpec spec=assembler.prepare(new Request(LocalDate.parse(text(input,"periodStart")),LocalDate.parse(text(input,"periodEnd")),NOW,mode),
                new Allocations(run,inputId),policy,List.of(s),a);
        InsertReturning returned=new InsertReturning(new Parent("abs_entitlement_input_versions",41,201+n,inputId),
                new Parent("abs_entitlement_runs",41,221+n,run),0,Map.of(selectedId,new Parent("abs_entitlement_selected_items",41,241+n,selectedId)));
        return new Fixture(input,a,assembler,spec,returned,enrollment,policy);
    }
    public static OwnerArtifactReturning artifact(VerifiedOwner owner,long id,String allocatedUuid) {
        UUID local=UUID.fromString(allocatedUuid);
        return new OwnerArtifactReturning(new Parent("abs_owner_artifact_snapshots",41,id,local),local,owner.kind().contractId,
                uuid(owner.header(),"snapshotPublicId"),nativeLong(owner.header().get("businessRevision")),text(owner.header(),"contentSha256"));
    }
    public static LoadedRun claimed(Fixture f,Long fence) {
        return new LoadedRun(f.returning.run(),1,1,"RUNNING",ACTOR,NOW.plusSeconds(100),fence);
    }
    public static ItemReturning item(Parent returning) {return new ItemReturning(returning.publicId(),returning);}
    public static ObjectNode error(PersistedInput p) {
        ObjectNode o=JSON.createObjectNode();o.put("kind","FAILED");
        o.put("selectedItemPublicId",p.source().selections().getFirst().allocatedPublicId().toString());
        o.put("inputSha256",text(p.source().input(),"contentSha256"));o.put("leaseVersion","1");
        o.put("errorCode","ZERO_DENOMINATOR");o.put("failedStage","PRORATION");o.putArray("sourceRefs");
        o.put("observedAt",NOW.toString());return o;
    }
    @ParameterizedTest @ValueSource(ints={0,1}) void actualAB68RecordSlotsClosedAndFullPayload(int n) throws Exception {
        Fixture f=setup(n,"PRORATE_FINAL_PERIOD",Mode.POST);PersistedInput p=f.assembler.persist(f.spec,f.returning);
        oracle(p.run());oracle(p.input());oracle(p.selected().getFirst());
        ObjectNode o=error(p);Row outcome=f.assembler.outcome(p,1,
                item(new Parent("abs_entitlement_run_items",41,261+n,UUID.fromString("e0000000-0000-4000-8000-00000000026"+n))),o,claimed(f,null));
        oracle(outcome);assertNull(outcome.get("quantity").value());assertEquals("ZERO_DENOMINATOR",outcome.get("error_code").value());
        assertEquals(Phase.DATABASE_DEFAULT_RETURNING,p.run().get("row_version").phase());
        assertEquals(0L,p.run().get("row_version").value());assertEquals(0L,p.run().get("lease_version").value());
        assertEquals(p.run().get("input_version_id").value(),p.input().get("input_version_id").value());
        assertEquals(p.selected().getFirst().get("input_version_id").value(),outcome.get("input_version_id").value());
        assertEquals(p.selected().getFirst().get("enrollment_id").value(),outcome.get("enrollment_id").value());
        assertEquals(p.selected().getFirst().get("selected_item_id").value(),outcome.get("selected_item_id").value());
        engine().validate("V4.SqlBind.abs_entitlement_runs",p.run().wireValues());
        engine().validate("V4.SqlBind.abs_entitlement_input_versions",p.input().wireValues());
        engine().validate("V4.SqlBind.abs_entitlement_selected_items",p.selected().getFirst().wireValues());
        System.out.println("J1_RECORD_AB_"+n+":"+JSON.writeValueAsString(Map.of("run",p.run().wireValues(),"input",p.input().wireValues(),
                "selected",p.selected().getFirst().wireValues(),"outcome",outcome.wireValues())));
    }
    @Test void independentLocalArtifactUuidMustNotBeOwnerSnapshotUuidCopy() throws Exception {
        Fixture f=setup(0,"PRORATE_FINAL_PERIOD",Mode.POST);Selection old=f.spec.selections().getFirst();
        Selection local=new Selection(old.allocatedPublicId(),old.enrollment(),old.employment(),
                artifact(old.employment(),101,"f0000000-0000-4000-8000-000000000101"),
                old.calendar(),new CalendarArtifactReturning(new Parent("abs_calendar_version_snapshots",41,121,
                        UUID.fromString("f0000000-0000-4000-8000-000000000121")),UUID.fromString("f0000000-0000-4000-8000-000000000121"),
                        old.calendarArtifact().ownerArtifact(),old.calendarArtifact().ownerSnapshotIdReturning(),old.calendarArtifact().calendarVersionPublicId(),
                        old.calendarArtifact().calendarRevision(),old.calendarArtifact().contentDigest()),
                old.configuration(),old.unit(),old.ledgerRevision(),old.reservationRevision(),null,null);
        assertDoesNotThrow(()->f.assembler.prepare(new Request(LocalDate.of(2026,10,1),LocalDate.of(2026,11,1),NOW,Mode.POST),
                new Allocations(f.returning.run().publicId(),f.returning.input().publicId()),f.policy,List.of(local),f.authority));
    }
    @Test void selectedEnrollmentNeedsResolvedLeavePlanParentSource() {
        assertTrue(Arrays.stream(Enrollment.class.getRecordComponents()).anyMatch(c->c.getName().equals("planVersion")),
                "Missing actual abs_worker_plan_enrollments.leave_plan_version_id parent source");
    }
    @Test void resolvedEnrollmentPlanParentMustMatchCurrentPolicyNotJustPublicUuid() throws Exception {
        Fixture f=setup(0,"PRORATE_FINAL_PERIOD",Mode.POST);Selection s=f.spec.selections().getFirst();Enrollment e=s.enrollment();
        for(Parent wrong:List.of(new Parent("abs_leave_plan_versions",41,999,e.planVersion().publicId()),
                new Parent("abs_leave_plan_versions",42,42,e.planVersion().publicId()),
                new Parent("tme_leave_plan_versions",41,42,e.planVersion().publicId()))) {
            Enrollment invalid=new Enrollment(e.local(),wrong,e.revision(),e.worker(),e.assignment(),e.effectiveFrom(),e.effectiveTo(),e.status());
            Selection changed=new Selection(s.allocatedPublicId(),invalid,s.employment(),s.employmentArtifact(),s.calendar(),
                    s.calendarArtifact(),s.configuration(),s.unit(),s.ledgerRevision(),s.reservationRevision(),null,null);
            assertThrows(IllegalArgumentException.class,()->f.assembler.prepare(
                    new Request(LocalDate.of(2026,10,1),LocalDate.of(2026,11,1),NOW,Mode.POST),
                    new Allocations(f.returning.run().publicId(),f.returning.input().publicId()),f.policy,List.of(changed),f.authority));
        }
    }
    @Test void localArtifactReturningRequiresAllocationAndExactExternalSourceTuple() throws Exception {
        Fixture f=setup(0,"PRORATE_FINAL_PERIOD",Mode.POST);Selection s=f.spec.selections().getFirst();OwnerArtifactReturning r=s.employmentArtifact();
        assertNotEquals(r.local().publicId(),r.snapshotPublicId());
        assertEquals(s.calendarArtifact().ownerArtifact().local().table(),"abs_owner_artifact_snapshots");
        assertNotEquals(s.calendarArtifact().local().publicId(),uuid(s.calendar().header(),"snapshotPublicId"));
        List<OwnerArtifactReturning> mutations=List.of(
                new OwnerArtifactReturning(r.local(),ACTOR,r.ownerContractId(),r.snapshotPublicId(),r.snapshotRevision(),r.contentDigest()),
                new OwnerArtifactReturning(new Parent("abs_owner_artifact_snapshots",42,101,r.local().publicId()),r.allocatedPublicId(),
                        r.ownerContractId(),r.snapshotPublicId(),r.snapshotRevision(),r.contentDigest()),
                new OwnerArtifactReturning(r.local(),r.allocatedPublicId(),"TIME.PublishedScheduleSnapshot.proposal.v4",r.snapshotPublicId(),r.snapshotRevision(),r.contentDigest()),
                new OwnerArtifactReturning(r.local(),r.allocatedPublicId(),r.ownerContractId(),ACTOR,r.snapshotRevision(),r.contentDigest()),
                new OwnerArtifactReturning(r.local(),r.allocatedPublicId(),r.ownerContractId(),r.snapshotPublicId(),r.snapshotRevision()+1,r.contentDigest()),
                new OwnerArtifactReturning(r.local(),r.allocatedPublicId(),r.ownerContractId(),r.snapshotPublicId(),r.snapshotRevision(),"f".repeat(64)));
        for(OwnerArtifactReturning wrong:mutations) {
            Selection changed=new Selection(s.allocatedPublicId(),s.enrollment(),s.employment(),wrong,s.calendar(),s.calendarArtifact(),
                    s.configuration(),s.unit(),s.ledgerRevision(),s.reservationRevision(),null,null);
            assertThrows(IllegalArgumentException.class,()->f.assembler.prepare(
                    new Request(LocalDate.of(2026,10,1),LocalDate.of(2026,11,1),NOW,Mode.POST),
                    new Allocations(f.returning.run().publicId(),f.returning.input().publicId()),f.policy,List.of(changed),f.authority));
        }
        PersistedInput persisted=f.assembler.persist(f.spec,f.returning);
        CalendarArtifactReturning c=s.calendarArtifact();
        var wrongCalendar=new CalendarArtifactReturning(c.local(),c.allocatedPublicId(),c.ownerArtifact(),c.ownerSnapshotIdReturning()+1,
                c.calendarVersionPublicId(),c.calendarRevision(),c.contentDigest());
        Selection wrongParent=new Selection(s.allocatedPublicId(),s.enrollment(),s.employment(),s.employmentArtifact(),s.calendar(),wrongCalendar,
                s.configuration(),s.unit(),s.ledgerRevision(),s.reservationRevision(),null,null);
        assertThrows(IllegalArgumentException.class,()->f.assembler.prepare(
                new Request(LocalDate.of(2026,10,1),LocalDate.of(2026,11,1),NOW,Mode.POST),
                new Allocations(f.returning.run().publicId(),f.returning.input().publicId()),f.policy,List.of(wrongParent),f.authority));
        Parent returned=new Parent("abs_entitlement_run_items",41,261,UUID.fromString("e0000000-0000-4000-8000-000000000260"));
        assertThrows(IllegalArgumentException.class,()->f.assembler.outcome(persisted,1,
                new ItemReturning(null,returned),error(persisted),claimed(f,null)));
        assertThrows(IllegalArgumentException.class,()->f.assembler.outcome(persisted,1,
                new ItemReturning(ACTOR,returned),error(persisted),claimed(f,null)));
        ObjectNode badConfiguration=s.configuration().payload();badConfiguration.put("timeArtifactSha256","f".repeat(64));
        VerifiedOwner wrongConfig=owner(OwnerKind.TIME_CONFIGURATION,uuid(s.configuration().header(),"snapshotPublicId"),f.authority,badConfiguration);
        Selection wrongDependency=new Selection(s.allocatedPublicId(),s.enrollment(),s.employment(),s.employmentArtifact(),s.calendar(),s.calendarArtifact(),
                wrongConfig,s.unit(),s.ledgerRevision(),s.reservationRevision(),null,null);
        assertThrows(IllegalArgumentException.class,()->f.assembler.prepare(
                new Request(LocalDate.of(2026,10,1),LocalDate.of(2026,11,1),NOW,Mode.POST),
                new Allocations(f.returning.run().publicId(),f.returning.input().publicId()),f.policy,List.of(wrongDependency),f.authority));
    }
    @Test void callerClockCannotReuseExpiredGuardDespiteDifferentOwnerClock() throws Exception {
        Fixture f=setup(0,"PRORATE_FINAL_PERIOD",Mode.POST);
        var stale=new EntitlementInputAssemblerV4(port(),engine(),Clock.fixed(NOW.plusSeconds(30),ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class,()->stale.prepare(
                new Request(LocalDate.of(2026,10,1),LocalDate.of(2026,11,1),NOW,Mode.POST),
                new Allocations(f.returning.run().publicId(),f.returning.input().publicId()),f.policy,f.spec.selections(),f.authority));
    }
    @Test void currentRunLeaseOwnerMustBeActingPrincipal() throws Exception {
        Fixture f=setup(0,"PRORATE_FINAL_PERIOD",Mode.POST);PersistedInput p=f.assembler.persist(f.spec,f.returning);
        LoadedRun other=new LoadedRun(f.returning.run(),1,1,"RUNNING",UUID.fromString("f0000000-0000-4000-8000-000000000999"),NOW.plusSeconds(100),null);
        assertThrows(IllegalArgumentException.class,()->f.assembler.outcome(p,1,item(new Parent("abs_entitlement_run_items",41,261,ACTOR)),error(p),other));
    }
    @Test void postingRowClockComesFromPostingTransactionNotInputCreation() throws Exception {
        Fixture f=setup(0,"PRORATE_FINAL_PERIOD",Mode.POST);PersistedInput p=f.assembler.persist(f.spec,f.returning);
        var posting=new EntitlementInputAssemblerV4(port(),engine(),Clock.fixed(NOW.plusSeconds(1),ZoneOffset.UTC));
        Row item=posting.outcome(p,1,item(new Parent("abs_entitlement_run_items",41,261,ACTOR)),error(p),claimed(f,null));
        assertEquals(NOW.plusSeconds(1),item.get("created_at").value());
    }

    @Test void missingReturningWrongTenantWrongDefaultAndExistingCasAreNotNew() throws Exception {
        Fixture f=setup(0,"PRORATE_FINAL_PERIOD",Mode.POST);
        assertThrows(IllegalArgumentException.class,()->f.assembler.persist(f.spec,null));
        assertThrows(IllegalArgumentException.class,()->f.assembler.persist(f.spec,new InsertReturning(f.returning.input(),f.returning.run(),1,f.returning.selected())));
        assertThrows(IllegalArgumentException.class,()->f.assembler.persist(f.spec,new InsertReturning(
                new Parent("abs_entitlement_input_versions",42,201,f.returning.input().publicId()),f.returning.run(),0,f.returning.selected())));
        LoadedRun old=new LoadedRun(f.returning.run(),0,0,"QUEUED",null,null,null),next=claimed(f,null);
        assertEquals(next,f.assembler.claim(new ExistingCasMutation(old,0,0,next),f.authority));
        assertThrows(IllegalArgumentException.class,()->f.assembler.claim(new ExistingCasMutation(old,1,0,next),f.authority));
        assertThrows(IllegalArgumentException.class,()->f.assembler.claim(new ExistingCasMutation(null,0,0,next),f.authority));
    }
    @Test void immutableRefetchAndIndependentOracleMutationsFail() throws Exception {
        Fixture f=setup(0,"PRORATE_FINAL_PERIOD",Mode.POST);PersistedInput p=f.assembler.persist(f.spec,f.returning);ObjectNode doc=f.spec.input();
        ObjectNode selected=(ObjectNode)doc.path("selectedItems").get(0);
        assertEquals(doc,f.assembler.refetchInput(doc,List.of(selected),f.returning.run().publicId(),text(doc,"contentSha256")));
        ObjectNode wrong=doc.deepCopy();wrong.put("periodEnd","2026-11-02");
        assertThrows(IllegalArgumentException.class,()->f.assembler.refetchInput(wrong,List.of(selected),f.returning.run().publicId(),text(doc,"contentSha256")));
        List<Binding> missing=new ArrayList<>(p.run().bindings());missing.removeLast();
        assertThrows(AssertionError.class,()->oracle(new Row(p.run().table(),missing)));
        Binding actual=p.run().get("selected_count");List<Binding> typed=new ArrayList<>(p.run().bindings());
        typed.set(typed.indexOf(actual),new Binding(actual.column(),"BIGINT",false,1L,actual.phase(),actual.sourceExpression(),null));
        assertThrows(AssertionError.class,()->oracle(new Row(p.run().table(),typed)));
        assertThrows(IllegalArgumentException.class,()->new Binding("x","BIGINT",false,null,Phase.INSERT_RETURNING,"RETURNING.x",null));
        assertThrows(IllegalArgumentException.class,()->new Binding("x","BIGINT",false,1L,Phase.INSERT_RETURNING,"RETURNING.x",new Parent("x",41,2,ACTOR)));
    }
    @Test void cancellationFenceRetainsNullQuantitiesAndUnknownCannotClaimSuccess() throws Exception {
        Fixture f=setup(0,"PRORATE_FINAL_PERIOD",Mode.POST);PersistedInput p=f.assembler.persist(f.spec,f.returning);
        ObjectNode o=JSON.createObjectNode();o.put("kind","CANCELLED");o.put("selectedItemPublicId",f.spec.selections().getFirst().allocatedPublicId().toString());
        o.put("inputSha256",text(f.spec.input(),"contentSha256"));o.put("cancelFence","2");o.put("cancelledAt",NOW.toString());
        o.put("reasonCode","RUN_CANCELLED_BEFORE_ITEM_EFFECT");
        Parent item=new Parent("abs_entitlement_run_items",41,261,UUID.fromString("e0000000-0000-4000-8000-000000000260"));
        Row result=f.assembler.outcome(p,1,item(item),o,claimed(f,2L));oracle(result);assertNull(result.get("quantity").value());
        assertThrows(IllegalArgumentException.class,()->f.assembler.outcome(p,1,item(item),o,claimed(f,3L)));
        LoadedRun unknown=new LoadedRun(f.returning.run(),2,1,"RESULT_UNKNOWN",ACTOR,NOW.plusSeconds(100),2L);
        assertThrows(IllegalArgumentException.class,()->f.assembler.outcome(p,1,item(item),o,unknown));
    }
}

package com.dwp.services.time.absence.source;

import static com.dwp.services.time.absence.contracts.v4.AbsOwnerResponseV4.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.dwp.platform.contracts.hris.identity.v2.VerifiedCurrentHrisAuthorizationV2;
import com.dwp.services.time.absence.ports.AbsOwnerSnapshotRefetchPortV4;
import com.dwp.services.time.absence.ports.AbsOwnerSnapshotRefetchPortV4.*;
import com.dwp.services.time.absence.source.EntitlementInputAssemblerV4.Enrollment;

/** Employment-end instructions are not ordinary enrollment cancellation commands. Native writers are absent. */
public final class TerminationInstructionAssemblerV4 {
    // Internal employment-end command allocation / permission publication remains OPEN, no automatic grant.
    public static final String EMPLOYMENT_END_OPERATION="tim.internal.leave.employment-end.prepare.proposal.v4";
    public static final String EMPLOYMENT_END_PURPOSE="TIM_EMPLOYMENT_END_PREPARE";
    public enum Treatment { STOP_FUTURE_ONLY, PRORATE_FINAL_PERIOD, OWNER_APPROVED_SETTLEMENT }
    public enum SettlementKind { CLOSED_TIME_PERIOD, DURABLE_PAY_HANDOFF, PAST_CONSUMPTION }
    public record CancelEnrollment(Enrollment before,long expectedVersion,LocalDate effectiveOn,String reasonCode,
            long updateReturningVersion) {}
    public record EnrollmentCancellation(Parent enrollment,long expectedVersion,long returnedVersion,
            LocalDate effectiveTo,String status,String reasonCode) {}
    public record RealEmploymentEnd(UUID workflowPublicId,Enrollment enrollment,VerifiedTermination employmentEnd,
            VerifiedPolicy policy,ObjectNode ledgerSummary,Parent correctiveRun,UUID settlementIntentPublicId,
            SettlementKind settlementKind,VerifiedCurrentHrisAuthorizationV2 authority) {
        public RealEmploymentEnd {ledgerSummary=ledgerSummary==null?null:ledgerSummary.deepCopy();}
        @Override public ObjectNode ledgerSummary(){return ledgerSummary==null?null:ledgerSummary.deepCopy();}
    }
    public static final class NewInstruction {
        private final RealEmploymentEnd source;private final ObjectNode instruction,command;
        private final Treatment treatment;
        private NewInstruction(RealEmploymentEnd source,ObjectNode instruction,ObjectNode command,Treatment treatment) {
            this.source=source;this.instruction=instruction.deepCopy();this.command=command==null?null:command.deepCopy();
            this.treatment=treatment;
        }
        public ObjectNode instruction(){return instruction.deepCopy();}
        public Optional<ObjectNode> command(){return command==null?Optional.empty():Optional.of(command.deepCopy());}
        public LocalDate enrollmentEndExclusive(){return LocalDate.parse(text(source.employmentEnd.payload(),"employmentEndedOn")).plusDays(1);}
    }
    public record InsertReturning(Parent workflow,Parent intent) {}
    public record PersistedTermination(Row workflow,Optional<Row> intent,NewInstruction source,InsertReturning returning) {}
    public record ExistingWorkflow(Parent workflow,long version,String status) {}
    public record WorkflowCas(ExistingWorkflow before,long expectedVersion,ExistingWorkflow returning) {}
    private final AbsOwnerSnapshotRefetchPortV4 owners;private final SchemaValidator schema;private final Clock clock;
    public TerminationInstructionAssemblerV4(AbsOwnerSnapshotRefetchPortV4 owners,SchemaValidator schema,Clock clock) {
        this.owners=owners;this.schema=schema;this.clock=clock;
    }
    /** No HRM adapter parameter or invocation: ordinary cancel cannot synthesize a termination workflow. */
    public EnrollmentCancellation ordinaryCancel(CancelEnrollment action,VerifiedCurrentHrisAuthorizationV2 a) {
        current(a,"tim.leave.enrollment.cancel","TIM_ENROLLMENT_CANCEL");
        require(action!=null&&action.before!=null&&action.before.local()!=null&&"ACTIVE".equals(action.before.status())
                &&action.before.local().table().equals("abs_worker_plan_enrollments")
                &&action.before.local().tenantId()==a.actor().tenantId()
                &&action.before.planVersion()!=null&&action.before.planVersion().table().equals("abs_leave_plan_versions")
                &&action.before.planVersion().tenantId()==a.actor().tenantId(),"CANCEL_PRESTATE_REQUIRED");
        require(action.before.worker().equals(a.target().workerPublicId())&&action.before.assignment().equals(a.target().assignmentPublicId()),
                "CANCEL_TARGET_MISMATCH");
        require(action.expectedVersion==action.before.revision()&&action.expectedVersion<Long.MAX_VALUE
                &&action.updateReturningVersion==action.expectedVersion+1,"CANCEL_CAS_CONFLICT");
        require(action.effectiveOn!=null&&!action.effectiveOn.isBefore(action.before.effectiveFrom())
                &&(action.before.effectiveTo()==null||!action.effectiveOn.isAfter(action.before.effectiveTo()))
                &&Set.of("USER_REQUEST","SOURCE_CHANGE").contains(action.reasonCode),"CANCEL_NORMALIZED_INPUT_INVALID");
        return new EnrollmentCancellation(action.before.local(),action.expectedVersion,action.updateReturningVersion,
                action.effectiveOn,"CANCELLED",action.reasonCode);
    }
    public NewInstruction prepare(RealEmploymentEnd source) {
        require(owners!=null&&clock!=null&&source!=null&&source.authority!=null,"CURRENT_SOURCE_MISSING");
        current(source.authority,EMPLOYMENT_END_OPERATION,EMPLOYMENT_END_PURPOSE);require(source.employmentEnd!=null&&source.policy!=null
                &&source.employmentEnd.authority()==source.authority&&source.policy.authority()==source.authority,
                "CURRENT_TERMINATION_AND_POLICY_REFETCH_REQUIRED");
        require(source.workflowPublicId!=null&&source.enrollment!=null&&source.enrollment.local()!=null
                &&source.enrollment.local().table().equals("abs_worker_plan_enrollments")
                &&source.enrollment.local().tenantId()==source.authority.actor().tenantId()
                &&"ACTIVE".equals(source.enrollment.status())&&source.enrollment.revision()>=0,"WORKFLOW_ALLOCATION_OR_ENROLLMENT_MISSING");
        require(source.enrollment.planVersion()!=null&&source.enrollment.planVersion().equals(source.policy.localParent()),
                "END_ENROLLMENT_PLAN_VERSION_PARENT_MISMATCH");
        ObjectNode ended=source.employmentEnd.payload(),ledger=source.ledgerSummary();
        validate(schema,"LEDGER_SUMMARY",ledger);
        require(uuid(ledger,"enrollmentPublicId").equals(source.enrollment.local().publicId())
                &&text(ledger,"unit").equals(text(source.policy.fullPayload(),"unit"))
                &&text(ledger,"summarySha256").equals(ownSlotDigest(ledger,"summarySha256")),"LEDGER_SOURCE_BINDING_MISMATCH");
        require(uuid(ended,"workerPublicId").equals(source.enrollment.worker()),"END_ENROLLMENT_WORKER_MISMATCH");
        boolean assignment=false;for(JsonNode id:ended.path("assignmentPublicIds"))
            if(id.asText().equals(source.enrollment.assignment().toString()))assignment=true;
        require(assignment&&source.enrollment.worker().equals(source.authority.target().workerPublicId())
                &&source.enrollment.assignment().equals(source.authority.target().assignmentPublicId()),"END_ENROLLMENT_ASSIGNMENT_MISMATCH");
        LocalDate last=LocalDate.parse(text(ended,"employmentEndedOn"));
        require(!last.isBefore(source.enrollment.effectiveFrom()),"EMPLOYMENT_END_BEFORE_ENROLLMENT");
        Treatment treatment;
        try{treatment=Treatment.valueOf(text(source.policy.fullPayload(),"terminationTreatment"));}
        catch(RuntimeException ex){throw denied("UNKNOWN_TERMINATION_TREATMENT");}
        require(treatment==Treatment.PRORATE_FINAL_PERIOD?source.correctiveRun!=null:source.correctiveRun==null,
                "CORRECTIVE_RUN_BRANCH_REQUIRED_OR_FORBIDDEN");
        if(source.correctiveRun!=null)require(source.correctiveRun.table().equals("abs_entitlement_runs")
                &&source.correctiveRun.tenantId()==source.authority.actor().tenantId(),"CORRECTIVE_RUN_PARENT_MISMATCH");
        require(treatment==Treatment.OWNER_APPROVED_SETTLEMENT
                ?source.settlementIntentPublicId!=null&&source.settlementKind!=null
                :source.settlementIntentPublicId==null&&source.settlementKind==null,"SETTLEMENT_INTENT_BRANCH_REQUIRED_OR_FORBIDDEN");
        ObjectNode instruction=JSON.createObjectNode();instruction.put("wireContractId",WIRE_ID);
        instruction.put("workflowPublicId",source.workflowPublicId.toString());instruction.set("enrollment",source.enrollment.wire());
        instruction.set("employmentTermination",ended);instruction.set("policyRef",source.policy.reference());
        instruction.set("policyContent",source.policy.fullPayload());instruction.set("ledgerSummary",ledger);
        instruction.put("treatment",treatment.name());instruction=seal(instruction,"instructionSha256");validate(schema,"INSTRUCTION",instruction);
        ObjectNode command=null;
        if(treatment==Treatment.OWNER_APPROVED_SETTLEMENT) {
            command=JSON.createObjectNode();command.put("requestPublicId",source.settlementIntentPublicId.toString());
            command.put("workflowPublicId",source.workflowPublicId.toString());command.put("revision","1");
            command.set("enrollment",source.enrollment.wire());command.set("employmentTerminationRef",terminationReference(ended,source.authority));
            command.set("policyRef",source.policy.reference());command.set("ledgerSummary",ledger);
            command.put("settlementKind",source.settlementKind.name());command.put("purposeCode","TIM_TERMINATION_SETTLEMENT");
            command=seal(command,"commandSha256");validate(schema,"SETTLEMENT_COMMAND",command);
        }
        current(source.authority,EMPLOYMENT_END_OPERATION,EMPLOYMENT_END_PURPOSE);
        return new NewInstruction(source,instruction,command,treatment);
    }
    private ObjectNode terminationReference(ObjectNode ended,VerifiedCurrentHrisAuthorizationV2 a) {
        ObjectNode ref=JSON.createObjectNode();ref.put("contractId","HRM.EmploymentTerminationSnapshot.v2");ref.put("schemaVersion",2);
        for(String key:List.of("snapshotPublicId","revision","contentSha256","asOf","validUntil"))ref.set(key,ended.get(key));
        ref.put("ownerService","dwp-people-server");ref.put("streamKey","people-main");
        ref.put("purposeCode",a.requirements().purpose());ref.put("populationScopeDigest",candidateTargetBindingDigest(a));return ref;
    }
    public PersistedTermination persist(NewInstruction source,InsertReturning returned) {
        require(source!=null&&returned!=null,"TERMINATION_INSERT_RETURNING_MISSING");
        current(source.source.authority,EMPLOYMENT_END_OPERATION,EMPLOYMENT_END_PURPOSE);
        long tenant=source.source.authority.actor().tenantId();Parent workflow=returned.workflow;
        require(workflow!=null&&workflow.table().equals("abs_enrollment_termination_workflows")
                &&workflow.tenantId()==tenant&&workflow.publicId().equals(source.source.workflowPublicId),"WORKFLOW_RETURNING_PARENT_MISMATCH");
        boolean settlement=source.treatment==Treatment.OWNER_APPROVED_SETTLEMENT;
        require(settlement?returned.intent!=null:returned.intent==null,"INAPPLICABLE_INTENT_NOT_A_NULL_FILLED_ROW");
        if(settlement)require(returned.intent.table().equals("abs_termination_settlement_intents")&&returned.intent.tenantId()==tenant
                &&returned.intent.publicId().equals(source.source.settlementIntentPublicId),"INTENT_RETURNING_PARENT_MISMATCH");
        Instant transactionAt=clock.instant();List<Binding> rows=base(source,workflow,"termination_workflow_id",transactionAt);
        rows.add(parent("enrollment_id",false,source.source.enrollment.local(),Phase.LOCAL_TENANT_RESOLVE,"tenant/public enrollment loaded parent"));
        rows.add(bind("employment_ended_on","DATE",false,LocalDate.parse(text(source.source.employmentEnd.payload(),"employmentEndedOn")),
                Phase.OWNER_REFETCH,"HRM current employmentEnd inclusive DATE, not cancel body"));
        rows.add(bind("policy_treatment","VARCHAR(32)",false,source.treatment.name(),Phase.OWNER_REFETCH,"current ABS full policy.terminationTreatment"));
        rows.add(bind("instruction_payload","JSONB",false,source.instruction,Phase.IMMUTABLE_CONTENT,"full closed instruction source vector"));
        rows.add(bind("instruction_digest","CHAR(64)",false,text(source.instruction,"instructionSha256"),Phase.IMMUTABLE_CONTENT,"SHA256(instruction excluding own digest)"));
        String status=switch(source.treatment){case STOP_FUTURE_ONLY->"STOPPED";case PRORATE_FINAL_PERIOD->"CORRECTION_QUEUED";case OWNER_APPROVED_SETTLEMENT->"SETTLEMENT_PENDING";};
        rows.add(bind("status","VARCHAR(32)",false,status,Phase.LIFECYCLE_CONSTRUCTOR,"exact NEW workflow treatment branch"));
        rows.add(parent("corrective_run_id",true,source.source.correctiveRun,source.source.correctiveRun==null?Phase.NULL_INAPPLICABLE:Phase.INSERT_RETURNING,
                "PRORATE new corrective run RETURNING; other treatments NULL"));
        rows.add(parent("settlement_intent_id",true,returned.intent,settlement?Phase.INSERT_RETURNING:Phase.NULL_INAPPLICABLE,
                "OWNER_SETTLEMENT intent RETURNING attached by deferred tenant FK/CAS plan; others NULL"));
        rows.add(bind("final_settlement_public_id","UUID",true,null,Phase.NULL_INAPPLICABLE,"NEW has no PAY matched success receipt"));
        rows.add(bind("row_version","BIGINT",false,0L,Phase.LIFECYCLE_CONSTRUCTOR,"NEW_WORKFLOW technical epoch0; no historical DB default"));
        Row workflowRow=new Row("abs_enrollment_termination_workflows",rows);
        Optional<Row> intent=Optional.empty();
        if(settlement) {
            List<Binding> values=base(source,returned.intent,"settlement_intent_id",transactionAt);
            values.add(parent("termination_workflow_id",false,workflow,Phase.INSERT_RETURNING,"workflow exact INSERT RETURNING parent"));
            values.add(bind("revision","BIGINT",false,1L,Phase.LIFECYCLE_CONSTRUCTOR,"NEW_IMMUTABLE_SETTLEMENT_COMMAND revision1"));
            values.add(bind("command_payload","JSONB",false,source.command,Phase.IMMUTABLE_CONTENT,"full closed PAY command"));
            values.add(bind("command_digest","CHAR(64)",false,text(source.command,"commandSha256"),Phase.IMMUTABLE_CONTENT,"SHA256(command excluding commandSha256)"));
            values.add(bind("owner_receipt_payload","JSONB",true,null,Phase.NULL_INAPPLICABLE,"NEW awaiting actual matched PAY receipt"));
            values.add(bind("status","VARCHAR(24)",false,"DELIVERY_PENDING",Phase.LIFECYCLE_CONSTRUCTOR,"NEW intent DELIVERY_PENDING, not ACCEPTED"));
            values.add(bind("row_version","BIGINT",false,0L,Phase.LIFECYCLE_CONSTRUCTOR,"NEW_INTENT technical epoch0; no historical DB default"));
            intent=Optional.of(new Row("abs_termination_settlement_intents",values));
        }
        validate(schema,"V4.SqlBind.abs_enrollment_termination_workflows",workflowRow.wireValues());
        intent.ifPresent(i->validate(schema,"V4.SqlBind.abs_termination_settlement_intents",i.wireValues()));
        current(source.source.authority,EMPLOYMENT_END_OPERATION,EMPLOYMENT_END_PURPOSE);
        return new PersistedTermination(workflowRow,intent,source,returned);
    }
    public ExistingWorkflow observeCas(WorkflowCas cas,VerifiedCurrentHrisAuthorizationV2 a) {
        current(a,EMPLOYMENT_END_OPERATION,EMPLOYMENT_END_PURPOSE);
        require(cas!=null&&cas.before!=null&&cas.returning!=null,"WORKFLOW_CAS_PRESTATE_AND_RETURNING_REQUIRED");
        require(cas.before.workflow.equals(cas.returning.workflow)&&cas.before.workflow.tenantId()==a.actor().tenantId()
                &&cas.expectedVersion==cas.before.version&&cas.before.version<Long.MAX_VALUE
                &&cas.returning.version==cas.before.version+1,"WORKFLOW_CAS_CONFLICT");
        require(!Set.of("COMPLETED","REJECTED","STOPPED").contains(cas.before.status),"TERMINAL_WORKFLOW_IMMUTABLE");
        // Owner receipt-based state transitions belong to a separately allocated native receipt assembler.
        require(cas.before.status.equals(cas.returning.status),"OWNER_RECEIPT_STATE_SOURCE_REQUIRED");
        return cas.returning;
    }
    private void current(VerifiedCurrentHrisAuthorizationV2 a,String operation,String purpose) {
        require(owners!=null&&clock!=null,"CURRENT_SOURCE_MISSING");owners.current(a,clock);
        require(operation.equals(a.requirements().operationId())&&purpose.equals(a.requirements().purpose()),
                "WRONG_TERMINATION_OR_CANCEL_OPERATION_AUTHORITY");
    }
    private List<Binding> base(NewInstruction source,Parent returned,String id,Instant transactionAt) {
        var a=source.source.authority;List<Binding> row=new ArrayList<>();
        row.add(bind(id,"BIGINT",false,returned.internalId(),Phase.INSERT_RETURNING,"native INSERT RETURNING "+id));
        row.add(bind("tenant_id","BIGINT",false,a.actor().tenantId(),Phase.GUARDED_AUTHORITY,"guarded ACTOR Auth tenant"));
        row.add(bind("public_id","UUID",false,returned.publicId(),Phase.ID_ALLOCATION,"allocated business UUID matched RETURNING"));
        row.add(bind("created_at","TIMESTAMPTZ",false,transactionAt,Phase.TRANSACTION_CLOCK,"row INSERT transaction Clock.instant; not prepared instruction capture time"));
        row.add(bind("created_by","UUID",false,a.actor().principalPublicId(),Phase.GUARDED_AUTHORITY,"guarded ACTOR principal UUID, never target Auth"));
        return row;
    }
}

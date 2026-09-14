package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class WorkplaceExperienceFacilitiesRetentionPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    static final AtomicLong TENANTS=new AtomicLong(9_950_000);
    static JdbcTemplate jdbc; static TransactionTemplate transaction;
    static WorkplaceExperienceFacilitiesRetention retention;
    static WorkplaceExperienceCollaborationRepository collaboration;
    @BeforeAll static void migrate() {
        var ds=new PGSimpleDataSource();ds.setURL(POSTGRES.getJdbcUrl());ds.setUser(POSTGRES.getUsername());ds.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("filesystem:src/main/resources/db/migration","filesystem:../dwp-core/src/main/resources/db/migration").load().migrate();
        jdbc=new JdbcTemplate(ds);transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));
        retention=new WorkplaceExperienceFacilitiesRetention(jdbc);collaboration=new WorkplaceExperienceCollaborationRepository(jdbc);
    }
    @Test void terminalRequestsUseActualCurrentTenantPolicyBoundaryAndRedactNativeAuditCopies() {
        var f=fixture(30);var other=fixture(60);
        UUID outside=request(other,"RESOLVED",31);UUID old=request(f,"RESOLVED",31);UUID audit=audit(f,old,"FACILITY_REQUEST","STANDARD");
        tx(() -> {UUID exact=request(f,"CANCELLED",30),open=request(f,"OPEN",100),working=request(f,"IN_PROGRESS",100),recent=request(f,"RESOLVED",29);
            assertThat(collaboration.privacy(f.tenant).facilityRequestEligibleRetentionCount()).isOne();
            assertThat(retention.purgeTenant(f.tenant,100)).isEqualTo(new WorkplaceExperienceFacilitiesRetention.Purged(1,0));
            assertThat(exists("wp_experience_facility_requests","request_id",old)).isFalse();
            for(UUID id:new UUID[]{exact,open,working,recent,outside}) assertThat(exists("wp_experience_facility_requests","request_id",id)).isTrue();
            var privacy=collaboration.privacy(f.tenant);assertThat(privacy.facilityRequestsPurgedCount()).isOne();
            assertThat(privacy.facilityRequestEligibleRetentionCount()).isZero();assertThat(collaboration.privacy(other.tenant).facilityRequestsPurgedCount()).isZero();
            return null; });
        assertRedacted(audit);
    }
    @Test void closureRetentionKeepsFutureRecentAndLegalHeldIntervalsButPurgesExpiredInactivePeriods() {
        var f=fixture(30);UUID old=closure(f,40,40,"ACTIVE"),cancelled=closure(f,60,60,"CANCELLED");
        UUID future=closure(f,-5,60,"CANCELLED"),freshEnd=closure(f,10,60,"ACTIVE"),freshReason=closure(f,60,10,"CANCELLED");
        UUID held=closure(f,50,50,"ACTIVE");heldBooking(f,held,true);
        audit(f,old,"FACILITY_CLOSURE","STANDARD");
        assertThat(collaboration.privacy(f.tenant).facilityClosureEligibleRetentionCount()).isEqualTo(2);
        assertThat(tx(() -> retention.purgeTenant(f.tenant,100))).isEqualTo(new WorkplaceExperienceFacilitiesRetention.Purged(0,2));
        assertThat(exists("wp_experience_facility_closures","closure_id",old)).isFalse();assertThat(exists("wp_experience_facility_closures","closure_id",cancelled)).isFalse();
        for(UUID id:new UUID[]{future,freshEnd,freshReason,held}) assertThat(exists("wp_experience_facility_closures","closure_id",id)).isTrue();
        assertThat(collaboration.privacy(f.tenant).facilityClosuresPurgedCount()).isEqualTo(2);
    }
    @Test void centralLegalHoldCaseEvidenceAndInFlightAuditDeliveryProtectFacilityRows() {
        var f=fixture(30);UUID held=request(f,"CANCELLED",40),sending=request(f,"RESOLVED",40),caseBound=request(f,"RESOLVED",40);
        audit(f,held,"FACILITY_REQUEST","LEGAL_HOLD");UUID sendingAudit=audit(f,sending,"FACILITY_REQUEST","STANDARD");
        jdbc.update("UPDATE sys_audit_outbox SET status='SENDING',locked_by='test-worker',locked_until=CURRENT_TIMESTAMP+INTERVAL '1 minute' WHERE event_id=?",sendingAudit);
        UUID caseAudit=audit(f,caseBound,"FACILITY_REQUEST","STANDARD"),caseId=UUID.randomUUID();
        jdbc.update("INSERT INTO sys_audit_cases(case_id,tenant_id,title,severity,status,created_by,updated_by,due_at) VALUES(?,?,'Preserve evidence','HIGH','OPEN','7','7',CURRENT_TIMESTAMP+INTERVAL '7 days')",caseId,f.tenant);
        jdbc.update("INSERT INTO sys_audit_case_events(case_id,event_id,event_occurred_at,added_by) SELECT ?,audit_event_id,occurred_at,'7' FROM wp_audit_events WHERE audit_event_id=?",caseId,caseAudit);
        assertThat(collaboration.privacy(f.tenant).facilityRequestEligibleRetentionCount()).isZero();
        assertThat(tx(() -> retention.purgeTenant(f.tenant,100))).isEqualTo(new WorkplaceExperienceFacilitiesRetention.Purged(0,0));
        for(UUID id:new UUID[]{held,sending,caseBound}) assertThat(exists("wp_experience_facility_requests","request_id",id)).isTrue();
    }
    @Test void failedPhysicalPurgeRollsBackAllSnapshotRedactionAndCounters() {
        var f=fixture(30);UUID old=request(f,"RESOLVED",40),event=audit(f,old,"FACILITY_REQUEST","STANDARD");
        jdbc.update("UPDATE wp_experience_facility_requests SET description='force fail retention' WHERE request_id=?",old);
        assertThatThrownBy(() -> tx(() -> {
            jdbc.execute("CREATE FUNCTION test_reject_facility_retention() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN IF OLD.description='force fail retention' THEN RAISE EXCEPTION 'Injected retention failure'; END IF; RETURN OLD; END $$");
            jdbc.execute("CREATE TRIGGER test_facility_retention_failure BEFORE DELETE ON wp_experience_facility_requests FOR EACH ROW EXECUTE FUNCTION test_reject_facility_retention()");
            return retention.purgeTenant(f.tenant,100);
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(exists("wp_experience_facility_requests","request_id",old)).isTrue();
        assertThat(jdbc.queryForObject("SELECT snapshot::text FROM wp_audit_events WHERE audit_event_id=?",String.class,event)).contains("private-phone");
        assertThat(jdbc.queryForObject("SELECT payload::text FROM sys_audit_outbox WHERE event_id=?",String.class,event)).contains("private-phone");
        assertThat(exists("sys_audit_events","event_id",event)).isTrue();assertThat(collaboration.privacy(f.tenant).facilityRequestsPurgedCount()).isZero();
    }
    @Test void concurrentWorkersSkipLockedRowsAndCountOnePhysicalPurge() throws Exception {
        var f=fixture(30);request(f,"RESOLVED",40);var pool=Executors.newFixedThreadPool(2);
        var written=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var first=pool.submit(() -> tx(() -> {var count=retention.purgeTenant(f.tenant,100);written.countDown();await(release);return count;}));
            assertThat(written.await(10,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(() -> tx(() -> retention.purgeTenant(f.tenant,100)));
            assertThat(second.get(5,TimeUnit.SECONDS).requests()).isZero();release.countDown();assertThat(first.get(5,TimeUnit.SECONDS).requests()).isOne();
            assertThat(collaboration.privacy(f.tenant).facilityRequestsPurgedCount()).isOne();
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void legalHoldCommittedDuringBookingRowWaitIsRecheckedBeforeClosureDeletion() throws Exception {
        var f=fixture(30);UUID closure=closure(f,40,40,"ACTIVE"),booking=heldBooking(f,closure,false);
        var pool=Executors.newFixedThreadPool(2);var held=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        try {
            var holder=pool.submit(() -> tx(() -> {jdbc.update("UPDATE wp_bookings SET legal_hold=TRUE WHERE booking_id=?",booking);held.countDown();await(release);return true;}));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            var purge=pool.submit(() -> {started.countDown();return tx(() -> retention.purgeTenant(f.tenant,100));});
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            try {assertThatThrownBy(() -> purge.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);} finally {release.countDown();}
            holder.get(5,TimeUnit.SECONDS);assertThat(purge.get(5,TimeUnit.SECONDS).closures()).isZero();
            assertThat(exists("wp_experience_facility_closures","closure_id",closure)).isTrue();
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void facilityRedactionDoesNotRelaxUnrelatedAuditImmutabilityOrRemainEnabledAfterJob() {
        var f=fixture(30);UUID old=request(f,"RESOLVED",40),event=audit(f,old,"FACILITY_REQUEST","STANDARD");
        tx(() -> {retention.purgeTenant(f.tenant,100);
            assertThat(jdbc.queryForObject("SELECT current_setting('dwp.facility_retention_redaction',TRUE)",String.class)).isEqualTo("off");return null;});
        assertThatThrownBy(() -> jdbc.update("UPDATE wp_audit_events SET snapshot='{}'::jsonb WHERE audit_event_id=?",event)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        UUID unrelated=UUID.randomUUID();jdbc.update("INSERT INTO wp_audit_events(audit_event_id,tenant_id,action,aggregate_type,actor_user_id,snapshot) VALUES(?,?,'workplace.booking.created','BOOKING',7,'{}'::jsonb)",unrelated,f.tenant);
        assertThatThrownBy(() -> tx(() -> {jdbc.execute("SELECT set_config('dwp.facility_retention_redaction','on',true)");
            return jdbc.update("UPDATE wp_audit_events SET actor_user_id=0,snapshot='{\"retentionAction\":\"FACILITY_PERSONAL_DATA_REDACTED\"}'::jsonb WHERE audit_event_id=?",unrelated);}))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM wp_audit_events WHERE audit_event_id=?",Long.class,unrelated)).isEqualTo(7L);
    }
    static <T>T tx(Supplier<T> action){return transaction.execute(s -> action.get());}
    static Fixture fixture(int days){long tenant=TENANTS.incrementAndGet();UUID site=UUID.randomUUID(),floor=UUID.randomUUID(),resource=UUID.randomUUID();
        jdbc.update("INSERT INTO sys_service_tenants(provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,data_region,isolation_model,created_by,updated_by) VALUES(?,?,?,'Retention test','ACTIVE','kr','POOL',7,7)",UUID.randomUUID(),tenant,"retention_"+tenant);
        jdbc.update("INSERT INTO wp_tenant_policies(tenant_id,booking_retention_days) VALUES(?,?)",tenant,days);
        jdbc.update("INSERT INTO wp_sites(site_id,tenant_id,site_code,name_ko,name_en) VALUES(?,?,?,'Seoul','Seoul')",site,tenant,"SITE_"+site);
        jdbc.update("INSERT INTO wp_floors(floor_id,tenant_id,site_id,floor_number,name_ko,name_en,lifecycle_state) VALUES(?,?,?,1,'1F','1F','ACTIVE')",floor,tenant,site);
        jdbc.update("INSERT INTO wp_resources(resource_id,tenant_id,floor_id,resource_code,name_ko,name_en,resource_type) VALUES(?,?,?,?,'Desk','Desk','DESK')",resource,tenant,floor,"R_"+resource);return new Fixture(tenant,site,resource);}
    static UUID request(Fixture f,String status,int age){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO wp_experience_facility_requests(request_id,tenant_id,resource_id,requester_user_id,category,description,request_status,status_reason,idempotency_key,request_fingerprint,created_at,updated_at,updated_by) VALUES(?,?,?,7,'REPAIR','private-phone',?,'private-phone',?,repeat('a',64),CURRENT_TIMESTAMP-make_interval(days=>?),CURRENT_TIMESTAMP-make_interval(days=>?),7)",id,f.tenant,f.resource,status,"key_"+id,age,age);return id;}
    static UUID closure(Fixture f,int endedAge,int updatedAge,String status){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO wp_experience_facility_closures(closure_id,tenant_id,resource_id,starts_at,ends_at,closure_status,reason,cancellation_reason,idempotency_key,request_fingerprint,resource_version_at_create,created_at,updated_at,created_by,updated_by) VALUES(?,?,?,CURRENT_TIMESTAMP-make_interval(days=>?)-INTERVAL '1 hour',CURRENT_TIMESTAMP-make_interval(days=>?),?,'private-phone','private-phone',?,repeat('a',64),0,CURRENT_TIMESTAMP-make_interval(days=>?),CURRENT_TIMESTAMP-make_interval(days=>?),7,7)",id,f.tenant,f.resource,endedAge,endedAge,status,"key_"+id,updatedAge,updatedAge);return id;}
    static UUID audit(Fixture f,UUID target,String kind,String retentionClass){UUID id=UUID.randomUUID();String action="FACILITY_REQUEST".equals(kind)?"workplace.facility.request_status_changed":"workplace.facility.closure_cancelled";
        jdbc.update("INSERT INTO wp_audit_events(audit_event_id,tenant_id,action,aggregate_type,aggregate_id,actor_user_id,correlation_id,snapshot,occurred_at) VALUES(?,?,?,?,?,7,'private-phone','{\"reason\":\"private-phone\",\"description\":\"private-phone\"}'::jsonb,CURRENT_TIMESTAMP-INTERVAL '40 days')",id,f.tenant,action,kind,target);
        jdbc.update("INSERT INTO sys_audit_events(event_id,occurred_at,event_version,tenant_id,category,action,outcome,severity,actor_type,actor_id,source_service,source_module,environment,target_type,target_id,after_state,retention_class,record_hash) SELECT audit_event_id,occurred_at,'1.0',tenant_id,'ADMIN_CHANGE',action,'SUCCESS','INFO','USER','7','dwp-platform-server','platform-administration','test',aggregate_type,aggregate_id::text,snapshot,?,repeat('b',64) FROM wp_audit_events WHERE audit_event_id=?",retentionClass,id);return id;}
    static UUID heldBooking(Fixture f,UUID closure,boolean hold){return tx(() -> {
        var row=jdbc.queryForObject("SELECT starts_at,ends_at,created_at,updated_at FROM wp_experience_facility_closures WHERE closure_id=?",(rs,i)->new OffsetDateTime[]{rs.getObject(1,OffsetDateTime.class),rs.getObject(2,OffsetDateTime.class),rs.getObject(3,OffsetDateTime.class),rs.getObject(4,OffsetDateTime.class)},closure);
        // Build the historical fixture in production order, with every trigger enabled.
        jdbc.update("DELETE FROM wp_experience_facility_closures WHERE tenant_id=? AND closure_id=?",f.tenant,closure);
        var mapper=new ObjectMapper().findAndRegisterModules();UUID id=new WorkplaceBookingRepository(jdbc,mapper).createBooking(f.tenant,7L,null,"Private held reservation",new WorkplaceDtos.BookingRequest(f.resource,row[0],row[1],null,false),new WorkplaceCatalogRepository(jdbc,mapper).policy(f.tenant),null,false).bookingId();
        jdbc.update("INSERT INTO wp_experience_facility_closures(closure_id,tenant_id,resource_id,starts_at,ends_at,reason,idempotency_key,request_fingerprint,resource_version_at_create,created_at,updated_at,created_by,updated_by) VALUES(?,?,?,?,?,'private-phone',?,repeat('a',64),0,?,?,7,7)",closure,f.tenant,f.resource,row[0],row[1],"key_"+closure,row[2],row[3]);
        jdbc.update("UPDATE wp_bookings SET legal_hold=?,booking_status='COMPLETED' WHERE booking_id=?",hold,id);return id;});}
    static boolean exists(String table,String field,UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM "+table+" WHERE "+field+"=?)",Boolean.class,id));}
    static void assertRedacted(UUID event){assertThat(jdbc.queryForObject("SELECT snapshot::text FROM wp_audit_events WHERE audit_event_id=?",String.class,event)).contains("FACILITY_PERSONAL_DATA_REDACTED").doesNotContain("private-phone");
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM wp_audit_events WHERE audit_event_id=?",Long.class,event)).isZero();
        assertThat(jdbc.queryForObject("SELECT after_snapshot FROM sys_platform_audit_events WHERE audit_event_id=?",String.class,event)).contains("FACILITY_PERSONAL_DATA_REDACTED").doesNotContain("private-phone");
        assertThat(jdbc.queryForObject("SELECT payload::text FROM sys_audit_outbox WHERE event_id=?",String.class,event)).contains("FACILITY_PERSONAL_DATA_REDACTED").doesNotContain("private-phone");assertThat(exists("sys_audit_events","event_id",event)).isFalse();}
    static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Retention test release timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    record Fixture(long tenant,UUID site,UUID resource){}
}

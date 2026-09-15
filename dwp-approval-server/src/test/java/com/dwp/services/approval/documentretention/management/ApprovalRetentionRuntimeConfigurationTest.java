package com.dwp.services.approval.documentretention.management;

import com.dwp.services.approval.documentretention.ApprovalRetentionObjectJobs;
import com.dwp.services.approval.documentretention.ApprovalRetentionObjectWorker;
import com.dwp.services.approval.documentretention.ApprovalRetentionStorage;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalRetentionRuntimeConfigurationTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner()
            .withUserConfiguration(ApprovalRetentionRuntimeConfiguration.class)
            .withBean("approvalRetentionExecutorDataSource",DataSource.class,()->mock(DataSource.class))
            .withBean(ApprovalRetentionManagedExecutionRepository.class,()->mock(ApprovalRetentionManagedExecutionRepository.class))
            .withBean(ApprovalRetentionExecutionAuthorityPort.class,()->target->null)
            .withBean(ApprovalRetentionExecutionVerifier.class,()->{
                var verifier=mock(ApprovalRetentionExecutionVerifier.class);when(verifier.configured()).thenReturn(true);return verifier;
            })
            .withBean(ApprovalRetentionStorage.class,ApprovalRetentionRuntimeConfigurationTest::storage)
            .withBean(ApprovalRetentionObjectJobs.class,()->mock(ApprovalRetentionObjectJobs.class))
            .withBean(ApprovalRetentionObjectWorker.class,()->new ApprovalRetentionObjectWorker(
                    mock(ApprovalRetentionObjectJobs.class),storage()))
            .withBean(ApprovalRetentionForeignJournal.class,()->mock(ApprovalRetentionForeignJournal.class))
            .withBean("auditRetentionForeignPort",ApprovalRetentionForeignPort.class,()->port("AUDIT"))
            .withBean("notificationRetentionForeignPort",ApprovalRetentionForeignPort.class,()->port("NOTIFICATION"));

    @Test void explicitOperationalProfileWiresExecutorWorkerAndScheduler() {
        context.withPropertyValues("dwp.approval.retention-worker.enabled=true",
                        "dwp.approval.retention-worker.worker-id=retention-context-test",
                        "dwp.approval.retention-worker.batch-size=7")
                .run(value->{
                    assertThat(value).hasSingleBean(ApprovalRetentionIntentExecutor.class)
                            .hasSingleBean(ApprovalRetentionManagedWorker.class)
                            .hasSingleBean(ApprovalRetentionManagedSchedule.class);
                    assertThat(value.getBean(ApprovalRetentionManagedWorker.class).configuredDependencies())
                            .containsOnly(entry("authority",true),entry("objectStorage",true),
                                    entry("auditOwner",true),entry("notificationOwner",true));
                });
    }

    @Test void disabledProfileDoesNotAcquireExecutorCredentialsOrScheduleDeletion() {
        context.withPropertyValues("dwp.approval.retention-worker.enabled=false")
                .run(value->assertThat(value).doesNotHaveBean(ApprovalRetentionIntentExecutor.class)
                        .doesNotHaveBean(ApprovalRetentionManagedWorker.class)
                        .doesNotHaveBean(ApprovalRetentionManagedSchedule.class));
    }

    @Test void parsesOnlyClosedNumericBounds() {
        var environment=new MockEnvironment().withProperty("lease","30");
        assertThat(ApprovalRetentionRuntimeConfiguration.boundedInt(environment,"lease",90,30,300)).isEqualTo(30);
        assertThat(ApprovalRetentionRuntimeConfiguration.boundedInt(new MockEnvironment(),"lease",90,30,300)).isEqualTo(90);
        for(String invalid:new String[]{"","-1","+30","30.0"," 30","301","9999999999"}) {
            var value=new MockEnvironment().withProperty("lease",invalid);
            assertThatThrownBy(()->ApprovalRetentionRuntimeConfiguration.boundedInt(value,"lease",90,30,300))
                    .as(invalid).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void parsesBooleansWithoutPermissiveFallback() {
        assertThat(ApprovalRetentionRuntimeConfiguration.strictBoolean(
                new MockEnvironment().withProperty("enabled","TRUE"),"enabled",false)).isTrue();
        assertThatThrownBy(()->ApprovalRetentionRuntimeConfiguration.strictBoolean(
                new MockEnvironment().withProperty("enabled","yes"),"enabled",false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ApprovalRetentionStorage storage() {
        return new ApprovalRetentionStorage() {
            public String locatorSha256(){return "a".repeat(64);}
            public com.dwp.services.approval.attachment.ApprovalAttachmentStorage.Stored reconcile(String key,long size,String sha){return null;}
            public void verifyPresence(com.dwp.services.approval.attachment.ApprovalAttachmentStorage.Stored stored) {}
            public boolean deleteAndConfirmAbsent(com.dwp.services.approval.attachment.ApprovalAttachmentStorage.Stored stored){return false;}
        };
    }
    private static ApprovalRetentionForeignPort port(String service) {
        return new ApprovalRetentionForeignPort() {
            public String consumerService(){return service;}
            public ApprovalRetentionForeignDtos.SignedAck deleteDeclaredCopies(ApprovalRetentionForeignDtos.DeletionRequest request){return null;}
            public ApprovalRetentionForeignDtos.SignedAck reconcileDeclaredCopies(ApprovalRetentionForeignDtos.DeletionRequest request){return null;}
        };
    }
}

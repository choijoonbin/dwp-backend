package com.dwp.services.approval.attachment;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class ApprovalAttachmentClamAvInteropTest {
    @Container static final GenericContainer<?> CLAM=container();
    private static GenericContainer<?> container() {
        var container=new GenericContainer<>("clamav/clamav-debian:1.4.6@sha256:6a7d6f2d65398bfafb0cb171a9746ac8868fae6c56f188e90b34ddb0caf8d5dc")
            .withEnv("TZ","UTC").withEnv("CLAMAV_NO_FRESHCLAMD","true").withExposedPorts(3310)
            .withCreateContainerCmdModifier(command->command.getHostConfig().withMemory(3L*1024*1024*1024))
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(4)));
        return container;
    }
    @Test void actualClamDaemonReturnsClearForPassiveTextAndMalwareForEicar() throws Exception {
        var pinnedClock=Clock.fixed(Instant.parse("2026-09-06T07:00:00Z"),ZoneOffset.UTC);
        var scanner=new ApprovalAttachmentClamAv(CLAM.getHost(),CLAM.getMappedPort(3310),30000,Duration.ofDays(2),pinnedClock);
        var reload=CLAM.execInContainer("clamdscan","--reload");
        assertThat(reload.getExitCode()).withFailMessage("Actual definition reload failed: %s",reload.getStderr()).isZero();
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        while(!"ENGINE_VERIFIED".equals(scanner.readiness()) && System.nanoTime()<deadline) Thread.sleep(250);
        assertThat(scanner.readiness()).withFailMessage("Actual current engine required: %s%n%s",CLAM.execInContainer("clamdscan","--version").getStdout(),CLAM.getLogs()).isEqualTo("ENGINE_VERIFIED");
        byte[] passive="Actual ordinary approval evidence".getBytes(StandardCharsets.UTF_8);
        var clear=scanner.scan(passive,ApprovalAttachmentIntegrity.sha(passive));
        assertThat(clear.verdict()).isEqualTo(ApprovalAttachmentScanner.Verdict.AV_CLEAR);
        assertThat(clear.engineVersion()).startsWith("ClamAV ");assertThat(clear.definitionsAt()).isNotNull();
        byte[] eicar="X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*".getBytes(StandardCharsets.US_ASCII);
        assertThat(scanner.scan(eicar,ApprovalAttachmentIntegrity.sha(eicar)).verdict()).isEqualTo(ApprovalAttachmentScanner.Verdict.MALWARE);
    }
    @Test void staleActualDefinitionsRemainFailClosed() {
        var staleClock=Clock.fixed(Instant.parse("2026-09-09T07:00:00Z"),ZoneOffset.UTC);
        var scanner=new ApprovalAttachmentClamAv(CLAM.getHost(),CLAM.getMappedPort(3310),30000,Duration.ofDays(2),staleClock);
        byte[] bytes="Approval evidence".getBytes(StandardCharsets.UTF_8);
        assertThat(scanner.readiness()).isEqualTo("SCANNER_UNAVAILABLE");
        assertThat(scanner.scan(bytes,ApprovalAttachmentIntegrity.sha(bytes)).verdict())
                .isEqualTo(ApprovalAttachmentScanner.Verdict.INDETERMINATE);
    }
    @Test void unreachableActualSocketNeverBecomesClear() {
        var scanner=new ApprovalAttachmentClamAv("127.0.0.1",1,100,Duration.ofDays(2),Clock.systemUTC());
        byte[] bytes={1};assertThat(scanner.scan(bytes,ApprovalAttachmentIntegrity.sha(bytes)).verdict()).isEqualTo(ApprovalAttachmentScanner.Verdict.INDETERMINATE);
    }
}

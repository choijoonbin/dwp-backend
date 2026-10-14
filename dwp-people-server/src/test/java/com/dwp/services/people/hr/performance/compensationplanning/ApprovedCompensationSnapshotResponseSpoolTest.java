package com.dwp.services.people.hr.performance.compensationplanning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovedCompensationSnapshotResponseSpoolTest {

    @TempDir
    Path temp;

    @Test
    void completesBeforeTransferAndDeletesTheSpoolAfterStreaming() throws Exception {
        var spool = new ApprovedCompensationSnapshotResponseSpool(
                new ObjectMapper(), 1024, temp);

        var artifact = spool.prepare(Map.of("snapshotId", "exact", "revision", 4));
        assertThat(Files.exists(artifact.path())).isTrue();
        assertThat(artifact.length()).isPositive();

        var response = new ByteArrayOutputStream();
        artifact.transferTo(response);

        assertThat(response.toString()).contains("\"snapshotId\":\"exact\"");
        assertThat(Files.exists(artifact.path())).isFalse();
    }

    @Test
    void deletesPartialSpoolWhenTheBoundIsExceeded() {
        var spool = new ApprovedCompensationSnapshotResponseSpool(
                new ObjectMapper(), 8, temp);

        assertThatThrownBy(() -> spool.prepare(Map.of("payload", "too-large")))
                .isInstanceOf(
                        ApprovedCompensationSnapshotResponseSpool
                                .ResponseBoundExceededException.class);
        assertThat(temp).isEmptyDirectory();
    }
}

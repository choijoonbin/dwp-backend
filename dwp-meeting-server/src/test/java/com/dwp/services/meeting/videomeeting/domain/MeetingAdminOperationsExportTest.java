package com.dwp.services.meeting.videomeeting.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeetingAdminOperationsExportTest {

    @Test
    void preservesContentAfterProducerAndConsumerMutations() {
        byte[] content = "schemaVersion\r\nmeeting-admin-operations-v1\r\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] expected = content.clone();
        var export = new MeetingAdminOperationsExport("operations.csv", content);

        content[0] = 0;
        byte[] downloaded = export.content();
        assertThat(downloaded).containsExactly(expected).isNotSameAs(content);
        downloaded[1] = 0;

        assertThat(export.filename()).isEqualTo("operations.csv");
        assertThat(export.content()).containsExactly(expected).isNotSameAs(downloaded);
    }

    @Test
    void rejectsMissingFilenameBeforeContent() {
        assertThatThrownBy(() -> new MeetingAdminOperationsExport(null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("filename");
    }

    @Test
    void rejectsMissingContent() {
        assertThatThrownBy(() -> new MeetingAdminOperationsExport("operations.csv", null))
                .isInstanceOf(NullPointerException.class).hasMessage("content");
    }
}

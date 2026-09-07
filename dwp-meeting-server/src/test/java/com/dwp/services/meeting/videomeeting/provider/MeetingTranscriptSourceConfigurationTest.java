package com.dwp.services.meeting.videomeeting.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingTranscriptSourceConfigurationTest {

    @Test
    void httpTranscriptSourceUsesItsOwnSignerWithoutAnIntelligenceSignerBean() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withUserConfiguration(MeetingTranscriptSourceConfiguration.class)
                .withPropertyValues(
                        "dwp.meeting.transcript-source.provider=http",
                        "dwp.meeting.transcript-source.base-url=https://transcript.example.test",
                        "dwp.meeting.transcript-source.allowed-hosts=transcript.example.test",
                        "dwp.meeting.transcript-source.service-token=" + "s".repeat(32),
                        "dwp.meeting.transcript-source.assertion-key-id=transcript-workload-v1",
                        "dwp.meeting.transcript-source.assertion-secret-base64="
                                + Base64.getEncoder().encodeToString(new byte[32]),
                        "dwp.meeting.transcript-source.assertion-ttl=PT30S")
                .run(context -> {
                    assertThat(context).hasSingleBean(MeetingTranscriptSource.class);
                    assertThat(context).doesNotHaveBean(MeetingWorkloadAssertionSigner.class);
                });
    }
}

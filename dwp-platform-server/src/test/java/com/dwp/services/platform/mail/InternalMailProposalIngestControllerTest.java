package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalMailProposalIngestControllerTest {

    @Test
    void forwardsTheAuthenticatedProducerAndTenantActorContext() {
        MailProposalIngestService service = mock(MailProposalIngestService.class);
        InternalMailProposalIngestController controller =
                new InternalMailProposalIngestController(service);
        MailProposalIngestDtos.Request request = mock(MailProposalIngestDtos.Request.class);
        MailProposalIngestDtos.Receipt receipt = new MailProposalIngestDtos.Receipt(
                UUID.randomUUID(), "PROPOSED", false, 0L);
        when(service.ingest("dwp-agent-runtime", 1L, 7L, "corr", request))
                .thenReturn(receipt);

        var response = controller.ingest(
                "dwp-agent-runtime", 1L, 7L, "corr", request);

        assertThat(response.getData()).isEqualTo(receipt);
        verify(service).ingest("dwp-agent-runtime", 1L, 7L, "corr", request);
    }
}

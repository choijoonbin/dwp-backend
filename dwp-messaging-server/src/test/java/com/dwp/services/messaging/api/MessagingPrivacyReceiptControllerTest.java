package com.dwp.services.messaging.api;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.messaging.privacy.MessagingPrivacyController;
import com.dwp.services.messaging.privacy.MessagingPrivacyDtos;
import com.dwp.services.messaging.privacy.MessagingPrivacyService;
import com.dwp.services.messaging.receipt.MessagingReceiptController;
import com.dwp.services.messaging.receipt.MessagingReceiptDtos;
import com.dwp.services.messaging.receipt.MessagingReceiptService;
import com.dwp.services.messaging.security.MessagingSecurityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class MessagingPrivacyReceiptControllerTest {
    private final MessagingPrivacyService privacy = mock(MessagingPrivacyService.class);
    private final MessagingReceiptService receipts = mock(MessagingReceiptService.class);
    private final MockMvc mvc = standaloneSetup(new MessagingPrivacyController(privacy),
            new MessagingReceiptController(receipts))
            .setControllerAdvice(new GlobalExceptionHandler(new org.springframework.context.support.StaticMessageSource()))
            .addFilters(new MessagingSecurityFilter("test-service-token", new ObjectMapper().findAndRegisterModules())).build();

    @Test
    void getAndPutPreferenceSerializeVersionAndRequireExplicitChoice() throws Exception {
        when(privacy.preference()).thenReturn(new MessagingPrivacyDtos.PrivacyPreference(true, 0));
        when(privacy.update(new MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest(false, 0L)))
                .thenReturn(new MessagingPrivacyDtos.PrivacyPreference(false, 1));
        mvc.perform(auth(get("/v1/privacy-preferences")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.readReceiptsEnabled").value(true))
                .andExpect(jsonPath("$.data.version").value(0));
        mvc.perform(auth(put("/v1/privacy-preferences")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"readReceiptsEnabled\":false,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.readReceiptsEnabled").value(false));
        for (String body : List.of("{}", "{\"version\":0}", "{\"readReceiptsEnabled\":false}",
                "{\"readReceiptsEnabled\":true,\"version\":-1}")) {
            mvc.perform(auth(put("/v1/privacy-preferences")).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void batchAndSingleReceiptRoutesHaveSameShapeWithoutTimestamp() throws Exception {
        UUID conversation = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        var summary = new MessagingReceiptDtos.ReceiptSummary(message,
                List.of(new MessagingReceiptDtos.Recipient(200, null, "Reader", MessagingReceiptDtos.Status.READ)),
                1, 0, 0);
        when(receipts.receipts(conversation, List.of(message))).thenReturn(List.of(summary));
        when(receipts.receipt(conversation, message)).thenReturn(summary);
        mvc.perform(auth(get("/v1/conversations/{id}/read-receipts", conversation)
                        .param("messageIds", message.toString())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data[0].messageId").value(message.toString()))
                .andExpect(jsonPath("$.data[0].readCount").value(1))
                .andExpect(jsonPath("$.data[0].recipients[0].status").value("READ"))
                .andExpect(jsonPath("$.data[0].recipients[0].readAt").doesNotExist());
        mvc.perform(auth(get("/v1/conversations/{id}/messages/{message}/receipts", conversation, message)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.messageId").value(message.toString()));
    }

    @Test
    void observationPostAcceptsReadOnlyMembersAndBindsUuidList() throws Exception {
        UUID conversation = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        var request = new MessagingReceiptDtos.ObserveRequest(List.of(message));
        when(receipts.observe(conversation, request))
                .thenReturn(new MessagingReceiptDtos.ObservationResponse(List.of(message)));
        mvc.perform(auth(post("/v1/conversations/{id}/read-receipts", conversation))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageIds\":[\"" + message + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.observedMessageIds[0]").value(message.toString()));
        verify(receipts).observe(conversation, request);
        mvc.perform(auth(post("/v1/conversations/{id}/read-receipts", conversation))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"messageIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requestsCannotSpoofServiceIdentityOrOmitMessagingAccess() throws Exception {
        mvc.perform(get("/v1/privacy-preferences").header("X-DWP-User-ID", "100"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/privacy-preferences").header("X-DWP-Service-Token", "test-service-token")
                        .header("X-DWP-User-ID", "100").header("X-DWP-Tenant-ID", "7"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(privacy, receipts);
    }

    @Test
    void selfWriteExceptionsDoNotGrantOtherCommandsToViewOnlyUsers() throws Exception {
        UUID conversation = UUID.randomUUID();
        for (var request : List.of(post("/v1/conversations/{id}/messages", conversation),
                put("/v1/conversations/{id}", conversation), put("/v1/admin/policy"),
                put("/v1/privacy-preferences/100"), delete("/v1/privacy-preferences"))) {
            mvc.perform(auth(request).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(privacy, receipts);
    }

    @Test
    void commaSeparatedBatchIdsBindInRequestOrder() throws Exception {
        UUID conversation = UUID.randomUUID();
        var ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(receipts.receipts(conversation, ids)).thenReturn(List.of());
        mvc.perform(auth(get("/v1/conversations/{id}/read-receipts", conversation)
                        .param("messageIds", ids.getFirst() + "," + ids.getLast())))
                .andExpect(status().isOk());
        verify(receipts).receipts(conversation, ids);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("X-DWP-Service-Token", "test-service-token")
                .header("X-DWP-User-ID", "100").header("X-DWP-Tenant-ID", "7")
                .header("X-DWP-Permissions", "APP.MESSAGING:VIEW");
    }
}

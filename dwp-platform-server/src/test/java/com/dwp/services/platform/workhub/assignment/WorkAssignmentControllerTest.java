package com.dwp.services.platform.workhub.assignment;

import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.AccessContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.UUID;
import static com.dwp.services.platform.workhub.assignment.WorkAssignmentDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkAssignmentControllerTest {
    private final WorkAssignmentService service = mock(WorkAssignmentService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkAssignmentController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    private static final String BASE = "/v1/workspace/work-hub/assignments";

    @Test void queryCannotOverrideTheAuthenticatedActor() throws Exception {
        when(service.list(any(), any(), anyInt(), anyInt())).thenReturn(new TaskPage(List.of(), 0, 50, 0, false));
        mvc.perform(get(BASE).header("X-DWP-Tenant-ID", "7").header("X-DWP-User-ID", "11")
                .header("X-DWP-Permissions", "APP.WORK:VIEW")
                .param("tenantId", "99").param("userId", "31").param("permissions", "APP.WORK:UPDATE"))
                .andExpect(status().isOk());
        ArgumentCaptor<AccessContext> context = ArgumentCaptor.forClass(AccessContext.class);
        verify(service).list(context.capture(), eq(Scope.ASSIGNED_TO_ME), eq(0), eq(50));
        assertThat(context.getValue().tenantId()).isEqualTo(7);
        assertThat(context.getValue().userId()).isEqualTo(11);
        assertThat(context.getValue().permissions()).isEqualTo("APP.WORK:VIEW");
    }

    @Test void creatingRequiresConfirmedSourceVersionAndStableCommandKey() throws Exception {
        var source = new SourceIdentity(SourceSystem.MEETING_FOLLOWUP, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        byte[] body = mapper.writeValueAsBytes(new CreateRequest(source, 3L));
        mvc.perform(post(BASE).header("X-DWP-Tenant-ID", "7").header("X-DWP-User-ID", "11")
                .header("X-DWP-Permissions", "APP.WORK:VIEW,APP.WORK:UPDATE")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(BASE).header("X-DWP-Tenant-ID", "7").header("X-DWP-User-ID", "11")
                .header("X-DWP-Permissions", "APP.WORK:VIEW,APP.WORK:UPDATE")
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(new CreateRequest(source, null)))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void acceptCarriesBothVersionAndAssignmentRevisionToTheDistinctAction() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        mvc.perform(post(BASE + "/" + taskId + "/accept")
                .header("X-DWP-Tenant-ID", "7").header("X-DWP-User-ID", "21")
                .header("X-DWP-Permissions", "APP.WORK:VIEW,APP.WORK:UPDATE")
                .header("Idempotency-Key", commandId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":4,\"assignmentRevision\":2}"))
                .andExpect(status().isOk());
        verify(service).command(any(), eq(taskId), eq(commandId), isNull(), eq(Action.ACCEPT),
                eq(new VersionCommand(4L, 2L, null)));
        mvc.perform(post(BASE + "/" + taskId + "/start")
                .header("X-DWP-Tenant-ID", "7").header("X-DWP-User-ID", "21")
                .header("X-DWP-Permissions", "APP.WORK:VIEW,APP.WORK:UPDATE")
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":4}"))
                .andExpect(status().isBadRequest());
    }

    @Test void reassignmentRequiresPositiveTargetAndBoundedReasonCode() throws Exception {
        mvc.perform(post(BASE + "/" + UUID.randomUUID() + "/reassign")
                .header("X-DWP-Tenant-ID", "7").header("X-DWP-User-ID", "11")
                .header("X-DWP-Permissions", "APP.WORK:VIEW,APP.WORK:UPDATE")
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeUserId\":0,\"version\":1,\"assignmentRevision\":0,\"reasonCode\":\"raw free text\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void sourceLookupKeepsAllThreeOwnerIdentifiers() throws Exception {
        UUID meetingId = UUID.randomUUID(); UUID reportId = UUID.randomUUID(); UUID candidateId = UUID.randomUUID();
        mvc.perform(get(BASE + "/by-source").header("X-DWP-Tenant-ID", "7").header("X-DWP-User-ID", "11")
                .header("X-DWP-Permissions", "APP.WORK:VIEW").param("meetingId", meetingId.toString())
                .param("reportId", reportId.toString()).param("candidateId", candidateId.toString()))
                .andExpect(status().isOk());
        verify(service).findBySource(any(), eq(new SourceIdentity(SourceSystem.MEETING_FOLLOWUP, meetingId, reportId, candidateId)));
    }
}

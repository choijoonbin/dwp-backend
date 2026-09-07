package com.dwp.services.platform.workhub.personal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workhub.personal.PersonalWorkDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PersonalWorkControllerTest {
    private final PersonalWorkService service = mock(PersonalWorkService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PersonalWorkController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
            .build();

    @Test
    void queryParametersCannotOverrideAuthenticatedHeaderContext() throws Exception {
        when(service.list(any(), any(), anyInt(), anyInt())).thenReturn(new TaskPage(List.of(), 0, 50, 0, false));
        mvc.perform(get("/v1/workspace/work-hub/personal-tasks")
                .header("X-DWP-Tenant-ID", "7").header("X-DWP-User-ID", "11")
                .header("X-DWP-Permissions", "APP.WORK:VIEW")
                .param("tenantId", "99").param("userId", "100").param("permissions", "APP.WORK:UPDATE"))
                .andExpect(status().isOk());
        ArgumentCaptor<AccessContext> context = ArgumentCaptor.forClass(AccessContext.class);
        verify(service).list(context.capture(), isNull(), eq(0), eq(50));
        assertThat(context.getValue().tenantId()).isEqualTo(7);
        assertThat(context.getValue().userId()).isEqualTo(11);
        assertThat(context.getValue().permissions()).isEqualTo("APP.WORK:VIEW");
    }

    @Test
    void creationRequiresAnIdempotencyKeyAndValidBoundedTitle() throws Exception {
        String path = "/v1/workspace/work-hub/personal-tasks";
        mvc.perform(post(path).header("X-DWP-Tenant-ID", "7").header("X-DWP-User-ID", "11")
                .header("X-DWP-Permissions", "APP.WORK:VIEW,APP.WORK:UPDATE")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Task\",\"priority\":\"NORMAL\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).header("X-DWP-Tenant-ID", "7").header("X-DWP-User-ID", "11")
                .header("X-DWP-Permissions", "APP.WORK:VIEW,APP.WORK:UPDATE")
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\" \",\"priority\":\"NORMAL\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}

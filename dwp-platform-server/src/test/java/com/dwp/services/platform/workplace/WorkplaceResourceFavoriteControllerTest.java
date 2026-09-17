package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceResourceFavoriteController.*;
import static com.dwp.services.platform.workplace.WorkplaceResourceFavoriteDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkplaceResourceFavoriteControllerTest {
    private final WorkplaceResourceFavoriteService service =
            mock(WorkplaceResourceFavoriteService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new WorkplaceResourceFavoriteController(service)).build();

    @Test
    void readAndMutationUseCanonicalPathsNoStoreAndPermissionBoundaries() throws Exception {
        UUID resourceId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T06:00:00Z");
        FavoriteView favorite = new FavoriteView(resourceId, true, 1, now);
        when(service.favorites(42, 7, List.of(resourceId))).thenReturn(List.of(favorite));
        when(service.set(eq(42L), eq(7L), eq(resourceId), eq("favorite-key"),
                eq("corr-14"), any())).thenReturn(new FavoriteReceipt(
                commandId, favorite, auditId, "corr-14", now));

        mvc.perform(get("/v1/workplace/resource-favorites")
                        .header(TENANT, 42).header(USER, 7).header(PERMISSIONS, VIEW)
                        .param("resourceIds", resourceId.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data[0].resourceId").value(resourceId.toString()))
                .andExpect(jsonPath("$.data[0].favorite").value(true));

        mvc.perform(put("/v1/workplace/resources/{resourceId}/favorite", resourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 7).header(PERMISSIONS, UPDATE)
                        .header(IDEMPOTENCY, "favorite-key").header(CORRELATION, "corr-14")
                        .content(mapper.writeValueAsBytes(new SetFavoriteRequest(true, 0))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.commandId").value(commandId.toString()))
                .andExpect(jsonPath("$.data.auditEventId").value(auditId.toString()));

        assertThatThrownBy(() -> WorkplaceResourceFavoriteController.requirePermission(VIEW, UPDATE))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}

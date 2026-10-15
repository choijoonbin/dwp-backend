package com.dwp.services.platform.hrisconfiguration.explorer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HrisExplorerCommandJsonBoundaryTest {

    private MockMvc mvc;

    @BeforeEach
    void setUpLenientGlobalMapper() {
        ObjectMapper lenient = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mvc = MockMvcBuilders.standaloneSetup(
                        new HrisExplorerController(mock(HrisExplorerService.class)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(lenient))
                .build();
    }

    @Test
    void preferenceCommandRejectsUnknownJsonEvenWithLenientGlobalMapper() throws Exception {
        mvc.perform(put("/v1/hris/explorer/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderedFavoriteRouteKeys":[],"clientAuthority":true}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recentCommandRejectsUnknownJsonEvenWithLenientGlobalMapper() throws Exception {
        mvc.perform(post("/v1/hris/explorer/recents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeKey":"hris.explorer.team-time","visitedAt":"client"}
                                """))
                .andExpect(status().isBadRequest());
    }
}

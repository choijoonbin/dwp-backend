package com.dwp.services.auth.controller.monitoring;

import com.dwp.services.auth.dto.monitoring.EventCollectRequest;
import com.dwp.services.auth.dto.monitoring.PageViewCollectRequest;
import com.dwp.services.auth.service.monitoring.MonitoringCollectService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MonitoringCollectController 테스트
 */
@WebMvcTest(MonitoringCollectController.class)
@SuppressWarnings("null")
class MonitoringCollectControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    // Note: @MockBean is deprecated in Spring Boot 3.4.0 but still functional
    @MockBean
    private MonitoringCollectService monitoringCollectService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Test
    void recordPageView_WithoutTenantId_Returns400() throws Exception {
        PageViewCollectRequest request = PageViewCollectRequest.builder()
                .path("/admin/monitoring")
                .visitorId("visitor_123")
                .build();
        
        mockMvc.perform(post("/monitoring/page-view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        
        verify(monitoringCollectService, never()).recordPageView(any(), any(), any(), any(), any());
    }
    
    @Test
    void recordPageView_WithTenantId_ReturnsSuccess() throws Exception {
        PageViewCollectRequest request = PageViewCollectRequest.builder()
                .path("/admin/monitoring")
                .visitorId("visitor_123")
                .build();
        
        mockMvc.perform(post("/monitoring/page-view")
                        .header("X-Tenant-ID", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accepted").value(true));
        
        verify(monitoringCollectService, times(1)).recordPageView(
                eq(1L), any(), any(), any(), any(PageViewCollectRequest.class));
    }
    
    @Test
    void recordEvent_WithoutTenantId_Returns400() throws Exception {
        EventCollectRequest request = EventCollectRequest.builder()
                .eventType("view")
                .resourceKey("menu.admin.users")
                .action("view_users")
                .build();
        
        mockMvc.perform(post("/monitoring/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        
        verify(monitoringCollectService, never()).recordEvent(any(), any(), any(), any(), any());
    }
    
    @Test
    void recordEvent_WithTenantId_ReturnsSuccess() throws Exception {
        EventCollectRequest request = EventCollectRequest.builder()
                .eventType("view")
                .resourceKey("menu.admin.users")
                .action("view_users")
                .label("Admin Users 조회")
                .visitorId("visitor_123")
                .path("/admin/users")
                .build();
        
        mockMvc.perform(post("/monitoring/event")
                        .header("X-Tenant-ID", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accepted").value(true));
        
        verify(monitoringCollectService, times(1)).recordEvent(
                eq(1L), any(), any(), any(), any(EventCollectRequest.class));
    }
    
    @Test
    void recordEvent_InvalidRequest_MissingRequiredFields() throws Exception {
        EventCollectRequest request = EventCollectRequest.builder()
                .eventType("view")
                // resourceKey, action 누락
                .build();
        
        mockMvc.perform(post("/monitoring/event")
                        .header("X-Tenant-ID", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}

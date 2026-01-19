package com.dwp.services.auth.controller.admin.monitoring;

import com.dwp.services.auth.dto.monitoring.EventLogItem;
import com.dwp.services.auth.dto.monitoring.TimeseriesResponse;
import com.dwp.services.auth.dto.monitoring.VisitorSummary;
import com.dwp.services.auth.service.MonitoringService;
import com.dwp.services.auth.service.monitoring.AdminMonitoringService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AdminMonitoringController 테스트
 */
@WebMvcTest(AdminMonitoringController.class)
@SuppressWarnings("null")
class AdminMonitoringControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    // Note: @MockBean is deprecated in Spring Boot 3.4.0 but still functional
    @MockBean
    private MonitoringService monitoringService;
    
    // Note: @MockBean is deprecated in Spring Boot 3.4.0 but still functional
    @MockBean
    private AdminMonitoringService adminMonitoringService;
    
    @Test
    @WithMockUser
    void getVisitors_ReturnsPage() throws Exception {
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 1, 31, 23, 59);
        
        VisitorSummary summary = VisitorSummary.builder()
                .visitorId("visitor_123")
                .firstSeenAt(from)
                .lastSeenAt(to)
                .pageViewCount(10L)
                .eventCount(5L)
                .lastPath("/admin/monitoring")
                .build();
        
        Page<VisitorSummary> page = new PageImpl<>(Arrays.asList(summary), PageRequest.of(0, 10), 1);
        
        when(adminMonitoringService.getVisitors(eq(1L), eq(from), eq(to), any(), any()))
                .thenReturn(page);
        
        mockMvc.perform(get("/admin/monitoring/visitors")
                        .header("X-Tenant-ID", "1")
                        .param("page", "1")
                        .param("size", "10")
                        .param("from", "2026-01-01T00:00:00")
                        .param("to", "2026-01-31T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].visitorId").value("visitor_123"));
    }
    
    @Test
    @WithMockUser
    void getEvents_ReturnsPage() throws Exception {
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 1, 31, 23, 59);
        
        EventLogItem item = EventLogItem.builder()
                .sysEventLogId(1L)
                .occurredAt(LocalDateTime.now())
                .eventType("view")
                .resourceKey("menu.admin.users")
                .action("view_users")
                .build();
        
        Page<EventLogItem> page = new PageImpl<>(Arrays.asList(item), PageRequest.of(0, 10), 1);
        
        when(adminMonitoringService.getEvents(eq(1L), eq(from), eq(to), any(), any(), any(), any()))
                .thenReturn(page);
        
        mockMvc.perform(get("/admin/monitoring/events")
                        .header("X-Tenant-ID", "1")
                        .param("page", "1")
                        .param("size", "10")
                        .param("from", "2026-01-01T00:00:00")
                        .param("to", "2026-01-31T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].eventType").value("view"));
    }
    
    @Test
    @WithMockUser
    void getTimeseries_ReturnsTimeseriesData() throws Exception {
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 1, 3, 23, 59);
        
        TimeseriesResponse response = TimeseriesResponse.builder()
                .interval("DAY")
                .metric("PV")
                .labels(Arrays.asList("2026-01-01", "2026-01-02", "2026-01-03"))
                .values(Arrays.asList(100L, 150L, 120L))
                .build();
        
        when(adminMonitoringService.getTimeseries(eq(1L), eq(from), eq(to), eq("DAY"), eq("PV")))
                .thenReturn(response);
        
        mockMvc.perform(get("/admin/monitoring/timeseries")
                        .header("X-Tenant-ID", "1")
                        .param("from", "2026-01-01T00:00:00")
                        .param("to", "2026-01-03T23:59:59")
                        .param("interval", "DAY")
                        .param("metric", "PV"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.interval").value("DAY"))
                .andExpect(jsonPath("$.data.metric").value("PV"))
                .andExpect(jsonPath("$.data.labels[0]").value("2026-01-01"))
                .andExpect(jsonPath("$.data.values[0]").value(100));
    }
}

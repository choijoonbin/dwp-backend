package com.dwp.services.auth.controller.admin.monitoring;

import com.dwp.services.auth.config.AdminEndpointPolicyRegistry;
import com.dwp.services.auth.dto.monitoring.EventLogItem;
import com.dwp.services.auth.dto.monitoring.TimeseriesResponse;
import com.dwp.services.auth.dto.monitoring.VisitorSummary;
import com.dwp.services.auth.service.MonitoringService;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.service.monitoring.AdminMonitoringService;
import com.dwp.services.auth.service.rbac.AdminGuardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ContextConfiguration;
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
@WebMvcTest(value = AdminMonitoringController.class, excludeAutoConfiguration = RedisAutoConfiguration.class)
@ContextConfiguration(classes = com.dwp.services.auth.SliceTestApplication.class)
@SuppressWarnings({"null", "removal"})
class AdminMonitoringControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MonitoringService monitoringService;

    @MockBean
    private AdminMonitoringService adminMonitoringService;

    @MockBean
    private AdminGuardService adminGuardService;

    @MockBean
    private AdminEndpointPolicyRegistry endpointPolicyRegistry;

    @MockBean
    private AuditLogService auditLogService;
    
    @Test
    @WithMockJwt(userId = 1L, tenantId = 1L)
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
        
        when(adminMonitoringService.getVisitors(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class), any(), any()))
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
    @WithMockJwt(userId = 1L, tenantId = 1L)
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
        
        when(adminMonitoringService.getEvents(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class), any(), any(), any(), any()))
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
    @WithMockJwt(userId = 1L, tenantId = 1L)
    void getTimeseries_ReturnsTimeseriesData() throws Exception {
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 1, 3, 23, 59);
        
        TimeseriesResponse response = TimeseriesResponse.builder()
                .interval("DAY")
                .metric("PV")
                .labels(Arrays.asList("2026-01-01", "2026-01-02", "2026-01-03"))
                .values(Arrays.asList(100.0, 150.0, 120.0))
                .build();
        
        when(adminMonitoringService.getTimeseries(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class), eq("DAY"), eq("PV")))
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

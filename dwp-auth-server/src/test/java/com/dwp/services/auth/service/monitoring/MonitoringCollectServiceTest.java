package com.dwp.services.auth.service.monitoring;

import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.monitoring.EventCollectRequest;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.monitoring.EventLog;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.monitoring.EventLogRepository;
import com.dwp.services.auth.util.CodeResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MonitoringCollectService 테스트 (BE P1-5)
 * 
 * 검증 항목:
 * - /api/auth/permissions 응답에 resourceKind 포함 확인
 * - /api/monitoring/event 수집 시 resourceKind/action 저장 확인
 * - tracking_enabled=false 인 리소스는 silent ignore 확인
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonitoringCollectService 테스트 (BE P1-5)")
@SuppressWarnings("null")  // Null type safety 경고 억제
class MonitoringCollectServiceTest {

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private EventLogRepository eventLogRepository;

    @Mock
    private CodeResolver codeResolver;

    @InjectMocks
    private MonitoringCollectService monitoringCollectService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // ObjectMapper 주입 (리플렉션 사용)
        try {
            java.lang.reflect.Field field = MonitoringCollectService.class.getDeclaredField("objectMapper");
            field.setAccessible(true);
            field.set(monitoringCollectService, objectMapper);
        } catch (Exception e) {
            // 무시
        }
    }

    @Test
    @DisplayName("이벤트 수집 시 resourceKind 저장 확인")
    void testRecordEventSavesResourceKind() {
        // Given: 리소스가 존재하고 tracking_enabled=true
        Resource resource = Resource.builder()
                .resourceId(1L)
                .tenantId(1L)
                .key("menu.admin.users")
                .resourceKind("PAGE")
                .trackingEnabled(true)
                .eventActions("[\"VIEW\",\"USE\"]")
                .build();

        when(resourceRepository.findByTenantIdAndKey(1L, "menu.admin.users"))
                .thenReturn(Arrays.asList(resource));
        when(codeResolver.validate("UI_ACTION", "CLICK")).thenReturn(true);

        EventCollectRequest request = EventCollectRequest.builder()
                .resourceKey("menu.admin.users")
                .action("CLICK")
                .eventType("USER_ACTION")
                .build();

        // When: 이벤트 수집
        monitoringCollectService.recordEvent(1L, 100L, "127.0.0.1", "Mozilla", request);

        // Then: resourceKind가 저장되어야 함
        ArgumentCaptor<EventLog> captor = ArgumentCaptor.forClass(EventLog.class);
        verify(eventLogRepository, times(1)).save(captor.capture());

        EventLog saved = captor.getValue();
        assertThat(saved.getResourceKind()).isEqualTo("PAGE");
        assertThat(saved.getAction()).isEqualTo("CLICK");
        assertThat(saved.getResourceKey()).isEqualTo("menu.admin.users");
    }

    @Test
    @DisplayName("tracking_enabled=false 인 리소스는 silent ignore")
    void testTrackingDisabledResourceIsSilentlyIgnored() {
        // Given: tracking_enabled=false 리소스
        Resource resource = Resource.builder()
                .resourceId(1L)
                .tenantId(1L)
                .key("menu.admin.users")
                .resourceKind("PAGE")
                .trackingEnabled(false)  // 추적 비활성화
                .build();

        when(resourceRepository.findByTenantIdAndKey(1L, "menu.admin.users"))
                .thenReturn(Arrays.asList(resource));

        EventCollectRequest request = EventCollectRequest.builder()
                .resourceKey("menu.admin.users")
                .action("CLICK")
                .build();

        // When: 이벤트 수집
        monitoringCollectService.recordEvent(1L, 100L, "127.0.0.1", "Mozilla", request);

        // Then: 이벤트가 저장되지 않아야 함
        verify(eventLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("리소스가 없으면 silent ignore")
    void testMissingResourceIsSilentlyIgnored() {
        // Given: 리소스가 없음
        when(resourceRepository.findByTenantIdAndKey(1L, "menu.nonexistent"))
                .thenReturn(Arrays.asList());

        EventCollectRequest request = EventCollectRequest.builder()
                .resourceKey("menu.nonexistent")
                .action("CLICK")
                .build();

        // When: 이벤트 수집
        monitoringCollectService.recordEvent(1L, 100L, "127.0.0.1", "Mozilla", request);

        // Then: 이벤트가 저장되지 않아야 함
        verify(eventLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("resourceKey 누락 시 예외 발생")
    void testMissingResourceKeyThrowsException() {
        EventCollectRequest request = EventCollectRequest.builder()
                .action("CLICK")
                .build();

        // When/Then: 예외 발생
        assertThatThrownBy(() -> monitoringCollectService.recordEvent(1L, 100L, "127.0.0.1", "Mozilla", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("resourceKey는 필수입니다");
    }

    @Test
    @DisplayName("action='click' 입력 시 DB에 'CLICK' 저장되는지 (소문자 정규화)")
    void testActionNormalizeLowerCase() {
        // Given: 소문자 action 입력
        Resource resource = Resource.builder()
                .resourceId(1L)
                .tenantId(1L)
                .key("menu.admin.users")
                .resourceKind("PAGE")
                .trackingEnabled(true)
                .eventActions("[\"VIEW\",\"CLICK\"]")
                .build();

        when(resourceRepository.findByTenantIdAndKey(1L, "menu.admin.users"))
                .thenReturn(Arrays.asList(resource));
        when(codeResolver.validate("UI_ACTION", "CLICK")).thenReturn(true);

        EventCollectRequest request = EventCollectRequest.builder()
                .resourceKey("menu.admin.users")
                .action("click")  // 소문자 입력
                .build();

        // When: 이벤트 수집
        monitoringCollectService.recordEvent(1L, 100L, "127.0.0.1", "Mozilla", request);

        // Then: 대문자로 저장되어야 함
        ArgumentCaptor<EventLog> captor = ArgumentCaptor.forClass(EventLog.class);
        verify(eventLogRepository, times(1)).save(captor.capture());

        EventLog saved = captor.getValue();
        assertThat(saved.getAction()).isEqualTo("CLICK");  // 대문자로 저장됨
    }

    @Test
    @DisplayName("eventType='view'만 입력해도 'VIEW'로 저장되는지 (deprecated 지원)")
    void testEventTypeDeprecatedSupport() {
        // Given: eventType만 입력 (action 없음)
        Resource resource = Resource.builder()
                .resourceId(1L)
                .tenantId(1L)
                .key("menu.admin.users")
                .resourceKind("PAGE")
                .trackingEnabled(true)
                .eventActions("[\"VIEW\"]")
                .build();

        when(resourceRepository.findByTenantIdAndKey(1L, "menu.admin.users"))
                .thenReturn(Arrays.asList(resource));
        when(codeResolver.validate("UI_ACTION", "VIEW")).thenReturn(true);

        EventCollectRequest request = EventCollectRequest.builder()
                .resourceKey("menu.admin.users")
                .eventType("view")  // eventType만 입력 (deprecated)
                .build();

        // When: 이벤트 수집
        monitoringCollectService.recordEvent(1L, 100L, "127.0.0.1", "Mozilla", request);

        // Then: VIEW로 저장되어야 함
        ArgumentCaptor<EventLog> captor = ArgumentCaptor.forClass(EventLog.class);
        verify(eventLogRepository, times(1)).save(captor.capture());

        EventLog saved = captor.getValue();
        assertThat(saved.getAction()).isEqualTo("VIEW");  // eventType이 action으로 변환되어 저장됨
    }

    @Test
    @DisplayName("UI_ACTION 코드 없으면 저장되지 않는지 (silent fail)")
    void testUIActionCodeNotFoundSilentFail() {
        // Given: UI_ACTION 코드에 없는 action
        Resource resource = Resource.builder()
                .resourceId(1L)
                .tenantId(1L)
                .key("menu.admin.users")
                .resourceKind("PAGE")
                .trackingEnabled(true)
                .build();

        when(resourceRepository.findByTenantIdAndKey(1L, "menu.admin.users"))
                .thenReturn(Arrays.asList(resource));
        when(codeResolver.validate("UI_ACTION", "INVALID_ACTION")).thenReturn(false);  // 코드 없음

        EventCollectRequest request = EventCollectRequest.builder()
                .resourceKey("menu.admin.users")
                .action("invalid_action")  // UI_ACTION 코드에 없는 값
                .build();

        // When: 이벤트 수집
        monitoringCollectService.recordEvent(1L, 100L, "127.0.0.1", "Mozilla", request);

        // Then: 저장되지 않아야 함 (silent fail)
        verify(eventLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("com_resource.event_actions 제한 위반 시 저장되지 않는지 (silent fail)")
    void testEventActionsRestrictionViolation() {
        // Given: event_actions에 없는 action
        Resource resource = Resource.builder()
                .resourceId(1L)
                .tenantId(1L)
                .key("menu.admin.users")
                .resourceKind("PAGE")
                .trackingEnabled(true)
                .eventActions("[\"VIEW\",\"USE\"]")  // CLICK은 허용되지 않음
                .build();

        when(resourceRepository.findByTenantIdAndKey(1L, "menu.admin.users"))
                .thenReturn(Arrays.asList(resource));
        when(codeResolver.validate("UI_ACTION", "CLICK")).thenReturn(true);  // UI_ACTION 코드는 있음

        EventCollectRequest request = EventCollectRequest.builder()
                .resourceKey("menu.admin.users")
                .action("CLICK")  // event_actions에 없는 값
                .build();

        // When: 이벤트 수집
        monitoringCollectService.recordEvent(1L, 100L, "127.0.0.1", "Mozilla", request);

        // Then: 저장되지 않아야 함 (silent fail)
        verify(eventLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("action 정규화: 공백 포함, 혼용 대소문자")
    void testActionNormalizeWithWhitespaceAndMixedCase() {
        // Given
        Resource resource = Resource.builder()
                .resourceId(1L)
                .tenantId(1L)
                .key("menu.admin.users")
                .resourceKind("PAGE")
                .trackingEnabled(true)
                .eventActions("[\"VIEW\",\"CLICK\"]")
                .build();

        when(resourceRepository.findByTenantIdAndKey(1L, "menu.admin.users"))
                .thenReturn(Arrays.asList(resource));
        when(codeResolver.validate("UI_ACTION", "CLICK")).thenReturn(true);

        EventCollectRequest request = EventCollectRequest.builder()
                .resourceKey("menu.admin.users")
                .action("  Click  ")  // 공백 포함, 혼용 대소문자
                .build();

        // When: 이벤트 수집
        monitoringCollectService.recordEvent(1L, 100L, "127.0.0.1", "Mozilla", request);

        // Then: 정규화되어 저장되어야 함
        ArgumentCaptor<EventLog> captor = ArgumentCaptor.forClass(EventLog.class);
        verify(eventLogRepository, times(1)).save(captor.capture());

        EventLog saved = captor.getValue();
        assertThat(saved.getAction()).isEqualTo("CLICK");  // 공백 제거, 대문자 변환
    }
}

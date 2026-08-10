package com.dwp.services.platform.home.preference;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomePreferenceServiceTest {

    @Mock
    private HomePreferenceRepository repository;
    @Mock
    private PlatformAuditService auditService;

    private ObjectMapper objectMapper;
    private HomePreferenceService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new HomePreferenceService(repository, objectMapper, auditService);
    }

    @Test
    void returnsGovernedDefaultsWithoutCreatingData() {
        when(repository.findByTenantIdAndUserId(7L, 11L)).thenReturn(Optional.empty());

        HomePreferenceDtos.HomePreferenceResponse result = service.get(7L, 11L);

        assertThat(result.customized()).isFalse();
        assertThat(result.version()).isZero();
        assertThat(result.layout().widgets()).extracting(HomePreferenceDtos.WidgetPreference::widgetKey)
                .containsExactly("announcements", "daily-brief", "focus", "schedule", "activity");
        assertThat(result.layout().widgets().get(0).visible()).isTrue();
    }

    @Test
    void rejectsHiddenGovernedAnnouncements() {
        HomePreferenceDtos.HomeLayoutPayload layout = new HomePreferenceDtos.HomeLayoutPayload(
                null,
                widgets(false));

        assertThatThrownBy(() -> service.update(
                        7L,
                        11L,
                        null,
                        new HomePreferenceDtos.UpdateHomePreferenceRequest(layout, 0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void storesAValidatedAppAndWidgetLayoutForTheCurrentUser() {
        ObjectNode appLayout = objectMapper.createObjectNode();
        appLayout.put("version", 1);
        appLayout.putObject("groups").putArray("work").add("dwp-work");
        appLayout.putObject("folders");
        when(repository.findByTenantIdAndUserId(7L, 11L)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(HomePreference.class))).thenAnswer(invocation -> {
            HomePreference saved = invocation.getArgument(0);
            saved.setHomePreferenceId(31L);
            saved.setVersion(0L);
            return saved;
        });

        HomePreferenceDtos.HomePreferenceResponse result = service.update(
                7L,
                11L,
                "corr-home",
                new HomePreferenceDtos.UpdateHomePreferenceRequest(
                        new HomePreferenceDtos.HomeLayoutPayload(appLayout, widgets(true)),
                        0L));

        assertThat(result.customized()).isTrue();
        assertThat(result.version()).isZero();
        assertThat(result.layout().appLayout().path("version").asInt()).isEqualTo(1);
        assertThat(result.layout().widgets()).hasSize(5);
        verify(auditService).success(
                eq(7L),
                eq(11L),
                eq("home-preference.updated"),
                eq("HOME_PREFERENCE"),
                eq("11"),
                eq("corr-home"),
                anyMap(),
                anyMap());
    }

    private List<HomePreferenceDtos.WidgetPreference> widgets(boolean announcementsVisible) {
        return List.of(
                new HomePreferenceDtos.WidgetPreference("announcements", announcementsVisible),
                new HomePreferenceDtos.WidgetPreference("daily-brief", true),
                new HomePreferenceDtos.WidgetPreference("focus", true),
                new HomePreferenceDtos.WidgetPreference("schedule", true),
                new HomePreferenceDtos.WidgetPreference("activity", true));
    }
}

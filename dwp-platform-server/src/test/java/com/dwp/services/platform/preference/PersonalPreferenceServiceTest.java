package com.dwp.services.platform.preference;

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
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonalPreferenceServiceTest {

    @Mock
    private PersonalPreferenceRepository repository;
    @Mock
    private PlatformAuditService auditService;

    private ObjectMapper objectMapper;
    private PersonalPreferenceService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new PersonalPreferenceService(repository, objectMapper, auditService);
    }

    @Test
    void returnsDefaultsWithoutCreatingARecord() {
        when(repository.findByTenantIdAndUserId(7L, 11L)).thenReturn(Optional.empty());

        PersonalPreferenceDtos.PersonalPreferenceResponse result = service.get(7L, 11L);

        assertThat(result.customized()).isFalse();
        assertThat(result.version()).isZero();
        assertThat(result.preferences().path("appearance").path("mode").asText()).isEqualTo("system");
        assertThat(result.preferences().path("accessibility").path("reduceMotion").asBoolean()).isFalse();
    }

    @Test
    void mergesAPartialPatchWithoutDroppingExistingOrFutureNamespaces() {
        ObjectNode stored = objectMapper.createObjectNode();
        stored.putObject("appearance").put("mode", "light").put("density", "comfortable");
        stored.putObject("accessibility").put("highContrast", false).put("reduceMotion", true);
        stored.putObject("notifications").put("digest", "daily");
        PersonalPreference preference = PersonalPreference.builder()
                .personalPreferenceId(19L)
                .tenantId(7L)
                .userId(11L)
                .schemaVersion(1)
                .preferencePayload(stored)
                .version(3L)
                .build();
        when(repository.findByTenantIdAndUserId(7L, 11L)).thenReturn(Optional.of(preference));
        when(repository.saveAndFlush(any(PersonalPreference.class))).thenAnswer(invocation -> {
            PersonalPreference saved = invocation.getArgument(0);
            saved.setVersion(4L);
            return saved;
        });

        ObjectNode patch = objectMapper.createObjectNode();
        patch.putObject("appearance").put("mode", "dark");
        PersonalPreferenceDtos.PersonalPreferenceResponse result = service.patch(
                7L,
                11L,
                "corr-pref",
                new PersonalPreferenceDtos.PatchPersonalPreferenceRequest(patch, 3L));

        assertThat(result.preferences().path("appearance").path("mode").asText()).isEqualTo("dark");
        assertThat(result.preferences().path("appearance").path("density").asText()).isEqualTo("comfortable");
        assertThat(result.preferences().path("accessibility").path("reduceMotion").asBoolean()).isTrue();
        assertThat(result.preferences().path("notifications").path("digest").asText()).isEqualTo("daily");
        assertThat(result.version()).isEqualTo(4L);
    }

    @Test
    void treatsNullKnownFieldsAsAResetToTheGovernedDefault() {
        ObjectNode stored = objectMapper.createObjectNode();
        stored.putObject("appearance").put("mode", "dark").put("density", "compact");
        stored.putObject("accessibility").put("highContrast", true).put("reduceMotion", true);
        PersonalPreference preference = PersonalPreference.builder()
                .tenantId(7L)
                .userId(11L)
                .schemaVersion(1)
                .preferencePayload(stored)
                .version(2L)
                .build();
        when(repository.findByTenantIdAndUserId(7L, 11L)).thenReturn(Optional.of(preference));
        when(repository.saveAndFlush(any(PersonalPreference.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ObjectNode patch = objectMapper.createObjectNode();
        patch.putObject("appearance").putNull("mode");
        PersonalPreferenceDtos.PersonalPreferenceResponse result = service.patch(
                7L,
                11L,
                null,
                new PersonalPreferenceDtos.PatchPersonalPreferenceRequest(patch, 2L));

        assertThat(result.preferences().path("appearance").path("mode").asText()).isEqualTo("system");
        assertThat(result.preferences().path("appearance").path("density").asText()).isEqualTo("compact");
    }

    @Test
    void rejectsUnknownFieldsAndInvalidValues() {
        ObjectNode unknown = objectMapper.createObjectNode();
        unknown.putObject("appearance").put("colour", "dark");
        ObjectNode invalid = objectMapper.createObjectNode();
        invalid.putObject("appearance").put("mode", "midnight");

        assertThatThrownBy(() -> service.patch(
                        7L,
                        11L,
                        null,
                        new PersonalPreferenceDtos.PatchPersonalPreferenceRequest(unknown, 0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        when(repository.findByTenantIdAndUserId(7L, 11L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.patch(
                        7L,
                        11L,
                        null,
                        new PersonalPreferenceDtos.PatchPersonalPreferenceRequest(invalid, 0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void rejectsAStaleVersion() {
        PersonalPreference preference = PersonalPreference.builder()
                .tenantId(7L)
                .userId(11L)
                .schemaVersion(1)
                .preferencePayload(objectMapper.createObjectNode())
                .version(5L)
                .build();
        when(repository.findByTenantIdAndUserId(7L, 11L)).thenReturn(Optional.of(preference));
        ObjectNode patch = objectMapper.createObjectNode();
        patch.putObject("appearance").put("mode", "dark");

        assertThatThrownBy(() -> service.patch(
                        7L,
                        11L,
                        null,
                        new PersonalPreferenceDtos.PatchPersonalPreferenceRequest(patch, 4L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void mapsADatabaseOptimisticLockFailureToAResourceConflict() {
        ObjectNode stored = objectMapper.createObjectNode();
        stored.putObject("appearance").put("mode", "light").put("density", "standard");
        stored.putObject("accessibility").put("highContrast", false).put("reduceMotion", false);
        PersonalPreference preference = PersonalPreference.builder()
                .tenantId(7L)
                .userId(11L)
                .schemaVersion(1)
                .preferencePayload(stored)
                .version(2L)
                .build();
        when(repository.findByTenantIdAndUserId(7L, 11L)).thenReturn(Optional.of(preference));
        when(repository.saveAndFlush(any(PersonalPreference.class)))
                .thenThrow(new OptimisticLockingFailureException("concurrent update"));
        ObjectNode patch = objectMapper.createObjectNode();
        patch.putObject("appearance").put("mode", "dark");

        assertThatThrownBy(() -> service.patch(
                        7L,
                        11L,
                        null,
                        new PersonalPreferenceDtos.PatchPersonalPreferenceRequest(patch, 2L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }
}

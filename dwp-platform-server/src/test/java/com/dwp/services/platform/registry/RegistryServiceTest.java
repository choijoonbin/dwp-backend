package com.dwp.services.platform.registry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.reference.ReferenceLifecycle;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistryServiceTest {

    @Mock
    private RegistryEntryRepository repository;
    @Mock
    private PlatformAuditService auditService;

    private RegistryService service;

    @BeforeEach
    void setUp() {
        service = new RegistryService(repository, auditService);
    }

    @Test
    void createsNormalizedFirstDraftRevision() {
        when(repository.existsByTenantIdAndRegistryTypeAndEntryKey(
                7L, RegistryType.AGENT, "DAILY_BRIEF")).thenReturn(false);
        when(repository.saveAndFlush(any(RegistryEntry.class))).thenAnswer(invocation -> {
            RegistryEntry entry = invocation.getArgument(0);
            entry.setRegistryEntryId(51L);
            entry.setVersion(0L);
            return entry;
        });

        RegistryDtos.RegistryEntryResponse result = service.create(
                7L,
                11L,
                "corr-1",
                new RegistryDtos.CreateRegistryEntryRequest(
                        RegistryType.AGENT,
                        "daily_brief",
                        "Daily brief",
                        "Prepares the employee brief",
                        "team:ai-platform",
                        RiskTier.MEDIUM,
                        "1.0.0"));

        assertThat(result.entryKey()).isEqualTo("DAILY_BRIEF");
        assertThat(result.revision()).isEqualTo(1);
        assertThat(result.lifecycleState()).isEqualTo(ReferenceLifecycle.DRAFT);
        verify(auditService).success(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void activatingARevisionSupersedesThePreviousActiveRevision() {
        RegistryEntry active = entry(70L, 1, ReferenceLifecycle.ACTIVE, 3L);
        RegistryEntry draft = entry(71L, 2, ReferenceLifecycle.DRAFT, 0L);
        when(repository.findByTenantIdAndRegistryTypeAndEntryKeyAndRevision(
                7L, RegistryType.AGENT, "DAILY_BRIEF", 2)).thenReturn(Optional.of(draft));
        when(repository.findByTenantIdAndRegistryTypeAndEntryKeyAndLifecycleState(
                7L,
                RegistryType.AGENT,
                "DAILY_BRIEF",
                ReferenceLifecycle.ACTIVE)).thenReturn(Optional.of(active));
        when(repository.saveAndFlush(any(RegistryEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RegistryDtos.RegistryEntryResponse result = service.activateRevision(
                7L,
                11L,
                "corr-2",
                RegistryType.AGENT,
                "daily_brief",
                2,
                0L);

        assertThat(active.getLifecycleState()).isEqualTo(ReferenceLifecycle.RETIRED);
        assertThat(result.lifecycleState()).isEqualTo(ReferenceLifecycle.ACTIVE);
        verify(auditService, times(2)).success(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesAnotherDraftForTheSameTenantRegistryKey() {
        RegistryEntry draft = entry(71L, 2, ReferenceLifecycle.DRAFT, 0L);
        when(repository.findByTenantIdAndRegistryTypeAndEntryKeyAndLifecycleState(
                7L,
                RegistryType.AGENT,
                "DAILY_BRIEF",
                ReferenceLifecycle.DRAFT)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.createRevision(
                7L,
                11L,
                "corr-3",
                RegistryType.AGENT,
                "daily_brief",
                new RegistryDtos.CreateRegistryRevisionRequest(
                        "Daily brief",
                        null,
                        "team:ai-platform",
                        RiskTier.MEDIUM,
                        "1.1.0")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void detailLookupNeverFallsBackAcrossTenants() {
        when(repository.findByTenantIdAndRegistryTypeAndEntryKeyOrderByRevisionDesc(
                9L,
                RegistryType.AGENT,
                "DAILY_BRIEF")).thenReturn(List.of());

        assertThatThrownBy(() -> service.get(9L, RegistryType.AGENT, "daily_brief"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private RegistryEntry entry(
            Long id,
            Integer revision,
            ReferenceLifecycle lifecycle,
            Long version) {
        return RegistryEntry.builder()
                .registryEntryId(id)
                .tenantId(7L)
                .registryType(RegistryType.AGENT)
                .entryKey("DAILY_BRIEF")
                .revision(revision)
                .name("Daily brief")
                .ownerRef("team:ai-platform")
                .riskTier(RiskTier.MEDIUM)
                .artifactVersion("1." + revision + ".0")
                .lifecycleState(lifecycle)
                .version(version)
                .build();
    }
}


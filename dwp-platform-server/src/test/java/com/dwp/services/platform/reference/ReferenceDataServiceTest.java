package com.dwp.services.platform.reference;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferenceDataServiceTest {

    @Mock
    private ReferenceSetRepository setRepository;
    @Mock
    private ReferenceItemRepository itemRepository;
    @Mock
    private ReferenceItemLabelRepository labelRepository;
    @Mock
    private PlatformAuditService auditService;

    private ReferenceDataService service;

    @BeforeEach
    void setUp() {
        service = new ReferenceDataService(
                setRepository,
                itemRepository,
                labelRepository,
                auditService);
    }

    @Test
    void createsTenantScopedDraftSetsWithNormalizedKeys() {
        when(setRepository.existsByTenantIdAndSetKey(7L, "WORK_PRIORITY")).thenReturn(false);
        when(setRepository.saveAndFlush(any(ReferenceSet.class))).thenAnswer(invocation -> {
            ReferenceSet set = invocation.getArgument(0);
            set.setReferenceSetId(41L);
            set.setVersion(0L);
            return set;
        });

        ReferenceDataDtos.ReferenceSetDetail result = service.createSet(
                7L,
                11L,
                "corr-1",
                new ReferenceDataDtos.CreateSetRequest(
                        "work_priority",
                        "Work priority",
                        "Tenant values"));

        assertThat(result.setKey()).isEqualTo("WORK_PRIORITY");
        assertThat(result.lifecycleState()).isEqualTo(ReferenceLifecycle.DRAFT);
        assertThat(result.revision()).isEqualTo(1L);
        verify(auditService).success(
                eq(7L),
                eq(11L),
                eq("reference-set.created"),
                eq("REFERENCE_SET"),
                eq("WORK_PRIORITY"),
                eq("corr-1"),
                isNull(),
                anyMap());
    }

    @Test
    void refusesToActivateEmptySets() {
        ReferenceSet set = activeCandidate();
        when(setRepository.findByTenantIdAndSetKey(7L, "WORK_PRIORITY"))
                .thenReturn(Optional.of(set));
        when(itemRepository.findByTenantIdAndReferenceSetIdOrderBySortOrderAscCodeAsc(7L, 41L))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.activateSet(7L, 11L, "corr-2", "WORK_PRIORITY", 0L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void neverFallsBackAcrossTenantBoundaries() {
        when(setRepository.findByTenantIdAndSetKey(9L, "WORK_PRIORITY"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSet(9L, "WORK_PRIORITY"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(setRepository).findByTenantIdAndSetKey(9L, "WORK_PRIORITY");
    }

    private ReferenceSet activeCandidate() {
        return ReferenceSet.builder()
                .referenceSetId(41L)
                .tenantId(7L)
                .setKey("WORK_PRIORITY")
                .name("Work priority")
                .lifecycleState(ReferenceLifecycle.DRAFT)
                .contentRevision(1L)
                .version(0L)
                .build();
    }

}

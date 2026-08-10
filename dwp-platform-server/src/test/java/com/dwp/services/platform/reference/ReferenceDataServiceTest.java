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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
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

    @Test
    void storesResolvedParentIdentityWithTheReferenceItem() {
        ReferenceSet set = activeCandidate();
        ReferenceItem parent = item(101L, "PARENT", null);
        when(setRepository.findByTenantIdAndSetKey(7L, "WORK_PRIORITY"))
                .thenReturn(Optional.of(set));
        when(itemRepository.existsByTenantIdAndReferenceSetIdAndCode(7L, 41L, "CHILD"))
                .thenReturn(false);
        when(itemRepository.findByTenantIdAndReferenceSetIdAndCode(7L, 41L, "PARENT"))
                .thenReturn(Optional.of(parent));
        when(itemRepository.findByTenantIdAndReferenceSetIdAndReferenceItemId(7L, 41L, 101L))
                .thenReturn(Optional.of(parent));
        when(itemRepository.saveAndFlush(any(ReferenceItem.class))).thenAnswer(invocation -> {
            ReferenceItem saved = invocation.getArgument(0);
            saved.setReferenceItemId(102L);
            saved.setVersion(0L);
            return saved;
        });
        when(itemRepository.findByTenantIdAndReferenceSetIdOrderBySortOrderAscCodeAsc(7L, 41L))
                .thenReturn(List.of());

        service.createItem(
                7L,
                11L,
                "corr-parent",
                "WORK_PRIORITY",
                new ReferenceDataDtos.CreateItemRequest(
                        "CHILD",
                        10,
                        "PARENT",
                        null,
                        null,
                        List.of(new ReferenceDataDtos.LocalizedLabelRequest(
                                "en-US",
                                "Child",
                                null))));

        verify(itemRepository).saveAndFlush(argThat(item ->
                item.getParentReferenceItemId().equals(101L)
                        && item.getParentCode().equals("PARENT")));
    }

    @Test
    void rejectsReferenceHierarchyCycles() {
        ReferenceSet set = activeCandidate();
        ReferenceItem current = item(101L, "A", null);
        ReferenceItem proposedParent = item(102L, "B", 101L);
        when(setRepository.findByTenantIdAndSetKey(7L, "WORK_PRIORITY"))
                .thenReturn(Optional.of(set));
        when(itemRepository.findByTenantIdAndReferenceSetIdAndCode(7L, 41L, "A"))
                .thenReturn(Optional.of(current));
        when(itemRepository.findByTenantIdAndReferenceSetIdAndCode(7L, 41L, "B"))
                .thenReturn(Optional.of(proposedParent));
        when(itemRepository.findByTenantIdAndReferenceSetIdAndReferenceItemId(7L, 41L, 102L))
                .thenReturn(Optional.of(proposedParent));

        assertThatThrownBy(() -> service.updateItem(
                        7L,
                        11L,
                        "corr-cycle",
                        "WORK_PRIORITY",
                        "A",
                        new ReferenceDataDtos.UpdateItemRequest(
                                10,
                                "B",
                                null,
                                null,
                                List.of(new ReferenceDataDtos.LocalizedLabelRequest(
                                        "en-US",
                                        "A",
                                        null)),
                                0L)))
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
                    assertThat(exception.getMessage()).contains("cycle");
                });
        verify(itemRepository, never()).saveAndFlush(any(ReferenceItem.class));
    }

    @Test
    void refusesToRetireParentsWithActiveChildren() {
        ReferenceSet set = activeCandidate();
        ReferenceItem parent = item(101L, "PARENT", null);
        parent.setLifecycleState(ReferenceLifecycle.ACTIVE);
        when(setRepository.findByTenantIdAndSetKey(7L, "WORK_PRIORITY"))
                .thenReturn(Optional.of(set));
        when(itemRepository.findByTenantIdAndReferenceSetIdAndCode(7L, 41L, "PARENT"))
                .thenReturn(Optional.of(parent));
        when(itemRepository
                        .existsByTenantIdAndReferenceSetIdAndParentReferenceItemIdAndLifecycleState(
                                7L,
                                41L,
                                101L,
                                ReferenceLifecycle.ACTIVE))
                .thenReturn(true);

        assertThatThrownBy(() -> service.retireItem(
                        7L,
                        11L,
                        "corr-retire-parent",
                        "WORK_PRIORITY",
                        "PARENT",
                        0L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(itemRepository, never()).saveAndFlush(any(ReferenceItem.class));
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

    private ReferenceItem item(Long id, String code, Long parentId) {
        return ReferenceItem.builder()
                .referenceItemId(id)
                .tenantId(7L)
                .referenceSetId(41L)
                .code(code)
                .lifecycleState(ReferenceLifecycle.DRAFT)
                .sortOrder(0)
                .parentReferenceItemId(parentId)
                .version(0L)
                .build();
    }

}

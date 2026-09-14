package com.dwp.services.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.repository.RoleMemberRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovalWorkflowRoleEnumerationTest {
    RoleMemberRepository members = mock(RoleMemberRepository.class);
    ApprovalWorkflowRoleEnumeration source = new ApprovalWorkflowRoleEnumeration(members);

    @Test void returnsCompleteOrderedMembersWithoutPermissionFilteringOrTruncation() {
        when(members.countEffectiveActiveUsers(42L, 10L)).thenReturn(3L);
        when(members.enumerateEffectiveActiveUsers(42L, 10L)).thenReturn(List.of(1L, 2L, 3L));
        assertEquals(List.of(1L, 2L, 3L), source.complete(42, 10));
        verify(members, times(2)).countEffectiveActiveUsers(42L, 10L);
    }

    @Test void excessPopulationFailsBeforeEnumeration() {
        when(members.countEffectiveActiveUsers(42L, 10L)).thenReturn(1001L);
        assertThrows(BaseException.class, () -> source.complete(42, 10));
        verify(members, never()).enumerateEffectiveActiveUsers(any(), any());
    }

    @Test void countMismatchAndCountChangeFailClosed() {
        when(members.countEffectiveActiveUsers(42L, 10L)).thenReturn(3L);
        when(members.enumerateEffectiveActiveUsers(42L, 10L)).thenReturn(List.of(1L, 2L));
        assertThrows(BaseException.class, () -> source.complete(42, 10));
        when(members.enumerateEffectiveActiveUsers(42L, 10L)).thenReturn(List.of(1L, 2L, 3L));
        when(members.countEffectiveActiveUsers(42L, 10L)).thenReturn(3L, 2L);
        assertThrows(BaseException.class, () -> source.complete(42, 10));
    }

    @Test void duplicateUnorderedAndAbsentEvidenceCannotShrinkQuorum() {
        when(members.countEffectiveActiveUsers(42L, 10L)).thenReturn(2L);
        for (var values : List.of(List.of(1L, 1L), List.of(2L, 1L), List.of(0L, 1L))) {
            when(members.enumerateEffectiveActiveUsers(42L, 10L)).thenReturn(values);
            assertThrows(BaseException.class, () -> source.complete(42, 10));
        }
        when(members.enumerateEffectiveActiveUsers(42L, 10L)).thenReturn(null);
        assertThrows(BaseException.class, () -> source.complete(42, 10));
    }
}

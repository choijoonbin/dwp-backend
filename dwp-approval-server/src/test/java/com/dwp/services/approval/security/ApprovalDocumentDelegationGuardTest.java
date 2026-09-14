package com.dwp.services.approval.security;

import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.dwp.services.approval.security.ApprovalDocumentPostgresFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalDocumentDelegationGuardTest {
    ApprovalIdentityDirectory identities;
    ApprovalDocumentOwnerRepository owners;
    ApprovalDocumentAuthority authority;
    @BeforeEach void before() {
        identities=mock(ApprovalIdentityDirectory.class);owners=mock(ApprovalDocumentOwnerRepository.class);
        var canonical=new ApprovalDocumentCanonical(new ObjectMapper().findAndRegisterModules());
        authority=new ApprovalDocumentAuthority(new ApprovalWorkAuthority(identities),identities,owners,canonical);
        ApprovalRequestContext.set(200L,42L,null,"Delegate",Set.of(),DOC_PERMISSIONS);
        when(identities.require(42,200)).thenReturn(documentSubject(200,List.of(),DOC_PERMISSIONS));
        when(identities.require(42,101)).thenReturn(documentSubject(101,List.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS));
    }
    @AfterEach void after(){ApprovalDocumentPostgresFixture.clear();}
    @Test void independentPolicyRejectsWrongOriginalSourceEvenIfResolverReturnsIt() {
        // Deliberate resolver fault injection, not a claim that current SQL returns this row.
        var owner=owner(100L,"APPROVAL_OPERATOR",200L);
        when(owners.lockDelegations(any(),eq(owner))).thenReturn(List.of(delegation(101,"[\"APPROVAL_OPERATOR\"]")));
        assertThatThrownBy(()->authority.require(owner,"VIEW")).isInstanceOf(BaseException.class);
        verify(identities,never()).require(42,101);
    }
    @Test void preclaimAlternativeResolverSourceRemainsEligible() {
        var owner=owner(null,null,null);
        when(owners.lockDelegations(any(),eq(owner))).thenReturn(List.of(delegation(101,"[\"APPROVAL_OPERATOR\"]")));
        assertThatCode(()->authority.require(owner,"VIEW")).doesNotThrowAnyException();
    }
    @Test void originalDirectAssignmentDoesNotRequireARoleCode() {
        when(identities.require(42,100)).thenReturn(documentSubject(100,List.of(),DOC_PERMISSIONS));
        var owner=owner(100L,null,200L);
        when(owners.lockDelegations(any(),eq(owner))).thenReturn(List.of(delegation(100,"[]")));
        assertThatCode(()->authority.require(owner,"VIEW")).doesNotThrowAnyException();
    }
    private ApprovalDocumentOwnerRepository.Delegation delegation(long source,String roles){return new ApprovalDocumentOwnerRepository.Delegation(UUID.randomUUID(),source,roles);}
    private ApprovalDocumentOwnerRepository.Owner owner(Long source,String role,Long assigned) {
        return new ApprovalDocumentOwnerRepository.Owner(UUID.randomUUID(),UUID.randomUUID(),2L,1L,100L,"APR-TEST","Document","Summary","IN_REVIEW","RESTRICTED","RS_APPROVALS",UUID.randomUUID(),"CLAIMED",assigned,"APPROVAL_OPERATOR",source,role,2,"a".repeat(64),"{}","{}");
    }
}

package com.dwp.services.platform.home.personalization;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;
import java.time.OffsetDateTime;

public interface HomeComposerProposalRepository
        extends JpaRepository<HomeComposerProposal, UUID> {
    Optional<HomeComposerProposal> findByProposalIdAndTenantIdAndUserId(
            UUID proposalId, Long tenantId, Long userId);
    Optional<HomeComposerProposal> findByTenantIdAndUserIdAndCreationCommandId(
            Long tenantId, Long userId, UUID creationCommandId);
    long countByTenantIdAndUserIdAndViewIdAndStateAndExpiresAtAfter(
            Long tenantId, Long userId, UUID viewId, String state, OffsetDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select proposal from HomeComposerProposal proposal "
            + "where proposal.proposalId = :proposalId "
            + "and proposal.tenantId = :tenantId and proposal.userId = :userId")
    Optional<HomeComposerProposal> findOwnedForUpdate(
            UUID proposalId, Long tenantId, Long userId);
}

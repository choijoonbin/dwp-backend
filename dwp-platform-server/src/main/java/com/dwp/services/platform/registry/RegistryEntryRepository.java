package com.dwp.services.platform.registry;

import com.dwp.services.platform.reference.ReferenceLifecycle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RegistryEntryRepository extends JpaRepository<RegistryEntry, Long> {

    boolean existsByTenantIdAndRegistryTypeAndEntryKey(
            Long tenantId,
            RegistryType registryType,
            String entryKey);

    Optional<RegistryEntry> findFirstByTenantIdAndRegistryTypeAndEntryKeyOrderByRevisionDesc(
            Long tenantId,
            RegistryType registryType,
            String entryKey);

    Optional<RegistryEntry> findByTenantIdAndRegistryTypeAndEntryKeyAndRevision(
            Long tenantId,
            RegistryType registryType,
            String entryKey,
            Integer revision);

    Optional<RegistryEntry> findByTenantIdAndRegistryTypeAndEntryKeyAndLifecycleState(
            Long tenantId,
            RegistryType registryType,
            String entryKey,
            ReferenceLifecycle lifecycleState);

    List<RegistryEntry> findByTenantIdAndRegistryTypeAndEntryKeyOrderByRevisionDesc(
            Long tenantId,
            RegistryType registryType,
            String entryKey);

    List<RegistryEntry> findByTenantIdAndLifecycleStateOrderByRegistryTypeAscNameAsc(
            Long tenantId,
            ReferenceLifecycle lifecycleState);

    List<RegistryEntry> findByTenantIdAndRegistryTypeAndLifecycleStateOrderByNameAsc(
            Long tenantId,
            RegistryType registryType,
            ReferenceLifecycle lifecycleState);

    @Query(
            value = """
                    select entry from RegistryEntry entry
                    where entry.tenantId = :tenantId
                      and entry.revision = (
                        select max(candidate.revision) from RegistryEntry candidate
                        where candidate.tenantId = entry.tenantId
                          and candidate.registryType = entry.registryType
                          and candidate.entryKey = entry.entryKey
                      )
                      and (:registryType is null or entry.registryType = :registryType)
                      and (:lifecycleState is null or entry.lifecycleState = :lifecycleState)
                      and (:queryPattern is null
                        or lower(entry.entryKey) like :queryPattern
                        or lower(entry.name) like :queryPattern
                        or lower(entry.ownerRef) like :queryPattern)
                    """,
            countQuery = """
                    select count(entry) from RegistryEntry entry
                    where entry.tenantId = :tenantId
                      and entry.revision = (
                        select max(candidate.revision) from RegistryEntry candidate
                        where candidate.tenantId = entry.tenantId
                          and candidate.registryType = entry.registryType
                          and candidate.entryKey = entry.entryKey
                      )
                      and (:registryType is null or entry.registryType = :registryType)
                      and (:lifecycleState is null or entry.lifecycleState = :lifecycleState)
                      and (:queryPattern is null
                        or lower(entry.entryKey) like :queryPattern
                        or lower(entry.name) like :queryPattern
                        or lower(entry.ownerRef) like :queryPattern)
                    """)
    Page<RegistryEntry> findHeads(
            @Param("tenantId") Long tenantId,
            @Param("registryType") RegistryType registryType,
            @Param("lifecycleState") ReferenceLifecycle lifecycleState,
            @Param("queryPattern") String queryPattern,
            Pageable pageable);
}


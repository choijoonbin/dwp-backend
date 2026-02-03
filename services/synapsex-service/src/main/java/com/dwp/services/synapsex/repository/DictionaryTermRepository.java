package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.DictionaryTerm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DictionaryTermRepository extends JpaRepository<DictionaryTerm, Long> {

    List<DictionaryTerm> findByTenantIdOrderByTermKeyAsc(Long tenantId);

    List<DictionaryTerm> findByTenantIdAndCategoryOrderByTermKeyAsc(Long tenantId, String category);

    Page<DictionaryTerm> findByTenantIdOrderByTermKeyAsc(Long tenantId, Pageable pageable);

    Page<DictionaryTerm> findByTenantIdAndCategoryOrderByTermKeyAsc(Long tenantId, String category, Pageable pageable);

    Optional<DictionaryTerm> findByTenantIdAndTermKey(Long tenantId, String termKey);
}

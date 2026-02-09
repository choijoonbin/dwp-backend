package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AppCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppCodeRepository extends JpaRepository<AppCode, Long> {

    List<AppCode> findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(String groupKey);

    Optional<AppCode> findByGroupKeyAndCodeAndIsActiveTrue(String groupKey, String code);

    List<AppCode> findByGroupKeyInAndIsActiveTrueOrderByGroupKeyAscSortOrderAsc(Iterable<String> groupKeys);
}

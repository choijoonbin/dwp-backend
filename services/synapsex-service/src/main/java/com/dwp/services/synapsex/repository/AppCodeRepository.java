package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AppCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppCodeRepository extends JpaRepository<AppCode, Long> {

    List<AppCode> findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(String groupKey);

    List<AppCode> findByGroupKeyInAndIsActiveTrueOrderByGroupKeyAscSortOrderAsc(Iterable<String> groupKeys);
}

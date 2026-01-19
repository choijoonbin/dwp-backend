package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.CodeGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 코드 그룹 Repository
 */
@Repository
public interface CodeGroupRepository extends JpaRepository<CodeGroup, Long> {
    
    /**
     * 그룹 키로 조회
     */
    Optional<CodeGroup> findByGroupKey(String groupKey);
    
    /**
     * 활성화된 그룹 목록 조회
     */
    List<CodeGroup> findByIsActiveTrueOrderByGroupKey();
}

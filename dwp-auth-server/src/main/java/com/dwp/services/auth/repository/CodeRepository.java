package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.Code;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 코드 Repository
 */
@Repository
public interface CodeRepository extends JpaRepository<Code, Long> {
    
    /**
     * 그룹 키와 코드로 조회
     */
    Optional<Code> findByGroupKeyAndCode(String groupKey, String code);
    
    /**
     * 그룹 키로 활성화된 코드 목록 조회 (정렬 순서 기준)
     */
    List<Code> findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(String groupKey);
    
    /**
     * 그룹 키로 코드 목록 조회 (정렬 순서 기준)
     */
    List<Code> findByGroupKeyOrderBySortOrderAsc(String groupKey);
    
    /**
     * 그룹 키 목록으로 활성화된 코드 목록 조회
     */
    List<Code> findByGroupKeyInAndIsActiveTrueOrderByGroupKeyAscSortOrderAsc(List<String> groupKeys);
    
    /**
     * 코드 존재 여부 확인
     */
    boolean existsByGroupKeyAndCodeAndIsActiveTrue(String groupKey, String code);
}

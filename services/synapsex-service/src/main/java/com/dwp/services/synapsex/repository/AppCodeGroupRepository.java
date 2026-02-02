package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AppCodeGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppCodeGroupRepository extends JpaRepository<AppCodeGroup, Long> {

    List<AppCodeGroup> findByIsActiveTrueOrderByGroupKeyAsc();

    Optional<AppCodeGroup> findByGroupKey(String groupKey);
}

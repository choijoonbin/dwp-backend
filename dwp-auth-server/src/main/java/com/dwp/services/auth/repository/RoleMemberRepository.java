package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.RoleMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RoleMemberRepository extends JpaRepository<RoleMember, Long> {

    @Query("""
            select member.roleId
            from RoleMember member
            where member.tenantId = :tenantId and member.userId = :userId
            """)
    List<Long> findRoleIds(
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId);
}

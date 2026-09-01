package com.dwp.services.auth.service;

import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.DirectoryGroupMemberRepository;
import com.dwp.services.auth.repository.DirectoryGroupRepository;
import com.dwp.services.auth.repository.OrganizationUnitRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class DirectoryAdminService extends DirectoryAdminGroupService {
    public DirectoryAdminService(
            OrganizationUnitRepository organizationRepository,
            DirectoryGroupRepository groupRepository,
            DirectoryGroupMemberRepository groupMemberRepository,
            UserRepository userRepository,
            AuthSessionRepository authSessionRepository,
            IdentityAuditService auditService,
            GroupRoleConflictGuard groupRoleConflictGuard) {
        super(
                organizationRepository,
                groupRepository,
                groupMemberRepository,
                userRepository,
                authSessionRepository,
                auditService,
                groupRoleConflictGuard);
    }
}

package com.dwp.core.database.authority;

import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

/**
 * Runtime-only native JDBC inspection SPI. No migration pool/credential/history SELECT is allowed.
 * An actual provider must inspect the ORIGINAL JDBC login, current_user/session_user, server/catalog,
 * pool posture, role/system/ownership/membership escapes, effective exact ACL plus column/default/
 * grant-option/group surfaces and ALL history privileges without selecting history rows.
 * Privilege hashes must use an independently pinned policy/compiler, not live-value self-discovery.
 * Native provider and PG proof are UNWIRED/NOT_IMPLEMENTED; absence or unavailable fails closed.
 */
public interface RuntimePoolInspectionPort {
    Optional<Inspection> inspect(RuntimeStreamStartupSeal.RuntimePurpose expectedRuntime, DataSource runtimePool);

    record Inspection(String originalJdbcLogin, String currentUser, String sessionUser,
            String database, String trustedEndpointId, String serverAddress, int serverPort,
            String postgresVersion, boolean autoCommit, boolean readOnly, List<String> searchPath,
            boolean roleSettingNone, boolean replicationRoleOrigin, boolean directLogin,
            boolean elevatedRoleAttributes, boolean elevatedMembership, boolean databaseCreate,
            boolean databaseTemporary, boolean foreignDatabaseConnect, boolean anySchemaCreate,
            boolean anyObjectOwnership, boolean anyHistoryPrivilege, String privilegeSurfaceSha256) {
        public Inspection {
            searchPath = List.copyOf(searchPath);
        }
    }
}

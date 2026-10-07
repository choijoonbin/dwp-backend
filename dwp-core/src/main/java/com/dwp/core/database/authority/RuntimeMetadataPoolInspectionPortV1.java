package com.dwp.core.database.authority;

import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

/**
 * UNWIRED phase-A seam. Phase-B must bind DS identity/qualifier/endpoint independently, use only the
 * original metadata login and pg_catalog, no history row SELECT, DDL, grant, SET ROLE or owner pool.
 * A missing provider returns no proof; this interface does not register pools or authorize startup.
 */
@FunctionalInterface
public interface RuntimeMetadataPoolInspectionPortV1 {
    RuntimeMetadataPoolInspectionPortV1 UNAVAILABLE = (expected, alreadyRegisteredMetadataPool) -> Optional.empty();

    Optional<NativeObservation> inspect(RuntimeMetadataCatalogReadEvidenceV1.ExpectedSource expected,
            DataSource alreadyRegisteredMetadataPool);

    record NativeObservation(String originalJdbcLogin, String currentUser, String sessionUser,
            String database, String trustedEndpointId, String serverAddress, int serverPort, String postgresVersion,
            boolean autoCommit, boolean readOnly, List<String> searchPath,
            boolean directLogin, boolean roleSettingNone, boolean replicationRoleOrigin,
            boolean elevatedRoleAttributes, boolean anyMembership, boolean databaseCreate, boolean databaseTemporary,
            boolean foreignDatabaseConnect, boolean anySchemaCreate, boolean anyObjectOwnership,
            boolean anyHistoryPrivilege, boolean applicationRelationPrivilege, boolean applicationColumnPrivilege,
            boolean applicationSequencePrivilege, boolean applicationRoutineExecute, boolean anyGrantOption,
            boolean catalogMutationAuthority, String compilerVersion, String historyPolicySha256, String privilegeSurfaceSha256) {
        public NativeObservation { searchPath = searchPath == null ? List.of() : List.copyOf(searchPath); }
    }
}

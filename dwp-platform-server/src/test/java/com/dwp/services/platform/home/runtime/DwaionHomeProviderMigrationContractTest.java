package com.dwp.services.platform.home.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DwaionHomeProviderMigrationContractTest {

    @Test
    void immutableV265PublishesTheExactSignedProviderContract() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V265__activate_signed_dwaion_home_provider.sql"));

        assertThat(migration)
                .contains("'1.1.0'")
                .contains(DwaionHomeWorkloadProtocol.DEFINITION_MANIFEST_HASH)
                .contains("[\"APP.ASK:VIEW\",\"APP.DWAION_ARTIFACTS:VIEW\"]")
                .contains("DWAION_HOME_SIGNED_OWNER_PROVIDER")
                .contains("dwp1-hmac-sha256")
                .contains("singleUseReplay")
                .contains("titleProjectionGate")
                .contains("OWNER_WIDGET_PROVIDER_ACTIVATED")
                .contains("contract:contracts/home-runtime/dwaion-signed-workload.v1.json")
                .contains("dd2ab8edc36e7d52db73d3f6ae954111819ad763742e23f24eb12986faae0482")
                .contains("git:7092a93ea7446d08af6e45bb88e7225dd9ed4148:")
                .contains("2b61d64f15ccbb14e680289c851221e4a2899517466178bfe061d4e0164d2fb3")
                .contains("'PASS', 1, 1")
                .contains("policy.enabled IS DISTINCT FROM TRUE");
        assertThat(migration)
                .doesNotContain("'PASSED'")
                .doesNotContain("WAVE6_FAIL_CLOSED_OWNER_PLACEHOLDER");
    }
}

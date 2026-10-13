package com.dwp.services.auth.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HrisWave2V38AuthorizationSeedMigrationTest {

    @Test
    void declaresOnlyThePinnedDraftSeed() throws Exception {
        try (var input = new ClassPathResource(
                "/db/migration/V246__declare_hris_wave2_v38_authorization_seed.sql")
                .getInputStream()) {
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql)
                    .contains("'product-surfaces'", "38")
                    .contains("'a41145d6a2faefd2e6030a2ca9a070769019d4db5513ea214bf542dda31c374a'")
                    .contains("'product-surfaces-v1.bundle-v38.generated.json'")
                    .contains("'DRAFT'", "FALSE")
                    .doesNotContain("APPROVED", "ACTIVE");
        }
    }
}

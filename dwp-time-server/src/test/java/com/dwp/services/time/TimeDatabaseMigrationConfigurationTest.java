package com.dwp.services.time;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TimeDatabaseMigrationConfigurationTest {

    @Test
    void publisherPolicyAdmitsOnlyTheClockOutboxRelaySurface() {
        var policy = TimeDatabaseMigrationConfiguration.publisherPolicy(
                "dwp_time_projection_publisher", true);

        assertThat(policy.allowedAclPrivileges().stream()
                .filter(privilege -> privilege.objectIdentity()
                        .startsWith("public.tme_clock_outbox_events"))
                .map(privilege -> privilege.objectClass() + ":"
                        + privilege.objectIdentity() + ":" + privilege.privilege()))
                .containsExactlyInAnyOrder(
                        "RELATION:public.tme_clock_outbox_events:SELECT",
                        "COLUMN:public.tme_clock_outbox_events.publish_attempts:UPDATE",
                        "COLUMN:public.tme_clock_outbox_events.published_at:UPDATE");
        assertThat(policy.requiredAclPrivileges())
                .isEqualTo(policy.allowedAclPrivileges());
    }
}

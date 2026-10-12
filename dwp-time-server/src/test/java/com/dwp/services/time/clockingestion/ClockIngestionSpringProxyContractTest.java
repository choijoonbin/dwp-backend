package com.dwp.services.time.clockingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class ClockIngestionSpringProxyContractTest {

    @Test
    void advisedClockComponentsRemainProxyable() {
        assertThat(Modifier.isFinal(ClockOwnerController.class.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(JdbcClockEventRepository.class.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(
                JdbcClockOwnerPolicyProjectionResolver.class.getModifiers())).isFalse();
    }
}

package com.dwp.services.platform.hrisconfiguration.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.core.event.DomainEventContractRegistry;
import org.junit.jupiter.api.Test;

class ControlledTransferEventContractTest {

    @Test
    void registersOnlyTheExactSysEvt008Version() {
        DomainEventContractRegistry registry = new DomainEventContractRegistry();

        new ControlledTransferEventContract(registry);

        assertThat(registry.snapshot()).containsOnlyKeys(
                "dwp.hris.controlled-transfer.state-changed.v1");
        assertThat(registry.snapshot().get(ControlledTransferEventContract.TYPE))
                .isEqualTo(new DomainEventContractRegistry.Contract(1, 1));
        assertThat(ControlledTransferEventContract.CONTRACT_ID).isEqualTo("SYS-EVT-008");
        assertThat(ControlledTransferEventContract.AGGREGATE_TYPE)
                .isEqualTo("CONTROLLED_TRANSFER");
    }
}

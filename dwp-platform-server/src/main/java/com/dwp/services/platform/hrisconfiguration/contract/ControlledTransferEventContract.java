package com.dwp.services.platform.hrisconfiguration.contract;

import com.dwp.core.event.DomainEventContractRegistry;
import org.springframework.stereotype.Component;

/** Registers the closed SYS-EVT-008 wire identity without enabling a producer or route. */
@Component
public final class ControlledTransferEventContract {

    public static final String CONTRACT_ID = "SYS-EVT-008";
    public static final String TYPE = "dwp.hris.controlled-transfer.state-changed.v1";
    public static final String SOURCE = "urn:dwp:platform:hris:controlled-transfer";
    public static final String AGGREGATE_TYPE = "CONTROLLED_TRANSFER";

    public ControlledTransferEventContract(DomainEventContractRegistry contracts) {
        contracts.register(TYPE, 1, 1);
    }
}

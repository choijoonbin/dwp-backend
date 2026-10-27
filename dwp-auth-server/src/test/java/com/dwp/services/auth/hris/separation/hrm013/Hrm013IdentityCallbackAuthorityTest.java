package com.dwp.services.auth.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService;
import org.junit.jupiter.api.Test;

class Hrm013IdentityCallbackAuthorityTest {
    @Test
    void dispatchAndCallbackUseTheTwoExactLocallyOwnedV271Tuples() {
        assertThat(HrisServiceGrantQuiesceService.IDENTITY_DISPATCH)
                .isEqualTo(tuple(Hrm013TaskDispatchV1.IDENTITY));
        assertThat(HrisServiceGrantQuiesceService.IDENTITY_RECEIPT)
                .isEqualTo(tuple(Hrm013SeparationBridgeV1.HRM_008_IDENTITY));
        assertThat(Hrm013TaskDispatchV1.IDENTITY.callbackBindingId())
                .isEqualTo(Hrm013SeparationBridgeV1.HRM_008_IDENTITY.bindingId());
        assertThat(Hrm013TaskDispatchV1.IDENTITY.callbackPurpose())
                .isEqualTo(Hrm013SeparationBridgeV1.HRM_008_IDENTITY.purpose());
    }

    private static HrisServiceGrantAuthorityV1.TargetTuple tuple(
            Hrm013TaskDispatchV1.Profile profile) {
        return new HrisServiceGrantAuthorityV1.TargetTuple(profile.bindingId(),
                profile.callerWorkloadId(), profile.ownerWorkloadId(),
                profile.purpose());
    }

    private static HrisServiceGrantAuthorityV1.TargetTuple tuple(
            Hrm013SeparationBridgeV1.BindingProfile profile) {
        return new HrisServiceGrantAuthorityV1.TargetTuple(profile.bindingId(),
                profile.callerWorkloadId(), profile.ownerWorkloadId(),
                profile.purpose());
    }
}

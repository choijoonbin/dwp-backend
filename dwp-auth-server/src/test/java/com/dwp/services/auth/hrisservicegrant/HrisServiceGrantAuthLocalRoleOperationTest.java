package com.dwp.services.auth.hrisservicegrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HrisServiceGrantAuthLocalRoleOperationTest {
    private static final String MODULUS = "g" + "A".repeat(341);
    private final HrisServiceGrantAuthorityRepository repository =
            mock(HrisServiceGrantAuthorityRepository.class);
    private final HrisServiceGrantQuiesceService service =
            new HrisServiceGrantQuiesceService(repository);

    @Test
    void expandsIdentityPairAndWaiverGroupInOneStableLockOrder() {
        var expanded = HrisServiceGrantQuiesceService.expandedLockOrder(List.of(
                HrisServiceGrantQuiesceService.IDENTITY_DISPATCH,
                HrisServiceGrantQuiesceService.REQUESTER));

        assertThat(expanded).containsExactly(
                HrisServiceGrantQuiesceService.REQUESTER,
                HrisServiceGrantQuiesceService.IDENTITY_DISPATCH,
                HrisServiceGrantQuiesceService.WAIVER_REQUEST,
                HrisServiceGrantQuiesceService.IDENTITY_RECEIPT,
                HrisServiceGrantQuiesceService.WAIVER_DECISION);
    }

    @Test
    void refusesPartialCoupledScope() {
        assertThatThrownBy(() -> HrisServiceGrantQuiesceService.requireAtomicScope(
                Set.of(HrisServiceGrantQuiesceService.IDENTITY_DISPATCH)))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(failure -> ((Hrm013SafeErrorV1.Failure) failure).errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.INVALID_STATE);
    }

    @Test
    void expandsEveryTaskPairFromEitherSharedContractSideAndRejectsPartialScopes() {
        List<Set<HrisServiceGrantAuthorityV1.TargetTuple>> pairs = List.of(
                Set.of(HrisServiceGrantQuiesceService.TIME_DISPATCH,
                        HrisServiceGrantQuiesceService.TIME_RECEIPT),
                Set.of(HrisServiceGrantQuiesceService.PAYROLL_DISPATCH,
                        HrisServiceGrantQuiesceService.PAYROLL_RECEIPT),
                Set.of(HrisServiceGrantQuiesceService.IDENTITY_DISPATCH,
                        HrisServiceGrantQuiesceService.IDENTITY_RECEIPT));
        for (Set<HrisServiceGrantAuthorityV1.TargetTuple> pair : pairs) {
            for (var side : pair) {
                assertThat(Set.copyOf(HrisServiceGrantQuiesceService.expandedLockOrder(
                        List.of(side))))
                        .isEqualTo(pair);
                assertThatThrownBy(() -> HrisServiceGrantQuiesceService.requireAtomicScope(
                        Set.of(side)))
                        .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
            }
            HrisServiceGrantQuiesceService.requireAtomicScope(pair);
        }
    }

    @Test
    void cutoverIsAFullDesiredSnapshotWithOnlyAffectedGroupDelta() {
        var timeDispatch = binding(HrisServiceGrantQuiesceService.TIME_DISPATCH, "time-d1");
        var timeReceipt = binding(HrisServiceGrantQuiesceService.TIME_RECEIPT, "time-r1");
        var payrollDispatch = binding(HrisServiceGrantQuiesceService.PAYROLL_DISPATCH, "pay-d1");
        var payrollReceipt = binding(HrisServiceGrantQuiesceService.PAYROLL_RECEIPT, "pay-r1");
        List<HrisServiceGrantQuiesceService.GrantBinding> current = List.of(
                timeDispatch, timeReceipt, payrollDispatch, payrollReceipt);
        List<HrisServiceGrantAuthorityV1.TargetTuple> affected =
                HrisServiceGrantQuiesceService.expandedLockOrder(
                        List.of(HrisServiceGrantQuiesceService.TIME_DISPATCH));
        var rotated = successor(List.of(
                binding(HrisServiceGrantQuiesceService.TIME_DISPATCH, "time-d2"),
                binding(HrisServiceGrantQuiesceService.TIME_RECEIPT, "time-r2"),
                payrollDispatch, payrollReceipt));

        HrisServiceGrantQuiesceService.requireCutoverSnapshot(affected, current, rotated);
        HrisServiceGrantQuiesceService.requireCutoverSnapshot(affected, current,
                successor(List.of(payrollDispatch, payrollReceipt)));
        assertThatThrownBy(() -> HrisServiceGrantQuiesceService.requireCutoverSnapshot(
                affected, current, successor(List.of(rotated.bindings().get(0),
                        rotated.bindings().get(1), payrollDispatch))))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
        assertThatThrownBy(() -> HrisServiceGrantQuiesceService.requireCutoverSnapshot(
                affected, current, successor(List.of(rotated.bindings().get(0),
                        rotated.bindings().get(1),
                        binding(HrisServiceGrantQuiesceService.PAYROLL_DISPATCH, "pay-d2"),
                        payrollReceipt))))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
        assertThatThrownBy(() -> HrisServiceGrantQuiesceService.requireCutoverSnapshot(
                affected, current, successor(List.of(rotated.bindings().get(0),
                        payrollDispatch, payrollReceipt))))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    @Test
    void ownerCredentialMustBeAnExactUnexpiredAtomicLease() {
        var target = HrisServiceGrantQuiesceService.REQUESTER;
        UUID grantSet = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-27T00:00:00Z");
        var lease = new HrisServiceGrantAuthorityOwnerCredentialProvider.OwnerCredentialLease(
                7, target, grantSet, 11, 13, target.ownerWorkloadId(), "owner-key-1",
                "vault://hrm013/tenant/7/owner-key-1", now.plusSeconds(30));
        var provider = new HrisServiceGrantAuthorityOwnerCredentialProvider(
                (tenant, requestedTarget) -> lease);

        assertThat(provider.requireCurrent(7, target, grantSet, 11, 13, now)).isEqualTo(lease);
        assertThatThrownBy(() -> provider.requireCurrent(
                7, target, grantSet, 12, 13, now))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    @Test
    void authLocalAdmissionAcceptsOnlyTheAuthSideAndNeverUsesResolverReplay() {
        var target = HrisServiceGrantQuiesceService.REQUESTER;
        var local = request(target.ownerWorkloadId(), target);
        var evidence = new Hrm013WorkloadProofV1.ReplayReservation(7, target.bindingId(),
                target.ownerWorkloadId(), "a".repeat(64), "b".repeat(64),
                Instant.now().plusSeconds(30));

        service.authorizeLocalNewWork(local, evidence, "c".repeat(64));

        verify(repository).resolveLocal(local, "c".repeat(64));
        verify(repository, never()).resolve(any(), any(), anyString());
        assertThatThrownBy(() -> service.authorizeLocalNewWork(
                request(target.callerWorkloadId(), target), evidence, "c".repeat(64)))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(value -> ((Hrm013SafeErrorV1.Failure) value).errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.AUTHORITY_DENIED);
    }

    private static HrisServiceGrantAuthorityV1.ResolveRequest request(String requester,
            HrisServiceGrantAuthorityV1.TargetTuple target) {
        return new HrisServiceGrantAuthorityV1.ResolveRequest(7, requester,
                target.callerWorkloadId(), target.ownerWorkloadId(), target.bindingId(),
                target.purpose(), HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK,
                1, 1, UUID.randomUUID(), null);
    }

    private static HrisServiceGrantQuiesceService.SuccessorGrant successor(
            List<HrisServiceGrantQuiesceService.GrantBinding> bindings) {
        return new HrisServiceGrantQuiesceService.SuccessorGrant(UUID.randomUUID(), 2, 2,
                "a".repeat(64), "b".repeat(64), bindings);
    }

    private static HrisServiceGrantQuiesceService.GrantBinding binding(
            HrisServiceGrantAuthorityV1.TargetTuple target, String keyPrefix) {
        var caller = new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                "RS256", "AQAB", keyPrefix + "-caller", "RSA", MODULUS, "sig");
        var owner = new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                "RS256", "AQAB", keyPrefix + "-owner", "RSA", MODULUS, "sig");
        return new HrisServiceGrantQuiesceService.GrantBinding(target,
                "spiffe://dwp/service/" + target.callerWorkloadId(), caller.kid(), caller,
                "spiffe://dwp/service/" + target.ownerWorkloadId(), owner.kid(), owner,
                "c".repeat(64));
    }
}

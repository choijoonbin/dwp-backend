package com.dwp.services.auth.informationreplay;

import static org.junit.jupiter.api.Assertions.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.service.InformationReplayIdentityAuthorityBridge;
import com.dwp.services.auth.service.ProductAuthorizationContractValidator;
import com.dwp.services.auth.service.ProductSurfaceAuthorityService;
import com.dwp.services.auth.service.WorkflowRuntimeActualAuthHarness;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/** Full fresh Auth migrations and real active DB descriptors; absence of sealed v9 is not positive activation. */
class InformationReplayRegistryPostgresTest {
    @Test void currentV8IsUnavailableAndImmutableBaselineOrUnsupportedDescriptorsCannotActivateReplay() throws Exception {
        var fixture = new InformationReplayProofFixture();
        try (var auth = new WorkflowRuntimeActualAuthHarness(fixture.owner, fixture.transport, fixture.signer)) {
            var contextField = WorkflowRuntimeActualAuthHarness.class.getDeclaredField("context"); contextField.setAccessible(true);
            var context = (AnnotationConfigApplicationContext) contextField.get(auth);
            var repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(context.getBean(jakarta.persistence.EntityManagerFactory.class)));
            var constructor = InformationReplayIdentityAuthorityBridge.class.getConstructors()[0];
            var bridge = (InformationReplayIdentityAuthorityBridge) constructor.newInstance(context.getBean("identity"),
                    context.getBean(ProductSurfaceAuthorityService.class), context.getBean(ProductAuthorizationContractRepository.class),
                    context.getBean(ProductAuthorizationContractValidator.class), auth.jdbc(), repositories.getRepository(RoleMemberRepository.class), fixture.mapper);
            var service = new InformationReplayAuthorityService(true, new InformationReplayProofVerifier(fixture.json, fixture.keys, Clock.systemUTC()),
                    bridge, new InformationReplayReplayStore(auth.redis()), new InformationReplayAttestationIssuer(fixture.keys, fixture.json), fixture.json);
            var contracts = context.getBean(ProductAuthorizationContractRepository.class);
            var validator = context.getBean(ProductAuthorizationContractValidator.class);
            var baseline = contracts.findActive("product-surfaces").orElseThrow();
            assertEquals(8, baseline.version());
            var exchange = fixture.exchange(fixture.bindings("REQUEST_INFO"));
            var proof = new InformationReplayProofVerifier(fixture.json, fixture.keys, Clock.systemUTC()).verify(exchange.body(), exchange.token());
            assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, assertThrows(BaseException.class,
                    () -> service.evaluate(exchange.body(), exchange.token())).getErrorCode());
            String prefix = "dwp:auth:information-replay:v1:{1:" + InformationReplayProtocol.OWNER_PURPOSE + "}:";
            for (String key : List.of(prefix + "owner:" + proof.sourceJti(), prefix + "transport:" + proof.transportJti())) assertFalse(auth.redis().hasKey(key));
            for (long version : new long[]{9, 10, 23}) {
                var rejected = assertThrows(DataAccessException.class, () -> auth.jdbc().update(
                        "UPDATE auth_product_authorization_bundle SET version=? WHERE bundle_status='ACTIVE'", version));
                assertEquals("P0001", ((java.sql.SQLException) rejected.getMostSpecificCause()).getSQLState());
                assertEquals(baseline, contracts.findActive("product-surfaces").orElseThrow());
                // Recomputed malicious descriptors still cannot become an installed lineage release.
                var fabricated = (ObjectNode) fixture.json.tree(contracts.loadContract(baseline));
                fabricated.put("version", version);
                fabricated.put("checksum", validator.checksum(fabricated));
                var failure = assertThrows(IllegalArgumentException.class, () -> validator.validateDocument(fabricated));
                if (version == 9) assertTrue(failure.getMessage().contains("v9 extension"));
                else if (version == 10) assertTrue(failure.getMessage().contains("release10 response projection coverage"));
                else assertTrue(failure.getMessage().contains("closed versions 1 through 22"));
            }
        }
    }
}

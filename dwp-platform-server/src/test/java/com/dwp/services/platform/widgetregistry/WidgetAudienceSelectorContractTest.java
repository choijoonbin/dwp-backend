package com.dwp.services.platform.widgetregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WidgetAudienceSelectorContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void matchesOnlyCanonicalClosedSelectors() throws Exception {
        var all = objectMapper.readTree("""
                {"schemaVersion":1,"mode":"ALL_ENTITLED","roleCodes":[],"groupRefs":[]}
                """);
        var any = objectMapper.readTree("""
                {"schemaVersion":1,"mode":"ANY_OF","roleCodes":["MANAGER"],"groupRefs":["group-1"]}
                """);
        var every = objectMapper.readTree("""
                {"schemaVersion":1,"mode":"ALL_OF","roleCodes":["MANAGER"],"groupRefs":["group-1"]}
                """);

        assertThat(WidgetAudienceSelectorContract.matches(all, Set.of(), Set.of())).isTrue();
        assertThat(WidgetAudienceSelectorContract.matches(any, Set.of("MANAGER"), Set.of())).isTrue();
        assertThat(WidgetAudienceSelectorContract.matches(any, Set.of(), Set.of())).isFalse();
        assertThat(WidgetAudienceSelectorContract.matches(
                every, Set.of("MANAGER"), Set.of("group-1"))).isTrue();
        assertThat(WidgetAudienceSelectorContract.matches(
                every, Set.of("MANAGER"), Set.of())).isFalse();
    }

    @Test
    void rejectsExpressionsExtrasDuplicatesAndNonCanonicalOrder() throws Exception {
        for (String json : new String[] {
                "{\"schemaVersion\":1,\"mode\":\"ALL_ENTITLED\",\"roleCodes\":[],\"groupRefs\":[],\"query\":\"*\"}",
                "{\"schemaVersion\":1,\"mode\":\"ANY_OF\",\"roleCodes\":[\"B\",\"A\"],\"groupRefs\":[]}",
                "{\"schemaVersion\":1,\"mode\":\"ANY_OF\",\"roleCodes\":[\"A\",\"A\"],\"groupRefs\":[]}",
                "{\"schemaVersion\":1,\"mode\":\"ALL_OF\",\"roleCodes\":[],\"groupRefs\":[\"bad group\"]}",
                "{\"schemaVersion\":1,\"mode\":\"ALL_ENTITLED\",\"roleCodes\":[\"ADMIN\"],\"groupRefs\":[]}"
        }) {
            assertThatThrownBy(() -> WidgetAudienceSelectorContract.requireValid(
                    objectMapper.readTree(json))).as(json).isInstanceOf(BaseException.class);
        }
    }

    @Test
    void trustedPermissionsMustContainEveryManifestAuthority() throws Exception {
        var manifest = objectMapper.readTree("""
                {"requiredAuthorities":["APP.WORK:VIEW","APP.CALENDAR:VIEW"]}
                """);
        var authority = WidgetCatalogService.AuthorityContext.of(
                "app.calendar:view, APP.WORK:VIEW", "employee", "group-1");

        assertThat(WidgetCatalogService.hasRequiredAuthorities(
                manifest, authority.permissions())).isTrue();
        assertThat(WidgetCatalogService.hasRequiredAuthorities(
                manifest, Set.of("APP.WORK:VIEW"))).isFalse();
        assertThat(authority.roles()).containsExactly("EMPLOYEE");
        assertThat(authority.groups()).containsExactly("group-1");
    }
}

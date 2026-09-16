package com.dwp.services.platform.home.runtime;

import com.dwp.services.platform.home.HomeExperienceDtos;
import com.dwp.services.platform.home.personalization.EffectiveHomeViewQuery;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.dwp.services.platform.widgetregistry.WidgetCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class HomeRuntimeAuthorizationEtagTest {

    @Test
    void authorityExpiryAndRevisionBothInvalidateTheEtag() throws Exception {
        UUID personId = UUID.randomUUID();
        OffsetDateTime firstExpiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2);
        HomeRuntimeContext first = context(personId, "authority-7", firstExpiry);
        HomeRuntimeContext renewed = context(personId, "authority-7", firstExpiry.plusMinutes(1));
        HomeRuntimeContext revised = context(personId, "authority-8", firstExpiry.plusMinutes(1));

        String firstEtag = changeVersion(first);
        String renewedEtag = changeVersion(renewed);
        String revisedEtag = changeVersion(revised);

        assertThat(renewed.fingerprint()).isNotEqualTo(first.fingerprint());
        assertThat(renewedEtag).isNotEqualTo(firstEtag);
        assertThat(revised.fingerprint()).isNotEqualTo(first.fingerprint());
        assertThat(revisedEtag).isNotEqualTo(firstEtag);
    }

    @Test
    void oldConditionalEtagCannotReturn304AfterAuthorityRevisionChanges() throws Exception {
        HomeReadModelService service = mock(HomeReadModelService.class);
        when(service.read(
                any(),
                any(HomeRuntimeRolloutDecision.TrustedInput.class),
                eq("CLASSIC"),
                eq("DESKTOP_STANDARD")))
                .thenReturn(new HomeReadModelDtos.ReadResult(model("etag-authority-8"),
                        "\"etag-authority-8\""));
        MockMvc mvc = standaloneSetup(new HomeReadModelController(
                service, mock(HomeWidgetCommandService.class), properties())).build();

        mvc.perform(get("/v2/home")
                        .queryParam("mode", "CLASSIC")
                        .queryParam("deviceClass", "DESKTOP_STANDARD")
                        .header("X-DWP-Tenant-ID", "71")
                        .header("X-DWP-User-ID", "82")
                        .header("X-DWP-Permissions", "APP.WORK:VIEW")
                        .header("X-DWP-Roles", "MEMBER")
                        .header("X-DWP-Current-Decision-Revision", "authority-8")
                        .header("X-DWP-Current-Revalidate-At",
                                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).toString())
                        .header("X-DWP-Home-Runtime-State", "SHADOW_COMPARE")
                        .header("X-DWP-Home-Rollout-Ring", "CONTROL")
                        .header("X-DWP-Home-Rollout-Revision", "rollout-authority-8")
                        .header("If-None-Match", "\"etag-authority-7\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"etag-authority-8\""));

        ArgumentCaptor<HomeRuntimeContext> context = ArgumentCaptor.forClass(
                HomeRuntimeContext.class);
        verify(service).read(
                context.capture(),
                any(HomeRuntimeRolloutDecision.TrustedInput.class),
                eq("CLASSIC"),
                eq("DESKTOP_STANDARD"));
        assertThat(context.getValue().authorityDecisionRevision()).isEqualTo("authority-8");
    }

    private String changeVersion(HomeRuntimeContext context) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HomeReadModelService service = new HomeReadModelService(
                null, null, null, null, mapper, new HomeCanonicalJson(mapper));
        HomeExperienceDtos.HomeExperienceResponse experience = mock(
                HomeExperienceDtos.HomeExperienceResponse.class);
        when(experience.version()).thenReturn(11L);
        EffectiveHomeViewQuery.EffectiveView view = mock(
                EffectiveHomeViewQuery.EffectiveView.class);
        when(view.revision()).thenReturn(17L);
        WidgetCatalogService.RuntimeCatalog catalog = mock(
                WidgetCatalogService.RuntimeCatalog.class);
        when(catalog.catalogRevision()).thenReturn("catalog-4");
        when(catalog.bindingRevision()).thenReturn("binding-3");
        when(catalog.policyRevision()).thenReturn("policy-9");
        when(catalog.safetyRevision()).thenReturn("safety-2");
        Method method = HomeReadModelService.class.getDeclaredMethod(
                "changeVersion",
                HomeRuntimeContext.class,
                HomeExperienceDtos.HomeExperienceResponse.class,
                EffectiveHomeViewQuery.EffectiveView.class,
                WidgetCatalogService.RuntimeCatalog.class,
                List.class,
                HomeReadModelDtos.HomeShell.class,
                List.class,
                String.class,
                String.class,
                HomeRuntimeRolloutDecision.class);
        method.setAccessible(true);
        HomeRuntimeRolloutDecision decision = mock(HomeRuntimeRolloutDecision.class);
        when(decision.revision()).thenReturn("rollout-17");
        when(decision.state()).thenReturn(HomeRuntimeRolloutDecision.State.SHADOW_COMPARE);
        return (String) method.invoke(
                service, context, experience, view, catalog, List.of(),
                new HomeReadModelDtos.HomeShell(
                        "Home", "", "LEFT", "COMFORTABLE", null, List.of()),
                List.of(), "CLASSIC", "DESKTOP_STANDARD", decision);
    }

    private HomeRuntimeContext context(
            UUID personId,
            String revision,
            OffsetDateTime expiry) {
        return HomeRuntimeContext.create(
                71L, 82L, personId, "APP.WORK:VIEW,APP.CALENDAR:VIEW",
                "MEMBER", "team-a", revision, expiry.toString(),
                "ko-KR", "Asia/Seoul");
    }

    private HomeReadModelDtos.HomeReadModel model(String version) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new HomeReadModelDtos.HomeReadModel(
                2, "CLASSIC", null, null, List.of(), List.of(), now,
                now.plusSeconds(30), false, List.of(), version, "SHADOW");
    }

    private HomeRuntimeProperties properties() {
        return new HomeRuntimeProperties(
                false, true, Duration.ofMillis(900), Duration.ofMillis(400),
                Duration.ofSeconds(30), Duration.ofMinutes(5), 100, 262_144);
    }
}

package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2Compiler;
import com.dwp.services.approval.domain.ApprovalFormUserBindingRepository;
import com.dwp.services.approval.domain.ApprovalFormUserCandidateService;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Authority;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.FormBinding;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Person;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApprovalFormUserCandidateServiceTest {
    private final ApprovalFormUserBindingRepository forms = mock(ApprovalFormUserBindingRepository.class);
    private final ApprovalFormUserDirectory directory = mock(ApprovalFormUserDirectory.class);
    private final ApprovalFormUserDirectory.AuthorityProvider provider = mock(ApprovalFormUserDirectory.AuthorityProvider.class);
    private final ApprovalFormUserCandidateService service = new ApprovalFormUserCandidateService(forms, provider, directory);
    private FormBinding binding;
    private Authority authority;
    private final Person person = new Person(42L, 123L, UUID.randomUUID(), "Kim", "TENANT", "ACTIVE");

    @BeforeEach void setUp() {
        ApprovalRequestContext.set(99L, 42L, null, Set.of(), Set.of());
        var compiled = new ApprovalFormSchemaV2Compiler().compile(Map.of("schemaContract", "DWP_APPROVAL_FORM_TYPED_V2", "schemaVersion", 2,
                "fields", List.of(field("summary", "TEXTAREA"), field("reviewer", "USER"),
                        Map.of("key", "lines", "labelKo", "행", "labelEn", "Lines", "type", "REPEATING_GROUP",
                                "minRows", 0, "maxRows", 10, "fields", List.of(field("owner", "USER"))))));
        binding = new FormBinding(42, 99, UUID.randomUUID(), UUID.randomUUID(), compiled.sha256());
        authority = new Authority(binding, ApprovalFormUserDirectory.SOURCE_POLICY, "ctx", "scope", "psr-" + "b".repeat(64),
                ApprovalFormUserCurrentAuthority.WORK_ROUTE, OffsetDateTime.now().plusSeconds(55));
        when(provider.requireCurrent(binding)).thenReturn(authority);
        when(forms.requirePublished(eq(binding), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(compiled);
        when(directory.search(authority, "Kim", 1)).thenReturn(new Result(authority, List.of(person)));
    }
    @AfterEach void tearDown() { ApprovalRequestContext.clear(); }

    @Test void projectsOnlyUuidAndNameWithoutInventedCursorOrTotal() throws Exception {
        var result = search(null, "reviewer", "Kim", 1);
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(result);
        assertThat(json.get("people").get(0).size()).isEqualTo(2);
        assertThat(json.get("people").get(0).has("subjectId")).isFalse();
        assertThat(json.has("cursor")).isFalse();
        assertThat(result.mayBeTruncated()).isTrue();
        assertThat(result.validUntil()).isEqualTo(authority.validUntil());
        verify(forms, org.mockito.Mockito.times(2)).requirePublished(binding, false);
        verify(provider, org.mockito.Mockito.times(3)).requireCurrent(binding);
    }

    @Test void directRepeatingGroupUserFieldIsSupported() {
        assertThat(search("lines", "owner", "Kim", 1).fieldPath()).isEqualTo("lines.owner");
    }

    @Test void nonexistentOrNonUserFieldNeverCallsSource() {
        assertThatThrownBy(() -> search(null, "summary", "Kim", 1)).isInstanceOf(BaseException.class);
        verify(directory, never()).search(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @ParameterizedTest @ValueSource(strings = {"", "K", " Kim", "Kim ", "Ki\nm"})
    void invalidQueryNeverRequestsAuthorityOrSource(String query) {
        assertThatThrownBy(() -> search(null, "reviewer", query, 1)).isInstanceOf(BaseException.class);
        verify(provider, never()).requireCurrent(any());
    }

    @Test void changedAuthorityAfterReadDiscardsCandidates() {
        var changed = new Authority(binding, authority.sourcePolicyKey(), authority.contextKey(), authority.contextScopeKey(),
                "psr-" + "c".repeat(64), authority.routeContractKey(), authority.validUntil());
        when(provider.requireCurrent(binding)).thenReturn(authority, authority, changed);
        assertThatThrownBy(() -> search(null, "reviewer", "Kim", 1)).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DECISION_REVISION_CONFLICT));
    }

    @Test void changedPublishedSchemaAfterSourceDiscardsCandidates() {
        var compiled = forms.requirePublished(binding, false);
        when(forms.requirePublished(binding, false)).thenReturn(compiled)
                .thenThrow(new BaseException(ErrorCode.RESOURCE_CONFLICT, "Changed"));
        assertThatThrownBy(() -> search(null, "reviewer", "Kim", 1)).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test void crossTenantPersonAndDuplicateSubjectsCannotLeakPublicProjection() {
        when(directory.search(authority, "Kim", 1)).thenReturn(new Result(authority,
                List.of(new Person(900L, 123L, person.personPublicId(), "Kim", "TENANT", "ACTIVE"))));
        assertThatThrownBy(() -> search(null, "reviewer", "Kim", 1)).isInstanceOf(BaseException.class);
        when(directory.search(authority, "Kim", 2)).thenReturn(new Result(authority, List.of(person,
                new Person(42L, 123L, UUID.randomUUID(), "Other", "TENANT", "ACTIVE"))));
        assertThatThrownBy(() -> search(null, "reviewer", "Kim", 2)).isInstanceOf(BaseException.class);
    }

    @Test void adminCannotBorrowWorkRouteEvidence() {
        assertThatThrownBy(() -> service.search(binding.formId(), binding.formVersionId(), binding.schemaSha256(), null,
                "reviewer", "Kim", 1, true)).isInstanceOf(BaseException.class);
        verify(directory, never()).search(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    private ApprovalFormUserCandidateService.Candidates search(String group, String field, String query, int size) {
        return service.search(binding.formId(), binding.formVersionId(), binding.schemaSha256(), group, field, query, size, false);
    }
    private Map<String, Object> field(String key, String type) { return Map.of("key", key, "type", type, "labelKo", "항목", "labelEn", "Field"); }
}

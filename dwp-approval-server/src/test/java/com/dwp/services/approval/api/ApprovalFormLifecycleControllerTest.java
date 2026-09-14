package com.dwp.services.approval.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.*;
import com.dwp.services.approval.forms.ApprovalFormLifecycleFacade;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApprovalFormLifecycleControllerTest {
    private final ApprovalFormLifecycleFacade forms=mock(ApprovalFormLifecycleFacade.class);
    private final UUID form=UUID.randomUUID(),version=UUID.randomUUID();
    private final String base="/v1/admin/forms/"+form;
    private final String branch="{\"expectedFormRevision\":0,\"expectedWorkspaceRevision\":null}";
    private MockMvc mvc;
    @BeforeEach void setUp() {
        mvc=MockMvcBuilders.standaloneSetup(new ApprovalFormLifecycleController(forms))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();
    }
    @Test void allFiveReadOperationsDispatchExactFormAndVersionIdentifiers() throws Exception {
        mvc.perform(get(base+"/versions")).andExpect(status().isOk());
        mvc.perform(get(base+"/versions/"+version)).andExpect(status().isOk());
        mvc.perform(get(base+"/diff").param("fromVersionId",version.toString()).param("toVersionId",form.toString())).andExpect(status().isOk());
        mvc.perform(get(base+"/working-draft")).andExpect(status().isOk());
        mvc.perform(get(base+"/publish-review")).andExpect(status().isOk());
        verify(forms).history(form,50);verify(forms).version(form,version);verify(forms).diff(form,version,form);
        verify(forms).workingDraft(form);verify(forms).review(form);verifyNoMoreInteractions(forms);
    }
    @Test void unknownDuplicateQueryAndNonCanonicalUuidsStopBeforeTheFacade() throws Exception {
        mvc.perform(get(base+"/working-draft").param("url","https://invalid.test")).andExpect(status().isBadRequest());
        mvc.perform(get(base+"/versions").param("size","10","20")).andExpect(status().isBadRequest());
        for(String alias:List.of("1-1-1-1-1",form.toString().toUpperCase(java.util.Locale.ROOT))) {
            mvc.perform(get("/v1/admin/forms/"+alias+"/working-draft")).andExpect(status().isBadRequest());
            mvc.perform(get(base+"/diff").param("fromVersionId",alias).param("toVersionId",version.toString())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(forms);
    }
    @Test void draftMutationsRequireOneOriginalCommandKey() throws Exception {
        String path=base+"/versions/"+version+"/branch";
        for(String key:List.of(""," "," padded")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(branch).header("Idempotency-Key",key))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(branch)).andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(branch).header("Idempotency-Key","first","second"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(forms);
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(branch).header("Idempotency-Key","original"))
                .andExpect(status().isOk());
        verify(forms).branch(eq(form),eq(version),eq(new Branch(0L,null)),eq("original"),isNull());
    }
    @Test void allFourNonPublishMutationRoutesUseTheirOwnTypedInput() throws Exception {
        mvc.perform(post(base+"/retire").contentType(MediaType.APPLICATION_JSON).content(branch).header("Idempotency-Key","retire"))
                .andExpect(status().isOk());
        mvc.perform(post(base+"/reinstate").contentType(MediaType.APPLICATION_JSON).content(branch).header("Idempotency-Key","reinstate"))
                .andExpect(status().isOk());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var metadata=new MetadataInput(form,"Name","Name","Description","Description","OWNER","REQUEST");
        var body=new UpdateWorkingDraft(version,0L,null,Map.of("schemaVersion",1,"fields",List.of()),metadata,form);
        mvc.perform(put(base+"/working-draft").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body))
                .header("Idempotency-Key","update")).andExpect(status().isOk());
        verify(forms).retire(form,new AvailabilityChange(0L,null),"retire",null);
        verify(forms).reinstate(form,new AvailabilityChange(0L,null),"reinstate",null);verify(forms).update(form,body,"update",null);
    }
    @Test void unsafeOrFloatingRevisionBodiesNeverReachAnyMutation() throws Exception {
        for(String value:List.of("0.0","9007199254740992","\"0\"","-1","null")) {
            mvc.perform(post(base+"/retire").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedFormRevision\":"+value+",\"expectedWorkspaceRevision\":null}")
                    .header("Idempotency-Key","original")).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(forms);
    }
    @Test void reviewedPublishRequiresExactSingleHeadersAndSafeIntegerObjectVersion() throws Exception {
        var body=new PublishReviewed(version,null,0L,1L,"a".repeat(64),"b".repeat(64));
        String raw=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        for(String revision:List.of("0.0","00","-1","9007199254740992")) {
            mvc.perform(post(base+"/publish-reviewed").contentType(MediaType.APPLICATION_JSON).content(raw)
                    .header("Idempotency-Key","original").header("X-DWP-Step-Up-Challenge","signed")
                    .header("X-DWP-Expected-Decision-Revision","psr-current").header("X-DWP-Expected-Object-Version",revision))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(base+"/publish-reviewed").contentType(MediaType.APPLICATION_JSON).content(raw)
                .header("Idempotency-Key","original")).andExpect(status().isBadRequest());
        mvc.perform(post(base+"/publish-reviewed").contentType(MediaType.APPLICATION_JSON).content(raw)
                .header("Idempotency-Key","original").header("X-DWP-Step-Up-Challenge","one","two")
                .header("X-DWP-Expected-Decision-Revision","psr-current").header("X-DWP-Expected-Object-Version","0"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(forms);
        mvc.perform(post(base+"/publish-reviewed").contentType(MediaType.APPLICATION_JSON).content(raw)
                .header("Idempotency-Key","original").header("X-DWP-Step-Up-Challenge","signed")
                .header("X-DWP-Expected-Decision-Revision","psr-current").header("X-DWP-Expected-Object-Version","0"))
                .andExpect(status().isOk());
        verify(forms).publish(form,body,new ApprovalStepUpHeaders("signed","original","psr-current",0L),null);
    }
    @Test void rawApiDeclarationsListRequiredCommandHeadersWithoutChangingTheSignature() {
        var methods=List.of(ApprovalFormLifecycleController.class.getDeclaredMethods()).stream()
                .filter(method->Set.of("branch","update","retire","reinstate","publish").contains(method.getName())).toList();
        assertThat(methods).hasSize(5);
        for(var method:methods) {
            var parameters=method.getAnnotation(Operation.class).parameters();
            assertThat(parameters).allMatch(parameter->parameter.required()&&parameter.in()==ParameterIn.HEADER);
            assertThat(parameters).anyMatch(parameter->parameter.name().equals("Idempotency-Key"));
            assertThat(parameters).hasSize(method.getName().equals("publish")?4:1);
        }
    }
}

package com.dwp.services.auth.workflowruntime;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletRegistrationBean;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Actual production security chain and default Tomcat connector; no header/body limit overrides. */
public final class WorkflowRuntimeEmbeddedServer implements AutoCloseable {
    private final AnnotationConfigServletWebServerApplicationContext context;
    public WorkflowRuntimeEmbeddedServer(WorkflowRuntimeAuthorityService actual, boolean enabled) {
        context = new AnnotationConfigServletWebServerApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("runtime-gate", Map.of("dwp.auth.approval-workflow-runtime.enabled", enabled)));
        context.registerBean(TomcatServletWebServerFactory.class, () -> new TomcatServletWebServerFactory(0));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(WorkflowRuntimeAuthorityService.class, () -> actual);
        context.registerBean(WorkflowRuntimeController.class, () -> new WorkflowRuntimeController(actual));
        context.registerBean(Errors.class, Errors::new);
        context.registerBean(DispatcherServlet.class, () -> new DispatcherServlet());
        context.registerBean(DispatcherServletRegistrationBean.class, () -> new DispatcherServletRegistrationBean(context.getBean(DispatcherServlet.class), "/"));
        context.registerBean(FilterRegistrationBean.class, () -> new FilterRegistrationBean<>(new DelegatingFilterProxy("springSecurityFilterChain")));
        context.register(Web.class); context.refresh();
    }
    public URI endpoint() { return URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + WorkflowRuntimeProtocol.PATH); }
    @Override public void close() { context.close(); }
    @Configuration(proxyBeanMethods = false) @EnableWebMvc @EnableWebSecurity @Import(WorkflowRuntimeSecurityConfig.class)
    static class Web {
        @org.springframework.context.annotation.Bean static org.springframework.context.support.PropertySourcesPlaceholderConfigurer placeholders() {
            return new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
        }
    }
    @RestControllerAdvice static class Errors {
        @ExceptionHandler(BaseException.class) ResponseEntity<Void> reject(BaseException exception) { return ResponseEntity.status(exception.getErrorCode().getHttpStatus()).build(); }
    }
}

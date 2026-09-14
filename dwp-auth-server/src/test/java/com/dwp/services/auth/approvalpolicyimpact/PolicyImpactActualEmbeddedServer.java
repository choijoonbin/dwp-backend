package com.dwp.services.auth.approvalpolicyimpact;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletRegistrationBean;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Actual purpose filter/controller over the default Tomcat connector, with no header-limit override. */
final class PolicyImpactActualEmbeddedServer implements AutoCloseable {
    private final AnnotationConfigServletWebServerApplicationContext context = new AnnotationConfigServletWebServerApplicationContext();
    PolicyImpactActualEmbeddedServer(PolicyImpactAuthorityService actual, boolean enabled) {
        try {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("impact-http-gate",
                    Map.of("dwp.auth.approval-policy-impact.enabled", enabled)));
            context.registerBean(TomcatServletWebServerFactory.class, () -> new TomcatServletWebServerFactory(0));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
            context.registerBean(PolicyImpactAuthorityService.class, () -> actual);
            context.registerBean(PolicyImpactController.class, () -> new PolicyImpactController(actual));
            context.registerBean(Errors.class, Errors::new);
            context.registerBean(DispatcherServlet.class, () -> new DispatcherServlet());
            context.registerBean(DispatcherServletRegistrationBean.class, () -> new DispatcherServletRegistrationBean(context.getBean(DispatcherServlet.class), "/"));
            context.registerBean(FilterRegistrationBean.class, () -> new FilterRegistrationBean<>(new DelegatingFilterProxy("springSecurityFilterChain")));
            context.register(Web.class); context.refresh();
        } catch (RuntimeException error) { context.close(); throw error; }
    }
    URI endpoint() { return URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + PolicyImpactProtocol.PATH); }
    @Override public void close() { context.close(); }
    @Configuration(proxyBeanMethods = false) @EnableWebMvc @EnableWebSecurity @Import(PolicyImpactSecurityConfiguration.class)
    static class Web {
        @Bean static org.springframework.context.support.PropertySourcesPlaceholderConfigurer placeholders() {
            return new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
        }
    }
    @RestControllerAdvice static class Errors {
        @ExceptionHandler(BaseException.class) ResponseEntity<Void> reject(BaseException error) {
            return ResponseEntity.status(error.getErrorCode().getHttpStatus()).build();
        }
    }
}

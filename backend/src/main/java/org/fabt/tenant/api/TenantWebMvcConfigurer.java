package org.fabt.tenant.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers tenant-module MVC interceptors. Lives alongside the interceptor itself
 * (not in {@code shared.web}) because {@code shared.web} must not depend on any
 * domain module per
 * {@code ArchitectureTest.shared_non_security_should_not_depend_on_modules}.
 * Spring picks up all {@link WebMvcConfigurer} beans automatically, so there is no
 * central registry.
 */
@Configuration
public class TenantWebMvcConfigurer implements WebMvcConfigurer {

    private final TenantStateHandlerInterceptor tenantStateHandlerInterceptor;

    public TenantWebMvcConfigurer(TenantStateHandlerInterceptor tenantStateHandlerInterceptor) {
        this.tenantStateHandlerInterceptor = tenantStateHandlerInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantStateHandlerInterceptor);
    }
}

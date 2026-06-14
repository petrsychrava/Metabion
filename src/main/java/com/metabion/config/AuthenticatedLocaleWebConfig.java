package com.metabion.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthenticatedLocaleWebConfig implements WebMvcConfigurer {

    private final AuthenticatedLocaleInterceptor authenticatedLocaleInterceptor;

    public AuthenticatedLocaleWebConfig(AuthenticatedLocaleInterceptor authenticatedLocaleInterceptor) {
        this.authenticatedLocaleInterceptor = authenticatedLocaleInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticatedLocaleInterceptor)
                .excludePathPatterns("/api/**");
    }
}

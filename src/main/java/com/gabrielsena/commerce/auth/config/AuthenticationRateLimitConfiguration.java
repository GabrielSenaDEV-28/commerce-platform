package com.gabrielsena.commerce.auth.config;

import com.gabrielsena.commerce.auth.application.AuthenticationRateLimiter;
import com.gabrielsena.commerce.security.PublicApiRateLimiter;
import com.gabrielsena.commerce.security.PublicApiRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthenticationRateLimitConfiguration implements WebMvcConfigurer {
    private final AuthenticationRateLimiter limiter;
    private final PublicApiRateLimiter publicLimiter;

    public AuthenticationRateLimitConfiguration(AuthenticationRateLimiter limiter, PublicApiRateLimiter publicLimiter) {
        this.limiter = limiter;
        this.publicLimiter = publicLimiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthenticationRateLimitInterceptor(limiter));
        registry.addInterceptor(new PublicApiRateLimitInterceptor(publicLimiter));
    }
}

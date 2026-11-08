package com.gabrielsena.commerce.auth.config;

import com.gabrielsena.commerce.auth.application.AuthenticationRateLimiter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthenticationRateLimitConfiguration implements WebMvcConfigurer {
    private final AuthenticationRateLimiter limiter;

    public AuthenticationRateLimitConfiguration(AuthenticationRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthenticationRateLimitInterceptor(limiter));
    }
}

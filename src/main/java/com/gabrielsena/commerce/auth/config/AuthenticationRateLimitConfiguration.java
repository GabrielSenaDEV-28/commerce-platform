package com.gabrielsena.commerce.auth.config;

import com.gabrielsena.commerce.auth.api.StoreSessionCookies;
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

    private final StoreSessionCookies cookies;

    public AuthenticationRateLimitConfiguration(AuthenticationRateLimiter limiter, PublicApiRateLimiter publicLimiter,
                                                StoreSessionCookies cookies) {
        this.limiter = limiter;
        this.cookies = cookies;
        this.publicLimiter = publicLimiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Origin/CSRF rejection runs first so cross-site requests cannot consume authentication rate budgets.
        registry.addInterceptor(new StoreSessionRequestGuard(cookies));
        registry.addInterceptor(new AuthenticationRateLimitInterceptor(limiter));
        registry.addInterceptor(new PublicApiRateLimitInterceptor(publicLimiter));
    }
}

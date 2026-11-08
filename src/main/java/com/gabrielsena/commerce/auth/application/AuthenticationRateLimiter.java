package com.gabrielsena.commerce.auth.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.gabrielsena.commerce.security.RateLimitCounter;

@Service
public class AuthenticationRateLimiter {
    public enum Category { REGISTER, LOGIN, REFRESH }
    public record Decision(boolean allowed, long retryAfterSeconds) { }

    private final RateLimitCounter counter;
    private final int registrations;
    private final int logins;
    private final int refreshes;
    private final int windowSeconds;

    public AuthenticationRateLimiter(RateLimitCounter counter,
            @Value("${auth.rate-limit.register:10}") int registrations,
            @Value("${auth.rate-limit.login:20}") int logins,
            @Value("${auth.rate-limit.refresh:60}") int refreshes,
            @Value("${auth.rate-limit.window-seconds:900}") int windowSeconds) {
        RateLimitCounter.validate(windowSeconds, registrations, logins, refreshes);
        this.counter = counter;
        this.registrations = registrations;
        this.logins = logins;
        this.refreshes = refreshes;
        this.windowSeconds = windowSeconds;
    }

    public Decision check(String address, Category category) {
        int limit = switch (category) {
            case REGISTER -> registrations;
            case LOGIN -> logins;
            case REFRESH -> refreshes;
        };
        var decision = counter.check(RateLimitCounter.Table.AUTHENTICATION, address, category.name(), limit, windowSeconds);
        return new Decision(decision.allowed(), decision.retryAfterSeconds());
    }

    @Scheduled(fixedDelay = 300_000)
    public void cleanup() {
        counter.cleanup(RateLimitCounter.Table.AUTHENTICATION, windowSeconds);
    }
}

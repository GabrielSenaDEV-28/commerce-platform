package com.gabrielsena.commerce.auth.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationRateLimiter {
    public enum Category { REGISTER, LOGIN, REFRESH }
    public record Decision(boolean allowed, long retryAfterSeconds) { }

    private final JdbcTemplate jdbc;
    private final int registrations;
    private final int logins;
    private final int refreshes;
    private final int windowSeconds;

    public AuthenticationRateLimiter(JdbcTemplate jdbc,
            @Value("${auth.rate-limit.register:10}") int registrations,
            @Value("${auth.rate-limit.login:20}") int logins,
            @Value("${auth.rate-limit.refresh:60}") int refreshes,
            @Value("${auth.rate-limit.window-seconds:900}") int windowSeconds) {
        if (registrations < 1 || registrations == Integer.MAX_VALUE || logins < 1 || logins == Integer.MAX_VALUE
                || refreshes < 1 || refreshes == Integer.MAX_VALUE || windowSeconds < 1 || windowSeconds > 86400) {
            throw new IllegalArgumentException("Invalid authentication rate limit configuration.");
        }
        this.jdbc = jdbc;
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
        return jdbc.queryForObject("""
                INSERT INTO authentication_rate_limits AS limits (client_address, category, window_started_at, attempts)
                VALUES (?, ?, clock_timestamp(), 1)
                ON CONFLICT (client_address, category) DO UPDATE SET
                    window_started_at = CASE
                        WHEN limits.window_started_at + (? * INTERVAL '1 second') <= statement_timestamp()
                        THEN statement_timestamp() ELSE limits.window_started_at END,
                    attempts = CASE
                        WHEN limits.window_started_at + (? * INTERVAL '1 second') <= statement_timestamp()
                        THEN 1 ELSE LEAST(limits.attempts + 1, ?) END
                RETURNING attempts <= ? AS allowed,
                    GREATEST(1, CEIL(EXTRACT(EPOCH FROM
                        window_started_at + (? * INTERVAL '1 second') - clock_timestamp())))::bigint AS retry_after
                """, (rs, row) -> new Decision(rs.getBoolean("allowed"), rs.getLong("retry_after")),
                address, category.name(), windowSeconds, windowSeconds, limit + 1, limit, windowSeconds);
    }

    @Scheduled(fixedDelay = 300_000)
    public void cleanup() {
        jdbc.update("""
                DELETE FROM authentication_rate_limits WHERE ctid IN (
                    SELECT ctid FROM authentication_rate_limits
                    WHERE window_started_at < clock_timestamp() - (? * INTERVAL '1 second')
                    ORDER BY window_started_at LIMIT 1000
                )
                """, windowSeconds);
    }
}

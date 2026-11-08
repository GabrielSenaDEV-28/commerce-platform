package com.gabrielsena.commerce.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class RateLimitCounter {
    public enum Table {
        AUTHENTICATION("authentication_rate_limits"), PUBLIC_API("public_api_rate_limits");
        private final String name;
        Table(String name) { this.name = name; }
    }

    public record Decision(boolean allowed, long retryAfterSeconds) { }
    private final JdbcTemplate jdbc;

    public RateLimitCounter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Decision check(Table table, String address, String category, int limit, int windowSeconds) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO %s AS limits (client_address, category, window_started_at, attempts)
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
                """.formatted(table.name), (rs, row) -> new Decision(rs.getBoolean("allowed"), rs.getLong("retry_after")),
                address, category, windowSeconds, windowSeconds, limit + 1, limit, windowSeconds));
    }

    public void cleanup(Table table, int windowSeconds) {
        jdbc.update("""
                DELETE FROM %s WHERE ctid IN (
                    SELECT ctid FROM %s
                    WHERE window_started_at < clock_timestamp() - (? * INTERVAL '1 second')
                    ORDER BY window_started_at LIMIT 1000
                )
                """.formatted(table.name, table.name), windowSeconds);
    }

    public static void validate(int windowSeconds, int... limits) {
        if (windowSeconds < 1 || windowSeconds > 86400) {
            throw new IllegalArgumentException("Invalid rate limit window.");
        }
        for (int limit : limits) {
            if (limit < 1 || limit == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid rate limit.");
            }
        }
    }
}

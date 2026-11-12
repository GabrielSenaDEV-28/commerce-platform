package com.gabrielsena.commerce.auth.application;

import com.gabrielsena.commerce.auth.InvalidRefreshTokenException;
import com.gabrielsena.commerce.auth.MerchantAccountRepository;
import com.gabrielsena.commerce.auth.domain.MerchantAccount;
import com.gabrielsena.commerce.customer.application.CustomerAuthService;
import com.gabrielsena.commerce.security.OpaqueTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Browser sessions backed by a rotating, single-use refresh token that only travels in an HttpOnly cookie.
 * Each login starts a session family; presenting an already used token revokes the whole family.
 */
@Service
public class StoreBrowserSessions {
    public static final String MERCHANT = "merchant";
    public static final String CUSTOMER = "customer";

    private final JdbcTemplate jdbc;
    private final AuthenticateStoreAccount accounts;
    private final CustomerAuthService customers;
    private final MerchantAccountRepository merchants;
    private final TokenService tokens;
    private final Duration refreshTtl;
    private final Duration absoluteTtl;

    public record Session(String accessToken, String accountKind, UUID identity, String refreshToken,
                          Duration refreshMaxAge) { }

    private record Presented(UUID sessionId, String accountKind, UUID accountId, Instant absoluteExpiresAt,
                             boolean revoked, Instant expiresAt, boolean used) { }

    public StoreBrowserSessions(JdbcTemplate jdbc, AuthenticateStoreAccount accounts, CustomerAuthService customers,
                                MerchantAccountRepository merchants, TokenService tokens,
                                @Value("${store-session.refresh-ttl:P7D}") Duration refreshTtl,
                                @Value("${store-session.absolute-ttl:P30D}") Duration absoluteTtl) {
        if (refreshTtl.isNegative() || refreshTtl.isZero() || absoluteTtl.compareTo(refreshTtl) < 0) {
            throw new IllegalArgumentException("Store session lifetimes are invalid.");
        }
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.customers = customers;
        this.merchants = merchants;
        this.tokens = tokens;
        this.refreshTtl = refreshTtl;
        this.absoluteTtl = absoluteTtl;
    }

    @Transactional
    public Session login(UUID store, String email, String password) {
        var account = accounts.authenticate(store, email, password);
        String access = MERCHANT.equals(account.accountKind())
                ? tokens.accessToken(account.merchant())
                : customers.accessToken(store, account.id());
        return start(store, account.accountKind(), account.id(), access);
    }

    @Transactional
    public Session register(UUID store, String name, String email, String password) {
        var customer = customers.register(store, name, email, password);
        return start(store, CUSTOMER, customer.id(), customers.accessToken(store, customer.id()));
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Session refresh(UUID store, String refreshToken) {
        String hash = OpaqueTokens.hash(refreshToken);
        // Locking the family and token rows serializes rotation and logout, so a token is consumed at most once.
        List<Presented> rows = jdbc.query("""
                SELECT s.session_id, s.account_kind, s.account_id, s.absolute_expires_at, s.revoked_at IS NOT NULL,
                       t.expires_at, t.used_at IS NOT NULL
                FROM store_browser_session_tokens t
                JOIN store_browser_sessions s ON s.session_id = t.session_id
                WHERE t.token_hash = ? AND s.store_id = ?
                FOR UPDATE OF s, t
                """, (rs, row) -> new Presented(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, UUID.class), rs.getTimestamp(4).toInstant(), rs.getBoolean(5),
                rs.getTimestamp(6).toInstant(), rs.getBoolean(7)), hash, store);
        if (rows.isEmpty()) {
            throw new InvalidRefreshTokenException();
        }
        Presented presented = rows.getFirst();
        if (presented.revoked()) {
            throw new InvalidRefreshTokenException();
        }
        if (presented.used()) {
            revoke(presented.sessionId(), "reuse_detected");
            throw new InvalidRefreshTokenException();
        }
        Instant now = Instant.now();
        if (!presented.expiresAt().isAfter(now) || !presented.absoluteExpiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException();
        }
        String access = accessToken(store, presented.accountKind(), presented.accountId());
        if (access == null) {
            revoke(presented.sessionId(), "account_unavailable");
            throw new InvalidRefreshTokenException();
        }
        if (jdbc.update("""
                UPDATE store_browser_session_tokens SET used_at = ? WHERE token_hash = ? AND used_at IS NULL
                """, Timestamp.from(now), hash) != 1) {
            revoke(presented.sessionId(), "reuse_detected");
            throw new InvalidRefreshTokenException();
        }
        var next = issueToken(presented.sessionId(), presented.absoluteExpiresAt(), now);
        return new Session(access, presented.accountKind(), presented.accountId(), next.token(), next.maxAge());
    }

    @Transactional
    public void logout(UUID store, String refreshToken) {
        jdbc.update("""
                UPDATE store_browser_sessions s SET revoked_at = clock_timestamp(), revocation_reason = 'logout'
                FROM store_browser_session_tokens t
                WHERE t.session_id = s.session_id AND t.token_hash = ? AND s.store_id = ? AND s.revoked_at IS NULL
                """, OpaqueTokens.hash(refreshToken), store);
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanup() {
        jdbc.update("DELETE FROM store_browser_sessions WHERE absolute_expires_at < ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(1))));
    }

    private Session start(UUID store, String accountKind, UUID accountId, String access) {
        Instant now = Instant.now();
        UUID sessionId = UUID.randomUUID();
        Instant absolute = now.plus(absoluteTtl);
        jdbc.update("""
                INSERT INTO store_browser_sessions (session_id, store_id, account_kind, account_id, absolute_expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, sessionId, store, accountKind, accountId, Timestamp.from(absolute));
        var next = issueToken(sessionId, absolute, now);
        return new Session(access, accountKind, accountId, next.token(), next.maxAge());
    }

    private record Issued(String token, Duration maxAge) { }

    private Issued issueToken(UUID sessionId, Instant absolute, Instant now) {
        String token = OpaqueTokens.generate();
        Instant expires = now.plus(refreshTtl);
        if (expires.isAfter(absolute)) {
            expires = absolute;
        }
        jdbc.update("""
                INSERT INTO store_browser_session_tokens (token_hash, session_id, expires_at) VALUES (?, ?, ?)
                """, OpaqueTokens.hash(token), sessionId, Timestamp.from(expires));
        return new Issued(token, Duration.between(now, expires));
    }

    private String accessToken(UUID store, String accountKind, UUID accountId) {
        if (CUSTOMER.equals(accountKind)) {
            return customers.exists(store, accountId) ? customers.accessToken(store, accountId) : null;
        }
        boolean owner = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM merchant_accounts a JOIN stores s ON s.merchant_id = a.merchant_id
                               WHERE s.store_id = ? AND a.account_id = ?)
                """, Boolean.class, store, accountId));
        MerchantAccount account = owner ? merchants.findById(accountId).orElse(null) : null;
        return account == null ? null : tokens.accessToken(account);
    }

    private void revoke(UUID sessionId, String reason) {
        jdbc.update("""
                UPDATE store_browser_sessions SET revoked_at = clock_timestamp(), revocation_reason = ?
                WHERE session_id = ? AND revoked_at IS NULL
                """, reason, sessionId);
    }
}

package com.gabrielsena.commerce.customer.application;

import com.gabrielsena.commerce.auth.InvalidCredentialsException;
import com.gabrielsena.commerce.auth.InvalidRefreshTokenException;
import com.gabrielsena.commerce.auth.application.TokenPair;
import com.gabrielsena.commerce.auth.application.TokenService;
import com.gabrielsena.commerce.security.OpaqueTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class CustomerAuthService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final String issuer;
    private final String dummyHash;

    public record Customer(UUID id, String name, String email) { }
    private record Account(Customer customer, String passwordHash) { }

    public CustomerAuthService(JdbcTemplate jdbc, PasswordEncoder passwords, JwtEncoder encoder,
                               @Value("${spring.application.name}") String issuer) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.encoder = encoder;
        this.issuer = issuer;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public Customer register(UUID store, String name, String email, String password) {
        UUID id = UUID.randomUUID();
        String normalized = normalize(email);
        int created = jdbc.update("""
                INSERT INTO customer_accounts (customer_id, store_id, name, email, password_hash)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (store_id, email) DO NOTHING
                """, id, store, name.strip(), normalized, passwords.encode(password));
        if (created == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account already exists for the supplied email.");
        }
        return new Customer(id, name.strip(), normalized);
    }

    @Transactional
    public TokenPair login(UUID store, String email, String password) {
        List<Account> rows = jdbc.query("""
                SELECT customer_id, name, email, password_hash FROM customer_accounts WHERE store_id = ? AND email = ?
                """, (rs, row) -> new Account(new Customer(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3)), rs.getString(4)), store, normalize(email));
        Account account = rows.isEmpty() ? null : rows.getFirst();
        boolean matches = passwords.matches(password, account == null ? dummyHash : account.passwordHash());
        if (account == null || !matches) {
            throw new InvalidCredentialsException();
        }
        return issue(store, account.customer().id());
    }

    @Transactional
    public TokenPair refresh(UUID store, String token) {
        List<UUID> accounts = jdbc.query("""
                UPDATE customer_refresh_tokens SET revoked_at = clock_timestamp()
                WHERE store_id = ? AND token_hash = ? AND revoked_at IS NULL AND expires_at > clock_timestamp()
                RETURNING customer_id
                """, (rs, row) -> rs.getObject(1, UUID.class), store, OpaqueTokens.hash(token));
        if (accounts.isEmpty()) {
            throw new InvalidRefreshTokenException();
        }
        return issue(store, accounts.getFirst());
    }

    @Transactional
    public void logout(UUID store, String token) {
        jdbc.update("""
                UPDATE customer_refresh_tokens SET revoked_at = clock_timestamp()
                WHERE store_id = ? AND token_hash = ? AND revoked_at IS NULL AND expires_at > clock_timestamp()
                """, store, OpaqueTokens.hash(token));
    }

    public Customer customer(UUID store, Jwt jwt) {
        if (jwt == null || !"customer".equals(jwt.getClaimAsString("account_type"))
                || !store.toString().equals(jwt.getClaimAsString("store_id"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer authentication required for this store.");
        }
        UUID id;
        try {
            id = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        List<Customer> result = jdbc.query("""
                SELECT customer_id, name, email FROM customer_accounts WHERE store_id = ? AND customer_id = ?
                """, (rs, row) -> new Customer(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), store, id);
        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return result.getFirst();
    }

    private TokenPair issue(UUID store, UUID customer) {
        Instant now = Instant.now();
        String refresh = OpaqueTokens.generate();
        jdbc.update("""
                INSERT INTO customer_refresh_tokens (token_hash, store_id, customer_id, expires_at)
                VALUES (?, ?, ?, ?)
                """, OpaqueTokens.hash(refresh), store, customer, java.sql.Timestamp.from(now.plusSeconds(7 * 86400L)));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).issuedAt(now)
                .expiresAt(now.plusSeconds(TokenService.ACCESS_TOKEN_SECONDS)).subject(customer.toString())
                .claim("account_type", "customer").claim("store_id", store.toString()).build();
        String access = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenPair(access, refresh);
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}

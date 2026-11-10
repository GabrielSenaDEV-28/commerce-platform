package com.gabrielsena.commerce.auth.application;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class StoreAccountIdentity {
    private final JdbcTemplate jdbc;

    public StoreAccountIdentity(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Identity(boolean merchant, boolean customer) { }

    public Identity resolve(UUID store, String normalizedEmail) {
        return jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM merchant_accounts a JOIN stores s ON s.merchant_id = a.merchant_id
                    WHERE s.store_id = ? AND a.email = ?
                ), EXISTS (
                    SELECT 1 FROM customer_accounts WHERE store_id = ? AND email = ?
                )
                """, (rs, row) -> new Identity(rs.getBoolean(1), rs.getBoolean(2)),
                store, normalizedEmail, store, normalizedEmail);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Identity lockForRegistration(UUID store, String normalizedEmail) {
        // Both account creation paths lock the same store before checking either account table.
        if (jdbc.query("SELECT store_id FROM stores WHERE store_id = ? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), store).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found.");
        }
        return resolve(store, normalizedEmail);
    }
}

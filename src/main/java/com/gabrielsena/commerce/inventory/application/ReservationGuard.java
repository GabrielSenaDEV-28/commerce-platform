package com.gabrielsena.commerce.inventory.application;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.UUID;

@Service
public class ReservationGuard {
    private final JdbcTemplate jdbc;

    public ReservationGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long reserved(UUID store, UUID product, UUID variant) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT COALESCE(SUM(i.quantity), 0)
                FROM checkout_reservation_items i
                JOIN checkout_reservations r ON r.store_id = i.store_id AND r.reservation_id = i.reservation_id
                WHERE i.store_id = ? AND i.product_id = ? AND (?::uuid IS NULL OR i.variant_id = ?)
                  AND i.reserved AND r.cancelled_at IS NULL AND r.expires_at > statement_timestamp()
                """, Long.class, store, product, variant, variant));
    }

    public void requireUnreserved(UUID store, UUID product, UUID variant) {
        if (reserved(store, product, variant) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Active reservations prevent deactivation.");
        }
    }
}

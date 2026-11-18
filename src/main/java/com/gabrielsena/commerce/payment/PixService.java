package com.gabrielsena.commerce.payment;

import com.gabrielsena.commerce.order.application.OrderService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class PixService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OrderService orders;
    private final MercadoPagoSandbox provider;

    PixService(JdbcTemplate jdbc, TransactionTemplate tx, OrderService orders, MercadoPagoSandbox provider) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.orders = orders;
        this.provider = provider;
    }

    record View(boolean enabled, String status, String copyCode, Instant expiresAt) { }
    private record Payment(UUID reference, String remote, String status, BigDecimal total, String qr,
                           Instant started) { }
    private record Reservation(UUID cart, UUID id, Instant expires, boolean active) { }
    private record Stock(UUID product, UUID variant, int units, int balance) { }

    View get(UUID store, UUID customer, UUID order) {
        var owned = orders.ownedOrder(store, customer, order);
        var p = payment(store, order);
        if (p == null) return new View(provider.enabled() && owned.total().signum() > 0, null, null, owned.expiresAt());
        if (provider.enabled() && p.remote() != null && !p.status().equals("PAID") && !p.status().equals("REVIEW")) {
            reconcile(store, order, provider.fetch(p.remote()), false);
        }
        return current(store, order);
    }

    View create(UUID store, UUID customer, UUID order) {
        var owned = orders.ownedOrder(store, customer, order);
        if (!provider.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Pix sandbox is disabled.");
        Payment p = Objects.requireNonNull(tx.execute(s -> {
            var r = lockReservation(store, order);
            var existing = payment(store, order);
            if (existing != null && (existing.remote() != null || !existing.status().equals("CREATING"))) return existing;
            if (!r.active() || owned.total().signum() <= 0) throw conflict();
            if (existing != null) return existing;
            if (r.expires().isBefore(now().plusSeconds(20))) throw conflict();
            jdbc.update("""
                    INSERT INTO sandbox_pix_payments (store_id, order_id, reference_id, total, status)
                    VALUES (?, ?, ?, ?, 'CREATING')
                    """, store, order, UUID.randomUUID(), owned.total());
            return payment(store, order);
        }));
        if (p.remote() == null && p.status().equals("CREATING")) {
            reconcile(store, order, provider.create(p.reference(), p.total()), true);
        } else if (p.remote() != null && p.status().equals("PENDING")) {
            reconcile(store, order, provider.fetch(p.remote()), false);
        }
        return current(store, order);
    }

    void notify(String remote) {
        if (!provider.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var known = jdbc.query("""
                SELECT store_id, order_id FROM sandbox_pix_payments WHERE provider_order_id = ?
                """, (rs, row) -> new UUID[] { rs.getObject(1, UUID.class), rs.getObject(2, UUID.class) }, remote);
        if (!known.isEmpty()) {
            UUID[] ids = known.getFirst();
            reconcile(ids[0], ids[1], provider.fetch(remote), false);
            return;
        }
        var data = provider.fetch(remote);
        if (!remote.equals(data.id()) || !data.sandboxTicket()) return;
        UUID ref;
        try { ref = UUID.fromString(data.reference()); }
        catch (IllegalArgumentException failure) { return; }
        var candidate = jdbc.query("""
                SELECT store_id, order_id FROM sandbox_pix_payments WHERE reference_id = ? AND provider_order_id IS NULL
                """, (rs, row) -> new UUID[] { rs.getObject(1, UUID.class), rs.getObject(2, UUID.class) }, ref);
        if (!candidate.isEmpty()) {
            UUID[] ids = candidate.getFirst();
            reconcile(ids[0], ids[1], data, true);
        }
    }

    private void reconcile(UUID store, UUID order, MercadoPagoSandbox.Remote remote, boolean mayAttach) {
        tx.executeWithoutResult(s -> {
            var r = lockReservation(store, order);
            var p = Objects.requireNonNull(payment(store, order));
            if (!p.reference().toString().equals(remote.reference()) || p.total().compareTo(remote.total()) != 0
                    || (p.remote() != null && !p.remote().equals(remote.id()))) throw MercadoPagoSandbox.unavailable();
            if (p.status().equals("PAID") || p.status().equals("REVIEW")) return;
            boolean eligible = r.active() && !p.status().equals("EXPIRED");
            if (p.remote() == null) {
                Instant deadline = remote.issuedAt().plusSeconds(1800);
                Instant now = now();
                if (!mayAttach || !remote.sandboxTicket() || remote.issuedAt().isAfter(now.plusSeconds(10))
                        || remote.issuedAt().isBefore(p.started().minusSeconds(10))) throw MercadoPagoSandbox.unavailable();
                jdbc.update("""
                        UPDATE sandbox_pix_payments SET provider_order_id = ?, status = 'PENDING', qr_code = ?
                        WHERE store_id = ? AND order_id = ?
                        """, remote.id(), remote.qr(), store, order);
                if (eligible && deadline.isAfter(now)) {
                    lockedStock(store, order);
                    eligible = jdbc.update("""
                            UPDATE checkout_reservations SET expires_at = ?
                            WHERE store_id = ? AND reservation_id = ? AND expires_at > clock_timestamp()
                              AND CAST(? AS timestamptz) > clock_timestamp()
                            """,
                            Timestamp.from(deadline), store, r.id(), Timestamp.from(deadline)) == 1;
                } else {
                    eligible = false;
                }
                if (!eligible) {
                    release(store, r);
                    status(store, order, "EXPIRED");
                }
            }
            if (remote.paid()) {
                if (!eligible || !settle(store, order, r)) {
                    release(store, r);
                    status(store, order, "REVIEW");
                } else {
                    status(store, order, "PAID");
                }
            } else if (remote.requiresReview()) {
                release(store, r);
                status(store, order, "REVIEW");
            } else if (remote.expired()) {
                release(store, r);
                status(store, order, "EXPIRED");
            }
        });
    }

    private boolean settle(UUID store, UUID order, Reservation r) {
        var stock = lockedStock(store, order);
        long itemCount = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM commerce_order_items WHERE store_id = ? AND order_id = ?", Long.class, store, order));
        if (stock.isEmpty() || stock.size() != itemCount || stock.stream().anyMatch(i -> i.balance() < i.units())) return false;
        int consumed = jdbc.update("""
                UPDATE checkout_reservations SET consumed_at = clock_timestamp()
                WHERE store_id = ? AND reservation_id = ? AND expires_at > clock_timestamp()
                  AND cancelled_at IS NULL AND consumed_at IS NULL
                """, store, r.id());
        if (consumed != 1) return false;
        for (Stock i : stock) {
            int after = i.balance() - i.units();
            jdbc.update("""
                    UPDATE inventory_balances SET quantity = ?, version = version + 1
                    WHERE store_id = ? AND variant_id = ?
                    """, after, store, i.variant());
            jdbc.update("""
                    INSERT INTO inventory_movements (movement_id, store_id, product_id, variant_id, sale_order_id,
                        occurred_at, movement_type, before_quantity, after_quantity, reason)
                    VALUES (?, ?, ?, ?, ?, clock_timestamp(), 'EXIT', ?, ?, 'Sandbox Pix sale')
                    """, UUID.randomUUID(), store, i.product(), i.variant(), order, i.balance(), after);
            int removed = jdbc.update("""
                    DELETE FROM shopping_cart_items
                    WHERE store_id = ? AND cart_id = ? AND variant_id = ? AND quantity <= ?
                    """, store, r.cart(), i.variant(), i.units());
            if (removed == 0) {
                jdbc.update("""
                        UPDATE shopping_cart_items SET quantity = quantity - ?
                        WHERE store_id = ? AND cart_id = ? AND variant_id = ?
                        """, i.units(), store, r.cart(), i.variant());
            }
        }
        return true;
    }

    private java.util.List<Stock> lockedStock(UUID store, UUID order) {
        return jdbc.query("""
                SELECT i.product_id, i.variant_id, i.quantity AS units, b.quantity AS balance
                FROM commerce_order_items i JOIN inventory_balances b USING (store_id, product_id, variant_id)
                WHERE i.store_id = ? AND i.order_id = ? ORDER BY i.variant_id FOR UPDATE OF b
                """, (rs, row) -> new Stock(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getInt(3), rs.getInt(4)), store, order);
    }

    private Reservation lockReservation(UUID store, UUID order) {
        UUID cart = jdbc.queryForObject("""
                SELECT c.cart_id FROM commerce_orders o JOIN checkout_reservations r USING (store_id, reservation_id)
                JOIN shopping_carts c USING (store_id, cart_id)
                WHERE o.store_id = ? AND o.order_id = ? FOR UPDATE OF c
                """, UUID.class, store, order);
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT r.reservation_id, r.expires_at, r.cancelled_at IS NULL AND r.consumed_at IS NULL
                    AND r.expires_at > clock_timestamp() AS active
                FROM commerce_orders o JOIN checkout_reservations r USING (store_id, reservation_id)
                WHERE o.store_id = ? AND o.order_id = ? FOR UPDATE OF r
                """, (rs, row) -> new Reservation(cart, rs.getObject(1, UUID.class),
                rs.getTimestamp(2).toInstant(), rs.getBoolean(3)), store, order));
    }

    private void release(UUID store, Reservation r) {
        jdbc.update("""
                UPDATE checkout_reservations SET expires_at = GREATEST(clock_timestamp(), created_at + interval '1 microsecond')
                WHERE store_id = ? AND reservation_id = ? AND expires_at > clock_timestamp()
                    AND consumed_at IS NULL AND cancelled_at IS NULL
                """, store, r.id());
    }

    private void status(UUID store, UUID order, String status) {
        jdbc.update("UPDATE sandbox_pix_payments SET status = ?, qr_code = NULL WHERE store_id = ? AND order_id = ?",
                status, store, order);
    }

    private Payment payment(UUID store, UUID order) {
        return jdbc.query("""
                SELECT reference_id, provider_order_id, status, total, qr_code, started_at
                FROM sandbox_pix_payments WHERE store_id = ? AND order_id = ?
                """, (rs, row) -> new Payment(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getBigDecimal(4), rs.getString(5), rs.getTimestamp(6).toInstant()), store, order)
                .stream().findFirst().orElse(null);
    }

    private View current(UUID store, UUID order) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT r.expires_at, CASE WHEN p.status IN ('CREATING', 'PENDING')
                    AND (r.expires_at <= statement_timestamp() OR r.cancelled_at IS NOT NULL) THEN 'EXPIRED'
                    ELSE p.status END AS status, p.qr_code
                FROM sandbox_pix_payments p JOIN commerce_orders o USING (store_id, order_id)
                    JOIN checkout_reservations r USING (store_id, reservation_id)
                WHERE p.store_id = ? AND p.order_id = ?
                """, (rs, row) -> new View(provider.enabled(), rs.getString(2),
                provider.enabled() && rs.getString(2).equals("PENDING") ? rs.getString(3) : null,
                rs.getTimestamp(1).toInstant()), store, order));
    }

    private Instant now() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class)).toInstant();
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The order is not eligible for a Pix charge.");
    }
}

package com.gabrielsena.commerce.checkout.application;

import com.gabrielsena.commerce.cart.application.ShoppingCartService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class CheckoutReservationService {
    private final JdbcTemplate jdbc;
    private final ShoppingCartService carts;

    public CheckoutReservationService(JdbcTemplate jdbc, ShoppingCartService carts) {
        this.jdbc = jdbc;
        this.carts = carts;
    }

    public record Item(UUID productId, UUID variantId, int quantity, BigDecimal price,
                       boolean reserved, int availableQuantity) { }
    public record Checkout(UUID id, String status, Instant expiresAt, List<Item> items) { }
    private record Header(UUID id, Instant expiresAt, String status) { }

    @Transactional
    public Checkout start(UUID store, String token) {
        UUID cart = carts.cartId(store, token, true);
        Header active = active(store, cart);
        if (active != null) {
            return checkout(store, active);
        }

        jdbc.query("""
                SELECT p.product_id FROM catalog_products p JOIN
                  (SELECT DISTINCT product_id FROM shopping_cart_items WHERE store_id = ? AND cart_id = ?) i
                  ON i.product_id = p.product_id
                WHERE p.store_id = ? ORDER BY p.product_id FOR UPDATE OF p
                """, (rs, row) -> rs.getObject(1, UUID.class), store, cart, store);
        List<UUID> variants = jdbc.query("""
                SELECT variant_id FROM shopping_cart_items
                WHERE store_id = ? AND cart_id = ? ORDER BY variant_id
                """, (rs, row) -> rs.getObject(1, UUID.class), store, cart);
        if (variants.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The cart is empty.");
        }
        for (UUID variant : variants) {
            jdbc.update("""
                    INSERT INTO inventory_balances (store_id, product_id, variant_id, quantity, version)
                    SELECT store_id, product_id, variant_id, 0, 0 FROM shopping_cart_items
                    WHERE store_id = ? AND cart_id = ? AND variant_id = ?
                    ON CONFLICT (variant_id) DO NOTHING
                    """, store, cart, variant);
            jdbc.queryForObject("SELECT quantity FROM inventory_balances WHERE store_id = ? AND variant_id = ? FOR UPDATE",
                    Integer.class, store, variant);
        }
        carts.cartId(store, token, true);
        List<Item> items = jdbc.query("""
                SELECT i.product_id, i.variant_id, i.quantity, COALESCE(v.price, p.price) AS price,
                       s.quantity AS available_quantity,
                       p.active AND v.active AND s.quantity >= i.quantity AS reserved
                FROM shopping_cart_items i
                JOIN catalog_products p ON p.store_id = i.store_id AND p.product_id = i.product_id
                JOIN catalog_product_variants v ON v.store_id = i.store_id AND v.product_id = i.product_id
                                                AND v.variant_id = i.variant_id
                JOIN inventory_available_stock s ON s.store_id = i.store_id AND s.variant_id = i.variant_id
                WHERE i.store_id = ? AND i.cart_id = ? ORDER BY i.product_id, i.variant_id
                """, (rs, row) -> new Item(rs.getObject("product_id", UUID.class),
                rs.getObject("variant_id", UUID.class), rs.getInt("quantity"), rs.getBigDecimal("price"),
                rs.getBoolean("reserved"), rs.getInt("available_quantity")), store, cart);
        if (items.stream().noneMatch(Item::reserved)) {
            return new Checkout(null, "UNAVAILABLE", null, items);
        }
        UUID id = UUID.randomUUID();
        Header header = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO checkout_reservations (reservation_id, store_id, cart_id, created_at, expires_at)
                SELECT ?, ?, ?, now_at, now_at + INTERVAL '20 minutes' FROM (SELECT clock_timestamp() AS now_at) t
                RETURNING reservation_id, expires_at
                """, (rs, row) -> new Header(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), "ACTIVE"),
                id, store, cart));
        for (Item item : items) {
            jdbc.update("""
                    INSERT INTO checkout_reservation_items
                      (store_id, reservation_id, product_id, variant_id, quantity, price, reserved, available_quantity)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, store, id, item.productId(), item.variantId(), item.quantity(), item.price(),
                    item.reserved(), item.availableQuantity());
        }
        int renewed = jdbc.update("""
                UPDATE shopping_carts SET expires_at = GREATEST(expires_at, ?::timestamptz + INTERVAL '30 days')
                WHERE store_id = ? AND cart_id = ? AND expires_at > clock_timestamp()
                """, Timestamp.from(header.expiresAt()), store, cart);
        if (renewed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart not found.");
        }
        return new Checkout(id, "ACTIVE", header.expiresAt(), items);
    }

    @Transactional(readOnly = true)
    public Checkout get(UUID store, String token) {
        UUID cart = carts.cartId(store, token, false);
        List<Header> headers = jdbc.query("""
                SELECT reservation_id, expires_at,
                  CASE WHEN cancelled_at IS NOT NULL THEN 'CANCELLED'
                       WHEN expires_at <= statement_timestamp() THEN 'EXPIRED' ELSE 'ACTIVE' END AS status
                FROM checkout_reservations WHERE store_id = ? AND cart_id = ?
                ORDER BY created_at DESC, reservation_id DESC LIMIT 1
                """, (rs, row) -> new Header(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(),
                rs.getString(3)), store, cart);
        if (headers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Checkout not found.");
        }
        return checkout(store, headers.getFirst());
    }

    @Transactional
    public Checkout cancel(UUID store, String token, UUID id) {
        UUID cart = carts.cartId(store, token, true);
        Header active = active(store, cart);
        if (active == null || !active.id().equals(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No active checkout to cancel.");
        }
        int updated = jdbc.update("""
                UPDATE checkout_reservations SET cancelled_at = clock_timestamp()
                WHERE store_id = ? AND reservation_id = ? AND cancelled_at IS NULL
                  AND expires_at > clock_timestamp()
                """, store, active.id());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout has expired.");
        }
        return checkout(store, new Header(active.id(), active.expiresAt(), "CANCELLED"));
    }

    private Header active(UUID store, UUID cart) {
        return jdbc.query("""
                SELECT reservation_id, expires_at FROM checkout_reservations
                WHERE store_id = ? AND cart_id = ? AND cancelled_at IS NULL AND expires_at > statement_timestamp()
                ORDER BY created_at DESC LIMIT 1
                """, (rs, row) -> new Header(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), "ACTIVE"),
                store, cart).stream().findFirst().orElse(null);
    }

    private Checkout checkout(UUID store, Header header) {
        List<Item> items = jdbc.query("""
                SELECT product_id, variant_id, quantity, price, reserved, available_quantity
                FROM checkout_reservation_items WHERE store_id = ? AND reservation_id = ?
                ORDER BY product_id, variant_id
                """, (rs, row) -> new Item(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getInt(3), rs.getBigDecimal(4), rs.getBoolean(5), rs.getInt(6)), store, header.id());
        return new Checkout(header.id(), header.status(), header.expiresAt(), items);
    }
}
